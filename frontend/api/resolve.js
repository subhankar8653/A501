// GET /api/resolve?link=<drive share link OR bare ID>
//
// IMPORTANT — isko use karne se pehle README.md zaroor padho.
//
// Drive ke "get_video_info" endpoint se per-quality direct googlevideo.com stream URLs
// nikalne ki koshish karta hai (yehi tarika gdflix/gdtot jaise tools bhi use karte hain).
// Yeh UNOFFICIAL/undocumented hai — Google ne yeh kabhi bhi band ya restrict kar sakta
// hai, aur sab files ke liye kaam nahi karta (bade/private/quota-hit files ke liye fail
// ho sakta hai). Isliye:
//
//   - Agar extraction kaamyab ho -> qualities[] milta hai, apna custom player use karo.
//   - Agar fail ho jaaye -> `fallback: "iframe"` + `previewUrl` milta hai. Us URL ko
//     WebView/iframe mein load karo — yeh seedha Google ka apna Drive preview player
//     hai jismein khud ka quality-selector (gear icon) already hota hai. Guaranteed
//     kaam karta hai kyunki yeh officially Google ka diya hua embed hai, sirf apna
//     custom player skin nahi milega us case mein.
//
// Practical suggestion: apne player mein dono handle karo — jab qualities[] mile to
// apna player dikhao, jab fallback mile to andar hi iframe embed kar do.

import { ok, fail, extractDriveId } from "./_util.js";

const ITAG_LABELS = {
  18: "360p",
  22: "720p",
  37: "1080p",
  43: "360p",
  59: "480p",
  133: "240p",
  134: "360p",
  135: "480p",
  136: "720p",
  137: "1080p",
  140: "audio",
  141: "audio (high)",
};

export default async function handler(req, res) {
  const raw = req.query.link || req.query.id;
  const fileId = extractDriveId(raw);
  if (!fileId) return fail(res, 400, "invalid_link", { message: "Drive link ya ID nahi mila." });

  const previewUrl = `https://drive.google.com/file/d/${fileId}/preview`;

  try {
    const infoUrl = `https://drive.google.com/get_video_info?docid=${fileId}&drive_originator_app=303`;
    const r = await fetch(infoUrl, {
      headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36" },
    });
    const text = await r.text();
    const params = new URLSearchParams(text);
    const status = params.get("status");

    if (status !== "ok") {
      // Reason milta hai jaise "urlEmpty", "notPlayable" — extraction is file ke liye
      // nahi ho payi. Fallback iframe hamesha kaam karega.
      return ok(res, {
        id: fileId,
        extracted: false,
        reason: params.get("reason") || "unknown",
        fallback: "iframe",
        previewUrl,
      });
    }

    const title = params.get("title");
    const fmtStreamMap = params.get("fmt_stream_map") || "";
    const qualities = fmtStreamMap
      .split(",")
      .filter(Boolean)
      .map((entry) => {
        const [itag, url] = entry.split("|");
        return {
          itag,
          label: ITAG_LABELS[itag] || `itag_${itag}`,
          url: url ? decodeURIComponent(url) : null,
        };
      })
      .filter((q) => q.url)
      .sort((a, b) => Number(a.itag) - Number(b.itag));

    if (qualities.length === 0) {
      return ok(res, {
        id: fileId,
        extracted: false,
        reason: "no_streams_in_response",
        fallback: "iframe",
        previewUrl,
      });
    }

    return ok(res, {
      id: fileId,
      extracted: true,
      title: title ? decodeURIComponent(title) : null,
      qualities,
      // audio-only tracks (agar file mein alag audio streams hain) qualities mein
      // "audio"/"audio (high)" label ke saath already mil jaate hain.
    });
  } catch (e) {
    // Network/parse error mein bhi hard-fail mat karo — fallback de do taaki player
    // kabhi bhi bilkul kaala screen na dikhaye.
    return ok(res, {
      id: fileId,
      extracted: false,
      reason: "extraction_error",
      message: e.message,
      fallback: "iframe",
      previewUrl,
    });
  }
}
