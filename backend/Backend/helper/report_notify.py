# FEATURE (user ask: "report kuchh aise kaam karna chahiye ki user ne
# report kiya to sidha Telegram pe jo bot hai uske sahare owner ke paas msg
# jaaye, video ke details ke saath, aur ek 'done' option ho jisse fix hone
# par user ko wapas notification chala jaaye"): this module builds the
# admin-facing report card + fires it to every approver, and later builds
# the "resolved" caption/DM once someone taps Done. The actual button
# handling lives in Backend/pyrofork/plugins/reports.py (same split as
# subscription.py: helper builds text/keyboard, plugin owns the callback).

from pyrogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from Backend import db
from Backend.config import Telegram
from Backend.helper.settings_manager import SettingsManager
from Backend.logger import LOGGER
from Backend.pyrofork.bot import StreamBot

REASON_LABELS = {
    "audio": "🔇 Audio issue",
    "subtitle": "💬 Subtitle issue",
    "broken": "⚠️ Broken / won't play",
    "quality": "📉 Quality issue",
    "other": "❓ Other",
}


#----- Same fallback-to-owner pattern as subscription.py's approvers
def approver_ids() -> list:
    return SettingsManager.current().approver_ids or [Telegram.OWNER_ID]


async def resolve_reporter_info(user_id: int):
    try:
        user = await StreamBot.get_users(user_id)
        return user.mention, (f"@{user.username}" if user.username else "N/A")
    except Exception:
        return f"User {user_id}", "N/A"


def _reason_label(reason: str) -> str:
    return REASON_LABELS.get(reason, reason)


def _build_report_caption(report: dict, mention: str, username_str: str) -> str:
    lines = [
        "🚩 <b>New Report</b>",
        "",
        f"👤 <b>User:</b> {mention}",
        f"🆔 <b>User ID:</b> <code>{report['user_id']}</code>",
        f"🔗 <b>Username:</b> {username_str}",
        "",
        f"🎬 <b>Title:</b> {report.get('title') or 'Unknown'}",
        f"🗂 <b>Type:</b> {'Series' if report['media_type'] in ('tv', 'series') else 'Movie'}",
    ]
    if report.get("season") and report.get("episode"):
        lines.append(f"📺 <b>Episode:</b> S{report['season']}E{report['episode']}")
    lines.append(f"🆔 <b>Media ID:</b> <code>{report['media_id']}</code>")
    lines.append(f"⚠️ <b>Reason:</b> {_reason_label(report['reason'])}")
    if report.get("note"):
        lines.append(f"📝 <b>Details:</b> {report['note']}")
    return "\n".join(lines)


#----- Fires right after db.add_report() succeeds. Runs as a background task
# from the API route so the user's report submission doesn't wait on
# Telegram round-trips; failures are logged, never raised back to the user.
async def notify_admins_new_report(report: dict) -> None:
    report_id = str(report["_id"])
    mention, username_str = await resolve_reporter_info(report["user_id"])
    caption = _build_report_caption(report, mention, username_str)
    keyboard = InlineKeyboardMarkup(
        [[InlineKeyboardButton("✅ Mark as Done", callback_data=f"reportdone_{report_id}")]]
    )

    admin_messages = []
    poster = report.get("poster")
    for approver_id in approver_ids():
        try:
            if poster:
                sent = await StreamBot.send_photo(approver_id, poster, caption=caption, reply_markup=keyboard)
            else:
                sent = await StreamBot.send_message(
                    approver_id, caption, reply_markup=keyboard, disable_web_page_preview=True
                )
            admin_messages.append({"chat_id": approver_id, "message_id": sent.id})
        except Exception as e:
            LOGGER.error(f"Failed to forward report {report_id} to approver {approver_id}: {e}")

    if admin_messages:
        await db.set_report_admin_messages(report_id, admin_messages)


#----- Builds the "✅ Fixed by <admin>" version of the same card, used to
# update every approver's copy once one of them taps Done.
def build_resolved_caption(report: dict, mention: str, username_str: str, admin_name: str) -> str:
    return _build_report_caption(report, mention, username_str) + f"\n\n✅ <b>Fixed by {admin_name}</b>"


#----- DMs the original reporter once their report is marked done.
async def notify_user_resolved(report: dict) -> None:
    title = report.get("title") or "the title you reported"
    text = f"✅ <b>Good news!</b>\n\nThe issue you reported for <b>{title}</b>"
    if report.get("season") and report.get("episode"):
        text += f" (S{report['season']}E{report['episode']})"
    text += (
        f" — {_reason_label(report['reason'])} — has been fixed.\n\n"
        "Thanks for reporting it, it helps us keep things running smoothly! 🙏"
    )

    try:
        poster = report.get("poster")
        if poster:
            await StreamBot.send_photo(report["user_id"], poster, caption=text)
        else:
            await StreamBot.send_message(report["user_id"], text)
    except Exception as e:
        LOGGER.warning(f"Could not DM user {report.get('user_id')} about resolved report: {e}")
