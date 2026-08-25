package com.suhani.videoplayer

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * A501 — direct phone<->Telegram migration.
 *
 * Talks to the new Railway endpoint (`/resolve/{token}/{id}`, added in
 * stream_routes.py) that hands back the raw Telegram chat_id/message_id for
 * a file, instead of proxying its bytes. See the migration doc, step 5.
 *
 * Deliberately derives the resolve URL from the SAME `/dl/{token}/{id}/{name}`
 * stream URL the player already receives (from the WebView/JS side) rather
 * than needing its own hardcoded backend base URL — this app has no such
 * constant today (the JS side owns the backend host), so reusing the URL
 * the player was already given keeps this consistent with the rest of the
 * app instead of introducing a second source of truth for the host.
 */
object TdlibResolveClient {

    data class Resolution(
        val chatId: Long,
        val msgId: Long,
        val title: String?,
        val source: String,
    )

    /** "https://host/dl/{token}/{id}/{name}" -> "https://host/resolve/{token}/{id}",
     *  or null if [streamUrl] doesn't look like a `/dl/` proxy URL at all
     *  (e.g. an already-external/direct link, nothing to resolve). */
    fun deriveResolveUrl(streamUrl: String): String? {
        val marker = "/dl/"
        val idx = streamUrl.indexOf(marker)
        if (idx < 0) return null
        val afterDl = streamUrl.substring(idx + marker.length)
        val parts = afterDl.split("/")
        if (parts.size < 3) return null // need token/id/name
        val token = parts[0]
        val id = parts[1]
        val prefix = streamUrl.substring(0, idx)
        return "$prefix/resolve/$token/$id"
    }

    /** Blocking call — caller (TdlibDataSource) is already off the main
     *  thread since it runs inside ExoPlayer's loading thread. */
    @Throws(Exception::class)
    fun resolve(resolveUrl: String): Resolution {
        val conn = (URL(resolveUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("resolve failed: HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return Resolution(
                chatId = json.getLong("chat_id"),
                msgId = json.getLong("msg_id"),
                title = if (json.isNull("title")) null else json.optString("title"),
                source = json.optString("source", "direct"),
            )
        } finally {
            conn.disconnect()
        }
    }
}
