"""
In-app update system (APK + OTA UI bundle) and endpoint pool.

Everything is controlled from the owner's Telegram bot (see
Backend/pyrofork/plugins/app_update_admin.py). State lives in MongoDB
(tracking.app_update) so it survives a VPS change; the actual files live in
UPDATE_DIR (a Docker volume) and are re-downloadable from Telegram if the
volume is lost.
"""

import asyncio
import hashlib
import os
import re
import secrets
from datetime import datetime
from pathlib import Path
from typing import Optional

from Backend import db
from Backend.logger import LOGGER

UPDATE_DIR = Path(os.environ.get("UPDATE_DIR", "data/app_updates"))
_COLL = "app_update"
_download_lock = asyncio.Lock()


def _col():
    return db.dbs["tracking"][_COLL]


def apk_path() -> Path:
    return UPDATE_DIR / "app.apk"


def bundle_path() -> Path:
    return UPDATE_DIR / "bundle.html"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


async def sha256_file_async(path: Path) -> str:
    return await asyncio.get_running_loop().run_in_executor(None, sha256_file, path)


async def get_release(kind: str) -> Optional[dict]:
    return await _col().find_one({"_id": kind})


async def save_release(kind: str, data: dict) -> dict:
    doc = dict(data)
    doc["key"] = secrets.token_urlsafe(18)
    doc["created_at"] = datetime.utcnow()
    await _col().replace_one({"_id": kind}, {"_id": kind, **doc}, upsert=True)
    return doc


async def clear_release(kind: str) -> bool:
    res = await _col().delete_one({"_id": kind})
    for p in ((apk_path(),) if kind == "apk" else (bundle_path(),) if kind == "bundle" else ()):
        try:
            p.unlink()
        except FileNotFoundError:
            pass
    return res.deleted_count > 0


# ---------------------------------------------------------------- endpoints
_URL_RE = re.compile(r"^https://[a-z0-9.-]+(:\d+)?$", re.I)


def clean_endpoint(url: str) -> Optional[str]:
    url = (url or "").strip().rstrip("/")
    return url if _URL_RE.match(url) else None


async def get_endpoints() -> list:
    doc = await _col().find_one({"_id": "endpoints"})
    return list((doc or {}).get("list", []))


async def set_endpoints(urls: list) -> list:
    cleaned = []
    for u in urls:
        c = clean_endpoint(u)
        if c and c not in cleaned:
            cleaned.append(c)
    await _col().replace_one({"_id": "endpoints"}, {"_id": "endpoints", "list": cleaned}, upsert=True)
    return cleaned


# ---------------------------------------------------------------- manifest
def _int(v, default=0):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


async def build_manifest(app_vc: int, bundle_v: int) -> dict:
    """What the app should know right now, given the versions it runs."""
    apk = await get_release("apk")
    bundle = await get_release("bundle")

    apk_out = None
    apk_needed = False
    if apk and _int(apk.get("version_code")) > app_vc and (apk_path().exists() or apk.get("file_id")):
        apk_needed = True
        apk_out = {
            "version_code": _int(apk.get("version_code")),
            "version_name": apk.get("version_name", ""),
            "changelog": apk.get("changelog", ""),
            "force": bool(apk.get("force")),
            "url": f"/api/app/update/file/{apk['key']}",
            "sha256": apk.get("sha256", ""),
            "size": _int(apk.get("size")),
        }

    bundle_out = None
    if bundle and _int(bundle.get("version")) > bundle_v:
        # A bundle that needs a newer native shell is skipped until the APK
        # update is installed (apk_out above then carries the notice).
        if _int(bundle.get("min_apk")) <= app_vc:
            bundle_out = {
                "version": _int(bundle.get("version")),
                "changelog": bundle.get("changelog", ""),
                "force": bool(bundle.get("force")),
                "url": f"/api/app/bundle/{bundle['key']}",
                "sha256": bundle.get("sha256", ""),
                "size": _int(bundle.get("size")),
            }

    return {"apk": apk_out, "bundle": bundle_out, "endpoints": await get_endpoints()}


# ------------------------------------------------- self-healing file fetch
async def ensure_file(kind: str) -> Optional[Path]:
    """Return the file on disk; if the volume was lost, re-pull it from Telegram."""
    path = apk_path() if kind == "apk" else bundle_path()
    if path.exists():
        return path
    rel = await get_release(kind)
    if not rel or not rel.get("file_id"):
        return None
    async with _download_lock:
        if path.exists():
            return path
        try:
            from Backend.pyrofork.bot import StreamBot

            UPDATE_DIR.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".part")
            await StreamBot.download_media(rel["file_id"], file_name=str(tmp))
            if rel.get("sha256") and await sha256_file_async(tmp) != rel["sha256"]:
                tmp.unlink(missing_ok=True)
                LOGGER.error(f"app_update: re-downloaded {kind} failed checksum")
                return None
            tmp.replace(path)
            return path
        except Exception as e:
            LOGGER.error(f"app_update: could not restore {kind} from Telegram: {e}")
            return None
