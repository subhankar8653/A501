package com.suhani.videoplayer

import androidx.media3.exoplayer.ExoPlayer

/**
 * Chhota (inline, MainActivity) aur fullscreen (PlayerActivity) player pehle
 * do bilkul alag ExoPlayer instances the — fullscreen khulte hi ek naya
 * player banta tha jo scratch se buffer karta tha, isliye tap karte hi
 * buffering dikhti thi chahe position match ho.
 *
 * Ab fullscreen khulte waqt PlayerActivity yahi (MainActivity ka) ExoPlayer
 * instance seedha reuse karta hai — koi naya buffer nahi banta, playback
 * bilkul seamlessly continue hoti hai. Jab tak `uri` match karta hai, dono
 * Activities ek hi player object ko point karte hain.
 */
object SharedPlayerHolder {
    var player: ExoPlayer? = null
    var uri: String = ""

    fun clear() {
        player = null
        uri = ""
    }
}
