package com.suhani.videoplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ek dekha/suna hua video ya audio track. More tab ke History section mein
 * dikhta hai, resume position ke saath tap karke wapas waheen se chalu ho jaata hai.
 */
data class HistoryEntry(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playedAt: Long
)

/**
 * VLC ke "History" section jaisa: har video/audio jo play hua uska record rakhta hai
 * (SharedPreferences mein JSON array), sabse recent sabse upar. Max 30 entries.
 */
object HistoryStore {
    private const val PREFS = "watch_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 30

    fun addOrUpdate(context: Context, entry: HistoryEntry) {
        if (entry.title.isBlank()) return
        val list = getAll(context).toMutableList()
        list.removeAll { it.uri == entry.uri }
        list.add(0, entry)
        save(context, list.take(MAX_ENTRIES))
    }

    fun getAll(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    uri = o.getString("uri"),
                    title = o.getString("title"),
                    isVideo = o.getBoolean("isVideo"),
                    positionMs = o.optLong("position", 0L),
                    durationMs = o.optLong("duration", 0L),
                    playedAt = o.optLong("playedAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun remove(context: Context, uri: String) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.uri == uri }
        save(context, list)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("uri", e.uri)
            o.put("title", e.title)
            o.put("isVideo", e.isVideo)
            o.put("position", e.positionMs)
            o.put("duration", e.durationMs)
            o.put("playedAt", e.playedAt)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
