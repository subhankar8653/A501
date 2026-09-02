# FEATURE (user ask: report -> Telegram -> owner, with a "done" button that
# fixes the report and notifies the user back): owner-side half of the flow
# started in Backend/helper/report_notify.py. Same authorization + "update
# every admin's copy" pattern as subscription.py's admin_review handler.

from pyrogram import Client, filters
from pyrogram.types import CallbackQuery

from Backend import db
from Backend.helper.report_notify import (
    approver_ids,
    resolve_reporter_info,
    build_resolved_caption,
    notify_user_resolved,
)
from Backend.logger import LOGGER


@Client.on_callback_query(filters.regex(r"^reportdone_([a-fA-F0-9]{24})$"))
async def mark_report_done(client: Client, callback_query: CallbackQuery):
    if callback_query.from_user.id not in approver_ids():
        return await callback_query.answer("You are not authorized to perform this action.", show_alert=True)

    report_id = callback_query.matches[0].group(1)

    #----- Atomic flip so two admins tapping at once can't both "win" and
    # double-notify the user.
    report = await db.resolve_report(report_id)
    if not report:
        return await callback_query.answer("Already marked as done (or not found).", show_alert=True)

    await callback_query.answer("Marked as done ✅")

    admin = callback_query.from_user
    admin_name = admin.first_name or admin.username or f"Admin {admin.id}"
    mention, username_str = await resolve_reporter_info(report["user_id"])
    status_caption = build_resolved_caption(report, mention, username_str, admin_name)

    #----- Update the tapped message, then every other approver's copy
    acting_msg_id = callback_query.message.id
    is_photo = bool(callback_query.message.photo)
    try:
        if is_photo:
            await callback_query.message.edit_caption(status_caption)
        else:
            await callback_query.message.edit_text(status_caption, disable_web_page_preview=True)
    except Exception:
        pass

    for am in report.get("admin_messages", []):
        if am["message_id"] == acting_msg_id:
            continue
        try:
            if is_photo:
                await client.edit_message_caption(chat_id=am["chat_id"], message_id=am["message_id"], caption=status_caption)
            else:
                await client.edit_message_text(chat_id=am["chat_id"], message_id=am["message_id"], text=status_caption, disable_web_page_preview=True)
        except Exception:
            pass

    try:
        await notify_user_resolved(report)
    except Exception as e:
        LOGGER.warning(f"notify_user_resolved failed for report {report_id}: {e}")
