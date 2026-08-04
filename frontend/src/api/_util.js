// Chhoti shared helper — har endpoint isse CORS + error format ke liye use karta hai,
// taaki Android app / admin panel dono se seedha fetch ho sake.

export function cors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
}

export function fail(res, status, error, extra = {}) {
  cors(res);
  return res.status(status).json({ ok: false, error, ...extra });
}

export function ok(res, data, cacheSeconds = 0) {
  cors(res);
  if (cacheSeconds > 0) {
    res.setHeader("Cache-Control", `s-maxage=${cacheSeconds}, stale-while-revalidate`);
  }
  return res.status(200).json({ ok: true, ...data });
}

export function requireApiKey() {
  const key = process.env.GOOGLE_API_KEY;
  if (!key) {
    throw new Error(
      "GOOGLE_API_KEY env var set nahi hai. Vercel project settings -> Environment " +
      "Variables mein Google Cloud Console se banaya hua Drive API key daalo."
    );
  }
  return key;
}

/** Kisi bhi format ka Drive URL/ID diya ho, seedha File/Folder ID nikaal deta hai —
 *  taaki admin panel mein poora link paste karna kaafi ho, ID nikalna manual na karna pade. */
export function extractDriveId(input) {
  if (!input) return null;
  const trimmed = input.trim();
  // Already a bare ID (Drive IDs are alphanumeric + - _ , usually 25-40+ chars)
  if (/^[a-zA-Z0-9_-]{10,}$/.test(trimmed) && !trimmed.includes("/")) return trimmed;

  const patterns = [
    /\/d\/([a-zA-Z0-9_-]{10,})/,        // .../file/d/ID/view  or /folders/ID
    /\/folders\/([a-zA-Z0-9_-]{10,})/,
    /[?&]id=([a-zA-Z0-9_-]{10,})/,      // ...open?id=ID or uc?id=ID
  ];
  for (const p of patterns) {
    const m = trimmed.match(p);
    if (m) return m[1];
  }
  return null;
}
