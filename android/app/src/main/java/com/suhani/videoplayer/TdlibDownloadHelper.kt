package com.suhani.videoplayer

import java.io.File

/**
 * A501 — direct phone<->Telegram migration (see migration doc, "Required
 * behavior" #2: downloads should pull directly via TDLib to local storage,
 * same as streaming, instead of proxying through Railway).
 *
 * ROOT-CAUSE FIX (user ask: "Railway ko download se poori tarah hata do"):
 * previously, if [TdlibResolveClient.resolve] itself hiccuped (a plain
 * network blip on the resolve API call — NOT a TDLib failure), this
 * returned false, and the caller ([NativeDownloadManager]) would then
 * download the actual media bytes over plain HTTP straight from
 * `streamUrl` — which IS Railway's `/dl/` proxy URL. So a transient resolve
 * hiccup used to quietly cost Railway real bandwidth for the whole file.
 * Now a resolve failure gets one retry, and if that still fails it's
 * reported as a genuine error (via [onError], returning true) instead of
 * ever falling through to that HTTP/Railway path.
 *
 * Mirrors [TdlibDataSource]'s shape and shares the same real [TdlibClient]
 * wiring (which itself retries once, clearing its local cache, on a
 * download failure — see [TdlibClient.resetFileForRetry]). Used from
 * `NativeDownloadManager.start()` (com.suhani.screen): when
 * [TdlibConfig.ENABLED] is false, or the URL genuinely isn't a Telegram
 * `/dl/` link at all, [attemptDirectDownload] returns false and the caller
 * proceeds with the existing generic HTTP download path (which, for a
 * non-Telegram URL, was never the Railway-bandwidth path this fix targets).
 */
object TdlibDownloadHelper {

    /**
     * Attempts to download [streamUrl]'s file directly via TDLib into
     * [outFile]. Returns true once TDLib has genuinely taken ownership of
     * this download (success OR a definitive, already-retried TDLib-side
     * error already reported via [onError]) — false means "this wasn't a
     * Telegram link / TDLib is off, caller's generic HTTP path handles it,"
     * which is NOT the Railway-proxy-for-Telegram-media case anymore.
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
            // Genuinely not a Telegram `/dl/{token}/{id}/{name}` proxy URL
            // at all (e.g. a direct external/CDN link) — the caller's
            // generic HTTP downloader is the right, and only, path for
            // this, same as before.
            TdlibDebugState.lastStatus = "TDLib: download skipped (URL not /dl/ shaped): $streamUrl"
            return false
        }

        val resolution = try {
            TdlibResolveClient.resolve(resolveUrl)
        } catch (firstError: Exception) {
            try {
                TdlibResolveClient.resolve(resolveUrl)
            } catch (e: Exception) {
                // Retried once and it's still failing — genuine error, NOT
                // a signal to fall back to Railway's HTTP proxy for the
                // actual file bytes.
                TdlibDebugState.lastStatus = "TDLib: download resolve failed after retry: ${e.message}"
                onError(e.message ?: "TDLib resolve failed")
                return true
            }
        }

        return try {
            TdlibClient.ensureConfigLoaded(streamUrl)
            TdlibDebugState.lastStatus = "TDLib: ACTIVE ✅ (download, direct — Railway not used)"
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
            // TdlibClient.downloadFull already retried once internally
            // (clean cache + retry) before this reached us — genuine,
            // already-retried failure. Report it; never fall back to
            // Railway's HTTP proxy for the bytes.
            TdlibDebugState.lastStatus = "TDLib: download failed (after retry): ${e.message}"
            onError(e.message ?: "TDLib direct download failed")
            true
        }
    }
}
