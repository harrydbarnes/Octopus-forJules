package com.jules.loader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.sin
import kotlin.random.Random

/**
 * An underwater Chrome-Dino-style side-scroller starring an octopus.
 *
 * The octopus swims along the ocean floor, jumping over reefs and oceanic
 * obstacles. Score increments by spoken word count (driven externally).
 *
 * Tap / click to jump. The game runs while voice recognition is active.
 */
class OctopusGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public API ──────────────────────────────────────────────────────

    /** Best survival time in seconds. Set externally from preferences on init. */
    var highScore: Int = 0

    /** Called when the game ends (octopus collides with obstacle). */
    var onGameOver: (() -> Unit)? = null

    private var gameRunning = false
    private var gameOver = false
    private var initialView = true

    /** True when the current game has ended and is waiting for a restart. */
    val isGameOver: Boolean get() = gameOver

    val isInitialView: Boolean get() = initialView

    // ── Timer ────────────────────────────────────────────────────────────

    private var elapsedTime = 0f  // seconds elapsed since game started

    // ── Dimensions ──────────────────────────────────────────────────────

    private val density = resources.displayMetrics.density
    private val dp = density  // shorthand

    // ── Octopus state ───────────────────────────────────────────────────

    private var octopusX = 0f
    private var octopusY = 0f
    private var octopusVelocityY = 0f
    private val octopusSize = 36f * dp
    private var isJumping = false
    private var tentaclePhase = 0f

    // ── Floor & scenery ─────────────────────────────────────────────────

    private var floorY = 0f
    private var scrollOffset = 0f
    private val scrollSpeed = 180f * dp  // px per second base speed
    private val obstacles = mutableListOf<Obstacle>()
    private val bubbles = mutableListOf<Bubble>()
    private var nextObstacleDistance = 0f
    private val seaweedPositions = mutableListOf<Float>()

    // ── Physics ─────────────────────────────────────────────────────────

    private val gravity = 1800f * dp
    private val jumpVelocity = -650f * dp

    // ── Paints ──────────────────────────────────────────────────────────

    private val primaryColor: Int
    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val octopusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tentaclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seaweedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tapToPlayText: String = context.getString(com.jules.loader.R.string.game_tap_to_play)
    private val gameOverText: String = context.getString(com.jules.loader.R.string.game_over)
    private val tapToRestartText: String = context.getString(com.jules.loader.R.string.game_tap_to_restart)
    private val gameTimeFormat: String = context.getString(com.jules.loader.R.string.game_time_elapsed)
    private val gameBestFormat: String = context.getString(com.jules.loader.R.string.game_best_time)

    // ── Reusable drawing objects (avoid per-frame allocations) ──────────
    private val clipPath = Path()
    private val clipRectF = RectF()
    private val sandPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 139, 119, 80)
        style = Paint.Style.FILL
    }
    private val octRect = RectF()
    private val obsRect = RectF()

    // Octopus draw objects
    private val tentaclePath = Path()
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Game-over hint text
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Obstacle draw objects
    private val obstacleRectF = RectF()
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockPath = Path()
    private val rockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swObstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swObstaclePath = Path()

    // Background seaweed draw object
    private val seaweedPath = Path()

    // ── Choreographer ───────────────────────────────────────────────────

    private var choreographerRunning = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameTimeNanos == 0L) 16f
                       else ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameTimeNanos = frameTimeNanos
            val dt = dtMs / 1000f

            if (gameRunning && !gameOver) {
                updateGame(dt)
                invalidate()
                if (choreographerRunning) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            } else {
                choreographerRunning = false
                lastFrameTimeNanos = 0L
            }
        }
    }

    init {
        primaryColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            Color.rgb(98, 0, 238)
        )

        octopusPaint.color = primaryColor
        octopusPaint.style = Paint.Style.FILL

        tentaclePaint.color = primaryColor
        tentaclePaint.style = Paint.Style.STROKE
        tentaclePaint.strokeWidth = 3f * dp
        tentaclePaint.strokeCap = Paint.Cap.ROUND

        obstaclePaint.color = Color.argb(200, 255, 127, 80) // coral colour
        obstaclePaint.style = Paint.Style.FILL

        scorePaint.color = Color.WHITE
        scorePaint.textSize = 16f * dp
        scorePaint.isFakeBoldText = true

        bubblePaint.color = Color.argb(60, 255, 255, 255)
        bubblePaint.style = Paint.Style.STROKE
        bubblePaint.strokeWidth = 1.5f * dp

        seaweedPaint.color = Color.argb(120, 34, 139, 34)
        seaweedPaint.style = Paint.Style.STROKE
        seaweedPaint.strokeWidth = 3f * dp
        seaweedPaint.strokeCap = Paint.Cap.ROUND

        floorPaint.color = Color.argb(180, 194, 178, 128) // sandy
        floorPaint.style = Paint.Style.FILL

        overlayTextPaint.color = Color.WHITE
        overlayTextPaint.textSize = 24f * dp
        overlayTextPaint.textAlign = Paint.Align.CENTER
        overlayTextPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        overlayTextPaint.letterSpacing = 0.15f

        overlayPaint.style = Paint.Style.FILL

        eyePaint.color = Color.WHITE
        eyePaint.style = Paint.Style.FILL

        pupilPaint.color = Color.BLACK
        pupilPaint.style = Paint.Style.FILL

        hintPaint.color = Color.argb(180, 255, 255, 255)
        hintPaint.textSize = 12f * dp
        hintPaint.isFakeBoldText = true
        hintPaint.textAlign = Paint.Align.CENTER

        branchPaint.color = Color.argb(220, 255, 100, 60)
        branchPaint.style = Paint.Style.FILL

        rockPaint.color = Color.argb(200, 100, 100, 100)
        rockPaint.style = Paint.Style.FILL

        swObstaclePaint.color = Color.argb(200, 0, 120, 60)
        swObstaclePaint.style = Paint.Style.STROKE
        swObstaclePaint.strokeCap = Paint.Cap.ROUND
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun startGame() {
        // Defer until the view has been laid out so width/height are valid
        if (width == 0 || height == 0) {
            post { startGame() }
            return
        }
        gameRunning = true
        gameOver = false
        initialView = false
        elapsedTime = 0f
        obstacles.clear()
        bubbles.clear()
        scrollOffset = 0f
        nextObstacleDistance = width * 0.6f
        octopusVelocityY = 0f
        isJumping = false
        tentaclePhase = 0f
        // Reset octopus position to floor on every (re)start
        octopusY = floorY - octopusSize

        if (!choreographerRunning) {
            choreographerRunning = true
            lastFrameTimeNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stopGame(resetToInitial: Boolean = false) {
        gameRunning = false
        if (resetToInitial) {
            initialView = true
            gameOver = false
        }
        if (choreographerRunning) {
            choreographerRunning = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        floorY = h * 0.78f
        octopusX = w * 0.18f
        octopusY = floorY - octopusSize

        // Pre-populate background seaweed
        seaweedPositions.clear()
        var sx = 0f
        while (sx < w * 2) {
            seaweedPositions.add(sx)
            sx += (80f + Random.nextFloat() * 120f) * dp
        }

        // Water gradient
        oceanPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(255, 10, 30, 70),
            Color.argb(255, 15, 60, 110),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        choreographerRunning = false
        lastFrameTimeNanos = 0L
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (initialView || gameOver) {
                startGame()
            } else {
                jump()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    fun jump() {
        // Only jump when the game is actually running (not game-over or initial view)
        if (!isJumping && gameRunning && !gameOver && !initialView) {
            isJumping = true
            octopusVelocityY = jumpVelocity
        }
    }

    // ── Update ──────────────────────────────────────────────────────────

    private fun updateGame(dt: Float) {
        if (initialView) return

        elapsedTime += dt
        val elapsedSec = elapsedTime.toInt()
        if (elapsedSec > highScore) highScore = elapsedSec

        val speed = scrollSpeed * (1f + elapsedTime * 0.02f).coerceAtMost(2.5f)
        scrollOffset += speed * dt
        tentaclePhase += 8f * dt

        // Octopus physics
        if (isJumping) {
            octopusVelocityY += gravity * dt
            octopusY += octopusVelocityY * dt
            if (octopusY >= floorY - octopusSize) {
                octopusY = floorY - octopusSize
                octopusVelocityY = 0f
                isJumping = false
            }
        }

        // Spawn obstacles
        nextObstacleDistance -= speed * dt
        if (nextObstacleDistance <= 0f) {
            val obstacleType = (Random.nextInt(3))
            val obstacleW = (20f + Random.nextFloat() * 16f) * dp
            val obstacleH = (30f + Random.nextFloat() * 30f) * dp
            obstacles.add(Obstacle(
                x = width.toFloat() + 20f * dp,
                width = obstacleW,
                height = obstacleH,
                type = obstacleType
            ))
            nextObstacleDistance = (200f + Random.nextFloat() * 180f) * dp
        }

        // Move obstacles
        val iter = obstacles.iterator()
        while (iter.hasNext()) {
            val obs = iter.next()
            obs.x -= speed * dt
            if (obs.x + obs.width < -20f * dp) {
                iter.remove()
                continue
            }
            // Collision detection
            octRect.set(
                octopusX + 4f * dp,
                octopusY + 4f * dp,
                octopusX + octopusSize - 4f * dp,
                octopusY + octopusSize - 4f * dp
            )
            obsRect.set(
                obs.x,
                floorY - obs.height,
                obs.x + obs.width,
                floorY
            )
            if (RectF.intersects(octRect, obsRect)) {
                gameOver = true
                onGameOver?.invoke()
                return
            }
        }

        // Bubbles
        if (Random.nextFloat() < 0.03f) {
            bubbles.add(Bubble(
                x = Random.nextFloat() * width,
                y = height.toFloat(),
                radius = (3f + Random.nextFloat() * 6f) * dp,
                speed = (40f + Random.nextFloat() * 60f) * dp
            ))
        }
        val biter = bubbles.iterator()
        while (biter.hasNext()) {
            val b = biter.next()
            b.y -= b.speed * dt
            b.x += sin((b.y * 0.02f).toDouble()).toFloat() * dp * 0.5f
            if (b.y + b.radius < 0f) biter.remove()
        }
    }

    // ── Draw ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Clip to rounded corners using reusable Path and RectF
        clipRectF.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(
            clipRectF,
            CORNER_RADIUS_DP * dp,
            CORNER_RADIUS_DP * dp,
            Path.Direction.CW
        )
        canvas.clipPath(clipPath)

        // Ocean background
        canvas.drawRect(0f, 0f, w, h, oceanPaint)

        // Background seaweed
        for (sx in seaweedPositions) {
            val adjustedX = ((sx - scrollOffset * 0.3f) % (w * 2f) + w * 2f) % (w * 2f) - w * 0.5f
            drawSeaweed(canvas, adjustedX, floorY)
        }

        // Sandy floor
        canvas.drawRect(0f, floorY, w, h, floorPaint)
        // Floor sand dots (reuse sandPaint to avoid per-frame allocations)
        var dotX = (-(scrollOffset * 0.5f) % (30f * dp) + 30f * dp) % (30f * dp)
        while (dotX < w) {
            canvas.drawCircle(dotX, floorY + 8f * dp, 2f * dp, sandPaint)
            canvas.drawCircle(dotX + 15f * dp, floorY + 16f * dp, 1.5f * dp, sandPaint)
            dotX += 30f * dp
        }

        // Bubbles
        for (b in bubbles) {
            canvas.drawCircle(b.x, b.y, b.radius, bubblePaint)
        }

        // Obstacles
        for (obs in obstacles) {
            drawObstacle(canvas, obs)
        }

        // Octopus
        drawOctopus(canvas)

        // Initial view overlay
        if (initialView) {
            overlayPaint.color = Color.argb(80, 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
            canvas.drawText(tapToPlayText, w / 2f, h / 2f, overlayTextPaint)
        } else {
            // Timer top-left (seconds only)
            val timerText = formatTime(elapsedTime)
            canvas.drawText(timerText, 16f * dp, 28f * dp, scorePaint)

            // Best time top-right (seconds only)
            val bestText = String.format(gameBestFormat, formatTime(highScore.toFloat()))
            val bestWidth = scorePaint.measureText(bestText)
            canvas.drawText(bestText, w - bestWidth - 16f * dp, 28f * dp, scorePaint)
        }

        // Game over overlay
        if (gameOver) {
            overlayPaint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
            canvas.drawText(gameOverText, w / 2f, h / 2f, overlayTextPaint)
            canvas.drawText(String.format(gameTimeFormat, formatTime(elapsedTime)), w / 2f, h / 2f + 30f * dp, scorePaint.apply {
                textAlign = Paint.Align.CENTER
            })
            canvas.drawText(tapToRestartText, w / 2f, h / 2f + 52f * dp, hintPaint)
            scorePaint.textAlign = Paint.Align.LEFT // reset
        }
    }

    private fun formatTime(seconds: Float): String {
        return "${seconds.toInt()}s"
    }

    private fun drawOctopus(canvas: Canvas) {
        val cx = octopusX + octopusSize / 2f
        val cy = octopusY + octopusSize * 0.35f
        val bodyRx = octopusSize * 0.38f
        val bodyRy = octopusSize * 0.3f

        // Tentacles (4 wavy lines below the body)
        val tentacleStartY = cy + bodyRy * 0.7f
        for (i in 0 until 4) {
            tentaclePath.reset()
            val tStartX = cx - bodyRx * 0.6f + (i * bodyRx * 0.4f)
            tentaclePath.moveTo(tStartX, tentacleStartY)
            val segments = 6
            val segLen = octopusSize * 0.12f
            for (s in 1..segments) {
                val sx = tStartX + sin((tentaclePhase + i * 1.2f + s * 0.8f).toDouble()).toFloat() * 5f * dp
                val sy = tentacleStartY + s * segLen
                tentaclePath.lineTo(sx, sy)
            }
            canvas.drawPath(tentaclePath, tentaclePaint)
        }

        // Body (oval)
        canvas.drawOval(
            cx - bodyRx, cy - bodyRy,
            cx + bodyRx, cy + bodyRy,
            octopusPaint
        )

        // Eyes
        val eyeR = bodyRx * 0.22f
        val eyeOffX = bodyRx * 0.35f
        val eyeY = cy - bodyRy * 0.1f
        canvas.drawCircle(cx - eyeOffX, eyeY, eyeR, eyePaint)
        canvas.drawCircle(cx + eyeOffX, eyeY, eyeR, eyePaint)
        canvas.drawCircle(cx - eyeOffX + 1.5f * dp, eyeY, eyeR * 0.5f, pupilPaint)
        canvas.drawCircle(cx + eyeOffX + 1.5f * dp, eyeY, eyeR * 0.5f, pupilPaint)
    }

    private fun drawObstacle(canvas: Canvas, obs: Obstacle) {
        val bottom = floorY
        val top = bottom - obs.height
        val left = obs.x
        val right = obs.x + obs.width

        when (obs.type) {
            0 -> {
                // Coral: rounded rectangle with branching top
                obstacleRectF.set(left, top, right, bottom)
                canvas.drawRoundRect(obstacleRectF, 6f * dp, 6f * dp, obstaclePaint)
                // Branch tops
                canvas.drawCircle(left + obs.width * 0.3f, top - 4f * dp, 6f * dp, branchPaint)
                canvas.drawCircle(left + obs.width * 0.7f, top - 2f * dp, 5f * dp, branchPaint)
            }
            1 -> {
                // Rock: triangle-ish shape
                rockPath.reset()
                rockPath.moveTo(left, bottom)
                rockPath.lineTo(left + obs.width * 0.5f, top)
                rockPath.lineTo(right, bottom)
                rockPath.close()
                canvas.drawPath(rockPath, rockPaint)
            }
            else -> {
                // Tall seaweed obstacle
                swObstaclePaint.strokeWidth = obs.width * 0.4f
                swObstaclePath.reset()
                swObstaclePath.moveTo(left + obs.width / 2f, bottom)
                val midY = (top + bottom) / 2f
                swObstaclePath.cubicTo(
                    left + obs.width * 1.2f, midY + obs.height * 0.2f,
                    left - obs.width * 0.2f, midY - obs.height * 0.2f,
                    left + obs.width / 2f, top
                )
                canvas.drawPath(swObstaclePath, swObstaclePaint)
            }
        }
    }

    private fun drawSeaweed(canvas: Canvas, x: Float, groundY: Float) {
        seaweedPath.reset()
        val swHeight = 30f * dp + sin((x * 0.1f).toDouble()).toFloat() * 15f * dp
        seaweedPath.moveTo(x, groundY)
        val segments = 5
        for (i in 1..segments) {
            val frac = i.toFloat() / segments
            val sx = x + sin((tentaclePhase * 0.5f + x * 0.01f + i).toDouble()).toFloat() * 8f * dp
            val sy = groundY - swHeight * frac
            seaweedPath.lineTo(sx, sy)
        }
        canvas.drawPath(seaweedPath, seaweedPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (260f * dp).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    // ── Data classes ────────────────────────────────────────────────────

    private data class Obstacle(
        var x: Float,
        val width: Float,
        val height: Float,
        val type: Int // 0 = coral, 1 = rock, 2 = seaweed
    )

    private data class Bubble(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speed: Float
    )

    companion object {
        private const val CORNER_RADIUS_DP = 16f
    }
}
