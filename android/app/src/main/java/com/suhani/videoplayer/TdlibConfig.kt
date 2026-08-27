package com.suhani.videoplayer

/**
 * A501 — direct phone<->Telegram migration (see
 * a501-direct-streaming-migration-prompt.md).
 *
 * Single on/off switch for the whole TDLib direct-path feature.
 *
 * ROOT-CAUSE FIX (user ask: "Railway ko download aur stream dono se hata
 * do, only direct hoga"): Telegram-hosted media (any `/dl/{token}/...`
 * URL) ab hamesha seedha TDLib se hi jaata hai — koi Railway HTTP
 * byte-proxy fallback nahi hai, na streaming mein (dekho
 * TelegramRoutingDataSource) na download mein (dekho
 * TdlibDownloadHelper/NativeDownloadManager). Agar TDLib fail ho, wo khud
 * apna local cache clear karke ek baar retry karta hai (dekho
 * TdlibClient.resetFileForRetry) — Railway ki taraf kabhi nahi girta.
 * (Non-Telegram URLs — local files, external CDN/m3u8 links — waise hi
 * direct/local rehte hain jaisa pehle the; unka Railway se kabhi lena-dena
 * nahi tha.)
 *
 * STATUS: the native dependency blocker is gone — libtdjson.so for all
 * four ABIs lives in app/src/main/jniLibs/, with its JNI entry point
 * (io.github.up9cloud.td.JsonClient) bundled alongside. [TdlibClient]
 * wraps that raw JSON interface, and TdlibDataSource/TdlibDownloadHelper
 * are wired to real TDLib calls through it.
 *
 * [API_ID]/[API_HASH]/[BOT_TOKEN] are NOT hardcoded here — Railway already
 * has these as env vars for its own Pyrofork bot (Backend/config.py), so
 * the app fetches them at runtime from `/tdlib-config/{token}`
 * ([TdlibRemoteConfigClient]) the first time TDLib needs to log in, and
 * [TdlibClient] populates these three fields itself. Nothing to fill in
 * by hand — only what's left before flipping [ENABLED]:
 *   1. Real-device testing: single-device spike, then a concurrent-session
 *      check (many devices sharing one bot token logging in at once is the
 *      flagged open risk — see [PER_USER_BOT]).
 */
object TdlibConfig {

    /** Master switch. Keep false until real-device testing (see doc above)
     *  is done. */
    const val ENABLED: Boolean = true

    /** Populated at runtime by [TdlibClient] via [TdlibRemoteConfigClient]
     *  — do not hardcode a value here (see class doc comment). */
    @Volatile var API_ID: Int = 0
    @Volatile var API_HASH: String = ""
    @Volatile var BOT_TOKEN: String = ""

    /**
     * If a shared bot token across many concurrent devices turns out to be
     * unreliable (session kick-outs / rate limits — the open risk flagged
     * in the migration doc), flip this to true and have the backend's
     * `/tdlib-config` endpoint return a per-user/per-pool token instead of
     * the single server-wide one. Kept as a config toggle (not a rewrite)
     * per the migration doc's requirement — the backend-side per-user
     * lookup itself is still a follow-up, not done by this pass.
     */
    const val PER_USER_BOT: Boolean = false

    /** ROOT-CAUSE-2 FIX (Railway hata diya gaya — ab koi HTTP proxy fallback
     *  hai hi nahi jo isse "safe" bana de agar bahut chhota rakha jaaye).
     *  Pehle yeh 4s tha kyunki FallbackDataSource ke paas ek safety net
     *  (Railway) tha — chhota timeout matlab bas jaldi Railway pe gir jaana.
     *  Ab TDLib hi ekmatra (only) path hai, isliye ise itna hi bada rakha hai ki
     *  ek genuinely slow-but-working mobile network ko bhi poora mauka mile
     *  before TdlibClient khud (see resetFileForRetry) cache clear karke
     *  ek baar retry kare. Used for both TdlibDataSource's open()-time probe
     *  and each read()'s range-wait. */
    const val OPEN_TIMEOUT_MS: Long = 8_000L

    /** One-time login wait (config fetch + client creation +
     *  setTdlibParameters + checkAuthenticationBotToken). Only paid once
     *  per process lifetime — can afford to be longer than
     *  [OPEN_TIMEOUT_MS] since a slow first login shouldn't need to fail
     *  every stream open behind it forever; subsequent opens reuse the
     *  already-authenticated client.
     *
     *  SPEED FIX (user report: "play dabane se pehle 10-15 second rukna
     *  padta hai"): iska poora cost ab bhi yahi rehta hai, lekin
     *  [TdlibClient.prewarm] ki wajah se yeh wait ab UI/player setup ke
     *  SAATH (parallel) chalta hai — jaise hi PlayerActivity ko video_uri
     *  milta hai, login yahin se shuru ho jaata hai, play dabane tak nahi
     *  ruka jaata. Isliye asli user-facing delay ab is poore 10s ke bajaye
     *  sirf "video select karne se play dabane tak" ka jo bhi thoda time
     *  bacha hai, wahi hai — zyaadatar cases mein ~0. */
    const val AUTH_TIMEOUT_MS: Long = 10_000L

    /** How long a full (non-streaming) TDLib download via
     *  [TdlibDownloadHelper] is allowed to run before giving up — much
     *  longer than [OPEN_TIMEOUT_MS] since whole-file downloads are
     *  expected to take a while and there's no Railway fallback mid-way
     *  through a download the way there is for playback. */
    const val DOWNLOAD_TIMEOUT_MS: Long = 600_000L
}
