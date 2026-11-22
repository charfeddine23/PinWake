package com.example.ui.alarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.R

class RadarPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_rose_tint)
    }

    private val ringColorRose = ContextCompat.getColor(context, R.color.accent_rose)
    private val ringColorCyan = ContextCompat.getColor(context, R.color.accent_cyan)

    private var pulseProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseProgress = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (minOf(width, height) / 2f) * 0.92f
        if (maxRadius <= 0f) return

        canvas.drawCircle(cx, cy, maxRadius * 0.28f, coreGlowPaint)

        val ringCount = 3
        for (i in 0 until ringCount) {
            val phase = (pulseProgress + i.toFloat() / ringCount) % 1f
            val radius = maxRadius * (0.24f + 0.76f * phase)
            val alpha = ((1f - phase) * 210f).toInt().coerceIn(0, 255)
            ringPaint.color = if (i % 2 == 0) ringColorRose else ringColorCyan
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }
}
