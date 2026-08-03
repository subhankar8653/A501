package com.suhani.videoplayer

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Ambient Glow Mode (signature feature) — Philips Ambilight/Chromecast/YouTube ambient
 * jaisa: video ke current frame ke 4 edges (top/bottom/left/right) ka average color nikaal
 * kar, video ke CHAARO TARAF/PEECHE (letterbox gutter — jahan bhi video screen ko poora
 * nahi bharta, jaise top black bar jahan title-bar sit karta hai) ek soft, blurred glow
 * banaya jaata hai. Scene badalte hi glow naye color mein smoothly (crossfade) transition
 * hota hai, isliye kabhi achanak/jhatke se nahi badalta — hamesha ek premium, "ambient"
 * feel deta hai.
 *
 * Yeh view PlayerView ke NEECHE (activity_player.xml mein usi FrameLayout ke andar,
 * PlayerView se PEHLE) baithta hai — isliye jahan bhi actual (opaque) video pixels hain
 * wahan yeh khud-ba-khud dikh hi nahi sakta (video upar se cover kar leta hai); sirf
 * letterbox/pillarbox gutter mein aur title-bar ke translucent gradient scrim ke peeche
 * se bleed karta hai — bilkul YouTube ke fullscreen ambient mode jaisa. Touch bilkul
 * consume nahi karta (isClickable=false, isFocusable=false).
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Har edge ka apna current (already-displayed) aur target color — ValueAnimator in
    // dono ke beech smoothly interpolate karta hai taaki transition abrupt na lage.
    private var topColor = Color.TRANSPARENT
    private var bottomColor = Color.TRANSPARENT
    private var leftColor = Color.TRANSPARENT
    private var rightColor = Color.TRANSPARENT

    private var glowEnabled = false
    private val argbEvaluator = ArgbEvaluator()
    private var transitionAnimator: ValueAnimator? = null

    // Glow strip ki "moti-ai" (kitni dur tak fade hoga) — screen size ke hisaab se scale
    // hota hai. Top ko thoda extra reach diya hai (topExtentPx) taaki title-bar ka poora
    // zone reliably cover ho jaaye (user report: "jidhar title hai udhar glow nahi ho raha
    // tha") — bar chahe kisi bhi size ka ho, isse zyada dur tak fade to kabhi nuksaan nahi.
    private var glowExtentPx = 0f
    private var topExtentPx = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        // Har frame par shader rebuild hota hai isliye software layer safe/simple hai —
        // is view ka size chhota-sa hi hota hai (sirf edges), CPU cost negligible hai.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setGlowEnabled(enabled: Boolean) {
        glowEnabled = enabled
        visibility = if (enabled) VISIBLE else INVISIBLE
        if (!enabled) {
            transitionAnimator?.cancel()
        }
        invalidate()
    }

    /**
     * Naye sampled edge-colors set karo. Turant jump nahi karta — purane se naye color
     * tak ~700ms mein smoothly animate hota hai (scene-change bhi is wajah se glow mein
     * "swipe"/"bleed" jaisa dikhta hai, jhatka nahi lagta).
     */
    fun updateEdgeColors(top: Int, bottom: Int, left: Int, right: Int) {
        if (!glowEnabled) return
        val fromTop = topColor
        val fromBottom = bottomColor
        val fromLeft = leftColor
        val fromRight = rightColor

        transitionAnimator?.cancel()
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                topColor = argbEvaluator.evaluate(f, fromTop, top) as Int
                bottomColor = argbEvaluator.evaluate(f, fromBottom, bottom) as Int
                leftColor = argbEvaluator.evaluate(f, fromLeft, left) as Int
                rightColor = argbEvaluator.evaluate(f, fromRight, right) as Int
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Glow strip: screen ke chhote side ka ~20%, taaki letterbox gutter + title-bar
        // zone reliably cover ho (pehle 16% tha aur video ke ANDAR draw hota tha — ab
        // video ke BAAHAR/letterbox mein draw hota hai isliye thoda zyada reach chahiye).
        glowExtentPx = (minOf(w, h) * 0.20f).coerceAtLeast(90f)
        // Top ko extra boost — yehi woh zone hai jahan title-bar (back/title/audio-track/
        // subtitle/speed/more row) baithta hai, poora us par glow chahiye.
        topExtentPx = glowExtentPx * 1.35f
    }

    override fun onDraw(canvas: Canvas) {
        if (!glowEnabled || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val extent = glowExtentPx
        val topExtent = topExtentPx

        // Alpha thoda boost kar dete hain taaki glow saaf dikhe lekin video content ko
        // andar se overpower na kare — sirf ek "rim light" jaisa feel.
        fun withGlowAlpha(color: Int): Int {
            val boosted = (Color.alpha(color).coerceAtLeast(190))
            return Color.argb(boosted, Color.red(color), Color.green(color), Color.blue(color))
        }

        // Top edge — title-bar zone; is edge ko extra extent milta hai (upar dekho).
        paint.shader = LinearGradient(
            0f, 0f, 0f, topExtent,
            withGlowAlpha(topColor), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, topExtent, paint)

        // Bottom edge
        paint.shader = LinearGradient(
            0f, h, 0f, h - extent,
            withGlowAlpha(bottomColor), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h - extent, w, h, paint)

        // Left edge
        paint.shader = LinearGradient(
            0f, 0f, extent, 0f,
            withGlowAlpha(leftColor), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, extent, h, paint)

        // Right edge
        paint.shader = LinearGradient(
            w, 0f, w - extent, 0f,
            withGlowAlpha(rightColor), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - extent, 0f, w, h, paint)
    }
}
