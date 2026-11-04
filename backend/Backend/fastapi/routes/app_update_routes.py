from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse, JSONResponse

from Backend.helper import app_update as au

router = APIRouter(tags=["App"])

_NO_STORE = {"Cache-Control": "no-store"}


#----- Public: the app asks "is there anything newer than what I run?"
@router.get("/api/app/update")
async def app_update_check(vc: int = Query(0, ge=0), bv: int = Query(0, ge=0)):
    data = await au.build_manifest(vc, bv)
    return JSONResponse({"status": "success", "data": data}, headers=_NO_STORE)


#----- Tiny reachability probe used by the app's endpoint failover
@router.get("/api/app/ping")
async def app_ping():
    return JSONResponse({"ok": True}, headers=_NO_STORE)


async def _serve(kind: str, key: str, media_type: str, filename: str):
    rel = await au.get_release(kind)
    if not rel or rel.get("key") != key:
        raise HTTPException(status_code=404, detail="Not found")
    path = await au.ensure_file(kind)
    if not path:
        raise HTTPException(status_code=404, detail="Not found")
    return FileResponse(path, media_type=media_type, filename=filename, headers=_NO_STORE)


#----- APK download (key is a per-release random secret handed out by /update)
@router.get("/api/app/update/file/{key}")
async def app_update_file(key: str):
    return await _serve("apk", key, "application/vnd.android.package-archive", "update.apk")


#----- OTA UI bundle (single-file index.html built by `npm run build`)
@router.get("/api/app/bundle/{key}")
async def app_bundle_file(key: str):
    return await _serve("bundle", key, "text/html; charset=utf-8", "bundle.html")
