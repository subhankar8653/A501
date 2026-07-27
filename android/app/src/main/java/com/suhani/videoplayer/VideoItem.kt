package com.suhani.videoplayer

data class VideoItem(
    val id: Long,
    val title: String,
    val uriString: String,
    val duration: Long,
    val sizeBytes: Long,
    val folderName: String,
    // Full folder chain relative to internal storage root, e.g. "Movies/Telegram/"
    // Real filesystem browsing (Browse tab) ke liye use hota hai.
    val relativePath: String = "",
    // Resolution, quality badge (SD/720p/1080p/4K) dikhane ke liye
    val width: Int = 0,
    val height: Int = 0,
    // Pehle playback try mein unrecoverable error de chuka hai (CorruptStore se aata hai) —
    // Home grid mein "Corrupt" badge dikhane ke liye.
    val isCorrupt: Boolean = false
)
