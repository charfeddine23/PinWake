package com.example.ui.alarm

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.example.R

class SlideToDismissView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onDismissListener: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackRect = RectF()
    private val activeRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.glass_pill_fill)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
        color = ContextCompat.getColor(context, R.color.glass_stroke_bright)
    }

    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val chevronDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_chevron_right)?.mutate()

    private val cyanColor = ContextCompat.getColor(context, R.color.accent_cyan)
    private val emeraldColor = ContextCompat.getColor(context, R.color.accent_emerald)
    private var labelText: String = context.getString(R.string.alarm_slide_to_dismiss)

    private var thumbOffsetPx = 0f
    private var maxOffsetPx = 1f
    private var isDragging = false
    private var dragStartX = 0f
    private var initialOffset = 0f
    private var triggered = false

    init {
        isClickable = true
        contentDescription = labelText
    }

    fun setLabel(text: String) {
        labelText = text
        contentDescription = text
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (64f * density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 2f * density
        trackRect.set(inset, inset, w - inset, h - inset)
        val thumbDiameter = h - 12f * density
        maxOffsetPx = (w - 12f * density - thumbDiameter).coerceAtLeast(1f)

        activeTrackPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.accent_cyan_tint),
                ContextCompat.getColor(context, R.color.accent_emerald_tint)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        thumbPaint.shader = LinearGradient(
            0f, 0f, thumbDiameter, thumbDiameter,
            cyanColor,
            emeraldColor,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cornerRadius = height / 2f
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        val padding = 6f * density
        val thumbRadius = (height - padding * 2f) / 2f
        val thumbCenterX = padding + thumbRadius + thumbOffsetPx
        val thumbCenterY = height / 2f

        val progress = (thumbOffsetPx / maxOffsetPx).coerceIn(0f, 1f)

        if (progress > 0.02f) {
            activeRect.set(
                trackRect.left,
                trackRect.top,
                (thumbCenterX + thumbRadius + 4f * density).coerceAtMost(trackRect.right),
                trackRect.bottom
            )
            canvas.drawRoundRect(activeRect, cornerRadius, cornerRadius, activeTrackPaint)
        }

        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, borderPaint)

        labelPaint.alpha = ((1f - progress * 1.15f).coerceIn(0.15f, 1f) * 255).toInt()
        val textY = thumbCenterY - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(labelText, width / 2f + thumbRadius * 0.35f, textY, labelPaint)

        canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, thumbPaint)

        chevronDrawable?.let { icon ->
            val iconHalf = (14f * density).toInt()
            icon.setBounds(
                (thumbCenterX - iconHalf).toInt(),
                (thumbCenterY - iconHalf).toInt(),
                (thumbCenterX + iconHalf).toInt(),
                (thumbCenterY + iconHalf).toInt()
            )
            icon.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (triggered) return true
        val padding = 6f * density
        val thumbDiameter = height - padding * 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val thumbRight = padding + thumbDiameter + thumbOffsetPx + 24f * density
                if (event.x <= thumbRight) {
                    isDragging = true
                    dragStartX = event.x
                    initialOffset = thumbOffsetPx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val delta = event.x - dragStartX
                thumbOffsetPx = (initialOffset + delta).coerceIn(0f, maxOffsetPx)
                invalidate()
                if (thumbOffsetPx >= maxOffsetPx * 0.85f && !triggered) {
                    triggered = true
                    isDragging = false
                    thumbOffsetPx = maxOffsetPx
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onDismissListener?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (thumbOffsetPx >= maxOffsetPx * 0.85f) {
                    triggered = true
                    thumbOffsetPx = maxOffsetPx
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onDismissListener?.invoke()
                } else {
                    animateBackToStart()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateBackToStart() {
        ValueAnimator.ofFloat(thumbOffsetPx, 0f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                thumbOffsetPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
