package com.suhani.videoplayer

/**
 * A501 — direct phone<->Telegram migration, TESTING AID ONLY.
 *
 * Right now the only way to know whether a given playback actually used the
 * on-device TDLib path (vs. silently falling back to the Railway proxy) is
 * to separately check Railway's /stream/stats — indirect, and requires a
 * second device/browser. This tiny holder lets [FallbackDataSource] record
 * exactly what happened for the CURRENT playback attempt, and
 * [PlayerActivity] shows it directly on screen (only while
 * [TdlibConfig.ENABLED] is true, so it's invisible to real users) — no PC,
 * no logcat, no separate Railway check needed.
 *
 * Not thread-safe beyond @Volatile (single-writer: FallbackDataSource's
 * worker thread and main thread both only ever write the whole string, so
 * torn reads aren't a concern here).
 */
object TdlibDebugState {
    @Volatile var lastStatus: String = "TDLib: idle"
}
