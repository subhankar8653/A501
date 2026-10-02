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
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * FEATURE (user ask: "stream hote waqt download queue mein chala jaega,
     * watch band karte hi download apne aap shuru ho jaega — single download
     * ya watch hoga, dono ek saath nahi, taaki jo bhi chal raha ho use full
     * power mile"): pehle streaming aur download dono ek saath TDLib se chal
     * sakte the, bas alag-alag priority (32 vs 16) ke saath bandwidth baant
     * lete the — ab jab watching shuru ho, download ko poori tarah RUKNA
     * hai (koi bhi network activity nahi), taaki streaming ko poora bandwidth
     * mile, aur watching rukte hi download turant fir se shuru ho jaaye.
     * Is exception se downloadFullAttempt() apne poll-loop ko cleanly, bina
     * error maane, beech mein rok deta hai — [TdlibDownloadHelper] ise pakad
     * kar `onPaused()` call karta hai (`onError()` nahi), taaki caller
     * (NativeDownloadManager) ise ek resumable pause maane, permanent
     * failure nahi.
     */
    class TdlibDownloadPausedException(message: String) : Exception(message)

    // ---------------------------------------------------------------------
    // BANDWIDTH SPLIT (user ask: "video dekhte dekhte bhi download ho, video
    // par 30% focus aur download par 70%; video nahi dekh rahe to poora 100%
    // download par"). Download ab watch ke time pause/queue NAHI hota.
    //
    // TDLib exact percentage split nahi deta — sirf per-file priority (1..32),
    // jisme zyada priority wali file ko pehle bandwidth milti hai. Isliye
    // watching ke dauraan download ki priority ek 10s ke cycle mein badalti
    // hai: pehle 7s (70%) download > stream, baaki 3s (30%) stream > download.
    // Video ke paas ExoPlayer ka buffer hota hai, to 7s ke download-window mein
    // bhi playback nahi rukta, aur 3s ke window mein buffer wapas bhar jaata
    // hai. Watching nahi hai to download hamesha max priority (100%) par.
    // ---------------------------------------------------------------------
    private const val STREAM_PRIORITY = 16
    private const val DOWNLOAD_PRIORITY_HIGH = 32
    private const val DOWNLOAD_PRIORITY_LOW = 8
    private const val SPLIT_CYCLE_MS = 10_000L
    private const val DOWNLOAD_SHARE_PERCENT = 70L
    private const val WATCH_STOP_GRACE_MS = 1_500L

    @Volatile private var watchingPlaying = false
    @Volatile private var watchingStoppedAt = 0L

    /** Player (inline overlay ya fullscreen) ke play/pause par bulao. Short
     *  buffering/pause blips par split baar-baar na badle, isliye stop ke baad
     *  [WATCH_STOP_GRACE_MS] tak abhi bhi "watching" maana jaata hai. */
    fun setWatching(isPlaying: Boolean) {
        watchingPlaying = isPlaying
        if (!isPlaying) watchingStoppedAt = System.currentTimeMillis()
    }

    fun isWatching(): Boolean =
        watchingPlaying || (System.currentTimeMillis() - watchingStoppedAt) < WATCH_STOP_GRACE_MS

    /** Download ki abhi ki priority: watching nahi -> hamesha max (100%);
     *  watching -> cycle ke pehle 70% waqt high, baaki 30% low. */
    private fun desiredDownloadPriority(): Int {
        if (!isWatching()) return DOWNLOAD_PRIORITY_HIGH
        val phase = System.currentTimeMillis() % SPLIT_CYCLE_MS
        return if (phase < SPLIT_CYCLE_MS * DOWNLOAD_SHARE_PERCENT / 100) DOWNLOAD_PRIORITY_HIGH else DOWNLOAD_PRIORITY_LOW
    }

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

    /** SPEED FIX (user report: "play dabane se pehle 10-15 second rukna
     *  padta hai"): pehle TDLib login sirf tab shuru hota tha jab ExoPlayer
     *  khud open() bulaata — matlab poora ~10s login cost seedha play
     *  dabaane ke baad hi user ko dikhta tha. Ab caller (PlayerActivity)
     *  isse jitni jaldi ho sake bula sakta hai (video select hote hi, play
     *  se pehle) — login background thread par UI/player setup ke saath
     *  parallel chalta hai, taaki play dabane tak client zyaadatar already
     *  warm mil jaaye. Fire-and-forget aur safe hai baar-baar call karne ke
     *  liye (ensureConfigLoaded/ensureClient/tdlibParametersSent/
     *  botTokenSent — sab pehle se hi idempotent guards ke saath hain), so
     *  agli video select hone par bhi dubara call karna harmless hai. */
    fun prewarm(streamUrl: String) {
        if (!TdlibConfig.ENABLED) return
        Thread(
            {
                try {
                    ensureConfigLoaded(streamUrl)
                    ensureClient()
                } catch (e: Exception) {
                    // Non-fatal — TdlibDataSource ka apna real attempt is
                    // failure ko dubara try karega aur genuine error surface
                    // karega agar zaroorat pade. Prewarm ka poora point sirf
                    // "jaldi shuru karo" hai, "guarantee karo" nahi.
                    Log.w(TAG, "prewarm() failed (non-fatal, real attempt will retry): ${e.message}")
                }
            },
            "TdlibClient-prewarm",
        ).apply { isDaemon = true }.start()
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

    // BUG FIX #9 (root cause of the periodic short stalls/buffering during
    // otherwise-normal playback): ensureRangeDownloaded() used to send a
    // fresh `downloadFile` command — a BLOCKING IPC call via send(), which
    // waits on a SynchronousQueue for TDLib's ack — on EVERY single
    // ExoPlayer read() call (~64 KB each). That both (a) added a blocking
    // round-trip on the hot path every ~64 KB and (b) kept re-narrowing
    // TDLib's download window to whatever tiny range was requested *right
    // now*, so TDLib never got to build any real read-ahead buffer.
    // Fix: request one much larger forward window per fileId and only
    // re-issue downloadFile when playback moves outside it (seek, or
    // catching up near its edge) — everything else just polls local
    // progress, no repeated IPC/ack wait.
    private const val READ_AHEAD_BYTES = 6L * 1024 * 1024 // 6 MB
    private data class DownloadWindow(val start: Long, val end: Long)
    private val activeWindow = ConcurrentHashMap<Int, DownloadWindow>()

    /** CLEAN-RETRY FIX (user ask: "fail ho gaya to sara cache clear karke
     *  fir se retry karo" — ab ki Railway hata diya gaya hai, yahi harr
     *  failure ka poora recovery path hai, koi HTTP proxy safety-net nahi
     *  bacha). Ek fileId ke liye TDLib ka apna partial-download progress
     *  bhula deta hai (deleteFile — disk se bhi hata deta hai) aur humara
     *  local window/state tracking bhi reset karta hai, taaki agla attempt
     *  bilkul saaf offset 0 se shuru ho, kisi stale/corrupt window state
     *  ko carry na kare. [ensureRangeDownloaded]/[downloadFull] dono isse
     *  khud-ba-khud ek baar retry ke liye use karte hain. */
    fun resetFileForRetry(fileId: Int) {
        activeWindow.remove(fileId)
        latestFileState.remove(fileId)
        try {
            send(
                JSONObject().apply {
                    put("@type", "deleteFile")
                    put("file_id", fileId)
                },
            )
        } catch (e: Exception) {
            // Best-effort — even if TDLib couldn't/wouldn't delete the local
            // copy (e.g. never actually got any bytes yet), our own window/
            // state tracking above is already cleared, which is the part
            // that actually mattered for a clean retry.
            Log.w(TAG, "resetFileForRetry: deleteFile cleanup failed for file_id=$fileId (continuing anyway): ${e.message}")
        }
    }

    /** FEATURE (user ask: "video load nahi hota, fail ho jata hai — fail
     *  hone ke baad sara cache clear karo aur wapas try karo"): [resetFileForRetry]
     *  ko `fileId` chahiye, lekin player error-handlers (PlayerActivity/
     *  MainActivity) ke paas sirf original stream URL hota hai — yeh
     *  convenience wrapper wahi resolve-then-reset kar deta hai ek hi call
     *  mein. Best-effort/silent-fail: agar URL Telegram link hi nahi hai ya
     *  resolve timeout ho jaaye, seedha no-op — caller ka apna retry
     *  (dobara DataSource.open()) beech mein jo bhi asli problem hai woh
     *  khud surface kar dega. */
    fun resetCacheForStreamUrl(streamUrl: String) {
        try {
            val resolveUrl = TdlibResolveClient.deriveResolveUrl(streamUrl) ?: return
            val resolution = TdlibResolveClient.resolve(resolveUrl)
            val resolved = resolveFile(resolution.chatId, resolution.msgId)
            resetFileForRetry(resolved.fileId)
        } catch (e: Exception) {
            Log.w(TAG, "resetCacheForStreamUrl: best-effort cache clear failed for $streamUrl (continuing anyway): ${e.message}")
        }
    }

    @Volatile private var authReadyLatch = CountDownLatch(1)
    @Volatile private var authFailure: String? = null

    // BUG FIX #3: the previous timeout message ("TDLib login timed out")
    // told us NOTHING about why — was it even attempting to reach
    // Telegram's servers? TDLib pushes its own connection-state updates
    // (waiting for network / connecting / connecting to proxy / updating /
    // ready) completely separately from authorizationState — we were never
    // listening to them. Tracked here so a timeout can say exactly which
    // stage TDLib was stuck at (e.g. still "connectionStateConnecting"
    // after 18s strongly points at the device/network not being able to
    // reach Telegram at all — ISP throttling/blocking, no outbound access
    // for this process, etc. — rather than a credentials problem).
    @Volatile private var lastConnectionState: String = "(no updateConnectionState received yet)"

    // Pure diagnostics — no logic depends on these. They exist so the next
    // test can tell, from the screen alone, whether receiveLoop is truly
    // alive and calling td_receive() in a loop (iterations climbing) vs
    // dead/never-started (stuck at 0), and what td_receive() is actually
    // returning each call (null / some @type / a raw non-JSON string).
    val receiveLoopIterations = AtomicInteger(0)
    @Volatile var lastRawSeen: String = "(none yet)"
    @Volatile var lastClientIdCreated: Int = -999

    private val configLoadLock = Any()
    @Volatile private var configLoaded = false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    // Stable per-install random id; backend uses it to give this device a
    // sticky bot from the phone-bot pool (spreads sessions across many bots).
    private fun deviceId(): String {
        val ctx = resolveAppContext() ?: return "unknown-device"
        val prefs = ctx.getSharedPreferences("tdlib_device", Context.MODE_PRIVATE)
        var id = prefs.getString("id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("id", id).apply()
        }
        return id
    }

    private fun tokenFingerprint(token: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)

    // Bot failover: if the assigned phone bot is rejected/revoked/flood-banned
    // at login, ask the backend for the next bot in the pool (rotate+1) and log
    // in again with a fresh client + its own database dir.
    private const val MAX_FAILOVERS = 3
    private val failoverLock = Any()
    @Volatile private var rotation = 0
    @Volatile private var failoverCount = 0
    @Volatile private var lastStreamUrl: String? = null
    @Volatile private var activeBotFingerprint: String = "default"

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
            lastStreamUrl = streamUrl
            val creds = try {
                TdlibRemoteConfigClient.fetch(streamUrl, deviceId(), rotation)
            } catch (e: Exception) {
                throw TdlibException("Could not fetch Telegram API credentials from backend: ${e.message}", e)
            }
            TdlibConfig.API_ID = creds.apiId
            TdlibConfig.API_HASH = creds.apiHash
            TdlibConfig.BOT_TOKEN = creds.botToken
            activeBotFingerprint = tokenFingerprint(creds.botToken)
            configLoaded = true
        }
    }

    private fun ensureClient() {
        if (clientId >= 0) return
        synchronized(creationLock) {
            if (clientId >= 0) return
            clientId = JsonClient.td_create_client_id()
            lastClientIdCreated = clientId
            Thread({ receiveLoop() }, "TdlibClient-receive").apply { isDaemon = true }.start()
            // CONFIRMED by build-7's test (diag showed 12 loop iterations,
            // valid clientId, but "last raw: (none yet)" — td_receive()
            // truly never returned anything, ever, without this): this
            // specific .so build does NOT auto-push
            // authorizationStateWaitTdlibParameters on its own — it needs
            // an explicit nudge. build-6 had this same call and DID get a
            // response (proving it works) but double-sent
            // setTdlibParameters because handleAuthUpdate() wasn't
            // idempotent — fixed below with sendGuard, not by removing
            // this.
            sendAuthCritical(JSONObject().apply { put("@type", "getAuthorizationState") })
        }
    }

    // BUG FIX #6: handleAuthUpdate() can now legitimately be reached twice
    // for the same state (once via getAuthorizationState's direct response,
    // once via TDLib's own updateAuthorizationState push after processing
    // that request) — guard each one-time send so a duplicate arrival is a
    // harmless no-op instead of TDLib rejecting the second copy with
    // "400 Unexpected setTdlibParameters" (which build-6 hit).
    @Volatile private var tdlibParametersSent = false
    @Volatile private var botTokenSent = false

    private fun receiveLoop() {
        while (true) {
            receiveLoopIterations.incrementAndGet() // every pass, timeouts included
            val raw = try {
                JsonClient.td_receive(2.0)
            } catch (e: Throwable) {
                // BUG FIX #4 (likely root cause of "no updateConnectionState
                // received yet" persisting the entire 18s wait, every time):
                // this used to catch only `Exception`. If the native call
                // itself fails at the JNI/linkage level (e.g.
                // UnsatisfiedLinkError — the exact ABI's libtdjson.so
                // missing a symbol, or a class-loading problem in the
                // io.github.up9cloud.td.JsonClient bridge), that's an
                // Error, not an Exception in Kotlin/Java — it was NOT
                // caught here, so this whole daemon thread died silently
                // on its very first iteration. Nothing ever processes
                // updateAuthorizationState/updateConnectionState again for
                // the rest of the process's life, which matches exactly
                // what every test so far has shown. Catching Throwable
                // surfaces the real error immediately instead of an
                // eternal silent hang.
                Log.e(TAG, "td_receive failed fatally", e)
                authFailure = "TDLib native receive loop crashed: " +
                    "${e::class.java.name}: ${e.message}"
                lastConnectionState = "receiveLoop crashed: ${e::class.java.simpleName}"
                if (authReadyLatch.count > 0L) authReadyLatch.countDown()
                return // don't keep spinning on a fatal native error
            } ?: continue

            lastRawSeen = raw.take(120)

            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                continue
            }

            // After a bot failover the abandoned client may still emit events —
            // ignore anything not from the current client (when TDLib tags it).
            val msgClientId = json.optInt("@client_id", -1)
            if (msgClientId >= 0 && clientId >= 0 && msgClientId != clientId) continue

            when (json.optString("@type")) {
                "updateAuthorizationState" -> handleAuthUpdate(json.optJSONObject("authorization_state"))
                "updateConnectionState" -> {
                    lastConnectionState = json.optJSONObject("state")?.optString("@type")
                        ?: "(unknown updateConnectionState shape)"
                }
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
                    } else if (requestType == "getAuthorizationState") {
                        // Unlike the other two, THIS response body IS the
                        // state object itself (same shape TDLib would push
                        // via updateAuthorizationState) — act on it the
                        // same way in case this build never pushes state
                        // changes unprompted.
                        handleAuthUpdate(json)
                    }
                }
            }
        }
    }

    private fun handleAuthUpdate(state: JSONObject?) {
        state ?: return
        when (state.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                if (tdlibParametersSent) return // already sent — see sendGuard doc above
                tdlibParametersSent = true
                // One TDLib database per bot (keyed by token fingerprint): a TDLib
                // db stays logged in as the bot it first used, so after a bot
                // rotation / token change we must not reuse the old one.
                resolveAppContext()?.filesDir?.let { fd ->
                    val legacy = File(fd, "tdlib")
                    if (legacy.exists()) {
                        // Old single-bot (main-bot) session + cache — remove once.
                        Thread { runCatching { legacy.deleteRecursively() } }.apply { isDaemon = true }.start()
                    }
                }
                val dbDir = resolveAppContext()?.filesDir?.let { File(it, "tdlib_$activeBotFingerprint").absolutePath }
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
                if (botTokenSent) return // already sent — see sendGuard doc above
                botTokenSent = true
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
                tdlibParametersSent = false
                botTokenSent = false
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
                throw TdlibException(
                    "TDLib request timed out: ${request.optString("@type")} " +
                        "(connection state: $lastConnectionState)"
                )
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
        val failure = awaitAuth() ?: return
        if (!failoverToNextBot(failure)) throw TdlibException(failure)
        awaitAuth()?.let { throw TdlibException(it) }
    }

    /** Waits for login; returns the auth failure message, or null if ready. */
    private fun awaitAuth(): String? {
        ensureClient()
        if (authReadyLatch.count > 0L && !authReadyLatch.await(TdlibConfig.AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw TdlibException(
                "TDLib login timed out (connection state: $lastConnectionState) — " +
                    "check network access to Telegram"
            )
        }
        return authFailure
    }

    /** Only bot-level auth problems justify switching bots (token rejected /
     *  revoked / session killed) — not network timeouts or native crashes. */
    private fun isBotAuthFailure(failure: String): Boolean =
        failure.startsWith("TDLib checkAuthenticationBotToken failed") ||
            failure.startsWith("TDLib authorization closed")

    private fun failoverToNextBot(failure: String): Boolean {
        if (!isBotAuthFailure(failure)) return false
        synchronized(failoverLock) {
            // Another thread already failed over while we waited on the lock.
            if (authFailure != failure) return true
            if (failoverCount >= MAX_FAILOVERS) return false
            val url = lastStreamUrl ?: return false
            failoverCount++
            rotation++
            Log.w(TAG, "Bot auth failed ($failure) — failing over to next phone bot (rotate=$rotation)")
            configLoaded = false
            authFailure = null
            authReadyLatch = CountDownLatch(1)
            tdlibParametersSent = false
            botTokenSent = false
            clientId = -1 // abandon old client (no close() so no stale Closed event)
            messageFileCache.clear()
            latestFileState.clear()
            activeWindow.clear()
            ensureConfigLoaded(url)
            ensureClient()
            return true
        }
    }

    /** True once TDLib has logged in (authorizationStateReady) at least once
     *  this process lifetime. [TdlibDataSource]/[TelegramRoutingDataSource]
     *  use this to know whether
     *  the NEXT open() attempt might still be paying the one-time cold-login
     *  cost (so it should wait [TdlibConfig.AUTH_TIMEOUT_MS]) or whether the
     *  client is already warm (so the short [TdlibConfig.OPEN_TIMEOUT_MS] is
     *  enough — no auth wait, just network for this one request). */
    fun isAuthReady(): Boolean = authReadyLatch.count == 0L

    /** Live TDLib connection state (waiting for network / connecting /
     *  ready / etc) — see [lastConnectionState] doc comment. Polled by the
     *  debug badge during a cold-login wait so the person testing can see
     *  progress in real time, not just a final generic timeout. */
    fun getConnectionState(): String = lastConnectionState

    /** Diagnostic snapshot for the debug badge — proves whether the
     *  receive loop is alive/iterating and what TDLib has actually
     *  returned, independent of the auth flow's own status text. */
    fun getDiagnostics(): String =
        "loop iters: ${receiveLoopIterations.get()}, clientId: $lastClientIdCreated, " +
            "last raw: $lastRawSeen"

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

        // CLEAN-RETRY FIX: chat/message resolve is a plain RPC round-trip
        // (no partial-download cache to poison), so a transient network
        // blip here just needs one plain retry — no [resetFileForRetry]
        // needed since there's nothing file-related to clean yet.
        return try {
            resolveFileAttempt(chatId, msgId, cacheKey)
        } catch (e: Exception) {
            Log.w(TAG, "resolveFile failed for chat_id=$chatId msg_id=$msgId, retrying once: ${e.message}")
            resolveFileAttempt(chatId, msgId, cacheKey)
        }
    }

    private fun resolveFileAttempt(chatId: Long, msgId: Long, cacheKey: String): ResolvedFile {
        // BUG FIX #8 (explains the "404: Not Found" that replaced "400:
        // Chat not found" once bug #7's getChat() fix landed): the msgId we
        // get back from Railway's resolve step is a plain Bot-API-style
        // server message id (small sequential number, e.g. 306) — but
        // TDLib's own message_id field is NOT that number directly. TDLib
        // reserves the low 20 bits of every message_id for local/internal
        // use and stores the real server id shifted left by 20
        // (message_id = server_id * 1048576). Passing the raw server id
        // straight to getMessage looks like some other, nonexistent
        // message to TDLib — hence "Not Found" even though the chat and
        // the real message are both completely fine.
        val tdlibMessageId = msgId shl 20

        // BUG FIX #7: TDLib only knows about chats it has actually loaded
        // into its internal chat list — a fresh login hasn't "seen" any
        // specific chat yet unless getChat() (or an update mentioning it)
        // has run first. Calling getMessage() directly on an unseen
        // chat_id fails with "Chat not found" even when the bot IS a
        // member and genuinely has access — this is exactly the "TDLib
        // error 400: Chat not found" every real test has hit once login
        // itself started succeeding. getChat() forces TDLib to
        // fetch+cache the chat before we reference it by ID.
        send(
            JSONObject().apply {
                put("@type", "getChat")
                put("chat_id", chatId)
            },
        ) ?: throw TdlibException("getChat returned no response for chat_id=$chatId")

        val response = send(
            JSONObject().apply {
                put("@type", "getMessage")
                put("chat_id", chatId)
                put("message_id", tdlibMessageId)
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
        // CLEAN-RETRY FIX (see [resetFileForRetry] doc comment) — this is
        // the hot path for both streaming reads and download-probe, so it's
        // the one most likely to hit a genuine transient stall. One clean
        // retry here, with cache cleared in between, replaces what used to
        // be "fail this attempt, let FallbackDataSource silently pay
        // Railway's bandwidth instead."
        return try {
            ensureRangeDownloadedAttempt(fileId, start, length, timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "ensureRangeDownloaded failed for file_id=$fileId range [$start, ${start + length}), clearing cache and retrying once: ${e.message}")
            resetFileForRetry(fileId)
            ensureRangeDownloadedAttempt(fileId, start, length, timeoutMs)
        }
    }

    private fun ensureRangeDownloadedAttempt(fileId: Int, start: Long, length: Long, timeoutMs: Long): String {
        val requestedEnd = start + length
        val window = activeWindow[fileId]
        // Only (re-)issue downloadFile when this request falls outside the
        // window we already asked TDLib to fetch — a seek backward, a seek
        // far forward, or normal playback finally catching up to the edge
        // of the current window. Anything inside it is left alone so
        // TDLib's own background download keeps running uninterrupted.
        val needsNewWindow = window == null || start < window.start || requestedEnd > window.end
        if (needsNewWindow) {
            val windowLength = maxOf(length, READ_AHEAD_BYTES)
            send(
                JSONObject().apply {
                    put("@type", "downloadFile")
                    put("file_id", fileId)
                    put("offset", start)
                    put("limit", windowLength)
                    // ROOT CAUSE FIX (user report: "streaming ke saath download
                    // shuru karta hun to download baar-baar ruk jaata hai"):
                    // yeh (streaming ka range-fetch) aur downloadFullAttempt()
                    // (poore-episode ka download) dono TDLib ka `downloadFile`
                    // pehle EK hi max priority (32) par bulate the. TDLib
                    // isi Telegram account/connection ke andar chal rahe saare
                    // active downloads ke beech bandwidth priority KE HISAAB SE
                    // baantta hai — jab dono 32 par barabar hote, TDLib unhe
                    // roughly barabar treat karta, isliye ek poore, steady
                    // download ki progress streaming ke chhote/bursty range-
                    // requests (seek, buffering-ahead) se baar-baar cut/slow ho
                    // jaati.
                    // Fix: streaming ki priority yahan jaan-bujh kar KAM (16)
                    // rakhi hai — download hamesha zyada priority (32, dekho
                    // downloadFullAttempt()) par chalega jab dono ek saath
                    // active hon, taaki download jaldi/steady poora ho, aur
                    // streaming (jo chal to rahi hai, bas kam priority par)
                    // thoda zyada dheere buffer ho.
                    put("priority", STREAM_PRIORITY)
                    put("synchronous", false)
                },
            )
            activeWindow[fileId] = DownloadWindow(start, start + windowLength)
        }

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
     *  way), then copies the finished file to [outFile].
     *
     *  [shouldPause] is polled between waits — when it flips true, TDLib's
     *  in-flight download is cancelled (`cancelDownloadFile`, freeing the
     *  bandwidth immediately for streaming) and this throws
     *  [TdlibDownloadPausedException] instead of retrying/erroring. TDLib
     *  keeps whatever bytes it already fetched in its own local cache, so a
     *  later call resumes from there — no separate byte-tracking needed on
     *  our side. */
    fun downloadFull(
        chatId: Long,
        msgId: Long,
        outFile: File,
        timeoutMs: Long,
        shouldPause: () -> Boolean = { false },
        onProgress: (progressPct: Int, sizeBytes: Long) -> Unit,
    ) {
        val resolved = resolveFile(chatId, msgId)
        ensureReady()
        // CLEAN-RETRY FIX (see [resetFileForRetry] doc comment, and user
        // ask: "Railway ko download se poori tarah hata do... fail ho gaya
        // to sara cache clear karke fir se retry karo"): pehle ek failure
        // yahan simply upar (NativeDownloadManager) tak throw ho jaata,
        // jahan se Railway ke asli /dl/ proxy se poora file phir se HTTP par
        // download hota — Telegram bandwidth ki jagah Railway ki. Ab
        // failure ke baad ek clean retry yahin ho jaata hai; Railway ka
        // koi role nahi bacha.
        //
        // A pause request is NOT a failure — never clear-cache-and-retry
        // for it, and never swallow it silently; it must propagate up so
        // the caller knows to stop treating this as an active download.
        try {
            downloadFullAttempt(resolved.fileId, resolved.totalSize, outFile, timeoutMs, shouldPause, onProgress)
        } catch (e: TdlibDownloadPausedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "downloadFull failed for file_id=${resolved.fileId}, clearing cache and retrying once: ${e.message}")
            resetFileForRetry(resolved.fileId)
            downloadFullAttempt(resolved.fileId, resolved.totalSize, outFile, timeoutMs, shouldPause, onProgress)
        }
    }

    /** Best-effort — asks TDLib to stop actively fetching bytes for this
     *  file right now (frees up bandwidth for streaming immediately).
     *  TDLib keeps whatever prefix it already downloaded, so a future
     *  `downloadFile` on the same file_id picks up from there. */
    private fun cancelDownloadFileQuiet(fileId: Int) {
        try {
            send(
                JSONObject().apply {
                    put("@type", "cancelDownloadFile")
                    put("file_id", fileId)
                    put("only_if_pending", false)
                },
                waitForResponse = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "cancelDownloadFile failed for file_id=$fileId (continuing anyway): ${e.message}")
        }
    }

    private fun sendDownloadRequest(fileId: Int, priority: Int) {
        send(
            JSONObject().apply {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("offset", 0)
                put("limit", 0) // 0 = no limit — download the whole file
                put("priority", priority)
                put("synchronous", false)
            },
        )
    }

    private fun downloadFullAttempt(
        fileId: Int,
        total: Long,
        outFile: File,
        timeoutMs: Long,
        shouldPause: () -> Boolean,
        onProgress: (progressPct: Int, sizeBytes: Long) -> Unit,
    ) {
        // Watching ke time download rukta nahi — sirf priority badalti hai
        // (dekho desiredDownloadPriority()). Same downloadFile dobara bhejne se
        // TDLib download restart nahi karta, bas priority update hoti hai.
        var currentPriority = desiredDownloadPriority()
        sendDownloadRequest(fileId, currentPriority)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (shouldPause()) {
                cancelDownloadFileQuiet(fileId)
                throw TdlibDownloadPausedException("download paused for file_id=$fileId")
            }
            val wanted = desiredDownloadPriority()
            if (wanted != currentPriority) {
                currentPriority = wanted
                sendDownloadRequest(fileId, wanted)
            }
            val local = latestFileState[fileId]?.optJSONObject("local")
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
        throw TdlibException("Timed out downloading file_id=$fileId")
    }
}
