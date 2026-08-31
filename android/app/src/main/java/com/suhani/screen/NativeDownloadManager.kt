package com.suhani.screen

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ROOT CAUSE FIX (user ask: "offline video player simple hai, online jaisa
 * poora-feature player chahiye"): downloads pehle sirf JS side (fetch +
 * IndexedDB Blob) mein store hote the, aur playback ke waqt ek `blob:` object
 * URL ban jaata tha. `window.AndroidPlayer` (chhota/fullscreen rich native
 * player — equalizer, cast, decoder-select, subtitle-track, PiP) sirf real
 * fetchable URI (http/https/file/content) hi mount kar sakta hai — `blob:`
 * URL uski reach ke bahar hai (sirf WebView ke andar JS-memory registry mein
 * exist karta hai). Isi wajah se offline downloads ab tak ek alag, seedha
 * HTML5 `<video>` fallback (VideoPlayer.jsx ka apna web-control UI) par gir
 * jaate the.
 *
 * Fix: video ko yahin native side par asli file ke roop mein disk par utaaro
 * (JS ke fetch-based progress-tracked download ki jagah), aur usse ek
 * `content://` URI de do — taaki offline download bhi `window.AndroidPlayer`
 * ke through hi wahi rich player istemal kare jo online streaming karta hai.
 * (Web frontend ka apna IndexedDB-Blob path bina-app plain-browser use ke
 * liye fallback ke taur par as-is rehta hai — dekho downloadsStore.js.)
 */
object NativeDownloadManager {

    private val active = ConcurrentHashMap<String, AtomicBoolean>() // id -> "keep going?" flag

    // FEATURE (user ask: "stream hote waqt download queue mein chala jaega,
    // watch band karte hi apne aap shuru ho jaega — single download ya
    // watch hoga, dono ek saath kabhi nahi, jo bhi chal raha ho use full
    // power mile"): a PAUSE is different from a CANCEL — cancel wipes
    // everything (used when the user deletes/discards a download); pause
    // just stops the network activity right now and keeps whatever's on
    // disk so the exact same download can pick up again later (Range-resume
    // for the plain-HTTP path below; TDLib resumes on its own, see
    // TdlibDownloadHelper/TdlibClient). id -> "please pause" flag.
    private val pauseRequested = ConcurrentHashMap<String, AtomicBoolean>()
    private const val AUTHORITY_SUFFIX = ".downloads.fileprovider"

    private fun downloadsDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "native_downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** id jaisa "series:abc123:2:5:1080p" — filesystem-safe naam banane ke liye sanitize karo. */
    private fun safeName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun fileFor(context: Context, id: String): File =
        File(downloadsDir(context), safeName(id))

    fun contentUriFor(context: Context, id: String): String? {
        val file = fileFor(context, id)
        if (!file.exists() || file.length() <= 0L) return null
        val authority = context.applicationContext.packageName + AUTHORITY_SUFFIX
        return FileProvider.getUriForFile(context.applicationContext, authority, file).toString()
    }

    fun delete(context: Context, id: String) {
        cancel(id)
        runCatching { fileFor(context, id).delete() }
    }

    fun cancel(id: String) {
        active[id]?.set(false)
        active.remove(id)
        pauseRequested.remove(id)
    }

    /** Stops the in-flight network activity for [id] RIGHT NOW but leaves
     *  any partial bytes on disk (the `.part` file / TDLib's own local
     *  cache) so a later [start] call for the same id continues instead of
     *  re-downloading from scratch. No-op if [id] isn't currently active —
     *  the flag it sets is read by that download's own loop, so if nothing
     *  is running there's nothing to pause. */
    fun pause(id: String) {
        pauseRequested.getOrPut(id) { AtomicBoolean(false) }.set(true)
    }

    /**
     * Background thread par download chalata hai. Callbacks kisi bhi thread se
     * aate hain — caller (MainActivity) unhe UI thread par hop karke JS ko
     * evaluateJavascript se bhejta hai.
     */
    fun start(
        context: Context,
        id: String,
        url: String,
        onProgress: (progressPct: Int, sizeBytes: Long) -> Unit,
        onDone: (contentUri: String) -> Unit,
        onPaused: () -> Unit = {},
        onError: (message: String) -> Unit,
    ) {
        // Same id ka pehle se chal raha download ho to usse pehle rok do (retry case).
        // NOTE: yeh sirf in-memory "keep going" flags reset karta hai — disk
        // par pehle se maujood `.part` file ko HAATH NAHI lagata, isliye ek
        // paused download ko yahi se resume karna safe hai.
        cancel(id)
        val keepGoing = AtomicBoolean(true)
        active[id] = keepGoing
        val pauseFlag = AtomicBoolean(false)
        pauseRequested[id] = pauseFlag
        fun isPauseRequested() = pauseFlag.get()

        // ROOT CAUSE FIX (download's TDLib attempt was throwing
        // "resolve failed: null" every time — that's the signature of
        // NetworkOnMainThreadException, whose .message is always null.
        // DownloadService.onStartCommand() runs on the main/UI thread, and
        // this whole function used to run its TDLib network calls
        // (TdlibResolveClient.resolve — blocking HTTP) directly on that
        // caller's thread. Only the OLD HTTP-fallback path below had its
        // own Thread{}; the newer TDLib path added in front of it did not.
        // Streaming doesn't hit this because TdlibDataSource.open() runs on
        // ExoPlayer's own background loading thread, never the UI thread.
        // Fix: the whole thing — TDLib attempt AND the HTTP fallback — now
        // runs on one background thread, same pattern as before.
        Thread {
            val outFile = fileFor(context, id)

            // Debug visibility: downloads had no on-screen indicator of which
            // path is actually moving bytes (unlike streaming's top-left
            // "TDLib: ACTIVE" badge in PlayerActivity). onProgress() only ever
            // fires from inside TdlibClient.downloadFull, so its first call is
            // live proof TDLib direct is really the one downloading — not just
            // that TdlibConfig.ENABLED is set.
            var tdlibConfirmed = false
            val debugWrappedOnProgress: (Int, Long) -> Unit = { pct, size ->
                if (!tdlibConfirmed) {
                    tdlibConfirmed = true
                    // BUG FIX: yeh pehle har download ki shuruaat mein user ko ek
                    // Toast dikhata tha ("TDLib direct ✅ ...") — internal routing
                    // debug info, jiska user ke liye koi matlab nahi tha aur
                    // random popup jaisa lagta tha. Ab sirf logcat mein jaata hai.
                    android.util.Log.d("NativeDownloadManager", "Download via TDLib direct (not Railway)")
                }
                onProgress(pct, size)
            }

            val handledByTdlib = com.suhani.videoplayer.TdlibDownloadHelper.attemptDirectDownload(
                streamUrl = url,
                outFile = outFile,
                shouldPause = ::isPauseRequested,
                onProgress = debugWrappedOnProgress,
                onDone = { f ->
                    active.remove(id)
                    pauseRequested.remove(id)
                    val uri = contentUriFor(context, id)
                    if (uri != null) onDone(uri) else onError("file save failed")
                },
                onPaused = {
                    // Network activity already stopped (TdlibClient sent
                    // cancelDownloadFile) — just stop tracking this as
                    // "active" so a fresh start(id, ...) call later is free
                    // to run; TDLib's own local cache keeps the bytes.
                    active.remove(id)
                    onPaused()
                },
                onError = { msg ->
                    active.remove(id)
                    pauseRequested.remove(id)
                    onError(msg)
                },
            )
            if (handledByTdlib) return@Thread

            // ROOT-CAUSE FIX (user ask: "Railway ko download se poori tarah
            // hata do"): reaching here NO LONGER means a Telegram download
            // is falling back to Railway's proxy — TdlibDownloadHelper now
            // only returns false for genuinely non-Telegram URLs (ENABLED
            // off, or the URL simply isn't a `/dl/` Telegram link at all).
            // A real Telegram `/dl/` link always returns true from above
            // (success or a reported, already-retried error) and never
            // reaches this HTTP path anymore. Show the exact reason (set by
            // TdlibDownloadHelper just above) instead of a generic message.
            // BUG FIX: yeh bhi pehle har fallback (HTTP-direct) download par ek
            // Toast dikhata tha internal reason ke saath — user ko sirf ek
            // confusing popup dikhta tha, kuch actionable nahi. Ab log-only.
            android.util.Log.d(
                "NativeDownloadManager",
                "Direct/local download (reason: ${com.suhani.videoplayer.TdlibDebugState.lastStatus})"
            )

            var conn: HttpURLConnection? = null
            val tmpFile = File(outFile.parentFile, outFile.name + ".part")
            // FEATURE (user ask: "download pause ho kar watch band hote hi
            // wahin se aage shuru ho jaega, poora se dobara nahi"): agar
            // pichhli baar pause hote waqt kuch bytes .part file mein already
            // save the, unhe fek kar poore se shuru karne ki jagah HTTP
            // Range header se sirf bacha hua hissa maango aur usi file mein
            // aage jodte jao.
            val alreadyOnDisk = if (tmpFile.exists()) tmpFile.length() else 0L
            try {
                var currentUrl = url
                var redirects = 0
                var connection: HttpURLConnection
                while (true) {
                    connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 20_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "A501-Player/1.0 (Android)")
                        if (alreadyOnDisk > 0) setRequestProperty("Range", "bytes=$alreadyOnDisk-")
                    }
                    connection.connect()
                    val code = connection.responseCode
                    if ((code == HttpURLConnection.HTTP_MOVED_PERM ||
                            code == HttpURLConnection.HTTP_MOVED_TEMP ||
                            code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) &&
                        redirects < 5
                    ) {
                        val next = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        currentUrl = next
                        redirects++
                        continue
                    }
                    break
                }
                conn = connection
                // Range maanga tha lekin server ne 206 (Partial Content) ki
                // jagah 200 diya — matlab woh Range support hi nahi karta,
                // aur ab poori file processwar naye sirse aa rahi hai. Isse
                // purani .part file ke aage seedha jodna corrupt file banata
                // — poore se hi shuru karo.
                val serverHonoredRange = alreadyOnDisk > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val startOffset = if (serverHonoredRange) alreadyOnDisk else 0L
                if (alreadyOnDisk > 0 && !serverHonoredRange) {
                    runCatching { tmpFile.delete() }
                }
                if (conn.responseCode !in 200..299) {
                    onError("HTTP ${conn.responseCode}")
                    return@Thread
                }
                val remaining = conn.contentLengthLong
                val total = if (remaining > 0) startOffset + remaining else -1L
                var received = startOffset
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile, serverHonoredRange).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastEmit = 0L
                        while (keepGoing.get() && !pauseFlag.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 300) {
                                lastEmit = now
                                val pct = if (total > 0) ((received * 100) / total).toInt() else 0
                                onProgress(pct, received)
                            }
                        }
                        output.flush()
                    }
                }
                if (pauseFlag.get()) {
                    // FEATURE: watching shuru ho gayi — abhi ke liye ruko,
                    // .part file jaisi hai waisi hi rehne do (delete NAHI
                    // karna), taaki agla start(id, ...) call Range header se
                    // yahin se aage badhe.
                    active.remove(id)
                    onPaused()
                    return@Thread
                }
                if (!keepGoing.get()) {
                    // cancel hua tha — adhoora file hata do
                    runCatching { tmpFile.delete() }
                    return@Thread
                }
                // ROOT CAUSE FIX (user report: "download hote waqt beech mein
                // cut kiya to bhi Downloads mein 'X MB · Offline available'
                // dikha raha tha, adhoori file thi"): pehle sirf `read == -1`
                // milte hi (input stream khatam) seedha "done" maan liya jaata
                // tha, chahe expected size se kam bytes hi kyun na mile hon.
                // Network drop / connection reset hone par bhi `read()` -1
                // return kar deta hai — us case mein yeh genuine completion
                // nahi, adhoora/truncated download hai. Fix: agar server ne
                // Content-Length diya tha aur utne bytes mile hi nahi, isko
                // error treat karo aur adhoori .part file turant delete karo —
                // list mein wo kabhi "Offline available" ban kar dikhega hi
                // nahi, seedha clear ho jaayegi.
                if (total > 0 && received < total) {
                    runCatching { tmpFile.delete() }
                    active.remove(id)
                    onError("incomplete download")
                    return@Thread
                }
                if (!tmpFile.renameTo(outFile)) {
                    // rename fail ho jaaye (rare) to copy fallback
                    tmpFile.copyTo(outFile, overwrite = true)
                    runCatching { tmpFile.delete() }
                }
                active.remove(id)
                pauseRequested.remove(id)
                val uri = contentUriFor(context, id)
                if (uri != null) onDone(uri) else onError("file save failed")
            } catch (e: Exception) {
                if (pauseFlag.get()) {
                    // Pause request ke beech hi connection wagera cut hui —
                    // ab bhi ek genuine pause hai, error nahi; .part file
                    // rehne do.
                    active.remove(id)
                    onPaused()
                    return@Thread
                }
                runCatching { tmpFile.delete() }
                active.remove(id)
                pauseRequested.remove(id)
                if (keepGoing.get()) onError(e.message ?: "download failed")
            } finally {
                conn?.disconnect()
            }
        }.apply { isDaemon = true }.start()
    }
}
