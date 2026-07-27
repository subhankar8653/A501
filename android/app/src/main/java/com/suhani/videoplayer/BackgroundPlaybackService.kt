package com.suhani.videoplayer

import com.suhani.screen.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Chhota, hamesha-chalne-wala foreground service — sirf isliye taaki jab video
 * background me chala jaaye (Background Play ON hone par) to Android process ko
 * "cached/low priority" maan kar turant kill na kare.
 *
 * Bina is foreground service ke, Activity background me jaate hi Android kuch hi
 * seconds/minutes me process ko suspend/kill kar sakta hai — isi wajah se pehle
 * sirf thodi der audio chalta tha phir ruk jaata tha. Ab jab tak yeh service
 * foreground me hai, process zinda rehta hai aur ExoPlayer (Activity ke andar)
 * bina rukawat ke audio decode/play karta rehta hai.
 *
 * Actual playback control (play/pause/seek) PlayerActivity ke ExoPlayer instance
 * me hi hota hai — yeh service sirf process ko alive rakhne + notification
 * dikhane ke liye hai.
 */
class BackgroundPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "background_playback_channel"
        const val NOTIF_ID = 4821
        const val EXTRA_TITLE = "extra_title"
        const val ACTION_STOP = "com.suhani.videoplayer.action.STOP_BG_PLAYBACK"

        fun start(context: Context, title: String) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundPlaybackService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Video"
        createChannelIfNeeded()

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = Intent(this, BackgroundPlaybackService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_flat)
            .setContentTitle(title)
            .setContentText("Background me play ho raha hai")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        return START_STICKY
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Background Playback",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Video background me play hote waqt dikhne wali notification"
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
