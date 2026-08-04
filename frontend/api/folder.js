// GET /api/folder?link=<drive folder share link OR bare ID>
// Admin panel folder link paste karke "save" dabaye to yeh saari video files list kar
// deta hai (naam, size, id) — backend un sabko ek season/episodes ki tarah auto-index
// kar sakta hai. Pagination khud handle karta hai (1000+ files wale folder ke liye bhi).

import { ok, fail, requireApiKey, extractDriveId } from "./_util.js";

export default async function handler(req, res) {
  const raw = req.query.link || req.query.id;
  const folderId = extractDriveId(raw);
  if (!folderId) return fail(res, 400, "invalid_link", { message: "Drive folder link ya ID nahi mila." });

  try {
    const key = requireApiKey();
    let files = [];
    let pageToken;

    do {
      const url = new URL("https://www.googleapis.com/drive/v3/files");
      url.searchParams.set("q", `'${folderId}' in parents and trashed = false`);
      url.searchParams.set("fields", "nextPageToken, files(id,name,mimeType,size,videoMediaMetadata)");
      url.searchParams.set("pageSize", "1000");
      url.searchParams.set("orderBy", "name_natural");
      url.searchParams.set("key", key);
      if (pageToken) url.searchParams.set("pageToken", pageToken);

      const r = await fetch(url.toString());
      const data = await r.json();
      if (data.error) return fail(res, 404, "drive_folder_not_accessible", { message: data.error.message });

      const videos = (data.files || [])
        .filter((f) => (f.mimeType || "").startsWith("video/"))
        .map((f) => ({
          id: f.id,
          title: f.name,
          sizeBytes: f.size ? Number(f.size) : null,
          durationMs: f.videoMediaMetadata?.durationMillis
            ? Number(f.videoMediaMetadata.durationMillis)
            : null,
        }));
      files = files.concat(videos);
      pageToken = data.nextPageToken;
    } while (pageToken);

    if (files.length === 0) {
      return fail(res, 404, "no_videos_found", {
        message: "Folder mein koi video file nahi mili — check karo folder public share hai ya nahi.",
      });
    }

    return ok(res, { folderId, count: files.length, files }, 1800);
  } catch (e) {
    return fail(res, 500, "server_error", { message: e.message });
  }
}
