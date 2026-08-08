package com.sweetgirlfriend.pet.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import com.sweetgirlfriend.pet.runtime.CharacterGameKit
import com.sweetgirlfriend.pet.runtime.PlayMode
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class MotionCommand(
    val dx: Float,
    val dy: Float,
    val completed: Boolean = false,
    val event: String? = null,
)

enum class ArcadeInteractionType { INPUT_REQUIRED, INPUT_ACCEPTED, INPUT_REJECTED, RESTORING, FINISHED }

data class ArcadeInteractionEvent(
    val type: ArcadeInteractionType,
    val mode: PlayMode,
    val message: String,
)

/**
 * Permission-free launcher game layer. Every obstacle, food token and destroyed block is drawn by
 * this process; this view never reads or changes launcher icons. The public touch/feedback hooks
 * let the host temporarily opt into interaction without coupling game rules to a window service.
 */
class GamePlayfieldView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD985596A.toInt()
        textSize = sp(11.5f)
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE633E50.toInt()
        textSize = sp(13f)
        isFakeBoldText = true
    }
    private val feedback = ArcadeFeedbackController(context)
    private val playableRect = RectF()
    private val random = Random(System.nanoTime())
    private val motionTrail = ArrayDeque<TrailDot>()
    private val particles = mutableListOf<Particle>()
    private val bursts = mutableListOf<Burst>()

    private var mode = PlayMode.NORMAL
    private var kit = CharacterGameKit()
    private var columns = 6
    private var rows = 9
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var dockTop = 0f
    private var snakeState: SnakeGameState? = null
    private var bomberState: BomberGameState? = null
    private var snakeWalls = linkedSetOf<ArcadeCell>()
    private var snakeFoods = linkedSetOf<ArcadeCell>()
    private var bomberHard = linkedSetOf<ArcadeCell>()
    private var bomberTargets = linkedSetOf<ArcadeCell>()
    private var originalSnakeFoods = emptySet<ArcadeCell>()
    private var originalBomberTargets = emptySet<ArcadeCell>()
    private var consumedSnakeFoods = linkedSetOf<ArcadeCell>()
    private var destroyedBomberTargets = linkedSetOf<ArcadeCell>()
    private var pendingWaypoint: ArcadeCell? = null
    private var queuedSnakeDirection: ArcadeDirection? = null
    private var manualSnakeSteps = 0
    private var bombRequested = false
    private var hideTarget: ArcadeCell? = null
    private var hideVisits = 0
    private var score = 0
    private var combo = 0
    private var progress = 0f
    private var restoreProgress = 0f
    private var flashAlpha = 0f
    private var restoreAnimator: ValueAnimator? = null
    private var interactionListener: ((ArcadeInteractionEvent) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var reducedEffects = false
    private var feedbackSound = false
    private var feedbackHaptics = true

    val wantsTouchInput: Boolean
        get() = mode == PlayMode.SNAKE || mode == PlayMode.BOMBER

    fun setInteractionListener(listener: ((ArcadeInteractionEvent) -> Unit)?) {
        interactionListener = listener
    }

    fun configureFeedback(soundEnabled: Boolean, hapticsEnabled: Boolean, reducedEffects: Boolean) {
        feedbackSound = soundEnabled
        feedbackHaptics = hapticsEnabled
        this.reducedEffects = reducedEffects
        feedback.configure(soundEnabled, hapticsEnabled, reducedEffects)
    }

    fun start(playMode: PlayMode, gameKit: CharacterGameKit) {
        restoreAnimator?.removeAllListeners()
        restoreAnimator?.cancel()
        restoreAnimator = null
        mode = playMode
        kit = gameKit
        val preferences = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
        configureFeedback(
            soundEnabled = preferences.getBoolean("game_sound_enabled", feedbackSound),
            hapticsEnabled = preferences.getBoolean("game_haptics_enabled", feedbackHaptics),
            // Re-evaluate the current profile for every round. OR-ing the previous field made
            // SAVER sticky until the whole overlay service was restarted.
            reducedEffects = preferences.getString("energy_profile", "ADAPTIVE") == "SAVER",
        )
        updateGridGeometry()
        resetRound()
        visibility = if (mode in ARCADE_MODES) VISIBLE else GONE
        if (wantsTouchInput) {
            interactionListener?.invoke(
                ArcadeInteractionEvent(
                    ArcadeInteractionType.INPUT_REQUIRED,
                    mode,
                    if (mode == PlayMode.SNAKE) "滑动转向；抓住头像可随时退出" else "点击目标或当前位置放置炸弹",
                ),
            )
        }
        invalidate()
    }

    fun stop(restore: Boolean) {
        if (restore && mode in setOf(PlayMode.SNAKE, PlayMode.BOMBER) && visibility == VISIBLE) {
            interactionListener?.invoke(ArcadeInteractionEvent(ArcadeInteractionType.RESTORING, mode, "正在复原安全拟态元素"))
            feedback.emit(ArcadeFeedbackEvent.RESTORE)
            restoreAnimator?.removeAllListeners()
            restoreAnimator?.cancel()
            restoreProgress = 0f
            restoreAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (reducedEffects) 460L else 980L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    restoreProgress = it.animatedValue as Float
                    advanceVisuals(if (reducedEffects) 0.11f else 0.065f)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        restoreAnimator = null
                        clearNow()
                    }
                })
                start()
            }
        } else {
            clearNow()
        }
    }

    fun release() {
        restoreAnimator?.removeAllListeners()
        restoreAnimator?.cancel()
        restoreAnimator = null
        feedback.release()
        clearNow()
        interactionListener = null
    }

    fun nextMotion(petCenterX: Float, petCenterY: Float): MotionCommand {
        if (mode !in ARCADE_MODES || width <= 0 || height <= 0 || restoreAnimator != null) {
            return MotionCommand(0f, 0f)
        }
        if (updateGridGeometry()) resetRound()
        appendTrail(petCenterX, petCenterY)
        advanceVisuals(if (reducedEffects) 0.09f else 0.055f)
        val current = cellAt(petCenterX, petCenterY)
        val command = when (mode) {
            PlayMode.SNAKE -> nextSnakeMotion(current, petCenterX, petCenterY)
            PlayMode.BOMBER -> nextBomberMotion(current, petCenterX, petCenterY)
            PlayMode.HIDE_SEEK -> nextHideMotion(current, petCenterX, petCenterY)
            else -> MotionCommand(0f, 0f)
        }
        invalidate()
        return command
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Keep the game grid in the same stable full-display coordinate space as the pet
            // physics. Gesture/navigation bars may auto-hide on vendor launchers; using only the
            // currently visible inset would otherwise make the board jump and reset mid-game.
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            insetLeft = safe.left
            insetTop = safe.top
            insetRight = safe.right
            insetBottom = safe.bottom
        } else {
            @Suppress("DEPRECATION")
            run {
                insetLeft = insets.systemWindowInsetLeft
                insetTop = insets.systemWindowInsetTop
                insetRight = insets.systemWindowInsetRight
                insetBottom = insets.systemWindowInsetBottom
            }
        }
        if (updateGridGeometry() && mode in ARCADE_MODES) resetRound()
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (updateGridGeometry() && mode in ARCADE_MODES) resetRound()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!wantsTouchInput) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                if (mode == PlayMode.SNAKE) handleSnakeSwipe(event.x - downX, event.y - downY)
                if (mode == PlayMode.BOMBER) handleBomberTap(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        feedback.release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (mode !in ARCADE_MODES || playableRect.isEmpty) return
        drawSafePlayground(canvas)
        when (mode) {
            PlayMode.SNAKE -> drawSnake(canvas)
            PlayMode.BOMBER -> drawBomber(canvas)
            PlayMode.HIDE_SEEK -> drawHideSeek(canvas)
            else -> Unit
        }
        drawMotionTrail(canvas)
        drawEffects(canvas)
        drawHud(canvas)
        if (flashAlpha > 0f) {
            paint.color = (kit.accentColor and 0x00FFFFFF) or ((flashAlpha * 62f).roundToInt().coerceIn(0, 80) shl 24)
            canvas.drawRoundRect(playableRect, dp(18f), dp(18f), paint)
        }
    }

    private fun nextSnakeMotion(current: ArcadeCell, x: Float, y: Float): MotionCommand {
        val state = snakeState ?: createSnakeState(current).also { snakeState = it }
        if (state.outcome != ArcadeOutcome.RUNNING) return finishedSnake(state)
        var waypoint = pendingWaypoint
        if (waypoint == null) {
            val queued = queuedSnakeDirection
            val direction = when {
                queued != null && state.accepts(queued) -> {
                    queuedSnakeDirection = null
                    manualSnakeSteps = MANUAL_SNAKE_STEPS
                    queued
                }
                manualSnakeSteps > 0 -> state.direction
                else -> state.suggestedDirection()
            }
            if (direction == null) {
                val update = state.step(state.direction)
                return MotionCommand(0f, 0f, completed = true, event = update.event ?: "snake-collision")
            }
            waypoint = state.head.moved(direction)
            pendingWaypoint = waypoint
        }
        val point = center(waypoint)
        if (isReached(point, x, y)) {
            val eatenCell = state.food
            val direction = ArcadeDirection.between(state.head, waypoint) ?: state.direction
            val update = state.step(direction)
            pendingWaypoint = null
            if (manualSnakeSteps > 0) manualSnakeSteps--
            score = update.score
            combo = update.combo
            progress = if (state.allFoods.isEmpty()) 1f else state.eatenCount.toFloat() / state.allFoods.size
            if (update.ateFood && eatenCell != null) {
                consumedSnakeFoods += eatenCell
                spawnBurst(center(eatenCell), kit.foodColor, if (combo >= 2) 18 else 11)
                flashAlpha = if (combo >= 2) 1f else 0.65f
                feedback.emit(if (combo >= 2) ArcadeFeedbackEvent.COMBO else ArcadeFeedbackEvent.COLLECT)
            }
            if (update.outcome != ArcadeOutcome.RUNNING) {
                feedback.emit(if (update.outcome == ArcadeOutcome.WON) ArcadeFeedbackEvent.WIN else ArcadeFeedbackEvent.LOSE)
                return MotionCommand(0f, 0f, completed = true, event = update.event)
            }
            return MotionCommand(0f, 0f)
        }
        return directionTo(point, x, y)
    }

    private fun nextBomberMotion(current: ArcadeCell, x: Float, y: Float): MotionCommand {
        val state = bomberState ?: createBomberState(current).also { bomberState = it }
        if (state.outcome != ArcadeOutcome.RUNNING) return finishedBomber(state)
        if (bombRequested || state.canDetonateTarget()) {
            bombRequested = false
            val update = state.detonate()
            pendingWaypoint = null
            score = update.score
            combo = update.combo
            progress = update.progress
            destroyedBomberTargets += update.destroyed
            update.affected.forEach { cell -> spawnBurst(center(cell), kit.bombColor, if (cell in update.destroyed) 9 else 4) }
            flashAlpha = if (update.destroyed.isEmpty()) 0.35f else 0.9f
            feedback.emit(if (update.destroyed.size >= 2) ArcadeFeedbackEvent.COMBO else ArcadeFeedbackEvent.BOMB)
            if (update.outcome != ArcadeOutcome.RUNNING) {
                feedback.emit(if (update.outcome == ArcadeOutcome.WON) ArcadeFeedbackEvent.WIN else ArcadeFeedbackEvent.LOSE)
                return MotionCommand(0f, 0f, completed = true, event = update.event)
            }
            return MotionCommand(0f, 0f)
        }
        var waypoint = pendingWaypoint
        if (waypoint == null) {
            val path = state.pathToTarget()
            if (path.isEmpty()) return MotionCommand(0f, 0f, completed = true, event = "path-blocked")
            waypoint = path.getOrNull(1)
            if (waypoint == null) {
                bombRequested = true
                return MotionCommand(0f, 0f)
            }
            pendingWaypoint = waypoint
        }
        val point = center(waypoint)
        if (isReached(point, x, y)) {
            state.moveTo(waypoint)
            pendingWaypoint = null
            return MotionCommand(0f, 0f)
        }
        return directionTo(point, x, y)
    }

    private fun nextHideMotion(current: ArcadeCell, x: Float, y: Float): MotionCommand {
        if (hideTarget == null) hideTarget = farCellFrom(current, emptySet())
        val target = hideTarget ?: return MotionCommand(0f, 0f, completed = true, event = "hide-finished")
        val point = center(target)
        if (isReached(point, x, y)) {
            hideVisits++
            score += 12
            combo = min(6, combo + 1)
            progress = hideVisits / HIDE_VISITS_TO_WIN.toFloat()
            spawnBurst(point, kit.accentColor, 10)
            feedback.emit(ArcadeFeedbackEvent.COLLECT)
            hideTarget = farCellFrom(target, bomberHard)
            if (hideVisits >= HIDE_VISITS_TO_WIN) {
                feedback.emit(ArcadeFeedbackEvent.WIN)
                return MotionCommand(0f, 0f, completed = true, event = "hide-finished")
            }
        }
        val path = ArcadeGridPathfinder.shortestPath(current, setOf(hideTarget ?: target), columns, rows, bomberHard)
        val waypoint = center(path.getOrNull(1) ?: (hideTarget ?: target))
        return directionTo(waypoint, x, y)
    }

    private fun createSnakeState(start: ArcadeCell): SnakeGameState {
        snakeWalls.remove(start)
        snakeFoods.remove(start)
        ArcadeDirection.entries.map(start::moved).forEach(snakeWalls::remove)
        return SnakeGameState(columns, rows, start, snakeWalls, snakeFoods).also {
            originalSnakeFoods = it.allFoods
        }
    }

    private fun createBomberState(start: ArcadeCell): BomberGameState {
        bomberHard.remove(start)
        bomberTargets.remove(start)
        ArcadeDirection.entries.map(start::moved).forEach { neighbor ->
            bomberHard.remove(neighbor)
            bomberTargets.remove(neighbor)
        }
        return BomberGameState(columns, rows, start, bomberHard, bomberTargets).also {
            originalBomberTargets = it.originalTargets
            progress = if (it.totalTargets == 0) 1f else 0f
        }
    }

    private fun finishedSnake(state: SnakeGameState): MotionCommand = MotionCommand(
        0f,
        0f,
        completed = true,
        event = if (state.outcome == ArcadeOutcome.WON) "snake-cleared" else "snake-collision",
    )

    private fun finishedBomber(state: BomberGameState): MotionCommand = MotionCommand(
        0f,
        0f,
        completed = true,
        event = if (state.outcome == ArcadeOutcome.WON) "bomber-cleared" else "path-blocked",
    )

    private fun handleSnakeSwipe(deltaX: Float, deltaY: Float) {
        if (hypot(deltaX.toDouble(), deltaY.toDouble()) < dp(18f)) return
        val requested = if (abs(deltaX) >= abs(deltaY)) {
            if (deltaX >= 0f) ArcadeDirection.RIGHT else ArcadeDirection.LEFT
        } else {
            if (deltaY >= 0f) ArcadeDirection.DOWN else ArcadeDirection.UP
        }
        val state = snakeState
        if (state != null && !state.accepts(requested)) {
            interactionListener?.invoke(ArcadeInteractionEvent(ArcadeInteractionType.INPUT_REJECTED, mode, "蛇身不能直接反向"))
            feedback.emit(ArcadeFeedbackEvent.LOSE)
            return
        }
        queuedSnakeDirection = requested
        interactionListener?.invoke(ArcadeInteractionEvent(ArcadeInteractionType.INPUT_ACCEPTED, mode, "方向已改变"))
        feedback.emit(ArcadeFeedbackEvent.MOVE)
    }

    private fun handleBomberTap(x: Float, y: Float) {
        val state = bomberState ?: return
        val tapped = cellAt(x, y)
        val selected = state.selectTarget(tapped)
        if (tapped == state.player || state.canDetonateTarget()) bombRequested = true
        if (selected || bombRequested) {
            pendingWaypoint = null
            interactionListener?.invoke(
                ArcadeInteractionEvent(
                    ArcadeInteractionType.INPUT_ACCEPTED,
                    mode,
                    if (bombRequested) "炸弹已放置" else "已选择可破坏目标",
                ),
            )
            feedback.emit(ArcadeFeedbackEvent.MOVE)
        } else {
            interactionListener?.invoke(ArcadeInteractionEvent(ArcadeInteractionType.INPUT_REJECTED, mode, "这里不是可达目标"))
        }
    }

    private fun resetRound() {
        snakeState = null
        bomberState = null
        pendingWaypoint = null
        queuedSnakeDirection = null
        manualSnakeSteps = 0
        bombRequested = false
        hideTarget = null
        hideVisits = 0
        score = 0
        combo = 0
        progress = 0f
        restoreProgress = 0f
        flashAlpha = 0f
        consumedSnakeFoods.clear()
        destroyedBomberTargets.clear()
        motionTrail.clear()
        particles.clear()
        bursts.clear()
        buildSafeLayout()
    }

    private fun buildSafeLayout() {
        if (columns <= 0 || rows <= 0 || playableRect.isEmpty) return
        val cells = allCells()
        snakeWalls = cells
            .filter { it.row > 0 && it.row < rows - 1 && (it.column * 7 + it.row * 11) % 19 == 0 }
            .take((cells.size / 14).coerceIn(3, 8))
            .toCollection(linkedSetOf())
        snakeFoods = cells
            .filterNot(snakeWalls::contains)
            .shuffled(random)
            .take((cells.size / 11).coerceIn(6, 9))
            .toCollection(linkedSetOf())
        bomberHard = cells
            .filter { it.column % 2 == 1 && it.row % 2 == 1 }
            .toCollection(linkedSetOf())
        bomberTargets = cells
            .filterNot(bomberHard::contains)
            .filter { it.row > 0 }
            .shuffled(random)
            .take((cells.size / 8).coerceIn(8, 13))
            .toCollection(linkedSetOf())
        originalSnakeFoods = snakeFoods.toSet()
        originalBomberTargets = bomberTargets.toSet()
    }

    private fun updateGridGeometry(): Boolean {
        if (width <= 0 || height <= 0) return false
        val sidePadding = dp(10f)
        val topPadding = max(insetTop.toFloat(), dp(34f)) + dp(7f)
        val bottomSafe = max(insetBottom + dp(18f), height * DOCK_RESERVE_RATIO)
        val newRect = RectF(
            insetLeft + sidePadding,
            topPadding,
            width - insetRight - sidePadding,
            height - bottomSafe,
        )
        if (newRect.width() < dp(180f) || newRect.height() < dp(240f)) return false
        val densityWidth = newRect.width() / resources.displayMetrics.density
        val newColumns = when {
            densityWidth < 330f -> 5
            densityWidth >= 600f -> 8
            densityWidth >= 430f -> 7
            else -> 6
        }
        val proposedCell = newRect.width() / newColumns
        val newRows = (newRect.height() / proposedCell).roundToInt().coerceIn(6, 13)
        val changed = abs(playableRect.left - newRect.left) > 1f || abs(playableRect.top - newRect.top) > 1f ||
            abs(playableRect.right - newRect.right) > 1f || abs(playableRect.bottom - newRect.bottom) > 1f ||
            columns != newColumns || rows != newRows
        playableRect.set(newRect)
        columns = newColumns
        rows = newRows
        cellWidth = playableRect.width() / columns
        cellHeight = playableRect.height() / rows
        dockTop = playableRect.bottom + dp(4f)
        return changed
    }

    private fun drawSafePlayground(canvas: Canvas) {
        paint.color = 0x0FC95E84
        canvas.drawRoundRect(playableRect, dp(18f), dp(18f), paint)
        strokePaint.color = 0x35C95E84
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(playableRect, dp(18f), dp(18f), strokePaint)
        strokePaint.color = 0x14A66A80
        strokePaint.strokeWidth = dp(0.7f)
        for (column in 1 until columns) {
            val x = playableRect.left + column * cellWidth
            canvas.drawLine(x, playableRect.top, x, playableRect.bottom, strokePaint)
        }
        for (row in 1 until rows) {
            val y = playableRect.top + row * cellHeight
            canvas.drawLine(playableRect.left, y, playableRect.right, y, strokePaint)
        }
        paint.color = 0x12A17486
        canvas.drawRoundRect(
            RectF(playableRect.left, dockTop, playableRect.right, height - insetBottom - dp(4f)),
            dp(13f),
            dp(13f),
            paint,
        )
        textPaint.color = 0xB37A6470.toInt()
        canvas.drawText("系统 Dock 安全保留区 · 不生成游戏目标", playableRect.left + dp(9f), dockTop + dp(20f), textPaint)
    }

    private fun drawSnake(canvas: Canvas) {
        val state = snakeState
        snakeWalls.forEach { drawHardObstacle(canvas, it, 1f - restoreProgress) }
        val remaining = state?.remainingFoodCells ?: originalSnakeFoods
        originalSnakeFoods.forEach { cell ->
            val currentFood = cell == state?.food
            val alpha = if (cell in remaining) 1f else restoreProgress
            if (alpha > 0.01f) drawFood(canvas, cell, alpha, currentFood)
        }
        state?.body?.drop(1)?.forEachIndexed { index, cell ->
            val alpha = ((0.78f - index * 0.055f).coerceAtLeast(0.24f)) * (1f - restoreProgress)
            val point = center(cell)
            val radius = min(cellWidth, cellHeight) * 0.18f
            paint.color = (kit.accentColor and 0x00FFFFFF) or ((alpha * 255).roundToInt() shl 24)
            canvas.drawCircle(point.x, point.y, radius, paint)
            strokePaint.color = 0xAAFFFFFF.toInt()
            strokePaint.strokeWidth = dp(1.2f)
            canvas.drawCircle(point.x, point.y, radius * 0.72f, strokePaint)
        }
        state?.let { drawDirectionIndicator(canvas, center(it.head), it.direction) }
    }

    private fun drawBomber(canvas: Canvas) {
        bomberHard.forEach { drawHardObstacle(canvas, it, 1f - restoreProgress * 0.65f) }
        val remaining = bomberState?.targets ?: originalBomberTargets
        originalBomberTargets.forEach { cell ->
            val alpha = if (cell in remaining) 1f else restoreProgress
            if (alpha > 0.01f) drawDestructible(canvas, cell, alpha, cell == bomberState?.target)
        }
        bomberState?.player?.let { cell ->
            val point = center(cell)
            strokePaint.color = 0x99FFFFFF.toInt()
            strokePaint.strokeWidth = dp(2f)
            canvas.drawCircle(point.x, point.y, min(cellWidth, cellHeight) * 0.27f, strokePaint)
        }
    }

    private fun drawHideSeek(canvas: Canvas) {
        bomberHard.take(8).forEach { drawHardObstacle(canvas, it, 0.55f) }
        hideTarget?.let { cell ->
            val point = center(cell)
            val pulse = ((sin(SystemClock.uptimeMillis() / 180.0) + 1.0) / 2.0).toFloat()
            paint.color = (kit.accentColor and 0x00FFFFFF) or ((55 + pulse * 70).roundToInt() shl 24)
            canvas.drawCircle(point.x, point.y, dp(13f) + pulse * dp(8f), paint)
            strokePaint.color = 0xBBFFFFFF.toInt()
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(point.x, point.y, dp(8f) + pulse * dp(5f), strokePaint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val top = max(dp(20f), insetTop + dp(9f))
        val title = when (mode) {
            PlayMode.SNAKE -> "安全贪吃蛇"
            PlayMode.BOMBER -> "安全炸弹人"
            PlayMode.HIDE_SEEK -> "桌面捉迷藏"
            else -> "安全拟态"
        }
        hudPaint.color = 0xEE633E50.toInt()
        canvas.drawText("$title  SCORE $score", playableRect.left + dp(4f), top, hudPaint)
        if (combo >= 2) {
            hudPaint.color = kit.accentColor
            hudPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("COMBO ×$combo", playableRect.right - dp(4f), top, hudPaint)
            hudPaint.textAlign = Paint.Align.LEFT
        }
        val barTop = top + dp(7f)
        paint.color = 0x24FFFFFF
        canvas.drawRoundRect(RectF(playableRect.left, barTop, playableRect.right, barTop + dp(4f)), dp(2f), dp(2f), paint)
        paint.color = kit.accentColor
        canvas.drawRoundRect(
            RectF(playableRect.left, barTop, playableRect.left + playableRect.width() * progress.coerceIn(0f, 1f), barTop + dp(4f)),
            dp(2f),
            dp(2f),
            paint,
        )
        textPaint.color = 0xC5795D69.toInt()
        val hint = when (mode) {
            PlayMode.SNAKE -> "滑动转向 · 禁止反向 · 抓住头像退出"
            PlayMode.BOMBER -> "点选目标/当前位置放炸弹 · 抓住头像退出"
            else -> "抓住头像即可结束 · 不读取真实桌面图标"
        }
        canvas.drawText(hint, playableRect.left + dp(4f), playableRect.bottom + dp(18f), textPaint)
    }

    private fun drawFood(canvas: Canvas, cell: ArcadeCell, alpha: Float, active: Boolean) {
        val point = center(cell)
        val pulse = if (active) ((sin(SystemClock.uptimeMillis() / 145.0) + 1.0) / 2.0).toFloat() else 0f
        val size = min(cellWidth, cellHeight) * (0.115f + pulse * 0.025f) * (0.55f + alpha * 0.45f)
        paint.color = (kit.foodColor and 0x00FFFFFF) or ((alpha * 220).roundToInt().coerceIn(0, 255) shl 24)
        canvas.drawRoundRect(RectF(point.x - size, point.y - size, point.x + size, point.y + size), size * 0.38f, size * 0.38f, paint)
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(point.x, point.y, size * 0.25f, paint)
        if (active) {
            strokePaint.color = (kit.foodColor and 0x00FFFFFF) or 0xAA000000.toInt()
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(point.x, point.y, size * 1.55f, strokePaint)
        }
    }

    private fun drawHardObstacle(canvas: Canvas, cell: ArcadeCell, alpha: Float) {
        if (alpha <= 0f) return
        val rect = cellRect(cell, 0.22f)
        paint.color = (0xFF6F8196.toInt() and 0x00FFFFFF) or ((alpha * 110).roundToInt().coerceIn(0, 255) shl 24)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), paint)
        strokePaint.color = 0x66FFFFFF
        strokePaint.strokeWidth = dp(1f)
        canvas.drawLine(rect.left + dp(4f), rect.top + dp(4f), rect.right - dp(4f), rect.bottom - dp(4f), strokePaint)
        canvas.drawLine(rect.right - dp(4f), rect.top + dp(4f), rect.left + dp(4f), rect.bottom - dp(4f), strokePaint)
    }

    private fun drawDestructible(canvas: Canvas, cell: ArcadeCell, alpha: Float, selected: Boolean) {
        val point = center(cell)
        val scale = if (cell in destroyedBomberTargets) 0.55f + 0.45f * restoreProgress else 1f
        val half = min(cellWidth, cellHeight) * 0.19f * scale
        paint.color = (kit.bombColor and 0x00FFFFFF) or ((alpha * 185).roundToInt().coerceIn(0, 255) shl 24)
        canvas.drawRoundRect(RectF(point.x - half, point.y - half, point.x + half, point.y + half), dp(6f), dp(6f), paint)
        strokePaint.color = if (selected) 0xEEFFFFFF.toInt() else 0x77FFFFFF
        strokePaint.strokeWidth = if (selected) dp(2f) else dp(1f)
        canvas.drawRoundRect(RectF(point.x - half, point.y - half, point.x + half, point.y + half), dp(6f), dp(6f), strokePaint)
        canvas.drawLine(point.x - half * 0.6f, point.y, point.x + half * 0.6f, point.y, strokePaint)
        canvas.drawLine(point.x, point.y - half * 0.6f, point.x, point.y + half * 0.6f, strokePaint)
    }

    private fun drawDirectionIndicator(canvas: Canvas, point: PointF, direction: ArcadeDirection) {
        val length = min(cellWidth, cellHeight) * 0.17f
        val dx = direction.columnDelta * length
        val dy = direction.rowDelta * length
        strokePaint.color = 0xDDFFFFFF.toInt()
        strokePaint.strokeWidth = dp(2f)
        canvas.drawLine(point.x, point.y, point.x + dx, point.y + dy, strokePaint)
    }

    private fun drawMotionTrail(canvas: Canvas) {
        motionTrail.forEach { dot ->
            paint.color = (kit.accentColor and 0x00FFFFFF) or ((dot.life * 72f * (1f - restoreProgress)).roundToInt().coerceIn(0, 90) shl 24)
            canvas.drawCircle(dot.x, dot.y, dp(2.5f) + dot.life * dp(2f), paint)
        }
    }

    private fun drawEffects(canvas: Canvas) {
        bursts.forEach { burst ->
            strokePaint.color = (burst.color and 0x00FFFFFF) or (((1f - burst.progress) * 205).roundToInt().coerceIn(0, 255) shl 24)
            strokePaint.strokeWidth = dp(2.2f) * (1f - burst.progress * 0.4f)
            canvas.drawCircle(burst.x, burst.y, dp(6f) + burst.progress * dp(30f), strokePaint)
        }
        particles.forEach { particle ->
            paint.color = (particle.color and 0x00FFFFFF) or ((particle.life * 230).roundToInt().coerceIn(0, 255) shl 24)
            canvas.save()
            canvas.rotate(particle.life * 180f, particle.x, particle.y)
            canvas.drawRoundRect(
                RectF(
                    particle.x - particle.size,
                    particle.y - particle.size * 0.42f,
                    particle.x + particle.size,
                    particle.y + particle.size * 0.42f,
                ),
                particle.size * 0.25f,
                particle.size * 0.25f,
                paint,
            )
            canvas.restore()
        }
    }

    private fun spawnBurst(point: PointF, color: Int, requestedParticles: Int) {
        bursts += Burst(point.x, point.y, color, 0f)
        val count = if (reducedEffects) min(4, requestedParticles) else requestedParticles
        repeat(count) { index ->
            val angle = index * (Math.PI * 2.0 / max(1, count)) + random.nextDouble(-0.18, 0.18)
            val speed = dp(random.nextDouble(0.8, 2.2).toFloat())
            particles += Particle(
                x = point.x,
                y = point.y,
                velocityX = (cos(angle) * speed).toFloat(),
                velocityY = (sin(angle) * speed).toFloat(),
                life = 1f,
                color = color,
                size = dp(random.nextDouble(1.5, 3.4).toFloat()),
            )
        }
    }

    private fun appendTrail(x: Float, y: Float) {
        val last = motionTrail.lastOrNull()
        if (last == null || hypot((last.x - x).toDouble(), (last.y - y).toDouble()) >= dp(5f)) {
            motionTrail += TrailDot(x, y, 1f)
        }
        while (motionTrail.size > if (reducedEffects) 12 else 34) motionTrail.removeFirst()
    }

    private fun advanceVisuals(delta: Float) {
        motionTrail.forEach { it.life -= delta * 0.48f }
        while (motionTrail.firstOrNull()?.life?.let { it <= 0f } == true) motionTrail.removeFirst()
        bursts.forEach { it.progress += delta }
        bursts.removeAll { it.progress >= 1f }
        particles.forEach {
            it.x += it.velocityX
            it.y += it.velocityY
            it.velocityY += dp(0.035f)
            it.life -= delta * 1.25f
        }
        particles.removeAll { it.life <= 0f }
        flashAlpha = (flashAlpha - delta * 1.8f).coerceAtLeast(0f)
    }

    private fun clearNow() {
        val finishedMode = mode
        mode = PlayMode.NORMAL
        snakeState = null
        bomberState = null
        pendingWaypoint = null
        motionTrail.clear()
        particles.clear()
        bursts.clear()
        restoreProgress = 0f
        visibility = GONE
        invalidate()
        if (finishedMode in ARCADE_MODES) {
            interactionListener?.invoke(ArcadeInteractionEvent(ArcadeInteractionType.FINISHED, finishedMode, "游戏层已恢复穿透"))
        }
    }

    private fun allCells(): List<ArcadeCell> = buildList {
        for (row in 0 until rows) for (column in 0 until columns) add(ArcadeCell(column, row))
    }

    private fun cellAt(x: Float, y: Float): ArcadeCell = ArcadeCell(
        ((x - playableRect.left) / cellWidth).toInt().coerceIn(0, columns - 1),
        ((y - playableRect.top) / cellHeight).toInt().coerceIn(0, rows - 1),
    )

    private fun center(cell: ArcadeCell): PointF = PointF(
        playableRect.left + (cell.column + 0.5f) * cellWidth,
        playableRect.top + (cell.row + 0.5f) * cellHeight,
    )

    private fun cellRect(cell: ArcadeCell, insetRatio: Float): RectF {
        val point = center(cell)
        val halfWidth = cellWidth * (0.5f - insetRatio)
        val halfHeight = cellHeight * (0.5f - insetRatio)
        return RectF(point.x - halfWidth, point.y - halfHeight, point.x + halfWidth, point.y + halfHeight)
    }

    private fun isReached(point: PointF, x: Float, y: Float): Boolean =
        hypot((point.x - x).toDouble(), (point.y - y).toDouble()) < min(cellWidth, cellHeight) * 0.27f

    private fun directionTo(point: PointF, x: Float, y: Float): MotionCommand {
        val dx = point.x - x
        val dy = point.y - y
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        return MotionCommand(dx / length, dy / length)
    }

    private fun farCellFrom(origin: ArcadeCell, blocked: Set<ArcadeCell>): ArcadeCell? = allCells()
        .filterNot(blocked::contains)
        .filter { abs(it.column - origin.column) + abs(it.row - origin.row) >= max(3, (columns + rows) / 3) }
        .shuffled(random)
        .firstOrNull()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private data class TrailDot(var x: Float, var y: Float, var life: Float)
    private data class Burst(val x: Float, val y: Float, val color: Int, var progress: Float)
    private data class Particle(
        var x: Float,
        var y: Float,
        var velocityX: Float,
        var velocityY: Float,
        var life: Float,
        val color: Int,
        val size: Float,
    )

    companion object {
        private const val DOCK_RESERVE_RATIO = 0.22f
        private const val MANUAL_SNAKE_STEPS = 6
        private const val HIDE_VISITS_TO_WIN = 7
        private val ARCADE_MODES = setOf(PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE)
    }
}
