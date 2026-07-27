package com.suhani.videoplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * BUG FIX (v2 - "bar hi nahi dikhta"): Purana implementation android:rotation
 * ki jagah canvas.rotate()/canvas.translate() trick se super.onDraw() (SeekBar
 * ka apna internal HORIZONTAL drawing) ko ghumata tha. Math theek thi, lekin
 * Material/AppCompat SeekBar internally apne progressDrawable/thumb ke bounds
 * khud apne padding/min-height/min-width attrs se calculate karta hai — jab
 * hum width/height measure-time par swap karte hain, kai devices/theme
 * combinations par wo internal bounds 0 ya galat aa jaate hain, aur nateeja:
 * track + thumb dono ka pura box khaali (invisible) dikhta tha, sirf upar-neeche
 * ke "0 dB"/"60 Hz" labels dikhte the.
 *
 * Fix: ab hum super.onDraw() par bilkul depend nahi karte. Track line, gold
 * progress-fill aur thumb — teeno khud Canvas + Paint se, seedhe view ke apne
 * (already-vertical) width/height coordinate space mein draw karte hain. Isse
 * rendering kisi rotation-math ya Drawable-bounds quirk par depend nahi karti,
 * hamesha guaranteed visible rehti hai, har device/theme par same dikhti hai.
 */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBar(context, attrs) {

    private var realListener: OnSeekBarChangeListener? = null

    override fun setOnSeekBarChangeListener(l: OnSeekBarChangeListener?) {
        realListener = l
        super.setOnSeekBarChangeListener(l)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFD700")
        style = Paint.Style.FILL
    }
    private val thumbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
    }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Koi swap/trick nahi — view jo bhi apni asli (vertical) width/height
        // XML se maang rahi hai, wahi seedhe accept karo.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // Deliberately super.onDraw() call NAHI karte — sab kuch khud draw karte hain.
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        thumbStrokePaint.strokeWidth = dp(1f)
        val trackWidth = dp(4f).coerceAtMost(w)
        val cx = w / 2f
        val thumbRadius = dp(5.5f)
        val glowRadius = dp(9f)
        val inset = glowRadius.coerceAtMost(h / 2f - 1f).coerceAtLeast(thumbRadius)

        val trackTop = inset
        val trackBottom = (h - inset).coerceAtLeast(trackTop + 1f)
        val trackHeight = trackBottom - trackTop

        val maxVal = max.coerceAtLeast(1)
        val fraction = (progress.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
        val thumbY = trackBottom - fraction * trackHeight

        // Poori height ka background track.
        val trackRect = RectF(cx - trackWidth / 2f, trackTop, cx + trackWidth / 2f, trackBottom)
        canvas.drawRoundRect(trackRect, trackWidth / 2f, trackWidth / 2f, trackPaint)

        // Neeche se thumb tak gold gradient fill (jitna zyada dB, utna zyada fill).
        if (thumbY < trackBottom) {
            progressPaint.shader = LinearGradient(
                0f, trackBottom, 0f, thumbY,
                Color.parseColor("#FFB300"), Color.parseColor("#FFD700"),
                Shader.TileMode.CLAMP
            )
            val fillRect = RectF(cx - trackWidth / 2f, thumbY, cx + trackWidth / 2f, trackBottom)
            canvas.drawRoundRect(fillRect, trackWidth / 2f, trackWidth / 2f, progressPaint)
        }

        // Thumb: soft glow ring + solid gold core + white outline (MX Player jaisa premium orb).
        canvas.drawCircle(cx, thumbY, glowRadius, thumbGlowPaint)
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbCorePaint)
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbStrokePaint)
    }

    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        invalidate()
    }

    override fun setMax(max: Int) {
        super.setMax(max)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val h = height
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                realListener?.onStartTrackingTouch(this)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (h > 0) {
                    val fraction = 1f - (event.y.coerceIn(0f, h.toFloat()) / h.toFloat())
                    val newProgress = (fraction * max).toInt().coerceIn(0, max)
                    progress = newProgress
                    realListener?.onProgressChanged(this, newProgress, true)
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    realListener?.onStopTrackingTouch(this)
                    performClick()
                }
                return true
            }
            else -> return false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
