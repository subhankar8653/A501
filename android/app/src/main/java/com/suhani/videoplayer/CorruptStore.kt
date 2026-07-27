package com.suhani.videoplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ek video jo playback try karne par unrecoverable error (corrupt/damaged file,
 * ya format jo koi bhi decoder — HW, HW+, SW — handle nahi kar paaya) de chuka hai.
 */
data class CorruptEntry(
    val uri: String,
    val reason: String,
    val taggedAt: Long
)

/**
 * "Corrupt" tag ko SharedPreferences mein persist karta hai (HistoryStore jaisa hi pattern)
 * taaki Home grid mein us video par ek baar dobara "Corrupt" badge dikhe — bina dobara
 * khol ke check kiye ki wo phir se fail hoga ya nahi.
 */
object CorruptStore {
    private const val PREFS = "corrupt_files"
    private const val KEY = "entries"

    fun markCorrupt(context: Context, uri: String, reason: String) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, CorruptEntry(uri, reason, System.currentTimeMillis()))
        save(context, list)
    }

    fun unmark(context: Context, uri: String) {
        val list = getAll(context).toMutableList()
        if (list.removeAll { it.uri == uri }) save(context, list)
    }

    fun isCorrupt(context: Context, uri: String): Boolean =
        getAll(context).any { it.uri == uri }

    fun getAll(context: Context): List<CorruptEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CorruptEntry(
                    uri = o.getString("uri"),
                    reason = o.optString("reason", ""),
                    taggedAt = o.optLong("taggedAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<CorruptEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("uri", e.uri)
            o.put("reason", e.reason)
            o.put("taggedAt", e.taggedAt)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
