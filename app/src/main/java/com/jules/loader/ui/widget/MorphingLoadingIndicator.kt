package com.jules.loader.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos

/**
 * A custom loading indicator inspired by Material 3 Expressive's morphing shapes pattern.
 * Shows 4 rounded rectangles (squircles) that animate in a wave — each shape morphs between
 * a compact square and a taller pill as the wave passes through it.
 */
class MorphingLoadingIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val dotCount = 4
    private val baseSize: Float get() = 10f * resources.displayMetrics.density
    private val dotGap: Float get() = 6f * resources.displayMetrics.density

    private val animators = mutableListOf<ValueAnimator>()
    // phase[i] ∈ [0, 2π], drives the morph cycle for dot i
    private val phases = FloatArray(dotCount) { 0f }

    init {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        paint.color = typedValue.data
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAnimations()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Use isShown to check whether this view and ALL its ancestors are visible.
        // The raw `visibility` parameter only reflects the changed ancestor, not this view itself,
        // so relying on it starts animations even when this view is still GONE.
        if (isShown) startAnimations() else stopAnimations()
    }

    private fun startAnimations() {
        stopAnimations()
        for (i in 0 until dotCount) {
            val anim = ValueAnimator.ofFloat(0f, (2f * Math.PI).toFloat()).apply {
                duration = 1200L
                startDelay = i * 120L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phases[i] = it.animatedValue as Float
                    invalidate()
                }
            }
            animators += anim
            anim.start()
        }
    }

    private fun stopAnimations() {
        animators.forEach { it.cancel() }
        animators.clear()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalW = (dotCount * baseSize + (dotCount - 1) * dotGap + paddingLeft + paddingRight).toInt()
        // Height accommodates the taller morph state (baseSize * 1.8)
        val totalH = (baseSize * 1.8f + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(totalW, widthMeasureSpec),
            resolveSize(totalH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalW = dotCount * baseSize + (dotCount - 1) * dotGap
        val startX = (width - totalW) / 2f
        val centerY = height / 2f

        for (i in 0 until dotCount) {
            val phase = phases[i]
            // cos(phase): 1 at 0, −1 at π, back to 1 at 2π
            val c = cos(phase.toDouble()).toFloat()
            // Width shrinks when tall, grows when flat
            val halfW = baseSize / 2f * (1f - 0.25f * c)
            // Height grows when cos is positive (peak), shrinks otherwise
            val halfH = baseSize / 2f * (1f + 0.4f * c)
            // Corner radius = min dimension so ends are always fully rounded
            val cornerR = halfW.coerceAtMost(halfH)

            val cx = startX + i * (baseSize + dotGap) + baseSize / 2f
            rect.set(cx - halfW, centerY - halfH, cx + halfW, centerY + halfH)
            canvas.drawRoundRect(rect, cornerR, cornerR, paint)
        }
    }
}
