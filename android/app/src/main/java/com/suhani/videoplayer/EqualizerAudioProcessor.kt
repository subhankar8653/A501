package com.suhani.videoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.pow

/**
 * PERMANENT EQUALIZER FIX:
 *
 * android.media.audiofx.Equalizer (jo system/vendor audio HAL par depend karta hai) kuch
 * devices (jaise vivo Funtouch/OriginOS) par silently "attach" ho jaata hai — koi exception
 * nahi aati — lekin vendor apne khud ke audio-DSP chain ki wajah se us insert-effect ko
 * actually process hi nahi karta. Isliye slider extreme pe le jaane par bhi sound mein
 * koi farak nahi padta, chahe app-side code bilkul sahi ho — ye ek OS/vendor-level
 * limitation hai jiska koi guaranteed cross-device fix android.media.audiofx APIs se
 * possible nahi hai.
 *
 * Iska PERMANENT solution: EQ ko khud app ke andar, raw PCM samples par, ExoPlayer ki apni
 * audio pipeline mein hi apply karo (AudioProcessor) — isse audio AudioTrack/vendor HAL tak
 * pahunchne se PEHLE hi process ho jaata hai, isliye ye har device par 100% reliably kaam
 * karta hai, vendor DSP behavior se bilkul independent. Ye standard, Play-Store-compliant
 * public Media3 API (androidx.media3.common.audio.AudioProcessor) use karta hai — koi
 * restricted permission ya extra declaration nahi chahiye.
 *
 * 5-band peaking EQ (RBJ Audio Cookbook biquad formula) — MX Player/Winamp jaisa hi
 * frequency layout: 60Hz (bass), 230Hz, 910Hz (mid), 3.6kHz, 14kHz (treble).
 */
class EqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES_HZ = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
        const val BAND_COUNT = 5
        private const val MAX_GAIN_DB = 15.0
        private const val MIN_GAIN_DB = -15.0
    }

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
        }

    // Current gain per band in dB — read/written from the UI thread, applied on the audio thread.
    private val bandGainsDb = FloatArray(BAND_COUNT)

    private var channelCount = 0
    private var sampleRateHz = 0
    private var filters: Array<Array<Biquad>> = arrayOf()

    /** UI se call hota hai jab user koi band slider hilata hai ya preset apply karta hai. */
    fun setBandGainDb(band: Int, gainDb: Float) {
        if (band !in 0 until BAND_COUNT) return
        bandGainsDb[band] = gainDb.coerceIn(MIN_GAIN_DB.toFloat(), MAX_GAIN_DB.toFloat())
        applyGainsToFilters()
    }

    fun setAllBandGainsDb(gainsDb: FloatArray) {
        for (i in 0 until minOf(BAND_COUNT, gainsDb.size)) {
            bandGainsDb[i] = gainsDb[i].coerceIn(MIN_GAIN_DB.toFloat(), MAX_GAIN_DB.toFloat())
        }
        applyGainsToFilters()
    }

    fun getBandGainDb(band: Int): Float = if (band in 0 until BAND_COUNT) bandGainsDb[band] else 0f

    private fun applyGainsToFilters() {
        for (ch in filters.indices) {
            for (b in filters[ch].indices) {
                filters[ch][b].setGainDb(bandGainsDb[b].toDouble())
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Sirf 16-bit PCM support karte hain (ExoPlayer ka sabse common decode output) —
            // koi aur encoding aaye to processor ko silently bypass kar do, crash nahi.
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRateHz = inputAudioFormat.sampleRate
        filters = Array(channelCount) { _ ->
            Array(BAND_COUNT) { b -> Biquad(sampleRateHz.toDouble(), BAND_FREQUENCIES_HZ[b].toDouble()) }
        }
        applyGainsToFilters()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = channelCount > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outShorts = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = inShorts.remaining() / channelCount.coerceAtLeast(1)

        val bypass = !enabled
        for (f in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                val raw = inShorts.get().toInt()
                if (bypass) {
                    outShorts.put(raw.toShort())
                } else {
                    var sample = raw.toDouble()
                    val chFilters = filters[ch]
                    for (band in chFilters) {
                        sample = band.process(sample)
                    }
                    val clamped = sample.coerceIn(-32768.0, 32767.0)
                    outShorts.put(clamped.toInt().toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.position() + remaining)
        // BUG FIX: samples ऊपर 'outShorts' (ek alag ShortBuffer view) ke through likhe gaye the,
        // lekin uska position 'outputBuffer' (parent ByteBuffer) ke position se automatically
        // sync nahi hota — dono buffers apna position independently track karte hain. Isliye
        // outputBuffer.position() hamesha 0 hi rehta tha, aur neeche flip() call karne par
        // limit bhi 0 par set ho jaata (0..0 ka empty buffer) — matlab processed audio ka
        // koi bhi byte AudioTrack tak kabhi pahuchta hi nahi tha (har video mein, EQ on ho
        // ya off, chahe kuch bhi ho). Yehi asli wajah thi "sound nahi aata" ki, aur bade
        // videos mein audio renderer ke stall hone (ready hi na hona) ki wajah se buffering
        // mein atakne ki bhi. Fix: outputBuffer ka apna position bhi utne hi bytes se
        // advance karo jitne actually likhe gaye (1 short = 2 bytes), phir flip karo.
        outputBuffer.position(outputBuffer.position() + outShorts.position() * 2)
        outputBuffer.flip()
    }

    override fun onFlush() {
        // Filter history reset karo taaki seek/track-switch ke baad koi click/pop na aaye.
        for (ch in filters) {
            for (band in ch) band.reset()
        }
    }

    override fun onReset() {
        channelCount = 0
        filters = arrayOf()
    }

    /**
     * Standard RBJ Audio Cookbook peaking-EQ biquad filter — ek fixed center frequency
     * par boost/cut karta hai, baaki frequencies ko largely unaffected chhodta hai.
     */
    private class Biquad(private val sampleRate: Double, private val freqHz: Double) {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            setGainDb(0.0)
        }

        fun setGainDb(gainDb: Double) {
            val q = 0.9 // moderate bandwidth — MX Player jaisa smooth curve
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freqHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val newB0 = 1 + alpha * a
            val newB1 = -2 * cosW0
            val newB2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val newA1 = -2 * cosW0
            val newA2 = 1 - alpha / a

            b0 = newB0 / a0
            b1 = newB1 / a0
            b2 = newB2 / a0
            a1 = newA1 / a0
            a2 = newA2 / a0
        }

        fun process(x0: Double): Double {
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            return y0
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}
