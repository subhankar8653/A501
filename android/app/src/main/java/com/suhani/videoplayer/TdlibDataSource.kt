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
 * On-device replacement for hitting Railway's `/dl/...` for actual media
 * bytes: given a stream URL, resolves the Telegram chat_id/message_id via
 * [TdlibResolveClient], then pulls bytes for the requested
 * `[position, position+length)` range straight from Telegram via
 * [TdlibClient] (TDLib/MTProto) — the on-device equivalent of what
 * `parse_range_header` / `_build_stream_headers` do server-side today.
 *
 * Wiring is complete (see [TdlibClient] for the actual TDLib JSON calls);
 * this class only owns the [DataSource] contract — position/length
 * bookkeeping, and turning any TDLib failure into a clean throw so
 * [FallbackDataSource] can fail over to the Railway proxy. [open] does a
 * small probe read (not just a metadata lookup) before returning, so a
 * file that resolves fine but genuinely can't be downloaded (e.g. bad bot
 * permissions, TDLib session issue) is caught by [FallbackDataSource]'s
 * open()-time timeout instead of surfacing mid-playback on the first
 * [read].
 */
@UnstableApi
class TdlibDataSource : DataSource {

    private var dataSpec: DataSpec? = null
    private var chatId: Long = 0
    private var msgId: Long = 0
    private var fileId: Int = -1
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibDataSource()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // No TDLib transfer-progress reporting wired up yet — no-op, same
        // as before; ExoPlayer's own buffering UI doesn't depend on this.
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec

        if (!TdlibConfig.ENABLED) {
            // Fast, cheap failure — see TdlibConfig doc comment for why
            // this is off by default. FallbackDataSource treats this as
            // "use the Railway proxy for this playback/seek instead."
            throw notWiredYet("TdlibConfig.ENABLED is false")
        }

        val streamUrl = dataSpec.uri.toString()
        val resolveUrl = TdlibResolveClient.deriveResolveUrl(streamUrl)
            ?: throw notWiredYet("not a /dl/ proxy URL, nothing to resolve: $streamUrl")

        try {
            TdlibClient.ensureConfigLoaded(streamUrl)
        } catch (e: Exception) {
            throw IOException("TDLib config fetch (api_id/api_hash/bot_token) failed", e)
        }

        val resolution = try {
            TdlibResolveClient.resolve(resolveUrl)
        } catch (e: Exception) {
            throw IOException("TDLib resolve step failed for $resolveUrl", e)
        }

        val resolved = try {
            TdlibClient.resolveFile(resolution.chatId, resolution.msgId)
        } catch (e: Exception) {
            throw IOException(
                "TDLib getMessage/file lookup failed for chat_id=${resolution.chatId} msg_id=${resolution.msgId}",
                e,
            )
        }

        chatId = resolution.chatId
        msgId = resolution.msgId
        fileId = resolved.fileId
        position = dataSpec.position

        val totalSize = resolved.totalSize
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> totalSize - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }

        // Prime the pump: confirm TDLib can actually deliver bytes (not
        // just resolve metadata) before declaring open() successful.
        // FallbackDataSource is watching this whole call with a hard
        // timeout (TdlibConfig.OPEN_TIMEOUT_MS) and falls back to Railway
        // on any throw here — see class doc comment #4 in that file.
        val probeLength = if (bytesRemaining in 1..65_536L) bytesRemaining else 65_536L
        try {
            TdlibClient.ensureRangeDownloaded(fileId, position, probeLength, TdlibConfig.OPEN_TIMEOUT_MS)
        } catch (e: Exception) {
            throw IOException("TDLib direct path failed its first-chunk probe", e)
        }

        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (fileId < 0) throw IOException("TdlibDataSource.read() called before a successful open()")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0

        val readLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        val bytesRead = try {
            TdlibClient.readRange(chatId, msgId, position, buffer, offset, readLength, TdlibConfig.OPEN_TIMEOUT_MS)
        } catch (e: Exception) {
            // On ANY failure here, throw (don't swallow) — this is
            // mid-stream, past FallbackDataSource's open()-time timeout, so
            // a failure here is a genuine playback error, not a signal to
            // silently retry with zero bytes (which would look like a
            // stall instead of a clean failure).
            throw IOException("TDLib read failed at position=$position length=$readLength", e)
        }

        if (bytesRead <= 0) return C.RESULT_END_OF_INPUT

        position += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    @Throws(IOException::class)
    override fun close() {
        fileId = -1
        dataSpec = null
    }

    private fun notWiredYet(reason: String): IOException =
        IOException("TDLib direct path unavailable ($reason) — falling back to Railway proxy")
}
