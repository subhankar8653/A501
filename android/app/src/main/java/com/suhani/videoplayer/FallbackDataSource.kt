package com.suhani.videoplayer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A501 — direct phone<->Telegram migration (see migration doc, "Required
 * behavior" #4: "gracefully fall back to the existing Railway-proxy
 * streaming path... rather than hard-failing... a clean, testable
 * fallback, not silent swallowing of errors").
 *
 * Tries [primaryFactory] (intended: [TdlibDataSource.Factory]) first. If it
 * throws OR doesn't open within [TdlibConfig.OPEN_TIMEOUT_MS], this closes
 * it out and opens [secondaryFactory] (the existing cache-backed HTTP
 * factory from [PlayerNetwork]) instead — completely transparent to
 * ExoPlayer, which only ever sees the [DataSource] interface.
 *
 * The timeout matters: without it, a TDLib path that hangs (rather than
 * cleanly erroring — e.g. blocked network, stuck auth) would make playback
 * hang too, instead of failing over. A clean throw from TdlibDataSource
 * doesn't need the timeout to trigger fallback, but network-level hangs do.
 */
@UnstableApi
class FallbackDataSource(
    private val primaryFactory: DataSource.Factory,
    private val secondaryFactory: DataSource.Factory,
) : DataSource {

    private var active: DataSource? = null
    private var usedFallback: Boolean = false

    class Factory(
        private val primaryFactory: DataSource.Factory,
        private val secondaryFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = FallbackDataSource(primaryFactory, secondaryFactory)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Applied to whichever source actually ends up active, in open().
        pendingListener = transferListener
    }

    private var pendingListener: TransferListener? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        // Cheap short-circuit: while the feature is off (default), don't
        // even spin up the worker thread below — go straight to the
        // Railway-proxy DataSource, same cost as before this migration.
        if (!TdlibConfig.ENABLED) {
            TdlibDebugState.lastStatus = "TDLib: disabled (TdlibConfig.ENABLED=false)"
        } else {
            TdlibDebugState.lastStatus = "TDLib: trying…"
        }
        val primaryResult = if (TdlibConfig.ENABLED) {
            tryOpenWithTimeout(primaryFactory, dataSpec, TdlibConfig.OPEN_TIMEOUT_MS)
        } else {
            null
        }
        if (primaryResult != null) {
            active = primaryResult.first
            usedFallback = false
            TdlibDebugState.lastStatus = "TDLib: ACTIVE ✅ (not using Railway)"
            pendingListener?.let { active?.addTransferListener(it) }
            return primaryResult.second
        }

        // Primary (TDLib) unavailable/failed/timed out — fall back to the
        // existing Railway-proxy DataSource. Any failure here is real and
        // should surface to ExoPlayer as a genuine playback error.
        usedFallback = true
        val secondary = secondaryFactory.createDataSource()
        pendingListener?.let { secondary.addTransferListener(it) }
        val length = secondary.open(dataSpec)
        active = secondary
        return length
    }

    /** Runs primary.open() on a worker thread so a network-level hang can't
     *  block playback past [timeoutMs] — returns null on any failure/timeout
     *  (caller falls back), or the opened DataSource + its reported length. */
    private fun tryOpenWithTimeout(
        factory: DataSource.Factory,
        dataSpec: DataSpec,
        timeoutMs: Long,
    ): Pair<DataSource, Long>? {
        val source = factory.createDataSource()
        val resultLength = AtomicReference<Long>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)

        val worker = Thread {
            try {
                resultLength.set(source.open(dataSpec))
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed || failure.get() != null) {
            // Timed out or failed — best-effort close, ignore its own errors,
            // and let the caller open the fallback DataSource instead.
            runCatching { source.close() }
            TdlibDebugState.lastStatus = if (!completed) {
                "TDLib: FAILED — timed out after ${timeoutMs}ms, used Railway instead"
            } else {
                val err = failure.get()
                val cause = err?.cause
                val detail = if (cause != null && cause.message != null) {
                    "${err.message} → ${cause.message}"
                } else {
                    err?.message ?: err?.toString() ?: "unknown error"
                }
                "TDLib: FAILED — $detail, used Railway instead"
            }
            return null
        }
        return source to (resultLength.get() ?: 0L)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = active ?: throw IOException("FallbackDataSource.read() called before open()")
        return current.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = active?.uri

    @Throws(IOException::class)
    override fun close() {
        active?.close()
        active = null
    }

    /** Exposed for logging/telemetry (e.g. reporting "used fallback" back to
     *  Railway per the migration doc's analytics-parity note) — not read by
     *  ExoPlayer itself. */
    fun didUseFallback(): Boolean = usedFallback
}
