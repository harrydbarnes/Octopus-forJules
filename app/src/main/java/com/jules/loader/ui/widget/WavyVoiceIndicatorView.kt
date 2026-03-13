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
 * Behaviour:
 *  - Idle: many fine cycles ([IDLE_CYCLES]) with tiny amplitude, gently scrolling
 *    at [IDLE_PHASE_SPEED]. The Choreographer loop runs whenever the view is attached.
 *  - On audio ([setAmplitude]): smoothly morphs to fewer, taller waves proportional
 *    to mic volume. Both amplitude and cycle-count lerp with fast-attack / slow-decay
 *    exponential smoothing for natural transitions.
 *  - After [stopListening]: wave returns to idle state.
 *  - Phase speed scales with amplitude so active audio feels more energetic.
 */
class WavyVoiceIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density: Float by lazy { resources.displayMetrics.density }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    // Amplitude: how tall the wave crests are (dp)
    private var currentAmplitudeDp = IDLE_AMPLITUDE_DP
    private var targetAmplitudeDp = IDLE_AMPLITUDE_DP

    // Cycles: how many full sine cycles span the view width
    private var currentCycles = IDLE_CYCLES
    private var targetCycles = IDLE_CYCLES

    // Phase offset for horizontal wave scroll (radians)
    private var phase = 0f

    // Whether speech recognition is actively in progress
    private var listeningActive = false

    // Choreographer loop state
    private var choreographerRunning = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameTimeNanos == 0L) FRAME_TIME_MS
                       else ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameTimeNanos = frameTimeNanos
            val dt = dtMs / 1000f // seconds

            // --- Amplitude smoothing (fast attack, slow decay) ---
            val ampDiff = targetAmplitudeDp - currentAmplitudeDp
            val ampBase = if (ampDiff > 0f) LERP_BASE_ATTACK else LERP_BASE_DECAY
            currentAmplitudeDp += ampDiff * (1f - ampBase.pow(dt * TARGET_FPS))
            if (abs(ampDiff) < SNAP_THRESHOLD_DP) currentAmplitudeDp = targetAmplitudeDp

            // --- Cycle-count smoothing (same rates, direction-aware) ---
            val cyclesDiff = targetCycles - currentCycles
            // Decreasing cycles (morphing to active) = attack; increasing (returning idle) = decay
            val cyclesBase = if (cyclesDiff < 0f) LERP_BASE_ATTACK else LERP_BASE_DECAY
            currentCycles += cyclesDiff * (1f - cyclesBase.pow(dt * TARGET_FPS))
            if (abs(cyclesDiff) < 0.02f) currentCycles = targetCycles

            // Phase speed scales from idle to active based on current amplitude
            val morphFraction = ((currentAmplitudeDp - IDLE_AMPLITUDE_DP)
                .coerceAtLeast(0f) / (ACTIVE_AMPLITUDE_DP - IDLE_AMPLITUDE_DP)).coerceAtMost(1f)
            val phaseSpeed = IDLE_PHASE_SPEED + morphFraction * (ACTIVE_PHASE_SPEED - IDLE_PHASE_SPEED)
            phase = (phase + phaseSpeed * dt) % TWO_PI

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
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
        if (!choreographerRunning) {
            choreographerRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        choreographerRunning = false
        lastFrameTimeNanos = 0L
    }

    /** Call when speech recognition begins. */
    fun startListening() {
        listeningActive = true
    }

    /** Call when speech recognition ends; wave returns to idle state. */
    fun stopListening() {
        listeningActive = false
        targetAmplitudeDp = IDLE_AMPLITUDE_DP
        targetCycles = IDLE_CYCLES
    }

    /**
     * Drive the wave shape from normalised microphone input.
     *
     * @param normalizedLevel Value in [0.0, 1.0]: 0 = silence, 1 = peak input.
     *                        Ignored before [startListening] is called.
     */
    fun setAmplitude(normalizedLevel: Float) {
        if (!listeningActive) return
        val morph = normalizedLevel.coerceIn(0f, 1f)
        targetAmplitudeDp = IDLE_AMPLITUDE_DP + morph * (ACTIVE_AMPLITUDE_DP - IDLE_AMPLITUDE_DP)
        // More amplitude = fewer, wider cycles (IDLE_CYCLES → ACTIVE_CYCLES)
        targetCycles = IDLE_CYCLES + morph * (ACTIVE_CYCLES - IDLE_CYCLES)
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

        // Line spans ~92% of view width with a small margin on each side
        val margin = w * 0.04f
        val startX = margin
        val endX = w - margin
        val lineWidth = endX - startX

        val amplitudePx = currentAmplitudeDp * density
        val waveLengthPx = lineWidth / currentCycles

        path.reset()
        // More steps for fine idle ripples; 160 is smooth at all cycle counts
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
        // Idle: many fine ripples with tiny height, always gently scrolling
        private const val IDLE_AMPLITUDE_DP = 1.5f
        private const val IDLE_CYCLES = 7f
        // Active: fewer, much taller waves when audio is detected
        private const val ACTIVE_AMPLITUDE_DP = 20f
        private const val ACTIVE_CYCLES = 2.5f
        // Phase scroll speed (radians/second): slow idle, faster during active audio
        private const val IDLE_PHASE_SPEED = 2.5f
        private const val ACTIVE_PHASE_SPEED = 7f
        // Exponential smoothing: fast attack (loud sounds), slow decay (settling)
        private const val LERP_RATE_ATTACK = 0.25f
        private const val LERP_RATE_DECAY = 0.08f
        private const val LERP_BASE_ATTACK = 1f - LERP_RATE_ATTACK
        private const val LERP_BASE_DECAY = 1f - LERP_RATE_DECAY
        private const val TARGET_FPS = 60f
        private const val SNAP_THRESHOLD_DP = 0.05f
        private const val FRAME_TIME_MS = 16f
        private val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
