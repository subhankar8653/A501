from datetime import datetime

from pyrogram import Client, enums, filters
from pyrogram.types import InlineKeyboardButton, InlineKeyboardMarkup, Message

from Backend import db
from Backend.config import Telegram
from Backend.helper.settings_manager import SettingsManager
from Backend.logger import LOGGER

#----- Prefix used on the deep-link code the Huka Tube app's "Sign up with
#----- Telegram" button hides inside https://t.me/<bot>?start=su_<code> —
#----- keeps it unambiguous from any other future /start payload.
APP_SIGNUP_PREFIX = "su_"


#----- App sign-up: user tapped the in-app "Sign up with Telegram" button,
#----- which deep-linked here with a one-time code. Verify the Telegram
#----- identity, hand them an API token, save their name/photo as their app
#----- profile, and let the (polling) app pick it up automatically.
async def _handle_app_signup(client: Client, message: Message, code: str):
    signup = await db.get_app_signup(code)
    if not signup or signup.get("status") == "expired":
        await message.reply_text(
            "⚠️ Yeh sign-up link expire ho chuka hai.\n"
            "App mein wapas jaakar dubara \"Sign up with Telegram\" par tap karo.",
            quote=True,
        )
        return

    user = message.from_user
    if not user:
        await message.reply_text("⚠️ Sign-up sirf personal Telegram account se ho sakta hai.", quote=True)
        return

    display_name = user.first_name or user.username or f"User {user.id}"
    token_doc = await db.ensure_api_token_for_user(user.id, display_name)
    token = token_doc.get("token") if token_doc else None
    if not token:
        await message.reply_text("⚠️ Kuch galat ho gaya, dubara try karo.", quote=True)
        return

    #----- Best-effort profile photo download (skipped quietly if user has none)
    photo_b64 = None
    try:
        if user.photo:
            import base64
            photo_bytes = await client.download_media(user.photo.big_file_id, in_memory=True)
            if photo_bytes:
                photo_b64 = base64.b64encode(photo_bytes.getvalue()).decode("ascii")
    except Exception as e:
        LOGGER.warning(f"App sign-up: couldn't fetch profile photo for {user.id}: {e}")

    profile = {
        "first_name": user.first_name or "",
        "last_name": user.last_name or "",
        "username": user.username or "",
        "photo_b64": photo_b64,
    }
    await db.complete_app_signup(code, user.id, token, profile)

    await message.reply_text(
        f"✅ <b>Welcome, {display_name}!</b>\n\n"
        "Aapka Huka Tube account ban gaya. App mein wapas jao — automatically sign in ho jaoge.",
        quote=True,
        parse_mode=enums.ParseMode.HTML,
    )

    log_channel = (Telegram.APP_SIGNUP_LOG_CHANNEL or "").strip()
    if log_channel:
        try:
            chat_id = int(log_channel) if log_channel.lstrip("-").isdigit() else log_channel
            handle = f"@{user.username}" if user.username else "—"
            await client.send_message(
                chat_id,
                "🆕 <b>New Huka Tube app sign-up</b>\n\n"
                f"👤 <b>Name:</b> {display_name}\n"
                f"🔗 <b>Username:</b> {handle}\n"
                f"🆔 <b>Telegram ID:</b> <code>{user.id}</code>\n"
                f"🕒 <b>Time:</b> {datetime.utcnow().strftime('%Y-%m-%d %H:%M UTC')}",
                parse_mode=enums.ParseMode.HTML,
            )
        except Exception as e:
            LOGGER.warning(f"App sign-up: couldn't post to log channel: {e}")


#----- /start: hand out the Stremio addon link, gated by subscription state
@Client.on_message(filters.command('start') & filters.private, group=10)
async def send_start_message(client: Client, message: Message):
    try:
        #----- App sign-up deep link (t.me/<bot>?start=su_<code>) takes over
        #----- completely — it's a different flow from the normal addon-link
        #----- reply below, and must never fall through to it.
        payload = message.command[1] if len(message.command) > 1 else None
        if payload and payload.startswith(APP_SIGNUP_PREFIX):
            await _handle_app_signup(client, message, payload[len(APP_SIGNUP_PREFIX):])
            return

        user_id = (message.from_user.id if message.from_user else None) or (message.sender_chat.id if message.sender_chat else None) or message.chat.id
        base_url = SettingsManager.current().base_url
        addon_url = f"{base_url}/stremio/manifest.json"

        #----- No subscription mode: owner-only, single personal token
        if not SettingsManager.current().subscription:
            if user_id != Telegram.OWNER_ID:
                return
            user_name = (message.from_user.first_name or message.from_user.username or f"User {user_id}") if message.from_user else f"Chat {user_id}"
            try:
                token_doc = await db.add_api_token(name=user_name, user_id=user_id)
                addon_url = f"{base_url}/stremio/{token_doc.get('token')}/manifest.json"
            except Exception as e:
                LOGGER.error(f"Error ensuring token for free user: {e}")

            await message.reply_text(
                '🎉 <b>Welcome to the Telegram Stremio Media Server!</b>\n\n'
                'Here is your personal Stremio Addon link:\n\n'
                '🎬 <b>Stremio Addon — Install Link:</b>\n'
                f'<code>{addon_url}</code>\n\n'
                'Tap the link above → <b>Install</b> in Stremio to start watching!',
                quote=True,
                parse_mode=enums.ParseMode.HTML
            )
            return

        #----- Subscription mode: verify active subscription, else offer plans
        user = await db.get_user(user_id)
        now = datetime.utcnow()

        is_active = db.is_subscription_active(user, now)
        if not is_active and user and user.get("subscription_status") == "active":
            await db.mark_user_expired(user_id)

        #----- Honour a manual token grant (never-expires or a future token expiry)
        if not is_active:
            token_doc = await db.get_api_token_by_user(user_id)
            if token_doc and (token_doc.get("subscription_exempt")
                              or (token_doc.get("expires_at") and token_doc["expires_at"] > now)):
                is_active = True

        if not is_active:
            plans = await db.get_subscription_plans()
            if not plans:
                return await message.reply_text(
                    '<b>Welcome to the Telegram Stremio Private Group!</b>\n\n'
                    'Currently, no subscription plans are set up. Please contact the administrator.',
                    quote=True,
                    parse_mode=enums.ParseMode.HTML
                )

            keyboard = InlineKeyboardMarkup([
                [InlineKeyboardButton(f"{plan['days']} Days - ₹{plan['price']}", callback_data=f"plan_{plan['_id']}")]
                for plan in plans
            ])
            return await message.reply_text(
                '<b>Welcome to the Telegram Stremio Private Group!</b>\n\n'
                'Access to this bot and the Stremio Addon requires an active subscription.\n'
                'Please select a subscription plan below to continue:',
                reply_markup=keyboard,
                quote=True,
                parse_mode=enums.ParseMode.HTML
            )

        #----- Active subscriber: return their token link, creating one if missing
        user_name = (user.get("first_name") or user.get("username")) if user else None
        token_doc = await db.ensure_api_token_for_user(user_id, user_name)
        if token_doc and token_doc.get("token"):
            addon_url = f"{base_url}/stremio/{token_doc['token']}/manifest.json"

        await message.reply_text(
            '🎉 <b>Welcome back to the Telegram Stremio Subscription Manager!</b>\n\n'
            'Your subscription is active. Here is your personal addon link:\n\n'
            '🎬 <b>Stremio Addon — Install Link:</b>\n'
            f'<code>{addon_url}</code>\n\n'
            'Tap the link above → <b>Install</b> in Stremio to start watching!',
            quote=True,
            parse_mode=enums.ParseMode.HTML
        )

    except Exception as e:
        await message.reply_text(f"⚠️ Error: {e}")
        LOGGER.error(f"Error in /start handler: {e}")
