package com.example.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * Multi-layered Fade-to-Blur Liquid Glass canopy for the top screen edge.
 * Combines a 6-stop Gaussian atmospheric fade, subtle frosted cyan/ice Liquid Glass
 * refractions, and hardware RenderEffect blur (Android 12+) so the map melts cleanly
 * into the status bar and top bezel without hard banding or blocking map gestures.
 */
class LiquidGlassTopFadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val baseMistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostedSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftRefractionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightRefractionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = 18f * resources.displayMetrics.density
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    blurPx,
                    blurPx,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val widthF = w.toFloat()
        val heightF = h.toFloat()

        // Smooth Gaussian-curve obsidian glass fade with zero transparent top fringe
        baseMistPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            heightF,
            intArrayOf(
                Color.argb(238, 9, 13, 22),   // #EE090D16 at top bezel
                Color.argb(198, 11, 18, 32),  // #C60B1220
                Color.argb(132, 14, 24, 43),  // #840E182B
                Color.argb(58, 16, 29, 52),   // #3A101D34
                Color.argb(16, 18, 35, 62),   // #1012233E
                Color.argb(0, 9, 13, 22)      // Seamless fade-out before bottom edge
            ),
            floatArrayOf(0f, 0.24f, 0.50f, 0.72f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // Subtle crystalline frosted highlight along top glass curve
        frostedSheenPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            heightF * 0.52f,
            intArrayOf(
                Color.argb(42, 224, 242, 254), // Cool ice glass top highlight
                Color.argb(18, 56, 189, 248),  // Soft cyan refraction
                Color.argb(0, 56, 189, 248)
            ),
            floatArrayOf(0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // Ambient liquid glass specular blobs that blend into the blur
        leftRefractionPaint.shader = RadialGradient(
            widthF * 0.22f,
            heightF * 0.14f,
            (widthF * 0.48f).coerceAtLeast(1f),
            intArrayOf(
                Color.argb(46, 56, 189, 248),
                Color.argb(14, 56, 189, 248),
                Color.argb(0, 56, 189, 248)
            ),
            floatArrayOf(0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )

        rightRefractionPaint.shader = RadialGradient(
            widthF * 0.82f,
            heightF * 0.10f,
            (widthF * 0.42f).coerceAtLeast(1f),
            intArrayOf(
                Color.argb(34, 248, 250, 252),
                Color.argb(10, 16, 185, 129),
                Color.argb(0, 16, 185, 129)
            ),
            floatArrayOf(0f, 0.52f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, baseMistPaint)
        canvas.drawRect(0f, 0f, w, h * 0.52f, frostedSheenPaint)
        canvas.drawRect(0f, 0f, w, h * 0.85f, leftRefractionPaint)
        canvas.drawRect(0f, 0f, w, h * 0.85f, rightRefractionPaint)
    }
}
