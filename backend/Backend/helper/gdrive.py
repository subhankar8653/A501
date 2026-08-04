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


#----- GET /api/title -> {title, sizeBytes, ...}
async def fetch_title(file_id: str) -> dict:
    data = await _get_json("/api/title", file_id)
    return {
        "title": data.get("title"),
        "size_bytes": data.get("sizeBytes"),
    }


#----- GET /api/resolve -> {extracted, qualities[] | fallback:"iframe", previewUrl}
async def resolve_stream(file_id: str) -> dict:
    return await _get_json("/api/resolve", file_id)
