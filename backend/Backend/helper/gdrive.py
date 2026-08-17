import re
from typing import Optional

import httpx

from Backend.config import Telegram as Config

#----- Same patterns as frontend/src/api/_util.js -> extractDriveId (kept in sync)
_ID_PATTERNS = [
    re.compile(r"/d/([a-zA-Z0-9_-]{10,})"),        # .../file/d/ID/view
    re.compile(r"/folders/([a-zA-Z0-9_-]{10,})"),  # .../folders/ID
    re.compile(r"[?&]id=([a-zA-Z0-9_-]{10,})"),    # ...open?id=ID or uc?id=ID
]
_BARE_ID = re.compile(r"^[a-zA-Z0-9_-]{10,}$")

_client: Optional[httpx.AsyncClient] = None


class GDriveError(Exception):
    """Raised whenever the Vercel resolver can't be reached or refuses the file."""


#----- Pull a bare Drive file/folder ID out of any share-link format
def extract_drive_id(raw: str) -> Optional[str]:
    if not raw:
        return None
    trimmed = raw.strip()
    if _BARE_ID.match(trimmed) and "/" not in trimmed:
        return trimmed
    for pattern in _ID_PATTERNS:
        m = pattern.search(trimmed)
        if m:
            return m.group(1)
    return None


async def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=20.0)
    return _client


def _resolver_base() -> str:
    base = (Config.GDRIVE_RESOLVER_BASE or "").rstrip("/")
    if not base:
        raise GDriveError(
            "GDRIVE_RESOLVER_BASE is not set. Add it in your Railway/Backend env vars "
            "as your Vercel project URL, e.g. https://your-project.vercel.app"
        )
    return base


async def _get_json(path: str, file_id: str) -> dict:
    base = _resolver_base()
    client = await _get_client()
    try:
        r = await client.get(f"{base}{path}", params={"id": file_id})
    except Exception as e:
        raise GDriveError(f"Could not reach the Vercel resolver: {e}")
    try:
        data = r.json()
    except Exception:
        raise GDriveError(f"Vercel resolver returned an unexpected response ({r.status_code}).")
    if r.status_code != 200 or not data.get("ok"):
        raise GDriveError(data.get("message") or f"Drive request failed ({r.status_code}).")
    return data


#----- GET /api/title -> {title, sizeBytes, width, height, ...}
async def fetch_title(file_id: str) -> dict:
    data = await _get_json("/api/title", file_id)
    return {
        "title": data.get("title"),
        "size_bytes": data.get("sizeBytes"),
        "width": data.get("width"),
        "height": data.get("height"),
    }


#----- GET /api/resolve -> {extracted, qualities[] | fallback:"iframe", previewUrl}
async def resolve_stream(file_id: str) -> dict:
    return await _get_json("/api/resolve", file_id)


#======================================================================
# Official Drive API v3 proxy path (API-key auth)
#
# Root-cause fix for the black-screen bug: the Vercel resolver above scrapes
# an unofficial googlevideo.com link. That link is bound to the IP that
# fetched it (Vercel's server IP) — when we 302-redirect the Android client
# straight to it, Google's CDN sees a different IP and refuses to serve the
# video, so ExoPlayer just sits there with nothing to render.
#
# The official Drive API v3 "alt=media" endpoint has no such IP-binding and
# fully supports Range requests, so instead of redirecting the client to a
# scraped link, WE (the backend) fetch bytes from it and stream them onward —
# same pattern as the existing Telegram media_streamer(). This needs
# GDRIVE_API_KEY (a Google Cloud API key with the Drive API enabled) and the
# Drive file/folder shared as "Anyone with the link".
#======================================================================

_DRIVE_API_BASE = "https://www.googleapis.com/drive/v3/files"


class GDriveAPIError(Exception):
    """Raised when the official Drive API v3 endpoint can't serve the file."""


def api_key_configured() -> bool:
    return bool((Config.GDRIVE_API_KEY or "").strip())


def _api_key() -> str:
    key = (Config.GDRIVE_API_KEY or "").strip()
    if not key:
        raise GDriveAPIError(
            "GDRIVE_API_KEY is not set. Add it in your Railway/Backend env vars — "
            "create an API key in Google Cloud Console with the Drive API v3 enabled."
        )
    return key


#----- Shared httpx client, reused by the proxy streamer in stream_routes.py
async def get_shared_client() -> httpx.AsyncClient:
    return await _get_client()


#----- GET /files/{id}?fields=name,size,mimeType via the official Drive API v3.
#----- Requires the file to be shared "Anyone with the link".
async def fetch_official_metadata(file_id: str) -> dict:
    key = _api_key()
    client = await _get_client()
    try:
        r = await client.get(
            f"{_DRIVE_API_BASE}/{file_id}",
            params={"key": key, "fields": "name,size,mimeType"},
        )
    except Exception as e:
        raise GDriveAPIError(f"Could not reach the Google Drive API: {e}")
    if r.status_code != 200:
        message = r.text[:200] if r.text else f"HTTP {r.status_code}"
        raise GDriveAPIError(f"Drive API metadata request failed ({r.status_code}): {message}")
    return r.json()


#----- Official, non-IP-locked, Range-capable direct media URL
def official_media_url(file_id: str) -> str:
    return f"{_DRIVE_API_BASE}/{file_id}?alt=media&key={_api_key()}"
