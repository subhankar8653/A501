package com.suhani.videoplayer

import java.io.File
import java.io.IOException

/**
 * A501 — direct phone<->Telegram migration (see migration doc, "Required
 * behavior" #2: downloads should pull directly via TDLib to local storage,
 * same as streaming, instead of proxying through Railway).
 *
 * Mirrors [TdlibDataSource]'s shape: real MTProto calls are TODO-stubbed
 * (see that class's doc comment for why — no TDLib SDK available in this
 * environment), but the entry point, config-flag gate, and fallback
 * contract are real and ready to wire in.
 *
 * Used from `NativeDownloadManager.start()` (com.suhani.screen): when
 * [TdlibConfig.ENABLED] is false (default), [attemptDirectDownload] returns
 * false immediately and the caller proceeds with the existing HTTP
 * download path, completely unchanged.
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
        if (!TdlibConfig.ENABLED) return false

        val resolveUrl = TdlibResolveClient.deriveResolveUrl(streamUrl) ?: return false
        val resolution = try {
            TdlibResolveClient.resolve(resolveUrl)
        } catch (e: Exception) {
            // Resolve step itself failed — treat as "didn't attempt" so the
            // caller's existing HTTP fallback (which uses the original
            // streamUrl, not TDLib) still gets a clean shot.
            return false
        }

        // TODO(tdlib-wiring): see TdlibDataSource's "WIRING CHECKLIST" —
        // same DownloadFile(chatId, msgId, offset=0, limit=fileSize) call,
        // but written straight to `outFile` instead of served through
        // ExoPlayer's read() contract. Report progress via TDLib's
        // UpdateFile callbacks -> onProgress, then onDone(outFile) once
        // file.local.is_downloading_completed is true.
        //
        // Until that's wired in, throw rather than silently "succeeding":
        // this keeps `false` (not-attempted) as the only path back to the
        // existing HTTP download, same clean-fallback contract as
        // TdlibDataSource.
        return runCatching {
            throw IOException(
                "TDLib direct download unavailable: resolved chat_id=${resolution.chatId} " +
                    "msg_id=${resolution.msgId} but no TDLib client is linked into this build yet"
            )
        }.fold(
            onSuccess = { true },
            onFailure = { false }, // not attempted -> HTTP fallback proceeds
        )
    }
}
