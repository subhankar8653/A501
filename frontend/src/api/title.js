// GET /api/title?link=<drive share link OR bare ID>
// Admin panel isse "link paste karo -> title automatic aa jaaye" wale field ke liye
// use karega. Official Drive API v3 (files.get) — koi scraping nahi, isliye reliable hai.
// Zaroori: file "Anyone with the link" ki tarah shared honi chahiye.

import { ok, fail, requireApiKey, extractDriveId } from "./_util.js";

export default async function handler(req, res) {
  const raw = req.query.link || req.query.id;
  const fileId = extractDriveId(raw);
  if (!fileId) return fail(res, 400, "invalid_link", { message: "Drive link ya ID nahi mila." });

  try {
    const key = requireApiKey();
    const url = `https://www.googleapis.com/drive/v3/files/${fileId}?fields=id,name,mimeType,size,videoMediaMetadata&key=${key}`;
    const r = await fetch(url);
    const data = await r.json();

    if (data.error) {
      // 404 = file exist nahi ya "Anyone with link" share nahi hai
      return fail(res, 404, "drive_file_not_accessible", { message: data.error.message });
    }
    if (data.mimeType && !data.mimeType.startsWith("video/")) {
      return fail(res, 422, "not_a_video_file", { mimeType: data.mimeType });
    }

    return ok(res, {
      id: data.id,
      title: data.name,
      sizeBytes: data.size ? Number(data.size) : null,
      durationMs: data.videoMediaMetadata?.durationMillis
        ? Number(data.videoMediaMetadata.durationMillis)
        : null,
      width: data.videoMediaMetadata?.width || null,
      height: data.videoMediaMetadata?.height || null,
    }, 3600);
  } catch (e) {
    return fail(res, 500, "server_error", { message: e.message });
  }
}
