package com.suhani.videoplayer

import android.content.Context
import android.util.Log
import io.github.up9cloud.td.JsonClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A501 — direct phone<->Telegram migration.
 *
 * Thin Kotlin wrapper around TDLib's raw JSON client interface
 * ([io.github.up9cloud.td.JsonClient], backed by the prebuilt
 * `libtdjson.so` shipped in `app/src/main/jniLibs/`). One process-wide
 * TDLib client, logged in as the app's own bot (see [TdlibConfig] — NEVER
 * a per-user personal login), shared by both [TdlibDataSource] (streaming
 * range-reads) and [TdlibDownloadHelper] (full downloads).
 *
 * TDLib's `downloadFile` range semantics (confirmed against TDLib's own
 * docs/changelog): `localFile.download_offset` is the START of the CURRENT
 * download window, and `downloaded_prefix_size` counts contiguous bytes
 * available starting FROM `download_offset` — not from byte 0 of the file.
 * So a requested `[start, start+length)` range is satisfied once:
 *
 *   `download_offset <= start && download_offset + downloaded_prefix_size >= start + length`
 *
 * (or `is_downloading_completed`). Bytes on disk at `local.path` sit at
 * their true absolute file offsets, so once that condition holds we can
 * `RandomAccessFile.seek(start)` and read directly — no need to track
 * partial chunks ourselves.
 */
object TdlibClient {

    private const val TAG = "TdlibClient"

    class TdlibException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class ResolvedFile(val fileId: Int, val totalSize: Long)

    @Volatile private var clientId: Int = -1
    private val creationLock = Any()
    private val extraCounter = AtomicLong(0)

    // BUG FIX #1 (root cause of "TDLib login timed out" every single time):
    // setTdlibParameters was using database_directory = "tdlib", a RELATIVE
    // path. On Android that resolves against the process's working
    // directory (effectively "/"), which the app has no permission to
    // write to — so TDLib's own database init silently failed and it never
    // progressed past authorizationStateWaitTdlibParameters. Needs the
    // app's own private storage, which needs a Context — stashed here once
    // via [init], called from TdlibDataSource/TdlibDownloadHelper with
    // their applicationContext.
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Best-effort automatic fallback if [init] was never called explicitly
     *  — reads the current process's Application via the standard
     *  ActivityThread trick (same technique many libraries use to avoid
     *  requiring a Context be threaded through every call site). Only used
     *  if [appContext] is still null when the database directory is
     *  actually needed. */
    private fun resolveAppContext(): Context? {
        appContext?.let { return it }
        return runCatching {
            val application = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
            application?.also { appContext = it.applicationContext }
        }.getOrNull()
    }

    // BUG FIX #2: setTdlibParameters/checkAuthenticationBotToken are sent
    // with waitForResponse=false (their real "result" is the next
    // updateAuthorizationState push, not their own response body) — but
    // that meant if TDLib replied with an explicit {"@type":"error",...}
    // for either of them (bad api_id/api_hash/bot_token, database-dir
    // failure, etc.), receiveLoop had nowhere to route it: no queue was
    // ever registered for that @extra, so the error was just dropped on
    // the floor and ensureReady() always fell through to the generic
    // "timed out" message after the full AUTH_TIMEOUT_MS wait — even
    // though TDLib told us exactly what went wrong within milliseconds.
    // This map lets receiveLoop recognize those two requests' @extra and
    // surface a real error immediately instead of waiting out the clock.
    private val authCriticalExtras = ConcurrentHashMap<String, String>() // extra -> request @type

    // extra-id -> single-slot handoff for that request's own response
    private val pendingResponses = ConcurrentHashMap<String, SynchronousQueue<JSONObject>>()

    // fileId -> most recent "file" object seen via updateFile
    private val latestFileState = ConcurrentHashMap<Int, JSONObject>()

    // "chatId_msgId" -> resolved fileId/size, so repeated ExoPlayer read()
    // calls on the same message don't re-issue getMessage every time
    private val messageFileCache = ConcurrentHashMap<String, ResolvedFile>()

    @Volatile private var authReadyLatch = CountDownLatch(1)
    @Volatile private var authFailure: String? = null

    private val configLoadLock = Any()
    @Volatile private var configLoaded = false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Fetches api_id/api_hash/bot_token from Railway's `/tdlib-config` and
     *  populates [TdlibConfig] — must happen before [ensureClient] since
     *  the auth flow (triggered automatically once the client starts
     *  receiving) reads those fields immediately. Callers: [TdlibDataSource]
     *  and [TdlibDownloadHelper], both of which already have the stream
     *  URL needed to derive the config endpoint (same trick as
     *  [TdlibResolveClient]). Cheap no-op after the first successful call. */
    fun ensureConfigLoaded(streamUrl: String) {
        if (configLoaded) return
        synchronized(configLoadLock) {
            if (configLoaded) return
            val creds = try {
                TdlibRemoteConfigClient.fetch(streamUrl)
            } catch (e: Exception) {
                throw TdlibException("Could not fetch Telegram API credentials from backend", e)
            }
            TdlibConfig.API_ID = creds.apiId
            TdlibConfig.API_HASH = creds.apiHash
            TdlibConfig.BOT_TOKEN = creds.botToken
            configLoaded = true
        }
    }

    private fun ensureClient() {
        if (clientId >= 0) return
        synchronized(creationLock) {
            if (clientId >= 0) return
            clientId = JsonClient.td_create_client_id()
            Thread({ receiveLoop() }, "TdlibClient-receive").apply { isDaemon = true }.start()
            // No priming request needed — TDLib automatically pushes its
            // first updateAuthorizationState (authorizationStateWaitTdlib-
            // Parameters) as soon as td_receive starts being polled.
        }
    }

    private fun receiveLoop() {
        while (true) {
            val raw = try {
                JsonClient.td_receive(2.0)
            } catch (e: Exception) {
                Log.e(TAG, "td_receive failed", e)
                null
            } ?: continue

            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                continue
            }

            when (json.optString("@type")) {
                "updateAuthorizationState" -> handleAuthUpdate(json.optJSONObject("authorization_state"))
                "updateFile" -> {
                    val file = json.optJSONObject("file")
                    val id = file?.optInt("id", -1) ?: -1
                    if (file != null && id >= 0) latestFileState[id] = file
                }
            }

            val extra = json.optString("@extra", "")
            if (extra.isNotEmpty()) {
                pendingResponses.remove(extra)?.offer(json, 2, TimeUnit.SECONDS)
                // BUG FIX #2 continued: this extra belonged to
                // setTdlibParameters or checkAuthenticationBotToken (sent
                // fire-and-forget) — if TDLib is telling us it errored,
                // surface that now instead of letting ensureReady() wait
                // out the full timeout for nothing.
                authCriticalExtras.remove(extra)?.let { requestType ->
                    if (json.optString("@type") == "error") {
                        authFailure = "TDLib $requestType failed: " +
                            "${json.optInt("code")} ${json.optString("message")}"
                        if (authReadyLatch.count > 0L) authReadyLatch.countDown()
                    }
                }
            }
        }
    }

    private fun handleAuthUpdate(state: JSONObject?) {
        state ?: return
        when (state.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                val dbDir = resolveAppContext()?.filesDir?.let { File(it, "tdlib").absolutePath }
                    ?: run {
                        // Genuinely couldn't get any Context (extremely
                        // unlikely) — fail loudly instead of silently
                        // repeating bug #1 with a relative path.
                        authFailure = "Could not resolve app Context for TDLib database_directory"
                        if (authReadyLatch.count > 0L) authReadyLatch.countDown()
                        return
                    }
                sendAuthCritical(
                    JSONObject().apply {
                        put("@type", "setTdlibParameters")
                        put("database_directory", dbDir)
                        put("use_message_database", true)
                        put("use_secret_chats", false)
                        put("api_id", TdlibConfig.API_ID)
                        put("api_hash", TdlibConfig.API_HASH)
                        put("system_language_code", "en")
                        put("device_model", "Android")
                        put("application_version", "A501-1.0")
                    },
                )
            }
            "authorizationStateWaitPhoneNumber" -> {
                // App logs in AS THE BOT, never a personal phone number —
                // hard constraint from the migration doc.
                sendAuthCritical(
                    JSONObject().apply {
                        put("@type", "checkAuthenticationBotToken")
                        put("token", TdlibConfig.BOT_TOKEN)
                    },
                )
            }
            "authorizationStateReady" -> authReadyLatch.countDown()
            "authorizationStateClosed" -> {
                authFailure = "TDLib authorization closed unexpectedly"
                clientId = -1 // allow a fresh client + login on the next ensureReady()
            }
        }
    }

    /** Fire-and-forget send (its real "result" is the next
     *  updateAuthorizationState push) that still registers the request's
     *  @extra in [authCriticalExtras] so receiveLoop can catch an explicit
     *  error response instead of dropping it — see bug #2 doc comment
     *  above [authCriticalExtras]. */
    private fun sendAuthCritical(request: JSONObject) {
        val extra = extraCounter.incrementAndGet().toString()
        request.put("@extra", extra)
        authCriticalExtras[extra] = request.optString("@type")
        JsonClient.td_send(clientId, request.toString())
    }

    /** Sends a request. [waitForResponse]=false is for auth-flow requests
     *  whose "result" is the next updateAuthorizationState push, not their
     *  own response body — those return immediately without blocking the
     *  receive loop that's calling this. */
    private fun send(request: JSONObject, waitForResponse: Boolean = true): JSONObject? {
        val extra = extraCounter.incrementAndGet().toString()
        request.put("@extra", extra)
        val queue = if (waitForResponse) SynchronousQueue<JSONObject>() else null
        if (queue != null) pendingResponses[extra] = queue
        JsonClient.td_send(clientId, request.toString())
        if (queue == null) return null

        val response = queue.poll(TdlibConfig.AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: run {
                pendingResponses.remove(extra)
                throw TdlibException("TDLib request timed out: ${request.optString("@type")}")
            }
        if (response.optString("@type") == "error") {
            throw TdlibException("TDLib error ${response.optInt("code")}: ${response.optString("message")}")
        }
        return response
    }

    private fun ensureReady() {
        if (!configLoaded) {
            throw TdlibException("TdlibClient.ensureConfigLoaded(streamUrl) must be called before any TDLib request")
        }
        ensureClient()
        if (authReadyLatch.count == 0L) {
            authFailure?.let { throw TdlibException(it) }
            return
        }
        if (!authReadyLatch.await(TdlibConfig.AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw TdlibException("TDLib login timed out — check BOT_TOKEN / API_ID / API_HASH in TdlibConfig")
        }
        authFailure?.let { throw TdlibException(it) }
    }

    /** True once TDLib has logged in (authorizationStateReady) at least once
     *  this process lifetime. [FallbackDataSource] uses this to know whether
     *  the NEXT open() attempt might still be paying the one-time cold-login
     *  cost (so it should wait [TdlibConfig.AUTH_TIMEOUT_MS]) or whether the
     *  client is already warm (so the short [TdlibConfig.OPEN_TIMEOUT_MS] is
     *  enough — no auth wait, just network for this one request). */
    fun isAuthReady(): Boolean = authReadyLatch.count == 0L

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Resolves a Telegram message to its downloadable file id + size,
     *  cached so repeated ExoPlayer reads on the same message don't
     *  re-issue getMessage. */
    fun resolveFile(chatId: Long, msgId: Long): ResolvedFile {
        ensureReady()
        val cacheKey = "${chatId}_$msgId"
        messageFileCache[cacheKey]?.let { return it }

        val response = send(
            JSONObject().apply {
                put("@type", "getMessage")
                put("chat_id", chatId)
                put("message_id", msgId)
            },
        ) ?: throw TdlibException("getMessage returned no response")

        val content = response.optJSONObject("content")
            ?: throw TdlibException("Message has no content")

        // messageVideo.video / messageDocument.document / messageAudio.audio /
        // messageAnimation.animation all nest their downloadable File object
        // under a field with the same name as the outer content key.
        val fileObj = listOf("video", "document", "audio", "animation")
            .firstNotNullOfOrNull { key -> content.optJSONObject(key)?.optJSONObject(key) }
            ?: throw TdlibException("Unsupported message content type for direct playback")

        val fileId = fileObj.getInt("id")
        val size = fileObj.optLong("size", 0L).let { if (it > 0) it else fileObj.optLong("expected_size", 0L) }
        val resolved = ResolvedFile(fileId, size)
        messageFileCache[cacheKey] = resolved
        return resolved
    }

    /** Blocks until `[start, start+length)` is available on local disk (per
     *  the class-doc range semantics above), then returns the local file
     *  path. Used directly by [readRange] and, with length=whole-file, by
     *  full downloads. */
    fun ensureRangeDownloaded(fileId: Int, start: Long, length: Long, timeoutMs: Long): String {
        ensureReady()
        send(
            JSONObject().apply {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("offset", start)
                put("limit", length)
                put("priority", 32)
                put("synchronous", false)
            },
        )

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val local = latestFileState[fileId]?.optJSONObject("local")
            if (local != null) {
                val path = local.optString("path", "")
                val downloadOffset = local.optLong("download_offset", 0L)
                val prefix = local.optLong("downloaded_prefix_size", 0L)
                val completed = local.optBoolean("is_downloading_completed", false)
                val covered = downloadOffset <= start && (downloadOffset + prefix) >= (start + length)
                if (path.isNotEmpty() && (covered || completed)) return path
            }
            Thread.sleep(40)
        }
        throw TdlibException("Timed out waiting for TDLib to download file_id=$fileId range [$start, ${start + length})")
    }

    /** Reads up to [length] bytes at [position] into [buffer]/[offset],
     *  blocking until TDLib has them on disk. Returns bytes read, or -1 at
     *  end-of-file (matches [androidx.media3.common.C.RESULT_END_OF_INPUT]). */
    fun readRange(chatId: Long, msgId: Long, position: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val resolved = resolveFile(chatId, msgId)
        if (resolved.totalSize > 0 && position >= resolved.totalSize) return -1

        val clampedLength = if (resolved.totalSize > 0) {
            minOf(length.toLong(), resolved.totalSize - position).toInt()
        } else {
            length
        }
        if (clampedLength <= 0) return -1

        val path = ensureRangeDownloaded(resolved.fileId, position, clampedLength.toLong(), timeoutMs)
        RandomAccessFile(path, "r").use { raf ->
            raf.seek(position)
            return raf.read(buffer, offset, clampedLength)
        }
    }

    /** Full-file variant used by [TdlibDownloadHelper]: downloads the
     *  entire file into TDLib's own storage (reporting progress along the
     *  way), then copies the finished file to [outFile]. */
    fun downloadFull(chatId: Long, msgId: Long, outFile: File, timeoutMs: Long, onProgress: (progressPct: Int, sizeBytes: Long) -> Unit) {
        val resolved = resolveFile(chatId, msgId)
        val total = resolved.totalSize
        ensureReady()
        send(
            JSONObject().apply {
                put("@type", "downloadFile")
                put("file_id", resolved.fileId)
                put("offset", 0)
                put("limit", 0) // 0 = no limit — download the whole file
                put("priority", 32)
                put("synchronous", false)
            },
        )

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val local = latestFileState[resolved.fileId]?.optJSONObject("local")
            if (local != null) {
                val prefix = local.optLong("downloaded_prefix_size", 0L)
                if (total > 0) onProgress(((prefix * 100) / total).toInt(), prefix)
                if (local.optBoolean("is_downloading_completed", false)) {
                    val path = local.optString("path", "")
                    if (path.isEmpty()) throw TdlibException("TDLib reported download complete but gave no local path")
                    File(path).copyTo(outFile, overwrite = true)
                    return
                }
            }
            Thread.sleep(150)
        }
        throw TdlibException("Timed out downloading file_id=${resolved.fileId}")
    }
}
