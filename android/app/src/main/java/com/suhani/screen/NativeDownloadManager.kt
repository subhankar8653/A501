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
        onError: (message: String) -> Unit,
    ) {
        // Same id ka pehle se chal raha download ho to usse pehle rok do (retry case).
        cancel(id)
        val keepGoing = AtomicBoolean(true)
        active[id] = keepGoing

        // A501 — direct phone<->Telegram migration (see
        // a501-direct-streaming-migration-prompt.md, "Required behavior" #2).
        // While TdlibConfig.ENABLED is false (default), this returns false
        // immediately and every line below is untouched — same HTTP thread
        // as before. See TdlibDownloadHelper for the real wiring point.
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        "Download: TDLib direct ✅ (Railway nahi use ho raha)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            onProgress(pct, size)
        }

        val handledByTdlib = com.suhani.videoplayer.TdlibDownloadHelper.attemptDirectDownload(
            streamUrl = url,
            outFile = outFile,
            onProgress = debugWrappedOnProgress,
            onDone = { f ->
                active.remove(id)
                val uri = contentUriFor(context, id)
                if (uri != null) onDone(uri) else onError("file save failed")
            },
            onError = { msg ->
                active.remove(id)
                onError(msg)
            },
        )
        if (handledByTdlib) return

        // Reaching here means TDLib direct was never even attempted for
        // this download (ENABLED=false, or the URL didn't resolve) — the
        // HTTP path below is the existing Railway proxy.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                "Download: Railway HTTP se ho raha hai",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        Thread {
            var conn: HttpURLConnection? = null
            val outFile = fileFor(context, id)
            val tmpFile = File(outFile.parentFile, outFile.name + ".part")
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
                if (conn.responseCode !in 200..299) {
                    onError("HTTP ${conn.responseCode}")
                    return@Thread
                }
                val total = conn.contentLengthLong
                var received = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastEmit = 0L
                        while (keepGoing.get()) {
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
                val uri = contentUriFor(context, id)
                if (uri != null) onDone(uri) else onError("file save failed")
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                active.remove(id)
                if (keepGoing.get()) onError(e.message ?: "download failed")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
