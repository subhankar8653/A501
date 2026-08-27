package com.suhani.videoplayer

import java.io.File

/**
 * A501 — direct phone<->Telegram migration (see migration doc, "Required
 * behavior" #2: downloads should pull directly via TDLib to local storage,
 * same as streaming, instead of proxying through Railway).
 *
 * Mirrors [TdlibDataSource]'s shape and now shares the same real
 * [TdlibClient] wiring. Used from `NativeDownloadManager.start()`
 * (com.suhani.screen): when [TdlibConfig.ENABLED] is false (default),
 * [attemptDirectDownload] returns false immediately and the caller
 * proceeds with the existing HTTP download path, completely unchanged.
 */
object TdlibDownloadHelper {

    /**
     * Attempts to download [streamUrl]'s file directly via TDLib into
     * [outFile]. Returns true only if the TDLib path itself fully handled
     * the download (success OR a definitive TDLib-side error already
     * reported via [onError]) — false means "didn't even try, caller should
     * fall back to the existing HTTP path," which is always what happens
     * while [TdlibConfig.ENABLED] is false.
     */
    fun attemptDirectDownload(
        streamUrl: String,
        outFile: File,
        onProgress: (progressPct: Int, sizeBytes: Long) -> Unit,
        onDone: (file: File) -> Unit,
        onError: (message: String) -> Unit,
    ): Boolean {
        if (!TdlibConfig.ENABLED) {
            TdlibDebugState.lastStatus = "TDLib: download skipped (TdlibConfig.ENABLED=false)"
            return false
        }

        val resolveUrl = TdlibResolveClient.deriveResolveUrl(streamUrl)
        if (resolveUrl == null) {
            // DIAGNOSTIC (was previously silent): this fires when streamUrl
            // doesn't contain "/dl/" — happens if the download quality
            // picker handed us a stream whose `url` isn't Railway's
            // `/dl/{token}/{id}/{name}` proxy shape at all (e.g. a direct
            // external/CDN link some sources return instead). Logged here
            // so it's visible instead of silently falling back.
            TdlibDebugState.lastStatus = "TDLib: download skipped (URL not /dl/ shaped): $streamUrl"
            return false
        }

        val resolution = try {
            TdlibResolveClient.resolve(resolveUrl)
        } catch (e: Exception) {
            // Resolve step itself failed — treat as "didn't attempt" so the
            // caller's existing HTTP fallback (which uses the original
            // streamUrl, not TDLib) still gets a clean shot.
            TdlibDebugState.lastStatus = "TDLib: download resolve failed: ${e.message}"
            return false
        }

        return try {
            TdlibClient.ensureConfigLoaded(streamUrl)
            TdlibDebugState.lastStatus = "TDLib: ACTIVE ✅ (download, not using Railway)"
            TdlibClient.downloadFull(
                chatId = resolution.chatId,
                msgId = resolution.msgId,
                outFile = outFile,
                timeoutMs = TdlibConfig.DOWNLOAD_TIMEOUT_MS,
                onProgress = onProgress,
            )
            onDone(outFile)
            true
        } catch (e: Exception) {
            // A real TDLib download was attempted and it failed — report it
            // rather than silently falling back here. Unlike streaming,
            // there's no FallbackDataSource watching downloads, so the
            // caller needs an explicit signal (per this class's original
            // contract) instead of a false that implies "never tried."
            TdlibDebugState.lastStatus = "TDLib: download failed: ${e.message}"
            onError(e.message ?: "TDLib direct download failed")
            true
        }
    }
}
