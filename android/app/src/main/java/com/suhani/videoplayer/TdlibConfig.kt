package com.suhani.videoplayer

/**
 * A501 — direct phone<->Telegram migration (see
 * a501-direct-streaming-migration-prompt.md).
 *
 * Single on/off switch for the whole TDLib direct-path feature. Everything
 * added by this migration (TdlibDataSource, the download-side TDLib path)
 * checks this flag FIRST and, if false, skips straight to the existing
 * Railway-proxy path — so merging this scaffolding changes NOTHING about
 * current behavior until someone deliberately flips it on.
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
    const val ENABLED: Boolean = false

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

    /** How long to wait for the on-device TDLib path before giving up and
     *  falling back to the Railway proxy for THIS attempt (used both for
     *  TdlibDataSource's open()-time probe and each read()'s range-wait).
     *  Keep short — the fallback should feel instant to the user, not like
     *  a hang. */
    const val OPEN_TIMEOUT_MS: Long = 4_000L

    /** One-time login wait (config fetch + client creation +
     *  setTdlibParameters + checkAuthenticationBotToken). Only paid once
     *  per process lifetime — can afford to be longer than
     *  [OPEN_TIMEOUT_MS] since a slow first login shouldn't need to fail
     *  every stream open behind it forever; subsequent opens reuse the
     *  already-authenticated client. */
    const val AUTH_TIMEOUT_MS: Long = 10_000L

    /** How long a full (non-streaming) TDLib download via
     *  [TdlibDownloadHelper] is allowed to run before giving up — much
     *  longer than [OPEN_TIMEOUT_MS] since whole-file downloads are
     *  expected to take a while and there's no Railway fallback mid-way
     *  through a download the way there is for playback. */
    const val DOWNLOAD_TIMEOUT_MS: Long = 600_000L
}
