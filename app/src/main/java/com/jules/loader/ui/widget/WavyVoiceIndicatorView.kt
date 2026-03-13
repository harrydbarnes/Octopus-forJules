package com.jules.loader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Material 3 Expressive-style wavy linear voice indicator.
 *
 * States:
 *  1. **Flat** – initial & final state: a straight horizontal line, no animation.
 *  2. **Idle ripple** – after [startListening]: ~5 smooth cycles at moderate
 *     amplitude gently scrolling left, matching the reference visual.
 *  3. **Active** – on audio ([setAmplitude]): fewer, taller waves proportional
 *     to mic volume; both amplitude and cycle-count lerp smoothly.
 *  4. **Settling** – after [stopListening]: wave morphs back to flat before
 *     [onSettledToFlat] fires, allowing the host to dismiss the sheet.
 *
 * Amplitude & cycle-count use fast-attack / slow-decay exponential smoothing
 * driven by [Choreographer.FrameCallback] for frame-rate-independent rendering.
 */
class WavyVoiceIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Callback fired once the wave has settled to flat after [stopListening]. */
    var onSettledToFlat: (() -> Unit)? = null

    private val density: Float by lazy { resources.displayMetrics.density }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    // --- Animated properties ---
    private var currentAmplitudeDp = 0f          // starts flat
    private var targetAmplitudeDp = 0f
    private var currentCycles = IDLE_CYCLES       // visual cycle count
    private var targetCycles = IDLE_CYCLES
    private var phase = 0f                        // horizontal scroll (radians)

    // --- State machine ---
    private var listeningActive = false
    private var settlingToFlat = false

    // --- Choreographer ---
    private var choreographerRunning = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameTimeNanos == 0L) FRAME_TIME_MS
                       else ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameTimeNanos = frameTimeNanos
            val dt = dtMs / 1000f // seconds

            // --- Amplitude smoothing ---
            val ampDiff = targetAmplitudeDp - currentAmplitudeDp
            val ampBase = if (ampDiff > 0f) LERP_BASE_ATTACK else LERP_BASE_DECAY
            currentAmplitudeDp += ampDiff * (1f - ampBase.pow(dt * TARGET_FPS))
            if (abs(ampDiff) < SNAP_THRESHOLD_DP) currentAmplitudeDp = targetAmplitudeDp

            // --- Cycle-count smoothing ---
            val cyclesDiff = targetCycles - currentCycles
            val cyclesBase = if (cyclesDiff < 0f) LERP_BASE_ATTACK else LERP_BASE_DECAY
            currentCycles += cyclesDiff * (1f - cyclesBase.pow(dt * TARGET_FPS))
            if (abs(cyclesDiff) < 0.02f) currentCycles = targetCycles

            // Phase scrolls left; speed scales with amplitude
            val morphFraction = ((currentAmplitudeDp - FLAT_AMPLITUDE_DP)
                .coerceAtLeast(0f) / (ACTIVE_AMPLITUDE_DP - FLAT_AMPLITUDE_DP)).coerceAtMost(1f)
            val phaseSpeed = IDLE_PHASE_SPEED + morphFraction * (ACTIVE_PHASE_SPEED - IDLE_PHASE_SPEED)
            phase = (phase + phaseSpeed * dt) % TWO_PI

            invalidate()

            // Check if settling to flat is complete
            if (settlingToFlat && currentAmplitudeDp <= SNAP_THRESHOLD_DP) {
                currentAmplitudeDp = 0f
                settlingToFlat = false
                choreographerRunning = false
                lastFrameTimeNanos = 0L
                invalidate()
                onSettledToFlat?.invoke()
                return // don't re-post
            }

            // Keep looping while there's something to animate
            val hasMotion = listeningActive || settlingToFlat ||
                    currentAmplitudeDp > SNAP_THRESHOLD_DP
            if (hasMotion) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                choreographerRunning = false
                lastFrameTimeNanos = 0L
            }
        }
    }

    init {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        paint.color = typedValue.data
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        choreographerRunning = false
        lastFrameTimeNanos = 0L
    }

    /**
     * Call when speech recognition begins.
     * Transitions from flat → idle ripple and starts the render loop.
     */
    fun startListening() {
        listeningActive = true
        settlingToFlat = false
        targetAmplitudeDp = IDLE_AMPLITUDE_DP
        targetCycles = IDLE_CYCLES
        ensureChoreographerRunning()
    }

    /**
     * Call when speech recognition ends.
     * The wave settles to flat; when complete, [onSettledToFlat] fires.
     */
    fun stopListening() {
        listeningActive = false
        settlingToFlat = true
        targetAmplitudeDp = FLAT_AMPLITUDE_DP
        targetCycles = IDLE_CYCLES
    }

    /**
     * Drive the wave shape from normalised microphone input.
     *
     * @param normalizedLevel [0.0, 1.0]: 0 = silence, 1 = peak input.
     */
    fun setAmplitude(normalizedLevel: Float) {
        if (!listeningActive) return
        val morph = normalizedLevel.coerceIn(0f, 1f)
        targetAmplitudeDp = IDLE_AMPLITUDE_DP + morph * (ACTIVE_AMPLITUDE_DP - IDLE_AMPLITUDE_DP)
        targetCycles = IDLE_CYCLES + morph * (ACTIVE_CYCLES - IDLE_CYCLES)
    }

    private fun ensureChoreographerRunning() {
        if (!choreographerRunning) {
            choreographerRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = ((ACTIVE_AMPLITUDE_DP * 2f + STROKE_WIDTH_DP + 8f) * density).toInt()
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

        val margin = w * 0.04f
        val startX = margin
        val endX = w - margin
        val lineWidth = endX - startX

        val amplitudePx = currentAmplitudeDp * density

        // Flat line shortcut
        if (amplitudePx < 0.5f) {
            canvas.drawLine(startX, centerY, endX, centerY, paint)
            return
        }

        val waveLengthPx = lineWidth / currentCycles

        path.reset()
        val steps = 160
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
        private const val FLAT_AMPLITUDE_DP = 0f
        // Idle: ~5 smooth cycles at moderate amplitude, gently scrolling left
        private const val IDLE_AMPLITUDE_DP = 5f
        private const val IDLE_CYCLES = 5f
        // Active: fewer, much taller waves
        private const val ACTIVE_AMPLITUDE_DP = 22f
        private const val ACTIVE_CYCLES = 2.5f
        // Phase speed (radians/second)
        private const val IDLE_PHASE_SPEED = 2f
        private const val ACTIVE_PHASE_SPEED = 6f
        // Exponential smoothing
        private const val LERP_RATE_ATTACK = 0.20f
        private const val LERP_RATE_DECAY = 0.06f
        private const val LERP_BASE_ATTACK = 1f - LERP_RATE_ATTACK
        private const val LERP_BASE_DECAY = 1f - LERP_RATE_DECAY
        private const val TARGET_FPS = 60f
        private const val SNAP_THRESHOLD_DP = 0.05f
        private const val FRAME_TIME_MS = 16f
        private val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
