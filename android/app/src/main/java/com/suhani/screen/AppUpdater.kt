package com.suhani.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Over-the-air updates, driven from the owner's Telegram bot via the backend
 * (`/setbundle` and `/setapk` there).
 *
 *  - OTA UI bundle: the whole React UI is one `index.html`. A newer one is
 *    downloaded into `filesDir/ota/` and used on the next start (or right
 *    away on [WebUpdateInterface.applyBundle]). If a new UI fails to boot
 *    twice in a row, it is thrown away and the UI that shipped inside the
 *    APK is used again (rollback guard, see [OtaBundle.loadHtml]).
 *  - APK: downloaded to `cacheDir/updates/` and handed to Android's package
 *    installer. This only works as an in-place update if every APK is signed
 *    with the SAME key (see android/app/build.gradle + the GitHub workflow).
 *
 * The web UI talks to this through `window.AndroidUpdater` and gets progress
 * back through `window.__nativeUpdate(kind, state, pct, message)`.
 */
object OtaBundle {
    private const val PREFS = "ota_prefs"
    private const val K_VERSION = "version"
    private const val K_FAILS = "boot_fail_count"

    /** Bundle version of the UI that is on screen right now. */
    @Volatile
    var loadedVersion: Int = 0
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "ota")
    fun file(ctx: Context) = File(dir(ctx), "index.html")

    /** Version of the UI baked into this APK (assets/bundle_version.txt, written by CI). */
    fun assetVersion(ctx: Context): Int = try {
        ctx.assets.open("bundle_version.txt").bufferedReader().use { it.readText().trim().toInt() }
    } catch (e: Exception) {
        0
    }

    private fun discard(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().remove(K_VERSION).putInt(K_FAILS, 0).apply()
    }

    /**
     * HTML the WebView should load. Prefers a downloaded UI that is newer than
     * the one inside the APK; otherwise (or after two unconfirmed boots, or
     * after an APK update that already contains something newer) uses the
     * bundled assets/index.html.
     */
    fun loadHtml(ctx: Context): String {
        val p = prefs(ctx)
        val assetV = assetVersion(ctx)
        val f = file(ctx)
        val v = p.getInt(K_VERSION, 0)

        if (f.exists() && v > assetV) {
            val fails = p.getInt(K_FAILS, 0)
            if (fails >= 2) {
                discard(ctx)
            } else {
                try {
                    val html = f.readText()
                    if (html.contains("id=\"root\"")) {
                        p.edit().putInt(K_FAILS, fails + 1).apply()
                        loadedVersion = v
                        return html
                    }
                } catch (e: Exception) {
                    // fall through to the bundled UI
                }
                discard(ctx)
            }
        } else if (f.exists()) {
            discard(ctx) // APK now ships a UI at least as new — drop the stale download
        }

        loadedVersion = assetV
        return ctx.assets.open("index.html").bufferedReader().use { it.readText() }
    }

    /** The UI reported it booted fine — cancel the rollback countdown. */
    fun markBootOk(ctx: Context) {
        prefs(ctx).edit().putInt(K_FAILS, 0).apply()
    }

    fun install(ctx: Context, tmp: File, version: Int) {
        dir(ctx).mkdirs()
        val dest = file(ctx)
        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        prefs(ctx).edit().putInt(K_VERSION, version).putInt(K_FAILS, 0).apply()
    }

    fun tmpFile(ctx: Context): File {
        dir(ctx).mkdirs()
        return File(dir(ctx), "download.part")
    }
}

class WebUpdateInterface(private val activity: MainActivity) {

    private val io = Executors.newFixedThreadPool(2)

    // One running job per kind, so a background UI-bundle download can never
    // block the user's "Update now" tap (and vice versa).
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @JavascriptInterface
    fun getAppInfo(): String {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return JSONObject()
            .put("versionCode", PackageInfoCompat.getLongVersionCode(pi))
            .put("versionName", pi.versionName ?: "")
            .put("bundleVersion", OtaBundle.loadedVersion)
            .toString()
    }

    @JavascriptInterface
    fun markBootOk() {
        OtaBundle.markBootOk(activity)
    }

    @JavascriptInterface
    fun applyBundle() {
        activity.runOnUiThread { activity.reloadShell() }
    }

    @JavascriptInterface
    fun downloadBundle(url: String, sha256: String, version: Int) {
        if (!isAllowedUrl(url)) return
        runJob("bundle") {
            val tmp = OtaBundle.tmpFile(activity)
            download(url, tmp, sha256) { pct -> emit("bundle", "progress", pct) }
            val head = tmp.readText()
            if (!head.contains("id=\"root\"")) {
                tmp.delete()
                throw IllegalStateException("bad bundle")
            }
            OtaBundle.install(activity, tmp, version)
            emit("bundle", "ready", 100)
        }
    }

    @JavascriptInterface
    fun downloadApk(url: String, sha256: String, versionCode: Int) {
        if (!isAllowedUrl(url)) return

        // Android needs a one-time "install unknown apps" permission for this app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            emit("apk", "needs_permission", 0)
            activity.runOnUiThread {
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    // no such settings screen on this ROM — the in-app hint is still shown
                }
            }
            return
        }

        runJob("apk") {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "update.apk")
            val tmp = File(dir, "update.part")
            download(url, tmp, sha256) { pct -> emit("apk", "progress", pct) }
            if (!tmp.renameTo(apk)) throw IllegalStateException("rename failed")

            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".downloads.fileprovider", apk)
            emit("apk", "installing", 100)
            activity.runOnUiThread {
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(i)
            }
        }
    }

    // ------------------------------------------------------------ internals

    /** Only https, and only our own update endpoints — never an arbitrary URL. */
    private fun isAllowedUrl(url: String): Boolean {
        val u = try { Uri.parse(url) } catch (e: Exception) { return false }
        return u.scheme == "https" && !u.host.isNullOrEmpty() && (u.path ?: "").startsWith("/api/app/")
    }

    private fun runJob(kind: String, job: () -> Unit) {
        if (!running.add(kind)) return
        io.execute {
            try {
                job()
            } catch (e: Exception) {
                emit(kind, "error", 0, e.message ?: "")
            } finally {
                running.remove(kind)
            }
        }
    }

    private fun emit(kind: String, state: String, pct: Int, message: String = "") {
        activity.postUpdateEvent(kind, state, pct, message)
    }

    private fun download(url: String, dest: File, expectedSha256: String, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (expectedSha256.isNotBlank()) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(expectedSha256.trim(), ignoreCase = true)) {
                    dest.delete()
                    throw IllegalStateException("checksum mismatch")
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
