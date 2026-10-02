from os import getenv, path

from dotenv import load_dotenv

load_dotenv(path.join(path.dirname(path.dirname(__file__)), "config.env"))


def _int_env(key: str, default: int = 0) -> int:
    try:
        return int((getenv(key) or "").strip())
    except ValueError:
        return default


#----- Environment-backed configuration
class Telegram:
    #----- Required: Telegram clients
    API_ID              = _int_env("API_ID")
    API_HASH            = getenv("API_HASH", "")
    BOT_TOKEN           = getenv("BOT_TOKEN", "")
    USER_SESSION_STRING = getenv("USER_SESSION_STRING", "")

    #----- Phone (on-device TDLib) bot pool — see /tdlib-config in stream_routes.py.
    #----- Phones NEVER receive BOT_TOKEN (the main bot). They get a token from this
    #----- comma-separated pool of throwaway "phone bots" instead, so a leaked token
    #----- can be revoked in BotFather without touching the main bot.
    PHONE_BOT_TOKENS = [t.strip() for t in (getenv("PHONE_BOT_TOKENS") or "").split(",") if t.strip()]
    #----- Emergency opt-in only: hand the MAIN bot token to phones when no pool is
    #----- configured (the old, insecure behaviour). Leave unset/false.
    ALLOW_MAIN_BOT_FOR_PHONES = (getenv("ALLOW_MAIN_BOT_FOR_PHONES", "") or "").strip().lower() in ("1", "true", "yes")
    #----- Abuse limits for /tdlib-config (per API token)
    PHONE_MAX_DEVICES_PER_TOKEN = _int_env("PHONE_MAX_DEVICES_PER_TOKEN", 3)
    PHONE_CONFIG_RATE_PER_HOUR  = _int_env("PHONE_CONFIG_RATE_PER_HOUR", 30)

    #----- Required: Database URIs
    DATABASE = [db.strip() for db in (getenv("DATABASE") or "").split(",") if db.strip()]

    #----- Required: Server
    PORT     = _int_env("PORT", 8000)
    OWNER_ID = _int_env("OWNER_ID")

    #----- Read/Write via SettingsManager
    REPLACE_MODE                  = getenv("REPLACE_MODE", "true").lower() == "true"
    HIDE_CATALOG                  = getenv("HIDE_CATALOG", "false").lower() == "true"
    AUTH_CHANNEL                  = [c.strip() for c in (getenv("AUTH_CHANNEL") or "").split(",") if c.strip()]
    TMDB_API                      = getenv("TMDB_API", "")
    BASE_URL                      = getenv("BASE_URL", "").rstrip("/")
    UPSTREAM_REPO                 = getenv("UPSTREAM_REPO", "")
    UPSTREAM_BRANCH               = getenv("UPSTREAM_BRANCH", "")
    ADMIN_USERNAME                = getenv("ADMIN_USERNAME", "admin")
    ADMIN_PASSWORD                = getenv("ADMIN_PASSWORD", "admin")
    SUBSCRIPTION                  = getenv("SUBSCRIPTION", "false").lower() == "true"
    SUBSCRIPTION_GROUP_ID         = _int_env("SUBSCRIPTION_GROUP_ID")
    APPROVER_IDS                  = [int(x.strip()) for x in (getenv("APPROVER_IDS") or "").split(",") if x.strip().isdigit()]
    HTTP_PROXY_URL                = getenv("HTTP_Proxy_URL", "")
    SHOW_PROXY_AND_NON_PROXY_BOTH = getenv("SHOW_ProxyAndNonProxyBoth", "false").lower() == "true"

    #----- App sign-up log channel: chat id (e.g. -100xxxxxxxxxx) that gets a
    #----- notification every time someone signs up in the Huka Tube app via
    #----- the "Sign up with Telegram" button. Leave blank to disable.
    APP_SIGNUP_LOG_CHANNEL        = getenv("APP_SIGNUP_LOG_CHANNEL", "")

    #----- Google Drive: base URL of the Vercel project (frontend/src/api/*.js)
    #----- used to fetch Drive file titles and resolve direct stream URLs.
    GDRIVE_RESOLVER_BASE          = getenv("GDRIVE_RESOLVER_BASE", "").rstrip("/")

    #----- Google Drive: official Drive API v3 key (Drive API enabled in Google
    #----- Cloud Console). When set, streaming proxies bytes through this backend
    #----- via the official alt=media endpoint instead of redirecting to the
    #----- Vercel resolver's scraped (IP-locked) googlevideo.com link. Files must
    #----- be shared "Anyone with the link". Falls back to GDRIVE_RESOLVER_BASE
    #----- when this is not set.
    GDRIVE_API_KEY                 = getenv("GDRIVE_API_KEY", "")

    #----- Capacity tuning for small servers (default assumes ~1GB RAM / 2 cores):
    #----- MAX_CONCURRENT_STREAMS caps how many viewers can be actively receiving
    #----- video bytes at once — beyond this, new requests wait briefly then get a
    #----- clean 503 (Retry-After) instead of the process running out of memory.
    #----- MAX_STREAM_PARALLELISM caps how many simultaneous Telegram chunk-fetches
    #----- a SINGLE viewer's stream may use (was hardcoded to 5) — lower means less
    #----- RAM/work_load per viewer, so more viewers fit in the same box.
    MAX_CONCURRENT_STREAMS          = _int_env("MAX_CONCURRENT_STREAMS", 40)
    MAX_STREAM_PARALLELISM          = _int_env("MAX_STREAM_PARALLELISM", 2)
