package com.suhani.videoplayer

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online subtitle search/download — OpenSubtitles.com REST API se.
 *
 * Setup (ek baar): https://www.opensubtitles.com/en/consumers par free account banao,
 * "API Consumers" section mein naya app register karo, milne wali API key neeche
 * API_KEY mein paste kar dena. Key ke bina "Online subtitles" button user ko clearly
 * bata dega ki key missing hai (crash nahi karega).
 *
 * Sab functions blocking hain (network call) — hamesha background thread se call karein,
 * result UI thread par runOnUiThread se dikhayein. (PlayerActivity.showOnlineSubtitleSearch()
 * mein isi pattern se use hota hai.)
 */
object OpenSubtitlesClient {

    // TODO: apni free OpenSubtitles.com API key yahan daalo
    const val API_KEY = ""

    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "Sisisisi v1.0"

    data class Result(
        val fileId: Long,
        val language: String,
        val releaseName: String
    )

    class ApiException(message: String) : Exception(message)

    fun isConfigured(): Boolean = API_KEY.isNotBlank()

    /** Query (movie/video ka naam) se subtitle results dhoondta hai. Blocking. */
    fun search(query: String): List<Result> {
        if (!isConfigured()) throw ApiException("Online subtitles ke liye free API key chahiye")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$BASE_URL/subtitles?query=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Api-Key", API_KEY)
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw ApiException("Search fail ho gayi (HTTP ${conn.responseCode})")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val results = mutableListOf<Result>()
            for (i in 0 until data.length()) {
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: continue
                val files = attrs.optJSONArray("files") ?: continue
                if (files.length() == 0) continue
                val fileId = files.getJSONObject(0).optLong("file_id", -1L)
                if (fileId <= 0) continue
                val lang = attrs.optString("language", "?")
                val release = attrs.optString("release").ifBlank { "Subtitle #${i + 1}" }
                results.add(Result(fileId, lang, release))
            }
            return results
        } finally {
            conn.disconnect()
        }
    }

    /** file_id se asli download link nikalta hai. Blocking. */
    private fun requestDownloadLink(fileId: Long): String {
        val conn = (URL("$BASE_URL/download").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Api-Key", API_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            val payload = JSONObject().put("file_id", fileId).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw ApiException("Download link nahi mila (HTTP ${conn.responseCode})")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val link = JSONObject(body).optString("link")
            if (link.isBlank()) throw ApiException("Download link khaali aaya")
            return link
        } finally {
            conn.disconnect()
        }
    }

    /** Subtitle file ko destFile mein download karta hai. Blocking. */
    fun download(fileId: Long, destFile: File) {
        val link = requestDownloadLink(fileId)
        val conn = (URL(link).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw ApiException("Subtitle download fail ho gaya (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
