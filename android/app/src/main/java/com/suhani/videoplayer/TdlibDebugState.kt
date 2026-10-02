package com.suhani.videoplayer

/**
 * Internal status string for logs only (no on-screen badge, no toast).
 * Written by [TelegramRoutingDataSource]/[TdlibDownloadHelper], read only
 * for logcat output.
 */
object TdlibDebugState {
    @Volatile var lastStatus: String = "TDLib: idle"
}
