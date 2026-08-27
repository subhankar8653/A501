package com.suhani.videoplayer

/**
 * A501 — direct phone<->Telegram migration, TESTING AID ONLY.
 *
 * Lets [TelegramRoutingDataSource]/[TdlibDownloadHelper] record exactly
 * what happened for the CURRENT playback/download attempt, and
 * [PlayerActivity] shows it directly on screen (only while
 * [TdlibConfig.ENABLED] is true, so it's invisible to real users) — no PC,
 * no logcat needed. Railway's HTTP `/dl/` proxy is no longer used as a
 * fallback for Telegram media at all, so this badge now only ever reports
 * genuine TDLib success/failure, never a silent Railway hand-off.
 *
 * Not thread-safe beyond @Volatile (single-writer at a time in practice —
 * whichever worker thread is actively handling the current attempt — so
 * torn reads aren't a concern here).
 */
object TdlibDebugState {
    // Bumped by hand every time this file (or the surrounding debug-badge
    // code) changes — so a screenshot instantly proves whether the running
    // APK actually has the latest fix or an older build got tested by
    // mistake, instead of us guessing from unchanged symptoms.
    const val BUILD_MARKER: String = "dbg-build-11"

    @Volatile var lastStatus: String = "TDLib: idle"
}
