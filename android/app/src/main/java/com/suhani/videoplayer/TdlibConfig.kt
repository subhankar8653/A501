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
 * Why default false / why this can't just "work" yet:
 *   TDLib integration needs the actual TDLib native library (a prebuilt
 *   `.aar`, e.g. `org.drinkless:tdlib`, or a self-built `.so` via NDK) added
 *   as a Gradle dependency, plus a bot token wired in below. Neither of
 *   those can be done from a sandboxed/offline environment — see the repo's
 *   migration doc, "Suggested order of work" step 1. Flip [ENABLED] to true
 *   only after:
 *     1. The TDLib dependency is added in app/build.gradle (see the TODO
 *        block left there).
 *     2. [BOT_TOKEN] below is set to the app's own bot/service account
 *        token (NOT a per-user personal login — see the migration doc's
 *        "hard constraint").
 *     3. The single-device spike (step 1) and the concurrent-session check
 *        (step 2) in the migration doc have both been done for real, on
 *        real devices.
 */
object TdlibConfig {

    /** Master switch. Keep false until the three prerequisites above are done. */
    const val ENABLED: Boolean = false

    /**
     * The app's own bot/service account token — TDLib authenticates AS THIS
     * BOT, never as the end user's personal Telegram account (hard
     * constraint from the migration doc; do not change this to a
     * phone-number/OTP flow).
     */
    const val BOT_TOKEN: String = "" // TODO: fill in before enabling

    /**
     * If a shared bot token across many concurrent devices turns out to be
     * unreliable (session kick-outs / rate limits — the open risk flagged
     * in the migration doc), flip this to true and provide a per-user/per-
     * pool token lookup instead of the single [BOT_TOKEN] above. Kept as a
     * config toggle (not a rewrite) per the migration doc's requirement.
     */
    const val PER_USER_BOT: Boolean = false

    /** How long to wait for the on-device TDLib path before giving up and
     *  falling back to the Railway proxy for THIS attempt. Keep short —
     *  the fallback should feel instant to the user, not like a hang. */
    const val OPEN_TIMEOUT_MS: Long = 4_000L
}
