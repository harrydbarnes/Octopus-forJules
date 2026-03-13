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
 *  - Initially renders a **flat** horizontal line with no animation.
 *  - Call [startListening] when speech recognition begins; the Choreographer-driven
 *    render loop starts and the wave smoothly grows from the audio input level.
 *  - [setAmplitude] drives the target wave height from mic input ([0,1]).
 *    The wave phase only scrolls when active audio is detected; it freezes
 *    otherwise, leaving a subtle static wave.
 *  - Amplitude uses fast-attack / slow-decay exponential smoothing for natural,
 *    smooth transitions between audio levels.
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

    // Smoothly interpolated amplitude currently being rendered (dp)
    private var currentAmplitudeDp = 0f

    // Target amplitude driven by mic input (dp); 0 = flat, IDLE = subtle wave
    private var targetAmplitudeDp = 0f

    // Phase offset for the wave scroll (radians)
    private var phase = 0f

    // Whether speech recognition is actively in progress
    private var listeningActive = false

    // Choreographer loop state
    private var choreographerRunning = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameTimeNanos == 0L) {
                FRAME_TIME_MS
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000f).coerceIn(1f, 50f)
            }
            lastFrameTimeNanos = frameTimeNanos
            val dt = dtMs / 1000f // seconds

            // Exponential smoothing: fast attack, slow decay for a natural feel
            val diff = targetAmplitudeDp - currentAmplitudeDp
            val base = if (diff > 0f) LERP_BASE_ATTACK else LERP_BASE_DECAY
            // Time-normalised blend factor so speed is frame-rate independent
            val alpha = 1f - base.pow(dt * TARGET_FPS)
            currentAmplitudeDp += diff * alpha

            // Snap to target when close enough to avoid infinite approach
            if (abs(diff) < SNAP_THRESHOLD_DP) currentAmplitudeDp = targetAmplitudeDp

            // Phase scrolls only when active audio is present; freezes when idle
            if (targetAmplitudeDp > IDLE_AMPLITUDE_DP + ACTIVE_THRESHOLD_DP) {
                phase = (phase + PHASE_SPEED_RADS_PER_SEC * dt) % TWO_PI
            }

            invalidate()

            // Keep looping while listening or while amplitude hasn't fully settled
            val settled = abs(currentAmplitudeDp - targetAmplitudeDp) < SNAP_THRESHOLD_DP
            if (listeningActive || !settled) {
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
        stopChoreographer()
    }

    /**
     * Call when speech recognition begins. Starts the animation loop and
     * transitions the line from flat to a subtle idle wave.
     */
    fun startListening() {
        listeningActive = true
        targetAmplitudeDp = IDLE_AMPLITUDE_DP
        if (!choreographerRunning) postChoreographerFrame()
    }

    /**
     * Call when speech recognition ends. Lets the wave settle to the idle
     * position before the Choreographer loop stops itself.
     */
    fun stopListening() {
        listeningActive = false
        targetAmplitudeDp = IDLE_AMPLITUDE_DP
    }

    /**
     * Drive the wave amplitude from normalised microphone input.
     *
     * @param normalizedLevel Value in [0.0, 1.0]: 0 = silence, 1 = peak input.
     *                        Ignored before [startListening] is called.
     */
    fun setAmplitude(normalizedLevel: Float) {
        if (!listeningActive) return
        val clamped = normalizedLevel.coerceIn(0f, 1f)
        targetAmplitudeDp = IDLE_AMPLITUDE_DP + clamped * (MAX_AMPLITUDE_DP - IDLE_AMPLITUDE_DP)
    }

    private fun postChoreographerFrame() {
        choreographerRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopChoreographer() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        choreographerRunning = false
        lastFrameTimeNanos = 0L
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

        // Line spans ~92% of view width with a small margin on each side
        val margin = w * 0.04f
        val startX = margin
        val endX = w - margin
        val lineWidth = endX - startX

        val amplitudePx = currentAmplitudeDp * density
        // 3 full sine cycles across the indicator width
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
        // Subtle amplitude for the resting wave once listening has started
        private const val IDLE_AMPLITUDE_DP = 2f
        private const val MAX_AMPLITUDE_DP = 18f
        // Phase scrolling activates when target exceeds idle by this margin (dp)
        private const val ACTIVE_THRESHOLD_DP = 1f
        // Wave phase speed when audio is active (radians per second)
        private const val PHASE_SPEED_RADS_PER_SEC = 4.5f
        // Exponential smoothing rates (per frame at TARGET_FPS)
        private const val LERP_RATE_ATTACK = 0.25f  // fast response to loud sounds
        private const val LERP_RATE_DECAY = 0.08f   // slow natural decay
        // Pre-computed bases for the per-frame blend factor (1 - rate)
        private const val LERP_BASE_ATTACK = 1f - LERP_RATE_ATTACK
        private const val LERP_BASE_DECAY = 1f - LERP_RATE_DECAY
        private const val TARGET_FPS = 60f
        private const val SNAP_THRESHOLD_DP = 0.05f
        private const val FRAME_TIME_MS = 16f
        private val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
