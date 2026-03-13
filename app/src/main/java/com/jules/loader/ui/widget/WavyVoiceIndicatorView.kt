package com.jules.loader.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * A Material 3 Expressive-style wavy linear voice indicator.
 *
 * Draws a thick horizontal sine-wave line with rounded caps, inspired by the
 * Material 3 Expressive linear progress indicator. The wave amplitude reacts
 * to microphone input level via [setAmplitude]. When idle, a subtle wave
 * motion is shown. Animation is handled efficiently via [ValueAnimator].
 */
class WavyVoiceIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density: Float get() = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    // Current driven amplitude (set by setAmplitude) — in dp
    private var targetAmplitudeDp = IDLE_AMPLITUDE_DP

    // Phase offset for horizontal wave scroll animation
    private var phase = 0f

    private val phaseAnimator = ValueAnimator.ofFloat(0f, TWO_PI).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            phase = animator.animatedValue as Float
            invalidate()
        }
    }

    init {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        paint.color = typedValue.data
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        phaseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        phaseAnimator.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!phaseAnimator.isRunning) phaseAnimator.start()
        } else {
            phaseAnimator.cancel()
        }
    }

    /**
     * Update the wave amplitude in response to microphone input.
     *
     * @param normalizedLevel Value in [0.0, 1.0] where 0 is silence and 1 is peak input.
     */
    fun setAmplitude(normalizedLevel: Float) {
        val clamped = normalizedLevel.coerceIn(0f, 1f)
        targetAmplitudeDp = IDLE_AMPLITUDE_DP + clamped * (MAX_AMPLITUDE_DP - IDLE_AMPLITUDE_DP)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = ((MAX_AMPLITUDE_DP * 2f + STROKE_WIDTH_DP + 8f) * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        paint.strokeWidth = STROKE_WIDTH_DP * density

        // Line spans ~92% of the view width (4% margin on each side)
        val margin = w * 0.04f
        val startX = margin
        val endX = w - margin
        val lineWidth = endX - startX

        val amplitudePx = targetAmplitudeDp * density
        // 3 full wave cycles across the indicator width
        val waveLengthPx = lineWidth / 3f

        path.reset()
        val steps = 120
        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            val x = startX + fraction * lineWidth
            val angle = (TWO_PI * (x - startX) / waveLengthPx) + phase
            val y = centerY + amplitudePx * sin(angle.toDouble()).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }

    companion object {
        private const val STROKE_WIDTH_DP = 6f
        private const val IDLE_AMPLITUDE_DP = 4f
        private const val MAX_AMPLITUDE_DP = 22f
        private val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
