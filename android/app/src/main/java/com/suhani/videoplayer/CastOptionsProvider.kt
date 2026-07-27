package com.suhani.videoplayer

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Google Cast SDK ko yahan se apni settings milti hain (AndroidManifest.xml mein
 * OPTIONS_PROVIDER_CLASS_NAME se reference hoti hai). Hum Google ka public
 * "Default Media Receiver" use kar rahe hain (app id DEFAULT_MEDIA_RECEIVER_APPLICATION_ID) —
 * isliye koi custom receiver app banane/publish karne ki zaroorat nahi, ye standard
 * video/audio streams (mp4, hls waghera) ko turant play kar sakta hai.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
