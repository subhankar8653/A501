package com.suhani.videoplayer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * A501 — direct phone<->Telegram migration.
 *
 * Fetches the on-device TDLib client's login credentials (api_id/api_hash/
 * bot_token) from Railway's `/tdlib-config/{token}` instead of hardcoding
 * them into the APK — Railway already has these as env vars for its own
 * Pyrofork bot (see Backend/config.py), so this just reuses them.
 *
 * Same URL-derivation trick as [TdlibResolveClient]: builds the config URL
 * from the SAME `/dl/{token}/{id}/{name}` stream URL the player already
 * received, rather than needing its own hardcoded backend base URL.
 */
object TdlibRemoteConfigClient {

    data class Credentials(val apiId: Int, val apiHash: String, val botToken: String)

    /** "https://host/dl/{token}/{id}/{name}" -> "https://host/tdlib-config/{token}",
     *  or null if [streamUrl] doesn't look like a `/dl/` proxy URL at all. */
    fun deriveConfigUrl(streamUrl: String): String? {
        val marker = "/dl/"
        val idx = streamUrl.indexOf(marker)
        if (idx < 0) return null
        val afterDl = streamUrl.substring(idx + marker.length)
        val token = afterDl.substringBefore("/")
        if (token.isEmpty()) return null
        val prefix = streamUrl.substring(0, idx)
        return "$prefix/tdlib-config/$token"
    }

    /** Blocking call — caller ([TdlibClient]) is already off the main
     *  thread. Throws on any failure; caller decides how that should
     *  surface (fallback to Railway proxy, in practice). */
    @Throws(Exception::class)
    fun fetch(streamUrl: String): Credentials {
        val configUrl = deriveConfigUrl(streamUrl)
            ?: throw IOException("Cannot derive tdlib-config URL from $streamUrl")

        val conn = (URL(configUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("tdlib-config fetch failed: HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return Credentials(
                apiId = json.getInt("api_id"),
                apiHash = json.getString("api_hash"),
                botToken = json.getString("bot_token"),
            )
        } finally {
            conn.disconnect()
        }
    }
}
