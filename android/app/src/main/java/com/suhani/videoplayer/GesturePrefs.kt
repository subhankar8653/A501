package com.suhani.videoplayer

import android.content.Context

/**
 * Player gestures (double-tap seek, volume/brightness swipe) aur online-subtitle
 * auto-fetch ke liye chhote settings — SharedPreferences mein save hote hain,
 * app band/dobara khulne par bhi yaad rehte hain. ThemeManager.kt jaisa hi
 * simple object pattern.
 */
object GesturePrefs {

    private const val PREFS_NAME = "gesture_prefs"
    private const val KEY_SEEK_SECONDS = "seek_seconds"
    private const val KEY_SWIPE_SENSITIVITY = "swipe_sensitivity"
    private const val KEY_AUTO_SUBTITLE = "auto_subtitle_online"

    const val SEEK_SECONDS_DEFAULT = 10
    const val SEEK_SECONDS_MIN = 5
    const val SEEK_SECONDS_MAX = 60

    // 100 = normal (jaisa pehle hardcoded tha). 50 = aadhi speed se swipe move hota
    // hai (dheema/precise), 200 = doguni speed se (tez).
    const val SWIPE_SENSITIVITY_DEFAULT = 100
    const val SWIPE_SENSITIVITY_MIN = 40
    const val SWIPE_SENSITIVITY_MAX = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSeekSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SEEK_SECONDS, SEEK_SECONDS_DEFAULT)

    fun setSeekSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SEEK_SECONDS, seconds.coerceIn(SEEK_SECONDS_MIN, SEEK_SECONDS_MAX)).apply()
    }

    fun getSwipeSensitivity(context: Context): Int =
        prefs(context).getInt(KEY_SWIPE_SENSITIVITY, SWIPE_SENSITIVITY_DEFAULT)

    fun setSwipeSensitivity(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_SWIPE_SENSITIVITY, percent.coerceIn(SWIPE_SENSITIVITY_MIN, SWIPE_SENSITIVITY_MAX))
            .apply()
    }

    /** Volume/brightness swipe delta par seedha multiply karne ke liye (1.0 = normal). */
    fun swipeMultiplier(context: Context): Float = getSwipeSensitivity(context) / 100f

    // Video khulte hi, agar koi subtitle (embedded ya attached) na ho, to chup-chaap
    // OpenSubtitles.com par title se search + top result auto-download+attach karna
    // hai ya nahi. Default OFF — data/API usage user ki marzi se hi ho.
    fun isAutoSubtitleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SUBTITLE, false)

    fun setAutoSubtitleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SUBTITLE, enabled).apply()
    }
}
