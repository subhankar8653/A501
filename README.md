# Suhani Screen (monorepo)

One repo, two deploy targets:

```
/backend    → Telegram-Stremio FastAPI server, hardened for a clean deploy → Railway
/frontend   → the Suhani Screen web UI (Vite/React)                        → Vercel
```

They don't need to be in the same repo to work — Railway and Vercel each
just need a **Root Directory** pointed at the right folder — but keeping
both here means one `git push` updates everything.

## What's different from the original template

- **Self-update is off by default.** The original code ran a git
  auto-update on every single boot that pulled from a hardcoded
  third-party GitHub repo and hard-reset your entire deployment to
  match it — under that person's git identity. That's now opt-in only
  (`ENABLE_SELF_UPDATE=true` + your own `UPSTREAM_REPO`), so Railway
  boots are faster and nothing gets silently overwritten.
- **No third-party branding/links.** Removed the promotional
  contributors table (another Telegram channel's link), a Colab
  notebook link, "star this repo" instructions, and a couple of
  hardcoded URLs/User-Agent strings that pointed at the original
  author's GitHub — all pointed at your own setup instead, or removed.
- **Addon name** is now "SuhaniBots" instead of the generic "Telegram"
  default.
- No functional/streaming behavior changed — same catalogs, same
  Stremio protocol, same Telegram-sourced streams.

See `backend/README.md` for the full, updated setup/config docs.

## 1. Backend → Railway

1. Railway → New Project → **Deploy from GitHub repo** → select this repo.
2. In the service settings, set **Root Directory** to `backend`.
   (Railway will then find `backend/Dockerfile` automatically.)
3. Go to the service's **Variables** tab and add every key from
   `backend/sample_config.env` (bot token, Mongo URI, admin id, etc.).
   Leave `ENABLE_SELF_UPDATE` unset unless you specifically want the
   auto-update behavior against your own fork.
4. Deploy. Railway gives you a public URL, e.g.
   `https://your-app.up.railway.app` — save it, the frontend needs it.

## 2. Frontend → Vercel

1. Vercel → Add New Project → import this same repo.
2. Set **Root Directory** to `frontend`.
3. Framework preset: **Vite** (auto-detected). Build command / output
   directory: leave as default (`npm run build` / `dist`).
4. Deploy. Vercel gives you a `*.vercel.app` URL.

## 3. Connect them

Open the `*.vercel.app` URL → on the setup screen enter:
- **Backend URL**: the Railway URL from step 1
- **Access token**: from your bot's `/start` (same token used for the
  Stremio addon link, `/stremio/<token>/manifest.json`)

That's it — Home/Search/Detail/Player all talk to your Railway backend
from there. See `frontend/README.md` for frontend-specific notes.
