"""
Owner-only Telegram commands that control in-app updates.

  /setapk      reply to (or caption) an .apk file  -> publish a new APK update
  /setbundle   reply to (or caption) index.html    -> publish an OTA UI update
  /updateinfo                                      -> show what is live now
  /clearupdate apk|bundle                          -> withdraw a release
  /endpoints   [add|rm|set <https://domain> ...]   -> mirror-domain pool

Arguments are `key=value` words on the first line; every following line is
the changelog the user sees in the app. Examples:

  /setapk code=12 name=1.2.0 force
  ✨ Naya player
  🐞 Bug fixes

  /setbundle version=12 min=10
  Naya theme + fixes
"""

import re
from pathlib import Path

from pyrogram import Client, enums, filters
from pyrogram.types import Message

from Backend.config import Telegram
from Backend.helper import app_update as au
from Backend.helper.custom_filter import CustomFilters
from Backend.logger import LOGGER

_OWNER_PRIVATE = filters.private & CustomFilters.owner
_APK_NAME_RE = re.compile(r"_(?P<name>\d+(?:\.\d+)*)_(?P<code>\d+)\.apk$", re.I)


def _parse(message: Message):
    raw = message.text or message.caption or ""
    lines = raw.splitlines()
    head = lines[0].split()[1:] if lines else []
    changelog = "\n".join(lines[1:]).strip()
    kv, flags = {}, set()
    for w in head:
        if "=" in w:
            k, v = w.split("=", 1)
            kv[k.lower()] = v
        else:
            flags.add(w.lower())
    return kv, flags, changelog


def _doc_message(message: Message):
    """The message that actually carries the file (this one, or the replied-to one)."""
    if message.document:
        return message
    if message.reply_to_message and message.reply_to_message.document:
        return message.reply_to_message
    return None


async def _reply(message: Message, text: str):
    await message.reply_text(text, quote=True, parse_mode=enums.ParseMode.DISABLED, disable_web_page_preview=True)


@Client.on_message((filters.command("setapk") | filters.caption & filters.regex(r"^/setapk\b")) & _OWNER_PRIVATE, group=5)
async def set_apk(client: Client, message: Message):
    try:
        carrier = _doc_message(message)
        if not carrier:
            return await _reply(message, "Pehle .apk file bhejo, phir uspar reply karke:\n/setapk code=12 name=1.2.0 [force]\n(changelog agli lines mein)")
        doc = carrier.document
        if not (doc.file_name or "").lower().endswith(".apk"):
            return await _reply(message, "Yeh .apk file nahi hai.")

        kv, flags, changelog = _parse(message)
        m = _APK_NAME_RE.search(doc.file_name or "")
        code = kv.get("code") or (m.group("code") if m else None)
        name = kv.get("name") or (m.group("name") if m else None)
        if not (code and code.isdigit() and name):
            return await _reply(message, "versionCode / versionName nahi mila. Aise likho:\n/setapk code=12 name=1.2.0 [force]")
        code = int(code)

        current = await au.get_release("apk")
        if current and code < int(current.get("version_code", 0)):
            return await _reply(message, f"Live version ({current['version_code']}) se purana code ({code}) nahi laga sakte. Pehle /clearupdate apk karo.")

        status = await message.reply_text("⬇️ APK download ho rahi hai...", quote=True)
        au.UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = au.apk_path().with_suffix(".part")
        await client.download_media(carrier, file_name=str(tmp))

        with open(tmp, "rb") as f:
            if f.read(2) != b"PK":
                tmp.unlink(missing_ok=True)
                return await status.edit_text("❌ File valid APK (zip) nahi lagti.")
        digest = await au.sha256_file_async(Path(tmp))
        tmp.replace(au.apk_path())

        rel = await au.save_release("apk", {
            "version_code": code,
            "version_name": name,
            "changelog": changelog,
            "force": "force" in flags,
            "sha256": digest,
            "size": au.apk_path().stat().st_size,
            "file_id": doc.file_id,
        })
        await status.edit_text(
            f"✅ APK update live\nVersion: {name} ({code})\nSize: {rel['size'] / 1048576:.1f} MB\n"
            f"Force: {'haan' if rel['force'] else 'nahi'}\nUsers ko app khulte hi notification milega."
        )
    except Exception as e:
        LOGGER.error(f"/setapk failed: {e}")
        await _reply(message, f"⚠️ Error: {e}")


@Client.on_message((filters.command("setbundle") | filters.caption & filters.regex(r"^/setbundle\b")) & _OWNER_PRIVATE, group=5)
async def set_bundle(client: Client, message: Message):
    try:
        carrier = _doc_message(message)
        if not carrier:
            return await _reply(message, "Pehle frontend ki dist/index.html file bhejo, phir uspar reply karke:\n/setbundle version=12 [min=10] [force]\n(changelog agli lines mein)")
        doc = carrier.document
        kv, flags, changelog = _parse(message)
        ver = kv.get("version") or kv.get("v")
        if not (ver and ver.isdigit()):
            return await _reply(message, "version nahi mila. Aise likho:\n/setbundle version=12 [min=10] [force]")
        ver = int(ver)

        current = await au.get_release("bundle")
        if current and ver < int(current.get("version", 0)):
            return await _reply(message, f"Live bundle ({current['version']}) se purana version nahi laga sakte. Pehle /clearupdate bundle karo.")
        apk = await au.get_release("apk")
        min_apk = int(kv["min"]) if kv.get("min", "").isdigit() else int((apk or {}).get("version_code", 0))

        status = await message.reply_text("⬇️ Bundle download ho raha hai...", quote=True)
        au.UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = au.bundle_path().with_suffix(".part")
        await client.download_media(carrier, file_name=str(tmp))

        head = tmp.read_text(encoding="utf-8", errors="ignore")
        if "<html" not in head.lower() or 'id="root"' not in head:
            tmp.unlink(missing_ok=True)
            return await status.edit_text("❌ Yeh frontend ka built index.html nahi lagta (id=\"root\" nahi mila).")
        digest = await au.sha256_file_async(Path(tmp))
        tmp.replace(au.bundle_path())

        rel = await au.save_release("bundle", {
            "version": ver,
            "min_apk": min_apk,
            "changelog": changelog,
            "force": "force" in flags,
            "sha256": digest,
            "size": au.bundle_path().stat().st_size,
            "file_id": doc.file_id,
        })
        await status.edit_text(
            f"✅ UI update live (bundle {ver})\nSize: {rel['size'] / 1048576:.2f} MB\nMinimum APK: {min_apk}\n"
            f"Force: {'haan' if rel['force'] else 'nahi'}\nApp background mein download karke 'restart' ka notification dikhayega."
        )
    except Exception as e:
        LOGGER.error(f"/setbundle failed: {e}")
        await _reply(message, f"⚠️ Error: {e}")


@Client.on_message(filters.command("updateinfo") & _OWNER_PRIVATE, group=5)
async def update_info(client: Client, message: Message):
    apk = await au.get_release("apk")
    bundle = await au.get_release("bundle")
    eps = await au.get_endpoints()
    lines = ["📦 Live updates"]
    lines.append(f"APK: {apk['version_name']} ({apk['version_code']}), force={bool(apk.get('force'))}" if apk else "APK: —")
    lines.append(f"Bundle: {bundle['version']} (min apk {bundle.get('min_apk', 0)}), force={bool(bundle.get('force'))}" if bundle else "Bundle: —")
    lines.append("Mirrors: " + (", ".join(eps) if eps else "—"))
    await _reply(message, "\n".join(lines))


@Client.on_message(filters.command("clearupdate") & _OWNER_PRIVATE, group=5)
async def clear_update(client: Client, message: Message):
    kind = (message.command[1].lower() if len(message.command) > 1 else "")
    if kind not in ("apk", "bundle"):
        return await _reply(message, "Use: /clearupdate apk  ya  /clearupdate bundle")
    ok = await au.clear_release(kind)
    await _reply(message, "🗑️ Hata diya." if ok else "Kuch live nahi tha.")


@Client.on_message(filters.command("endpoints") & _OWNER_PRIVATE, group=5)
async def endpoints_cmd(client: Client, message: Message):
    args = message.command[1:]
    eps = await au.get_endpoints()
    if not args:
        return await _reply(message, "Mirror domains (app inhe har update-check par seekhta hai):\n" + ("\n".join(eps) if eps else "—") +
                            "\n\n/endpoints add https://x.com\n/endpoints rm https://x.com\n/endpoints set https://a.com https://b.com")
    action, urls = args[0].lower(), args[1:]
    if action == "add":
        eps = await au.set_endpoints(eps + urls)
    elif action == "rm":
        gone = {u.rstrip("/") for u in urls}
        eps = await au.set_endpoints([e for e in eps if e not in gone])
    elif action == "set":
        eps = await au.set_endpoints(urls)
    else:
        return await _reply(message, "add / rm / set use karo.")
    await _reply(message, "✅ Ab pool:\n" + ("\n".join(eps) if eps else "—"))
