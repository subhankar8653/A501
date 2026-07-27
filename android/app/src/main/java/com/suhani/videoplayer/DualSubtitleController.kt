package com.suhani.videoplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * "Dual Subtitle" feature: primary subtitle ExoPlayer ke apne standard
 * TrackSelection + SubtitleView se hi chalti hai (attachSubtitle() /
 * embedded track selection — bilkul jaisa pehle tha, waha kuch nahi chheda).
 *
 * Yeh controller ek DOOSRI, bilkul independent subtitle file (SRT ya VTT)
 * ko khud parse karta hai aur ek alag TextView (secondarySubtitleText, jo
 * screen ke UPAR dikhti hai) par player ki current position ke hisaab se
 * manually update karta hai — ExoPlayer ke text-renderer/track-selection
 * pipeline ko bilkul touch nahi karta, isliye primary subtitle system ke
 * saath koi conflict nahi hota.
 *
 * Use: language-learning ya do-subtitle-ek-saath (jaise original + translated)
 * dekhne ke liye.
 */
class DualSubtitleController(
    private val context: Context,
    private val targetView: TextView,
    private val currentPositionMs: () -> Long
) {

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private var cues: List<Cue> = emptyList()
    private var lastActiveIndex = -1
    private var delayMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            if (running) handler.postDelayed(this, 200L)
        }
    }

    val isLoaded: Boolean get() = cues.isNotEmpty()

    /** Subtitle delay adjust karne ke liye (ms, +ve = subtitle late dikhegi). Sync dialog jaisa hi concept. */
    fun setDelay(ms: Long) {
        delayMs = ms
        lastActiveIndex = -1
    }

    fun getDelay(): Long = delayMs

    /** Uri se SRT/VTT file load karke parsing shuru karta hai. Fail hone par false return karta hai. */
    fun load(uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: return false

            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            val parsed = if (fileName.endsWith(".vtt")) parseVtt(text) else parseSrt(text)

            if (parsed.isEmpty()) return false
            cues = parsed
            lastActiveIndex = -1
            start()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tickRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    /** Subtitle hata do — TextView chhup jaayegi aur naya video/track load hone tak khaali rahegi. */
    fun clear() {
        stop()
        cues = emptyList()
        lastActiveIndex = -1
        targetView.text = ""
        targetView.visibility = android.view.View.GONE
    }

    private fun tick() {
        if (cues.isEmpty()) return
        val pos = currentPositionMs() - delayMs
        val index = cues.indexOfFirst { pos in it.startMs..it.endMs }
        if (index == lastActiveIndex) return
        lastActiveIndex = index
        if (index >= 0) {
            targetView.text = cues[index].text
            targetView.visibility = android.view.View.VISIBLE
        } else {
            targetView.text = ""
            targetView.visibility = android.view.View.GONE
        }
    }

    // ---------------------------------------------------------------------
    // Parsers — standard SRT aur WebVTT dono ka basic (widely-compatible)
    // subset handle karte hain. Style tags (<i>, <b>, {\an8} jaisi ASS-leftover
    // tags) hata dete hain taaki plain readable text bache.
    // ---------------------------------------------------------------------

    private val srtTimeRegex = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )

    private fun parseSrt(content: String): List<Cue> {
        val cuesOut = mutableListOf<Cue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLine = lines.firstOrNull { srtTimeRegex.containsMatchIn(it) } ?: continue
            val match = srtTimeRegex.find(timeLine) ?: continue
            val start = toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val end = toMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
            val timeLineIdx = lines.indexOf(timeLine)
            val textLines = lines.drop(timeLineIdx + 1)
            if (textLines.isEmpty()) continue
            val text = cleanText(textLines.joinToString("\n"))
            if (text.isNotBlank()) cuesOut.add(Cue(start, end, text))
        }
        return cuesOut.sortedBy { it.startMs }
    }

    private fun parseVtt(content: String): List<Cue> {
        val cuesOut = mutableListOf<Cue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLine = lines.firstOrNull { it.contains("-->") } ?: continue
            val parts = timeLine.split("-->")
            if (parts.size != 2) continue
            val start = parseVttTime(parts[0].trim()) ?: continue
            val end = parseVttTime(parts[1].trim().substringBefore(" ")) ?: continue
            val timeLineIdx = lines.indexOf(timeLine)
            val textLines = lines.drop(timeLineIdx + 1)
            if (textLines.isEmpty()) continue
            val text = cleanText(textLines.joinToString("\n"))
            if (text.isNotBlank()) cuesOut.add(Cue(start, end, text))
        }
        return cuesOut.sortedBy { it.startMs }
    }

    private fun parseVttTime(raw: String): Long? {
        // Supports HH:MM:SS.mmm aur MM:SS.mmm dono format
        val parts = raw.split(":")
        return try {
            when (parts.size) {
                3 -> toMs(parts[0], parts[1], parts[2].substringBefore('.'), parts[2].substringAfter('.', "000"))
                2 -> toMs("0", parts[0], parts[1].substringBefore('.'), parts[1].substringAfter('.', "000"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        return h.toLong() * 3600000L + m.toLong() * 60000L + s.toLong() * 1000L + ms.padEnd(3, '0').take(3).toLong()
    }

    private fun cleanText(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")       // <i>, <b>, <font ...> tags
            .replace(Regex("\\{\\\\an\\d}"), "")  // {\an8} jaisi ASS position tags jo kabhi SRT mein leak ho jaati hain
            .trim()
    }
}
