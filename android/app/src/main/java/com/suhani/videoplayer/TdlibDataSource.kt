package com.suhani.videoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A501 — direct phone<->Telegram migration (see
 * a501-direct-streaming-migration-prompt.md, "Required behavior" #1).
 *
 * This is the on-device replacement for hitting Railway's `/dl/...` for
 * actual media bytes: given a stream URL, it resolves the Telegram
 * chat_id/message_id via [TdlibResolveClient] and is INTENDED to then pull
 * bytes for the requested `[position, position+length)` range straight from
 * Telegram via TDLib (MTProto), the way `parse_range_header` /
 * `_build_stream_headers` do server-side today — just on-device instead.
 *
 * WHY THE ACTUAL BYTE-FETCH IS STUBBED:
 * TDLib requires a native library dependency (prebuilt `.aar`/`.so`, e.g.
 * `org.drinkless:tdlib`, or a from-source NDK build) that cannot be added
 * from a sandboxed/offline environment — see the TODO in app/build.gradle.
 * Everything UP TO the TDLib call itself (URL parsing, range math, the
 * DataSource contract, resolve-endpoint call, error surface for the
 * fallback layer) is real and wired correctly; only the innermost
 * `readFilePart`-equivalent call is a placeholder.
 *
 * WIRING CHECKLIST for whoever adds the real TDLib SDK:
 *   1. In [open], after `resolution` is available, replace the
 *      `throw notWiredYet(...)` with:
 *        a. `Client.execute(TdSend.OpenMessageContent/GetMessage)` (or
 *           whatever the chosen TDLib binding calls it) using
 *           `resolution.chatId` / `resolution.msgId` to get the file id.
 *        b. `Client.execute(TdSend.DownloadFile(fileId, priority=32,
 *           offset=bytesPosition, limit=readLength, synchronous=false))`
 *           and await the `UpdateFile` callback (or poll `file.local`)
 *           until enough bytes are on disk to satisfy this read.
 *   2. Implement [read] to read the requested slice out of the TDLib-
 *      managed local file path once available (TDLib writes partial
 *      downloads to disk itself — no need to buffer in Kotlin).
 *   3. Respect [DataSpec.length] / [DataSpec.position] the same way
 *      `parse_range_header` does server-side, so ExoPlayer seeking works.
 *   4. On ANY failure here, throw (don't swallow) — [FallbackDataSource]
 *      is what falls back to the Railway proxy; silently returning zero
 *      bytes would look like playback stalling instead of failing over.
 */
@UnstableApi
class TdlibDataSource : DataSource {

    private var dataSpec: DataSpec? = null
    private var opened = false

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibDataSource()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // No TDLib transfer to report progress for yet — no-op until wired.
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec

        if (!TdlibConfig.ENABLED) {
            // Fast, cheap failure — see TdlibConfig doc comment for why this
            // is off by default. FallbackDataSource treats this as "use the
            // Railway proxy for this playback/seek instead."
            throw notWiredYet("TdlibConfig.ENABLED is false")
        }

        val streamUrl = dataSpec.uri.toString()
        val resolveUrl = TdlibResolveClient.deriveResolveUrl(streamUrl)
            ?: throw notWiredYet("not a /dl/ proxy URL, nothing to resolve: $streamUrl")

        val resolution = try {
            TdlibResolveClient.resolve(resolveUrl)
        } catch (e: Exception) {
            throw IOException("TDLib resolve step failed for $resolveUrl", e)
        }

        // TODO(tdlib-wiring): see class doc "WIRING CHECKLIST" step 1-3.
        // resolution.chatId / resolution.msgId are correct and ready to use —
        // only the actual MTProto download call below is unimplemented.
        throw notWiredYet(
            "resolved chat_id=${resolution.chatId} msg_id=${resolution.msgId} " +
                "but no TDLib client is linked into this build yet"
        )
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("TdlibDataSource.read() called before a successful open()")
        // Unreachable until open() above actually succeeds post-wiring.
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = dataSpec?.uri

    @Throws(IOException::class)
    override fun close() {
        opened = false
        dataSpec = null
        // TODO(tdlib-wiring): cancel any in-flight TDLib DownloadFile call
        // for this source once real download calls exist.
    }

    private fun notWiredYet(reason: String): IOException =
        IOException("TDLib direct path unavailable ($reason) — falling back to Railway proxy")
}
