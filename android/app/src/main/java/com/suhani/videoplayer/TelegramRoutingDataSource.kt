package com.suhani.videoplayer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A501 — direct phone<->Telegram migration.
 *
 * ROOT-CAUSE FIX (user ask: "Railway ko download and stream donon se hata
 * do, only direct hoga"): replaces the old `FallbackDataSource`, which used
 * to try TDLib first and silently fall back to Railway's HTTP `/dl/` proxy
 * on any TDLib failure or timeout — meaning every TDLib hiccup quietly
 * shifted the media bytes back onto Railway's bandwidth, exactly what the
 * user doesn't want anymore.
 *
 * This class does routing, not fallback: it looks at the URL shape ONCE, up
 * front, and picks exactly one path — it never tries one and silently
 * switches to the other mid-request.
 *
 *  - A Telegram proxy URL (`.../dl/{token}/{id}/{name}`, same shape
 *    [TdlibResolveClient] already recognizes) always goes through
 *    [primaryFactory] (TDLib direct). If that fails, it fails for real —
 *    [TdlibClient] already retries once internally after clearing its own
 *    local cache (see [TdlibClient.resetFileForRetry]), so this is not
 *    "give up on the first hiccup," but there is no Railway byte-proxy
 *    behind it anymore.
 *  - Anything else (local `file://`/`content://` downloads, or a direct
 *    external CDN/`.m3u8`/`.mpd` link that was never a Railway media proxy
 *    to begin with) goes through [secondaryFactory] exactly as before —
 *    this was never the Railway-bandwidth path the user is asking to
 *    remove, so it's untouched.
 */
@UnstableApi
class TelegramRoutingDataSource(
    private val primaryFactory: DataSource.Factory,
    private val secondaryFactory: DataSource.Factory,
) : DataSource {

    private var active: DataSource? = null
    private var pendingListener: TransferListener? = null

    class Factory(
        private val primaryFactory: DataSource.Factory,
        private val secondaryFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramRoutingDataSource(primaryFactory, secondaryFactory)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        pendingListener = transferListener
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val streamUrl = dataSpec.uri.toString()
        val isTelegramProxyUrl = TdlibConfig.ENABLED && TdlibResolveClient.deriveResolveUrl(streamUrl) != null

        TdlibDebugState.lastStatus = if (isTelegramProxyUrl) {
            if (TdlibClient.isAuthReady()) {
                "TDLib: opening (warm)…"
            } else {
                "TDLib: opening (cold login in progress)…"
            }
        } else {
            "TDLib: not a Telegram link, using direct/local path"
        }

        val factory = if (isTelegramProxyUrl) primaryFactory else secondaryFactory
        val source = factory.createDataSource()
        pendingListener?.let { source.addTransferListener(it) }

        val length = try {
            source.open(dataSpec)
        } catch (e: IOException) {
            if (isTelegramProxyUrl) {
                TdlibDebugState.lastStatus = "TDLib: FAILED — ${e.message} (no Railway fallback, real error)"
            }
            throw e
        }

        active = source
        if (isTelegramProxyUrl) {
            TdlibDebugState.lastStatus = "TDLib: ACTIVE ✅ (direct, Railway not used)"
        }
        return length
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = active ?: throw IOException("TelegramRoutingDataSource.read() called before open()")
        return current.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = active?.uri

    @Throws(IOException::class)
    override fun close() {
        active?.close()
        active = null
    }
}
