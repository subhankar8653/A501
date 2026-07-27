package com.suhani.videoplayer

import android.content.Context

/**
 * Auto Intro/Recap Skip: user ek episode mein intro ka start+end ek baar "Mark Intro"
 * se set kar deta hai. Wahi range us poore series/folder (VideoItem.relativePath ko
 * key ki tarah use karke) ke liye yaad rakh li jaati hai — taaki agli episode (same
 * folder) khulte hi, jab playback us time-range mein pahunche, khud-ba-khud
 * "Skip Intro" button dikh jaaye, bina dobara mark kiye.
 *
 * Storage: SharedPreferences (halka-sa, per-folder do Long values) — koi database
 * ki zaroorat nahi, chhota sa data hai.
 */
object IntroSkipStore {
    private const val PREFS_NAME = "intro_skip_prefs"

    private fun keyStart(folderKey: String) = "start::$folderKey"
    private fun keyEnd(folderKey: String) = "end::$folderKey"

    /** Current episode ke folder ke liye intro range (ms) save karta hai. */
    fun saveRange(context: Context, folderKey: String, startMs: Long, endMs: Long) {
        if (folderKey.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(keyStart(folderKey), startMs)
            .putLong(keyEnd(folderKey), endMs)
            .apply()
    }

    /** [start, end] ms lautata hai agar is folder ke liye pehle se koi range marked hai, warna null. */
    fun getRange(context: Context, folderKey: String): Pair<Long, Long>? {
        if (folderKey.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val start = prefs.getLong(keyStart(folderKey), -1L)
        val end = prefs.getLong(keyEnd(folderKey), -1L)
        if (start < 0 || end < 0 || end <= start) return null
        return start to end
    }

    /** Agar user ne range galat mark kar diya ho to series ke liye reset karne ka option. */
    fun clearRange(context: Context, folderKey: String) {
        if (folderKey.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyStart(folderKey))
            .remove(keyEnd(folderKey))
            .apply()
    }
}
