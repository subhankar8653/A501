package com.suhani.videoplayer

/**
 * MainActivity video list ko yahan rakh deta hai, PlayerActivity yahan se
 * poori playlist (same folder ke videos) utha kar ExoPlayer mein daal deta hai.
 * Isse Shuffle, Loop aur Next/Previous (jo Media3 ka built-in playlist feature hai)
 * automatically kaam karne lagte hain.
 */
object PlaybackQueue {
    var items: List<VideoItem> = emptyList()
    var startIndex: Int = 0
}
