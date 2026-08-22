package com.suhani.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * ROOT CAUSE FIX (user report: "download pe lagata hu, kisi aur app mein
 * chala jaata hu to yahan download cancel ho jaata hai, aur notification bar
 * mein bhi progress nahi dikhta"): pehle `WebDownloadInterface.startDownload()`
 * seedha `NativeDownloadManager.start()` ko call kar deta tha, jo sirf ek
 * plain background `Thread` par chalta tha — koi foreground service nahi.
 *
 * Bina foreground service ke, jaise hi app background mein jaata (user koi
 * aur app khole), Android — khaaskar MIUI/ColorOS/FuntouchOS jaise aggressive
 * battery-saver OEM skins jo India mein bahut aam hain — is poore process ko
 * "background/cached" maan kar kuch hi seconds/minutes mein kill kar deta
 * tha. Process kill hote hi usi ke andar chal raha download Thread bhi turant
 * mar jaata — result: "background jaate hi download cancel ho jaata hai".
 * Aur chahe process zinda bhi rehta, kahin koi notification hi nahi thi
 * jisse progress dikhe.
 *
 * Fix: download ab isi foreground Service ke andar chalta hai (bilkul
 * BackgroundPlaybackService jaisa proven pattern) — `startForeground()` ke
 * saath ek ongoing, LOW-priority notification jo live progress % dikhati
 * hai. Jab tak yeh service foreground mein hai, Android is process ko
 * "user-visible/important" maan kar zinda rakhta hai chahe Activity khud
 * background mein ho — download poora hone tak (ya user khud cancel kare)
 * chalta rehta hai, aur notification bar mein progress bhi hamesha dikhta
 * hai.
 *
 * `NativeDownloadManager` ka underlying download logic (HTTP + file-write)
 * bilkul waisa hi rehta hai — is service ka kaam sirf usse ek foreground/
 * notification "ghar" dena hai, taaki OS use na maare.
 */
class DownloadService : Service() {

    /** App foreground mein ho (MainActivity zinda) to yahan register hoke JS
     *  ko bhi progress/done/error forward kiya jaata hai — notification
     *  hamesha update hoti rehti hai, listener registered ho ya na ho.
     *
     *  BUG FIX (build error: "Unresolved reference 'ProgressListener'" /
     *  "overrides nothing"): yeh interface pehle `companion object` ke ANDAR
     *  declare kiya gaya tha — Kotlin mein uska asli path tab
     *  `DownloadService.Companion.ProgressListener` ban jaata hai, na ki
     *  `DownloadService.ProgressListener` (jo MainActivity use kar raha
     *  tha). Fix: interface ko companion object se bahar, seedha class ke
     *  andar rakha — ab `DownloadService.ProgressListener` sahi se resolve
     *  hota hai. */
    interface ProgressListener {
        fun onDownloadProgress(id: String, pct: Int, bytes: Long)
        fun onDownloadDone(id: String, contentUri: String)
        fun onDownloadError(id: String, message: String)
    }

    companion object {
        const val CHANNEL_ID = "downloads_channel"
        const val NOTIF_ID = 5931
        const val ACTION_START = "com.suhani.screen.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.suhani.screen.action.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        @Volatile
        var listener: ProgressListener? = null

        fun start(context: Context, id: String, url: String, title: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            context.startService(intent)
        }
    }

    private class Progress(val title: String, var pct: Int)

    // id -> in-flight progress. ConcurrentHashMap kyunki progress updates
    // NativeDownloadManager ke apne background Thread se aate hain.
    private val activeDownloads = ConcurrentHashMap<String, Progress>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id == null) {
            if (activeDownloads.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL) {
            NativeDownloadManager.cancel(id)
            activeDownloads.remove(id)
            refreshNotificationOrStop()
            return START_NOT_STICKY
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (activeDownloads.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"

        createChannelIfNeeded()
        activeDownloads[id] = Progress(title, 0)
        postForegroundNotification()

        NativeDownloadManager.start(
            context = applicationContext,
            id = id,
            url = url,
            onProgress = { pct, bytes ->
                activeDownloads[id]?.pct = pct
                mainHandler.post {
                    // Download cancel ho chuka ho sakta hai isi beech (race) —
                    // sirf tabhi notification update karo jab abhi bhi active ho.
                    if (activeDownloads.containsKey(id)) postForegroundNotification()
                    listener?.onDownloadProgress(id, pct, bytes)
                }
            },
            onDone = { contentUri ->
                activeDownloads.remove(id)
                mainHandler.post {
                    refreshNotificationOrStop()
                    listener?.onDownloadDone(id, contentUri)
                }
            },
            onError = { message ->
                activeDownloads.remove(id)
                mainHandler.post {
                    refreshNotificationOrStop()
                    listener?.onDownloadError(id, message)
                }
            },
        )
        return START_NOT_STICKY
    }

    /** Ek hi ongoing "summary" notification — sabse abhi-abhi update hui download
     *  ka % dikhati hai, aur agar ek se zyada download saath mein chal rahe hon
     *  to baaki ki count bhi ("+2 aur"). Simple aur reliable: alag-alag har
     *  download ke liye alag notification track karne ki jhanjhat nahi. */
    private fun postForegroundNotification() {
        if (activeDownloads.isEmpty()) return
        val entries = activeDownloads.entries.toList()
        val (primaryId, primary) = entries.last()
        val extraCount = entries.size - 1
        val pct = primary.pct.coerceIn(0, 100)
        val contentText = if (extraCount > 0) {
            "$pct% · +$extraCount aur download ho rahe hain"
        } else {
            "$pct% download ho raha hai"
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val cancelIntent = Intent(this, DownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_ID, primaryId)
        val cancelPendingIntent = PendingIntent.getService(
            this, primaryId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_flat)
            .setContentTitle(primary.title)
            .setContentText(contentText)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun refreshNotificationOrStop() {
        if (activeDownloads.isEmpty()) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        } else {
            postForegroundNotification()
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Video download progress notification"
                manager.createNotificationChannel(channel)
            }
        }
    }
}
