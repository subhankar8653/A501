package com.suhani.videoplayer

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// Yeh humara APNA renderers factory hai (Apache-2.0 wale androidx/media source se khud build
// kiya FfmpegAudioRenderer use karta hai) — NextRenderersFactory (GPL-3.0) ka safe replacement.
// FFmpeg khud LGPL hai jab tak GPL-only codecs (jaise x264) enable na ho; humne build workflow
// mein sirf LGPL decoders (ac3/eac3/dca/truehd/mlp/alac/flac/vorbis/opus/mp3/aac) enable kiye
// hain, GPL flag kabhi nahi diya — isliye closed-source/monetized app mein legally safe hai.
@UnstableApi
class FfmpegRenderersFactory(
    context: Context,
    // PERMANENT EQUALIZER FIX: system android.media.audiofx.Equalizer kuch vendor audio HAL
    // (jaise vivo) par silently no-op ho jaata hai — koi crash nahi, lekin sound bhi nahi
    // badalta. Isliye EQ ko yahan, ExoPlayer ki apni audio pipeline mein (raw PCM par),
    // process karte hain — ye vendor DSP se pehle hi lagta hai, isliye har device par
    // guaranteed kaam karta hai. Dekho EqualizerAudioProcessor.kt.
    private val equalizerAudioProcessor: EqualizerAudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // Pehle stock (hardware/MediaCodec-based) audio renderers add karo, jaisa
        // DefaultRenderersFactory normally karta hai.
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return
        }

        // EXTENSION_RENDERER_MODE_PREFER → FfmpegAudioRenderer ko sabse pehle try karo
        // (index 0). EXTENSION_RENDERER_MODE_ON → sirf fallback ke taur par, list ke aakhir mein.
        val insertIndex = if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) 0 else out.size
        out.add(insertIndex, FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }

    // CRASH FIX: jab bhi extensionRendererMode OFF nahi hota (yeh app hamesha ON rakhta hai),
    // DefaultRenderersFactory apne aap reflection se "ExperimentalFfmpegVideoRenderer" class ko
    // bhi load/instantiate kar leta hai — kyunki woh class media3-ffmpeg-decoder.aar ke andar
    // FfmpegAudioRenderer ke saath hi bundled hai. Humare FFmpeg build (build-ffmpeg-decoder.yml)
    // mein sirf AUDIO codecs enable kiye gaye hain (ac3/eac3/dca/truehd/mlp/alac/flac/vorbis/
    // opus/mp3/aac) — koi video codec build hi nahi hua. Phir bhi ExoPlayer supportsFormat()
    // check ke liye us video renderer ko call karta hai, jo native side par kuch registered na
    // hone ki wajah se hard crash (SIGSEGV — bina Java exception ke, seedha app close) de raha
    // tha, chahe file audio ho ya video. Fix: video ke liye FFmpeg extension explicitly OFF
    // force karo, sirf stock/hardware (MediaCodec) video renderers use hon. Audio wala upar
    // waisa hi rehta hai (extensionRendererMode jo bhi pass hua ho).
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    // PERMANENT EQUALIZER FIX (part 2): DefaultRenderersFactory ka buildAudioSink() override
    // karke apna EqualizerAudioProcessor DefaultAudioSink ki processor chain mein daal do.
    // Ye AudioSink hi wahi cheez hai jo audio ko hardware (MediaCodec) aur FFmpeg — dono
    // audio renderers ko ABOVE waale buildAudioRenderers() mein di jaati hai, isliye EQ
    // dono decode paths (stock hardware decoder + apna FFmpeg decoder) par equally kaam
    // karega, chahe file kisi bhi codec ki ho.
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(equalizerAudioProcessor))
            .build()
    }
}
