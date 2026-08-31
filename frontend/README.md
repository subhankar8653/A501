

A browsing + playback UI on top of your existing **Telegram-Stremio** FastAPI
backend. It does not talk to any piracy embed API (VidSrc, Embed.su, etc.) —
it only consumes your own backend's Stremio-protocol endpoints
(`manifest` / `catalog` / `meta` / `stream`), which serve content from your
own Telegram channels.

## How it fits together

- **Backend (Railway)** — your existing `Telegram-Stremio-master/Backend`,
  deployed with the Dockerfile already in the repo. No backend code changes
  are required — it already has `CORS allow_origins=["*"]`, so the Vercel
  frontend can call it directly.
- **Frontend (Vercel)** — this folder. A static Vite/React app that:
  - asks once for your backend URL + access token (`/setup`), stored in
    the browser's localStorage
  - reads `manifest.json` to build Movie / Series / Anime / K-Drama rails
    (anime and K-Drama show up automatically if you name your custom
    catalogs "Anime" / "K-Drama" in the admin dashboard)
  - search, title detail with season/episode picker, and a player page
    that plays the direct/proxy `.mkv` stream URL your backend already
    returns

## Deploy the backend to Railway

1. Push `Telegram-Stremio-master/` to a GitHub repo (or use Railway's
   "Deploy from GitHub" / CLI).
2. Railway → New Project → Deploy from repo → it will detect the
   `Dockerfile` automatically.
3. Set the environment variables from `sample_config.env` in Railway's
   Variables tab (bot token, Mongo URI, etc.) — same as any other
   deployment target for this project.
4. Once deployed, note the public URL Railway gives you, e.g.
   `https://your-app.up.railway.app`.
5. Get your access token the same way you already do for Stremio — via
   the bot's `/start` flow / addon link
   (`/stremio/<token>/manifest.json`).

## Deploy the frontend to Vercel

1. Push this `frontend/` folder to a GitHub repo (own repo, or a
   subfolder of the same one).
2. Vercel → Add New Project → import the repo.
   - If it's a subfolder, set **Root Directory** to `frontend`.
   - Framework preset: **Vite**.
   - Build command: `npm run build` (default).
   - Output directory: `dist` (default).
3. Deploy. No environment variables needed at build time — backend URL
   + token are entered once in the app itself and stored in the
   browser.
4. Open the deployed `*.vercel.app` URL → enter your Railway backend URL
   and token on the setup screen.

## Local dev

```bash
npm install
npm run dev
```

## Notes

- Anime / K-Drama sections are just custom catalogs from your backend's
  admin dashboard — name a custom catalog "Anime" or "K-Drama" and it
  will get its own home-page rail automatically (see `groupCatalogs` in
  `src/api.js` if you want to match on a different name).
- The player uses a plain `<video>` tag against the `.mkv` stream URL
  your backend already serves (proxy or direct, whichever your
  `SettingsManager` is configured to return). If you want HLS-style
  adaptive streaming later, that's a backend-side change (transcoding),
  not a frontend one.
