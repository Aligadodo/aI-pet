package com.sweetgirlfriend.pet.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Surface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sweetgirlfriend.pet.content.ContentPackLoader
import com.sweetgirlfriend.pet.renderer.FrameAnimationView
import com.sweetgirlfriend.pet.runtime.EnergyPolicy
import com.sweetgirlfriend.pet.runtime.EnergyProfile
import com.sweetgirlfriend.pet.runtime.InteractionFrequency
import com.sweetgirlfriend.pet.runtime.InteractionStyle
import com.sweetgirlfriend.pet.runtime.CharacterGameKit
import com.sweetgirlfriend.pet.runtime.MotionInsets
import com.sweetgirlfriend.pet.runtime.MotionPoint
import com.sweetgirlfriend.pet.runtime.MotionRect
import com.sweetgirlfriend.pet.runtime.PetMotionGeometry
import com.sweetgirlfriend.pet.runtime.PlayMode
import com.sweetgirlfriend.pet.runtime.PetActivityLevel
import com.sweetgirlfriend.pet.runtime.PetStateMachine
import com.sweetgirlfriend.pet.runtime.PetTask
import com.sweetgirlfriend.pet.runtime.TaskOption
import com.sweetgirlfriend.pet.runtime.TaskPersistenceKeys
import com.sweetgirlfriend.pet.runtime.TaskScheduler
import com.sweetgirlfriend.pet.runtime.AutomaticFrameDirective
import com.sweetgirlfriend.pet.runtime.AutomaticInteractionPause
import com.sweetgirlfriend.pet.runtime.WeatherCachePolicy
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pet, lyric and task UI intentionally use separate overlay windows. The draggable bounds are
 * therefore derived only from the visible pet instead of a large invisible dialogue container.
 */
class OverlayPetService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(OverlayPetController.PREFERENCES, MODE_PRIVATE) }
    private val loader by lazy { ContentPackLoader(applicationContext) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val weatherProvider by lazy { WeatherContextProvider(applicationContext) }

    private lateinit var petHost: FrameLayout
    private lateinit var petParams: WindowManager.LayoutParams
    private lateinit var animationView: FrameAnimationView
    private lateinit var lyricView: MangaBubbleTextView
    private lateinit var lyricParams: WindowManager.LayoutParams
    private lateinit var panelHost: FrameLayout
    private lateinit var panelCard: LinearLayout
    private lateinit var panelBubble: MangaBubbleDrawable
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var petalBurst: PetalBurstView
    private lateinit var gameField: GamePlayfieldView
    private lateinit var gameFieldParams: WindowManager.LayoutParams
    private var gameKit = CharacterGameKit()
    private lateinit var stateMachine: PetStateMachine
    private lateinit var taskScheduler: TaskScheduler

    private var tasks: List<PetTask> = emptyList()
    private var runtimePackId = ""
    private var currentTask: PetTask? = null
    private var currentAction = "idle"
    private var screenInteractive = true
    private var lastInteractionAt = 0L
    private var lastAutomaticTaskAt = 0L
    private var sleepUntil = 0L
    private val automaticInteractionPause = AutomaticInteractionPause()
    private var reminderPresentationDeferredUntil = 0L
    private var lyricUntil = 0L
    private var petAdded = false
    private var lyricAdded = false
    private var panelAdded = false
    private var gameFieldAdded = false
    private var receiverRegistered = false
    private var lastTapAt = 0L
    private var singleTap: Runnable? = null
    private var dragState: DragState? = null
    private var playMode = PlayMode.NORMAL
    private var gravitySensor: Sensor? = null
    private var gravitySensorRegistered = false
    private var gravityFallbackNotified = false
    private var gravityX = 0f
    private var gravityY = 6f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastMotionAt = 0L
    private var borderProgress = 0f
    private var borderBounds: MotionRect? = null
    private var gameEndsAt = 0L
    private var nextContextDialogueAt = 0L
    private var nextWeatherRefreshAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenInteractive = intent?.action != Intent.ACTION_SCREEN_OFF && powerManager.isInteractive
            setOverlayVisibility(screenInteractive)
            if (screenInteractive) {
                registerGravitySensorIfNeeded()
                scheduleMotionLoop()
            } else {
                handler.removeCallbacks(motionLoop)
                sensorManager.unregisterListener(this@OverlayPetService)
                gravitySensorRegistered = false
            }
            updateEnergyMode(SystemClock.uptimeMillis())
        }
    }

    private val stateLoop = object : Runnable {
        override fun run() {
            if (!petAdded) return
            val now = SystemClock.uptimeMillis()
            screenInteractive = powerManager.isInteractive
            if (screenInteractive) updatePetState(now)
            updateEnergyMode(now)
            maybeShowAutomaticTask(now)
            maybeShowContextDialogue(now)
            handler.postDelayed(
                this,
                if (screenInteractive) STATE_INTERVAL_MS else SCREEN_OFF_STATE_INTERVAL_MS,
            )
        }
    }

    private val motionLoop = object : Runnable {
        override fun run() {
            if (!petAdded || !screenInteractive || playMode == PlayMode.NORMAL) return
            val now = SystemClock.uptimeMillis()
            if (dragState == null && panelHost.visibility != View.VISIBLE) updatePlayMotion(now)
            if (petAdded && screenInteractive && playMode != PlayMode.NORMAL) {
                handler.postDelayed(this, MOTION_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences.edit().putString("game_mode", PlayMode.NORMAL.name).apply()
        createNotificationChannel()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createOverlayWindows()
        registerScreenReceiver()
        OverlayPetController.markRunning(true)
        lastInteractionAt = SystemClock.uptimeMillis()
        lastAutomaticTaskAt = lastInteractionAt
        nextContextDialogueAt = lastInteractionAt + randomContextDelay()
        refreshWeatherContext()
        handler.post(stateLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SHOW_TASK -> if (petAdded) showNextTask(force = true)
            ACTION_TOGGLE_SLEEP -> if (petAdded) toggleRest()
            ACTION_REFRESH -> if (petAdded) refreshSettings()
            ACTION_SET_PLAY_MODE -> if (petAdded) {
                val mode = runCatching {
                    PlayMode.valueOf(intent.getStringExtra(EXTRA_PLAY_MODE) ?: PlayMode.NORMAL.name)
                }.getOrDefault(PlayMode.NORMAL)
                startPlayMode(mode, announce = true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        singleTap = null
        if (panelAdded) runCatching { windowManager.removeView(panelHost) }
        if (lyricAdded) runCatching { windowManager.removeView(lyricView) }
        if (petAdded) runCatching { windowManager.removeView(petHost) }
        if (gameFieldAdded) {
            runCatching { gameField.release() }
            runCatching { windowManager.removeView(gameField) }
        }
        sensorManager.unregisterListener(this)
        gravitySensorRegistered = false
        panelAdded = false
        lyricAdded = false
        petAdded = false
        gameFieldAdded = false
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        preferences.edit().putString("game_mode", PlayMode.NORMAL.name).apply()
        OverlayPetController.markRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createOverlayWindows() {
        configureRuntime()
        gameKit = loader.loadGameKit(currentPackId())
        val (petWidth, petHeight) = desiredPetSize()

        gameField = GamePlayfieldView(this).apply {
            visibility = View.GONE
            contentDescription = "桌宠安全拟态游戏层"
            setInteractionListener(::handleArcadeInteraction)
            configureFeedback(
                soundEnabled = preferences.getBoolean("game_sound_enabled", true),
                hapticsEnabled = preferences.getBoolean("game_haptics_enabled", true),
                reducedEffects = preferences.getString("energy_profile", "ADAPTIVE") == "SAVER",
            )
        }
        gameFieldParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
        ).apply {
            setTitle("SweetPet.GameField")
            alpha = PASS_THROUGH_WINDOW_ALPHA
        }
        windowManager.addView(gameField, gameFieldParams)
        gameFieldAdded = true

        animationView = FrameAnimationView(this).apply {
            configure(loader, currentPackId(), "idle")
            setCharacterScale(1f)
            setSpeedMultiplier(preferences.getInt("speed_percent", 100) / 100f)
            setTargetFrameRate(12)
            setTransparentSurfaceCompositing(true)
            setOnTouchListener(::handlePetTouch)
        }
        petHost = SurfaceClearingFrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            contentDescription = "可拖动桌宠"
            addView(
                animationView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        petParams = overlayParams(petWidth, petHeight, touchable = true).apply {
            setTitle("SweetPet.Character")
            x = preferences.getInt("overlay_x", screenWidth() - petWidth - dp(10))
            y = preferences.getInt("overlay_y", dp(180))
        }
        windowManager.addView(petHost, petParams)
        petAdded = true

        lyricView = MangaBubbleTextView(this).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 13.5f
            setTextColor(0xFF35282E.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(1).toFloat(), 1.04f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            contentDescription = "桌宠漫画台词"
        }
        lyricParams = overlayParams(dp(112), dp(72), touchable = false).apply {
            setTitle("SweetPet.Dialogue")
            alpha = PASS_THROUGH_WINDOW_ALPHA
        }
        windowManager.addView(lyricView, lyricParams)
        lyricAdded = true

        panelBubble = MangaBubbleDrawable(resources.displayMetrics.density)
        panelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(17))
            background = panelBubble
            elevation = dp(5).toFloat()
        }
        petalBurst = PetalBurstView(this)
        panelHost = SurfaceClearingFrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(4))
            clipChildren = false
            addView(
                panelCard,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                petalBurst,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            visibility = View.GONE
        }
        panelParams = overlayParams(panelWidth(), dp(116), touchable = true).apply {
            setTitle("SweetPet.InteractionPanel")
        }
        windowManager.addView(panelHost, panelParams)
        panelAdded = true

        clampPetAndUpdate()
        showMessage("悬浮陪伴已开启", "拖动人物即可贴近屏幕边缘，轻点人物打开快捷互动。", 3_600L)
    }

    private fun configureRuntime() {
        val packId = currentPackId()
        val actions = loader.availableActions(packId)
        stateMachine = PetStateMachine(actions, behavior = loader.loadBehavior(packId))
        tasks = loader.loadTasks(packId)
        runtimePackId = packId
        migrateLegacyTaskState(packId, tasks)
        taskScheduler = TaskScheduler(tasks)
        tasks.forEach { task ->
            taskScheduler.restore(
                task.id,
                preferences.getLong(TaskPersistenceKeys.nextEligible(packId, task.id), 0L),
                preferences.getBoolean(TaskPersistenceKeys.reminderPending(packId, task.id), false),
            )
        }
        taskScheduler.restoreRecent(
            preferences.getString(TaskPersistenceKeys.recent(packId), "")
                .orEmpty()
                .split(',')
                .filter(String::isNotBlank),
        )
    }

    /**
     * Legacy builds stored task ids globally. Attribute that one-time snapshot only to the pack
     * selected during migration; copying it on every later pack switch would recreate the leak.
     */
    private fun migrateLegacyTaskState(packId: String, packTasks: List<PetTask>) {
        if (preferences.getBoolean(TASK_NAMESPACE_MIGRATED, false)) return
        val editor = preferences.edit()
        packTasks.forEach { task ->
            val legacyNext = "task_next_${task.id}"
            val scopedNext = TaskPersistenceKeys.nextEligible(packId, task.id)
            if (preferences.contains(legacyNext) && !preferences.contains(scopedNext)) {
                runCatching { preferences.getLong(legacyNext, 0L) }.getOrNull()?.let {
                    editor.putLong(scopedNext, it)
                }
            }
            val legacyReminder = "task_snoozed_${task.id}"
            val scopedReminder = TaskPersistenceKeys.reminderPending(packId, task.id)
            if (preferences.contains(legacyReminder) && !preferences.contains(scopedReminder)) {
                runCatching { preferences.getBoolean(legacyReminder, false) }.getOrNull()?.let {
                    editor.putBoolean(scopedReminder, it)
                }
            }
            editor.remove(legacyNext).remove(legacyReminder)
        }
        if (preferences.contains(LEGACY_TASK_RECENT_KEY) &&
            !preferences.contains(TaskPersistenceKeys.recent(packId))
        ) {
            runCatching { preferences.getString(LEGACY_TASK_RECENT_KEY, "") }.getOrNull()?.let {
                editor.putString(TaskPersistenceKeys.recent(packId), it)
            }
        }
        editor.remove(LEGACY_TASK_RECENT_KEY)
            .putBoolean(TASK_NAMESPACE_MIGRATED, true)
            .apply()
    }

    private fun refreshSettings() {
        // A visible task belongs to the scheduler and pack that created its button callbacks.
        // Retire it before preferences can reconfigure the runtime for another pack.
        hidePanel()
        if (playMode != PlayMode.NORMAL) stopPlayMode(restoreTokens = true, message = null)
        // automaticInteractionPause is service-owned on purpose: configureRuntime may rebuild the
        // state machine for a new pack, but it must not reset a drag/rest deadline.
        configureRuntime()
        gameKit = loader.loadGameKit(currentPackId())
        currentAction = "idle"
        animationView.setPack(currentPackId())
        animationView.setAction("idle")
        animationView.setSpeedMultiplier(preferences.getInt("speed_percent", 100) / 100f)
        val (width, height) = desiredPetSize()
        petParams.width = width
        petParams.height = height
        panelParams.width = panelWidth()
        if (lyricView.text.isNotEmpty()) measureDialogueWindow()
        clampPetAndUpdate()
        refreshWeatherContext()
        updateEnergyMode(SystemClock.uptimeMillis())
    }

    private fun handlePetTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val panelWasVisible = panelHost.visibility == View.VISIBLE
                dragState = DragState(
                    event.rawX,
                    event.rawY,
                    petParams.x,
                    petParams.y,
                    panelWasVisible = panelWasVisible,
                )
                if (panelWasVisible) hidePanel()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val state = dragState ?: return true
                val deltaX = event.rawX - state.downRawX
                val deltaY = event.rawY - state.downRawY
                if (!state.moved && hypot(deltaX.toDouble(), deltaY.toDouble()) >= dp(6)) state.moved = true
                if (state.moved) {
                    petParams.x = state.startX + deltaX.toInt()
                    petParams.y = state.startY + deltaY.toInt()
                    clampPetAndUpdate()
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val state = dragState
                dragState = null
                if (playMode in ARCADE_MODES) {
                    stopPlayMode(restoreTokens = true, message = "被你抓住啦，游戏结束，所有拟态图标都回来啦。")
                    if (state?.moved == true) finishDrag() else Unit
                } else if (state?.moved == true) {
                    finishDrag()
                } else if (state?.panelWasVisible != true) {
                    view.performClick()
                    handlePetTap()
                }
                true
            }

            else -> true
        }
    }

    private fun finishDrag() {
        if (playMode == PlayMode.GRAVITY) {
            velocityX = 0f
            velocityY = 0f
            clampPetAndUpdate()
            preferences.edit().putInt("overlay_x", petParams.x).putInt("overlay_y", petParams.y).apply()
            showMessage("重力重新接管", "一松手，我就会沿着手机当前的重力方向落到边框。", 3_000L)
            return
        }
        if (playMode == PlayMode.BORDER_WALK || playMode == PlayMode.BORDER_RUN) {
            stopPlayMode(restoreTokens = false, message = "巡游暂停，我先待在你放下的位置。")
        }
        if (preferences.getBoolean("snap_to_edge", false)) {
            petParams.x = PetMotionGeometry.snappedWindowX(
                currentWindowCenterX = petParams.x + petParams.width / 2f,
                windowWidth = petParams.width.toFloat(),
                safeArea = safeMotionBounds(),
                edgeGapPx = dp(DRAG_SNAP_GAP_DP).toFloat(),
            ).roundToInt()
        }
        clampPetAndUpdate()
        preferences.edit()
            .putInt("overlay_x", petParams.x)
            .putInt("overlay_y", petParams.y)
            .apply()
        val now = SystemClock.uptimeMillis()
        lastInteractionAt = now
        val rest = preferences.getInt("manual_rest_minutes", 5).coerceIn(1, 60) * 60_000L
        automaticInteractionPause.cancelManualAction()
        automaticInteractionPause.blockFor(now, rest)
        setPetAction(stateMachine.onUserPlaced(now, rest))
        showMessage("就待在这里", "我会在这个位置安静一会儿，不会跟随屏幕点击。", 3_400L)
    }

    private fun handlePetTap() {
        val now = SystemClock.uptimeMillis()
        lastInteractionAt = now
        if (now - lastTapAt <= DOUBLE_TAP_MS) {
            singleTap?.let(handler::removeCallbacks)
            singleTap = null
            lastTapAt = 0L
            automaticInteractionPause.allowManualActionFor(now, MANUAL_ACTION_HOLD_MS)
            setPetAction(stateMachine.onDoubleTap(now))
            showMessage("抓到你啦", loader.randomDialogue(currentPackId(), "double_tap"), 3_200L)
            return
        }
        lastTapAt = now
        val pending = Runnable {
            if (panelHost.visibility == View.VISIBLE) {
                hidePanel()
            } else {
                showControlPanel()
            }
        }
        singleTap = pending
        handler.postDelayed(pending, DOUBLE_TAP_MS)
    }

    private fun showControlPanel() {
        currentTask = null
        panelCard.removeAllViews()
        if (playMode != PlayMode.NORMAL) {
            panelCard.addView(panelTitle("玩法中 · ${playModeTitle(playMode)}"))
            val controls = optionContainer(optionCount = 4)
            controls.addView(panelLink("继续") { _ -> hidePanel() }, optionParams(controls))
            controls.addView(panelLink("切换玩法") { _ -> showGameMenu() }, optionParams(controls))
            controls.addView(panelLink("休息") { _ -> toggleRest() }, optionParams(controls))
            controls.addView(
                panelLink("结束玩法") { _ ->
                    hidePanel()
                    stopPlayMode(restoreTokens = true, message = "玩法已结束，回到普通陪伴模式。")
                },
                optionParams(controls),
            )
            panelCard.addView(controls.withTop(dp(7)))
            showPanel()
            return
        }
        panelCard.addView(panelTitle("快捷互动"))
        val row = optionContainer(optionCount = 4)
        row.addView(panelLink("任务") { _ -> showNextTask(force = true) }, optionParams(row))
        row.addView(panelLink("玩法") { _ -> showGameMenu() }, optionParams(row))
        row.addView(
            panelLink(if (sleepUntil > SystemClock.uptimeMillis()) "结束休息" else "休息") { _ -> toggleRest() },
            optionParams(row),
        )
        row.addView(panelLink("收起桌宠") { _ -> stopSelf() }, optionParams(row))
        panelCard.addView(row.withTop(dp(7)))
        showPanel()
    }

    private fun showGameMenu() {
        currentTask = null
        if (!preferences.getBoolean("game_modes_enabled", true)) {
            hidePanel()
            showMessage("玩法已关闭", "可在应用设置里重新开启高级玩法。", 2_800L)
            return
        }
        panelCard.removeAllViews()
        panelCard.addView(panelTitle("选择玩法"))
        val modes = listOf(
            "重力挑战" to PlayMode.GRAVITY,
            "边框散步" to PlayMode.BORDER_WALK,
            "边框跑酷" to PlayMode.BORDER_RUN,
            "捉迷藏" to PlayMode.HIDE_SEEK,
            "炸弹人" to PlayMode.BOMBER,
            "贪吃蛇" to PlayMode.SNAKE,
        )
        val options = optionContainer(optionCount = modes.size + 1)
        modes.forEach { (title, mode) ->
            val supported = mode.name in gameKit.supportedModes
            options.addView(
                panelLink(if (supported) title else "$title · 未适配", enabled = supported) { source ->
                    animatePanelChoice(source) { startPlayMode(mode, announce = true) }
                },
                optionParams(options),
            )
        }
        options.addView(panelLink("‹ 返回") { _ -> showControlPanel() }, optionParams(options))
        panelCard.addView(options.withTop(dp(5)))
        showPanel()
    }

    private fun showNextTask(force: Boolean, allowGameInvites: Boolean = false) {
        if (!preferences.getBoolean("tasks_enabled", true)) {
            hidePanel()
            showMessage("任务互动已关闭", "可在应用设置中重新开启。", 2_800L)
            return
        }
        val now = System.currentTimeMillis()
        val weatherFresh = weatherCacheUsable(now)
        val task = taskScheduler.next(
            nowMs = now,
            force = force,
            hourOfDay = LocalDateTime.now().hour,
            weatherKind = if (weatherFresh) {
                preferences.getString("weather_kind", "unknown") ?: "unknown"
            } else {
                "unknown"
            },
            allowGameModes = allowGameInvites &&
                preferences.getBoolean("game_modes_enabled", true) &&
                preferences.getBoolean("random_mode_tasks", false),
        )
        if (task == null) {
            hidePanel()
            showMessage("今天先轻松一下", "当前任务都在冷却中，稍后再来看看。", 2_800L)
            return
        }
        val packId = runtimePackId
        preferences.edit()
            .putString(TaskPersistenceKeys.recent(packId), taskScheduler.recentTaskIds().joinToString(","))
            .putBoolean(
                TaskPersistenceKeys.reminderPending(packId, task.id),
                taskScheduler.reminderPending(task.id),
            )
            .apply()
        currentTask = task
        panelCard.removeAllViews()
        panelCard.addView(panelTitle(task.title))
        panelCard.addView(label(task.prompt, 12.5f, 0xFF5F4751.toInt()).withTop(dp(3)))
        val row = optionContainer(task.options.size)
        task.options.forEach { option ->
            val link = panelLink(option.label) { source -> chooseTask(task, option, source) }
            row.addView(link, optionParams(row))
        }
        panelCard.addView(row.withTop(dp(7)))
        val interactionAt = SystemClock.uptimeMillis()
        if (force) automaticInteractionPause.allowManualActionFor(interactionAt, MANUAL_ACTION_HOLD_MS)
        setPetAction(task.action)
        lastInteractionAt = interactionAt
        showPanel()
    }

    private fun chooseTask(task: PetTask, option: TaskOption, source: TextView) {
        source.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        vibrateChoice()
        val now = System.currentTimeMillis()
        taskScheduler.recordChoice(task, option, now)
        reminderPresentationDeferredUntil = 0L
        val packId = runtimePackId
        preferences.edit()
            .putLong(TaskPersistenceKeys.nextEligible(packId, task.id), taskScheduler.nextEligibleAt(task.id))
            .putBoolean(
                TaskPersistenceKeys.reminderPending(packId, task.id),
                taskScheduler.reminderPending(task.id),
            )
            .putInt("task_choices", preferences.getInt("task_choices", 0) + 1)
            .apply()
        currentTask = null
        automaticInteractionPause.allowManualActionFor(
            SystemClock.uptimeMillis(),
            MANUAL_ACTION_HOLD_MS,
        )
        setPetAction(option.action)
        option.playMode?.let { mode ->
            if (preferences.getBoolean("game_modes_enabled", true)) {
                handler.postDelayed({ startPlayMode(mode, announce = true) }, 650L)
            }
        }
        source.isEnabled = false
        panelCard.animate().alpha(0.08f).scaleX(0.94f).scaleY(0.94f).setDuration(220L).start()
        petalBurst.burst(panelHost.width / 2f, (source.top + source.height / 2f).coerceAtLeast(dp(34).toFloat()))
        handler.postDelayed({
            panelCard.animate().cancel()
            panelCard.alpha = 1f
            panelCard.scaleX = 1f
            panelCard.scaleY = 1f
            hidePanel()
            showMessage(task.title, option.response, 4_000L, alwaysShow = true)
        }, 520L)
        lastInteractionAt = SystemClock.uptimeMillis()
    }

    private fun animatePanelChoice(source: TextView, onFinished: () -> Unit) {
        source.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        vibrateChoice()
        source.isEnabled = false
        panelCard.animate().alpha(0.08f).scaleX(0.94f).scaleY(0.94f).setDuration(210L).start()
        petalBurst.burst(
            panelHost.width / 2f,
            (source.top + source.height / 2f).coerceAtLeast(dp(28).toFloat()),
        )
        handler.postDelayed({
            panelCard.animate().cancel()
            panelCard.alpha = 1f
            panelCard.scaleX = 1f
            panelCard.scaleY = 1f
            hidePanel()
            onFinished()
        }, 430L)
    }

    private fun showMessage(
        title: String,
        message: String,
        durationMs: Long,
        alwaysShow: Boolean = false,
    ) {
        if (!alwaysShow && !preferences.getBoolean("dialogue_enabled", true) && currentTask == null) return
        val shown = if (title.isBlank()) message else "【$title】\n$message"
        lyricView.visibility = View.INVISIBLE
        lyricView.text = shown
        lyricView.contentDescription = if (title.isBlank()) message else "$title，$message"
        lyricUntil = SystemClock.uptimeMillis() + durationMs
        measureDialogueWindow()
        positionLyric()
        lyricView.post {
            if (lyricView.text == shown && screenInteractive) {
                measureDialogueWindow()
                positionLyric()
                lyricView.visibility = View.VISIBLE
                lyricView.invalidate()
            }
        }
        handler.postDelayed({
            if (lyricView.text == shown) {
                lyricView.visibility = View.GONE
                lyricView.text = ""
                lyricUntil = 0L
            }
        }, durationMs)
    }

    private fun showPanel() {
        lyricView.visibility = View.GONE
        lyricUntil = 0L
        panelHost.visibility = View.VISIBLE
        val contentWidth = (panelParams.width - panelHost.paddingLeft - panelHost.paddingRight)
            .coerceAtLeast(dp(120))
        panelCard.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight() - dp(12), View.MeasureSpec.AT_MOST),
        )
        panelParams.height = (
            panelCard.measuredHeight + panelHost.paddingTop + panelHost.paddingBottom
        ).coerceIn(dp(72), screenHeight() - dp(12))
        positionPanel()
    }

    private fun hidePanel() {
        if (currentTask?.let { taskScheduler.reminderPending(it.id) } == true) {
            reminderPresentationDeferredUntil = maxOf(
                reminderPresentationDeferredUntil,
                SystemClock.uptimeMillis() + REMINDER_RETRY_DELAY_MS,
            )
        }
        panelHost.visibility = View.GONE
        currentTask = null
    }

    private fun toggleRest() {
        if (playMode != PlayMode.NORMAL) stopPlayMode(restoreTokens = true, message = null)
        val now = SystemClock.uptimeMillis()
        hidePanel()
        if (sleepUntil > now) {
            sleepUntil = 0L
            automaticInteractionPause.clear()
            lastInteractionAt = now
            stateMachine.resumeAutomaticActivity(now)
            showMessage("休息结束", "我回来啦，继续陪着你。", 2_700L)
        } else {
            val minutes = preferences.getInt("overlay_rest_minutes", 15).coerceIn(5, 120)
            sleepUntil = now + minutes * 60_000L
            automaticInteractionPause.cancelManualAction()
            automaticInteractionPause.blockUntil(sleepUntil)
            lastInteractionAt = now
            setPetAction(stateMachine.onUserPlaced(now, minutes * 60_000L))
            showMessage("安静休息", "接下来 $minutes 分钟保持低帧率原地休息。", 3_200L)
        }
        updateEnergyMode(now)
    }

    private fun startPlayMode(requested: PlayMode, announce: Boolean) {
        val mode = if (preferences.getBoolean("game_modes_enabled", true)) requested else PlayMode.NORMAL
        if (mode == PlayMode.NORMAL) {
            stopPlayMode(restoreTokens = true, message = if (announce) "已回到普通陪伴模式。" else null)
            return
        }
        if (mode.name !in gameKit.supportedModes) {
            stopPlayMode(
                restoreTokens = true,
                message = if (announce) "当前资源包没有声明支持${playModeTitle(mode)}，已保持普通陪伴。" else null,
            )
            return
        }
        sensorManager.unregisterListener(this)
        gravitySensorRegistered = false
        gameField.stop(restore = false)
        playMode = mode
        preferences.edit().putString("game_mode", mode.name).apply()
        lastMotionAt = 0L
        velocityX = 0f
        velocityY = 0f
        borderBounds = null
        gameEndsAt = 0L
        petHost.alpha = 1f
        hidePanel()
        val playStartedAt = SystemClock.uptimeMillis()
        lastInteractionAt = playStartedAt
        sleepUntil = 0L
        automaticInteractionPause.clear()
        when (mode) {
            PlayMode.GRAVITY -> {
                applyPetVisualMode(avatar = false)
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                gravityX = 0f
                gravityY = DEFAULT_FALLBACK_GRAVITY
                gravityFallbackNotified = false
                val sensorReady = registerGravitySensorIfNeeded(announceFailure = false)
                setPetAction("idle")
                if (announce) showMessage("重力模式", "拖动后松手试试；我会感应手机方向并落到对应边框。", 4_200L, true)
                if (!sensorReady && announce) {
                    handler.postDelayed({ notifyGravityFallback() }, SENSOR_FALLBACK_MESSAGE_DELAY_MS)
                }
            }

            PlayMode.BORDER_WALK, PlayMode.BORDER_RUN -> {
                applyPetVisualMode(avatar = false)
                borderProgress = currentBorderProgress()
                borderBounds = safeMotionBounds()
                setPetAction(if (mode == PlayMode.BORDER_RUN) "run" else "walk")
                if (announce) {
                    showMessage(
                        if (mode == PlayMode.BORDER_RUN) "边框跑酷" else "边框散步",
                        "我会沿四条屏幕边巡游；抓住并拖动就能暂停。",
                        3_800L,
                        true,
                    )
                }
            }

            PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE -> {
                // Snake uses the portrait as its head while the board draws a growing body.
                // Bomber keeps a compact full-body character so it is no longer just a moving head.
                applyPetVisualMode(
                    avatar = mode != PlayMode.BOMBER,
                    compactBody = mode == PlayMode.BOMBER,
                )
                gameField.start(mode, gameKit)
                setGameFieldTouchability(gameField.wantsTouchInput)
                if (!openHomeForArcade()) {
                    stopPlayMode(
                        restoreTokens = true,
                        message = "无法打开系统主页，本轮安全拟态玩法已取消。",
                    )
                    return
                }
                gameEndsAt = SystemClock.uptimeMillis() + when (mode) {
                    PlayMode.HIDE_SEEK -> 50_000L
                    PlayMode.BOMBER -> 65_000L
                    else -> 80_000L
                }
                setPetAction("run")
                val text = when (mode) {
                    PlayMode.HIDE_SEEK -> "我会在安全游戏场里捉迷藏，抓住我就算你赢。"
                    PlayMode.BOMBER -> "点选可破坏目标或当前位置放炸弹；爆炸只作用于游戏场元素。"
                    else -> "在安全游戏场滑动转向，吃掉目标、增长身体并避开障碍。"
                }
                if (announce) showMessage(playModeTitle(mode), text, 4_600L, true)
            }

            PlayMode.NORMAL -> Unit
        }
        animationView.restartAnimation()
        updateEnergyMode(playStartedAt)
        scheduleMotionLoop()
    }

    private fun stopPlayMode(restoreTokens: Boolean, message: String?) {
        sensorManager.unregisterListener(this)
        gravitySensorRegistered = false
        handler.removeCallbacks(motionLoop)
        val wasArcade = playMode in ARCADE_MODES
        playMode = PlayMode.NORMAL
        preferences.edit().putString("game_mode", PlayMode.NORMAL.name).apply()
        gameEndsAt = 0L
        lastMotionAt = 0L
        velocityX = 0f
        velocityY = 0f
        borderBounds = null
        setGameFieldTouchability(false)
        if (wasArcade || gameField.visibility == View.VISIBLE) gameField.stop(restoreTokens)
        petHost.alpha = 1f
        animationView.setAvatarMode(false)
        animationView.setMotionTransform(0f)
        applyPetVisualMode(avatar = false)
        setPetAction("idle")
        message?.let { showMessage("游戏收好啦", it, 3_600L, true) }
    }

    private fun applyPetVisualMode(avatar: Boolean, compactBody: Boolean = false) {
        val oldCenterX = petParams.x + petParams.width / 2
        val oldCenterY = petParams.y + petParams.height / 2
        val (width, height) = when {
            avatar -> dp(76) to dp(76)
            compactBody -> dp(92) to dp(104)
            else -> desiredPetSize()
        }
        petParams.width = width
        petParams.height = height
        petParams.x = oldCenterX - width / 2
        petParams.y = oldCenterY - height / 2
        animationView.setAvatarMode(avatar)
        animationView.setMotionTransform(0f)
        clampPetAndUpdate()
    }

    private fun handleArcadeInteraction(event: ArcadeInteractionEvent) {
        when (event.type) {
            ArcadeInteractionType.INPUT_REQUIRED -> {
                setGameFieldTouchability(gameField.wantsTouchInput)
                showMessage("玩法操作", event.message, 3_200L, alwaysShow = true)
            }

            ArcadeInteractionType.INPUT_ACCEPTED ->
                showMessage("操作生效", event.message, 1_500L, alwaysShow = true)

            ArcadeInteractionType.INPUT_REJECTED ->
                showMessage("换个方向", event.message, 1_800L, alwaysShow = true)

            ArcadeInteractionType.RESTORING,
            ArcadeInteractionType.FINISHED,
            -> setGameFieldTouchability(false)
        }
    }

    private fun setGameFieldTouchability(touchable: Boolean) {
        if (!gameFieldAdded) return
        gameFieldParams.flags = if (touchable) {
            gameFieldParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            gameFieldParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Explicit games can be vivid because they consume their own gestures. The passive layer
        // stays translucent so it never looks like a second launcher or obscures real content.
        gameFieldParams.alpha = if (touchable) INTERACTIVE_GAME_WINDOW_ALPHA else PASS_THROUGH_WINDOW_ALPHA
        gameField.isClickable = touchable
        runCatching { windowManager.updateViewLayout(gameField, gameFieldParams) }
    }

    private fun updatePlayMotion(now: Long) {
        if (lastMotionAt == 0L) {
            lastMotionAt = now
            return
        }
        val deltaSeconds = ((now - lastMotionAt).coerceIn(1L, 80L)) / 1_000f
        lastMotionAt = now
        if (gameEndsAt > 0L && now >= gameEndsAt) {
            stopPlayMode(true, "时间到啦，这一局先玩到这里。")
            return
        }
        when (playMode) {
            PlayMode.GRAVITY -> updateGravityMotion(deltaSeconds)
            PlayMode.BORDER_WALK, PlayMode.BORDER_RUN -> updateBorderMotion(deltaSeconds)
            PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE -> updateArcadeMotion(deltaSeconds, now)
            PlayMode.NORMAL -> Unit
        }
    }

    private fun scheduleMotionLoop() {
        handler.removeCallbacks(motionLoop)
        if (petAdded && screenInteractive && playMode != PlayMode.NORMAL) handler.post(motionLoop)
    }

    private fun registerGravitySensorIfNeeded(announceFailure: Boolean = true): Boolean {
        if (!screenInteractive || playMode != PlayMode.GRAVITY) return false
        sensorManager.unregisterListener(this)
        gravitySensorRegistered = gravitySensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } == true
        if (!gravitySensorRegistered) {
            gravityX = 0f
            gravityY = DEFAULT_FALLBACK_GRAVITY
            if (announceFailure) notifyGravityFallback()
        }
        return gravitySensorRegistered
    }

    private fun notifyGravityFallback() {
        if (playMode != PlayMode.GRAVITY || gravitySensorRegistered || gravityFallbackNotified) return
        gravityFallbackNotified = true
        showMessage(
            "重力传感器不可用",
            "已自动切换为屏幕向下的模拟重力；拖拽、落地和边界碰撞仍可正常体验。",
            4_000L,
            alwaysShow = true,
        )
    }

    private fun updateGravityMotion(deltaSeconds: Float) {
        var gx = gravityX
        var gy = gravityY
        if (hypot(gx.toDouble(), gy.toDouble()) < 0.8) {
            gx = 0f
            gy = 5.5f
        }
        velocityX = (velocityX + gx * dp(21) * deltaSeconds) * 0.992f
        velocityY = (velocityY + gy * dp(21) * deltaSeconds) * 0.992f
        val groundAnchor = animationView.groundAnchorInView()
        var anchorX = petParams.x + groundAnchor.x + velocityX * deltaSeconds
        var anchorY = petParams.y + groundAnchor.y + velocityY * deltaSeconds
        val safeBounds = safeMotionBounds()
        val minX = safeBounds.left
        val minY = safeBounds.top
        val maxX = safeBounds.right
        val maxY = safeBounds.bottom
        if (anchorX < minX || anchorX > maxX) {
            anchorX = anchorX.coerceIn(minX, maxX)
            velocityX = settledBoundaryVelocity(velocityX)
        }
        if (anchorY < minY || anchorY > maxY) {
            anchorY = anchorY.coerceIn(minY, maxY)
            velocityY = settledBoundaryVelocity(velocityY)
        }
        petParams.x = (anchorX - groundAnchor.x).roundToInt()
        petParams.y = (anchorY - groundAnchor.y).roundToInt()
        val speed = hypot(velocityX.toDouble(), velocityY.toDouble()).toFloat()
        setPetAction(if (speed > dp(38)) "run" else "idle")
        val surfaceRotation = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat() - 90f
        val velocityRotation = if (speed > dp(4)) {
            Math.toDegrees(atan2(velocityY.toDouble(), velocityX.toDouble())).toFloat()
        } else {
            surfaceRotation
        }
        setPetMotionTransform(
            surfaceRotationDegrees = surfaceRotation,
            velocityRotationDegrees = velocityRotation,
        )
        updatePetWindow()
    }

    private fun settledBoundaryVelocity(velocity: Float): Float =
        if (abs(velocity) >= dp(56)) -velocity * GRAVITY_RESTITUTION else 0f

    private fun updateBorderMotion(deltaSeconds: Float) {
        setPetAction(if (playMode == PlayMode.BORDER_RUN) "run" else "walk")
        val safeBounds = safeMotionBounds()
        borderBounds?.takeIf { it != safeBounds }?.let { previousBounds ->
            borderProgress = PetMotionGeometry.remapBorderProgress(
                progressPx = borderProgress,
                oldBounds = previousBounds,
                newBounds = safeBounds,
            )
        }
        borderBounds = safeBounds
        val perimeter = PetMotionGeometry.borderPerimeter(safeBounds)
        if (perimeter <= 0f) return
        val speed = if (playMode == PlayMode.BORDER_RUN) dp(125).toFloat() else dp(62).toFloat()
        borderProgress = (borderProgress + speed * deltaSeconds) % perimeter
        val pose = PetMotionGeometry.borderPose(borderProgress, safeBounds)
        animationView.setMotionTransform(
            surfaceRotationDegrees = pose.surfaceRotationDegrees,
            velocityRotationDegrees = pose.velocityRotationDegrees,
            surfaceFacingLeft = pose.faceLocalLeft,
        )
        val groundAnchor = animationView.groundAnchorInView()
        petParams.x = (pose.foot.x - groundAnchor.x).roundToInt()
        petParams.y = (pose.foot.y - groundAnchor.y).roundToInt()
        updatePetWindow()
    }

    private fun updateArcadeMotion(deltaSeconds: Float, now: Long) {
        val centerX = petParams.x + petParams.width / 2f
        val centerY = petParams.y + petParams.height / 2f
        val command = gameField.nextMotion(centerX, centerY)
        if (command.completed) {
            val message = when (command.event) {
                "snake-cleared" -> "拟态图标吃完啦，现在一个个吐回原位。"
                "bomber-cleared" -> "寻路完成，所有安全拟态方块正在复原。"
                else -> "这一轮捉迷藏结束，被你找到的感觉也不错。"
            }
            stopPlayMode(true, message)
            return
        }
        val speed = when (playMode) {
            PlayMode.HIDE_SEEK -> dp(145).toFloat()
            PlayMode.BOMBER -> dp(105).toFloat()
            else -> dp(92).toFloat()
        }
        petParams.x = (petParams.x + command.dx * speed * deltaSeconds).roundToInt()
            .coerceIn(0, (screenWidth() - petParams.width).coerceAtLeast(0))
        petParams.y = (petParams.y + command.dy * speed * deltaSeconds).roundToInt()
            .coerceIn(0, (screenHeight() - petParams.height).coerceAtLeast(0))
        val velocityRotation = if (abs(command.dx) + abs(command.dy) > 0.001f) {
            Math.toDegrees(atan2(command.dy.toDouble(), command.dx.toDouble())).toFloat()
        } else {
            0f
        }
        animationView.setMotionTransform(
            surfaceRotationDegrees = 0f,
            velocityRotationDegrees = velocityRotation,
            surfaceFacingLeft = command.dx < 0f,
        )
        petHost.alpha = if (playMode == PlayMode.HIDE_SEEK) {
            (0.48f + 0.44f * ((cos(now / 380.0) + 1.0) / 2.0)).toFloat()
        } else {
            1f
        }
        updatePetWindow()
    }

    private fun currentBorderProgress(): Float {
        val groundAnchor = animationView.groundAnchorInView()
        return PetMotionGeometry.borderProgress(
            point = MotionPoint(petParams.x + groundAnchor.x, petParams.y + groundAnchor.y),
            bounds = safeMotionBounds(),
        )
    }

    private fun updatePetWindow() {
        runCatching { windowManager.updateViewLayout(petHost, petParams) }
        positionLyric()
        if (panelHost.visibility == View.VISIBLE) positionPanel()
    }

    private fun refreshWeatherContext(force: Boolean = false) {
        if (!preferences.getBoolean("dynamic_weather_enabled", true)) return
        nextWeatherRefreshAt = SystemClock.uptimeMillis() + WEATHER_REFRESH_MS
        val city = preferences.getString("weather_city", "北京") ?: "北京"
        weatherProvider.refresh(city, force) { result ->
            if (result.isFailure) {
                nextWeatherRefreshAt = SystemClock.uptimeMillis() + WEATHER_RETRY_MS
            }
            if (force && result.isSuccess && weatherCacheUsable()) {
                val weather = result.getOrNull() ?: return@refresh
                showMessage("天气已更新", "${weather.city}现在${weather.description}，约 ${weather.temperature.roundToInt()}℃。", 3_400L)
            }
        }
    }

    private fun weatherCacheUsable(nowMs: Long = System.currentTimeMillis()): Boolean =
        WeatherCachePolicy.isUsable(
            dynamicWeatherEnabled = preferences.getBoolean("dynamic_weather_enabled", true),
            configuredCity = preferences.getString("weather_city", "北京").orEmpty(),
            cacheQuery = preferences.getString("weather_cache_query", "").orEmpty(),
            updatedAtMs = preferences.getLong("weather_updated_at", 0L),
            nowMs = nowMs,
        )

    private fun maybeShowContextDialogue(now: Long) {
        if (preferences.getBoolean("dynamic_weather_enabled", true) && now >= nextWeatherRefreshAt) {
            refreshWeatherContext()
        }
        if (!screenInteractive || playMode != PlayMode.NORMAL || now < nextContextDialogueAt) return
        if (automaticInteractionPause.isBlocked(now)) return
        if (panelHost.visibility == View.VISIBLE || lyricView.visibility == View.VISIBLE) return
        if (now - lastInteractionAt < 25_000L) return
        val inactivityMs = preferences.getInt("inactivity_sleep_minutes", 10)
            .coerceIn(1, 60) * 60_000L
        if (now - lastInteractionAt >= inactivityMs) return
        val date = LocalDateTime.now()
        val weatherFresh = weatherCacheUsable()
        val event = when {
            weatherFresh && Random.nextInt(100) < 42 -> "weather"
            date.hour in 5..10 -> "morning"
            date.hour in 11..13 -> "lunch"
            date.hour in 18..22 -> "evening"
            date.hour >= 23 || date.hour < 5 -> "late_night"
            else -> "idle"
        }
        showMessage("悄悄话", loader.randomDialogue(currentPackId(), event), 4_000L)
        nextContextDialogueAt = now + randomContextDelay()
    }

    private fun randomContextDelay(): Long {
        val frequency = runCatching {
            InteractionFrequency.valueOf(preferences.getString("interaction_frequency", "STANDARD") ?: "STANDARD")
        }.getOrDefault(InteractionFrequency.STANDARD)
        val range = when (frequency) {
            InteractionFrequency.GENTLE -> 18L..35L
            InteractionFrequency.STANDARD -> 7L..16L
            InteractionFrequency.ACTIVE -> 3L..8L
        }
        return Random.nextLong(range.first, range.last + 1L) * 60_000L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (playMode != PlayMode.GRAVITY) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val screenGravity = PetMotionGeometry.sensorGravityToScreen(
            sensorX = event.values.getOrElse(0) { 0f },
            sensorY = event.values.getOrElse(1) { DEFAULT_FALLBACK_GRAVITY },
            displayRotationQuarterTurns = rotation,
        )
        gravityX = gravityX * 0.78f + screenGravity.x * 0.22f
        gravityY = gravityY * 0.78f + screenGravity.y * 0.22f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun playModeTitle(mode: PlayMode): String = when (mode) {
        PlayMode.GRAVITY -> "重力模式"
        PlayMode.BORDER_WALK -> "边框散步"
        PlayMode.BORDER_RUN -> "边框跑酷"
        PlayMode.HIDE_SEEK -> "桌面捉迷藏"
        PlayMode.BOMBER -> "炸弹人拟态"
        PlayMode.SNAKE -> "贪吃蛇拟态"
        PlayMode.NORMAL -> "普通陪伴"
    }

    private fun openHomeForArcade(): Boolean {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(home) }.isSuccess
    }

    private fun updatePetState(now: Long) {
        if (playMode != PlayMode.NORMAL) return
        when (automaticInteractionPause.frameDirective(now)) {
            AutomaticFrameDirective.KEEP_MANUAL_ACTION -> return
            AutomaticFrameDirective.FORCE_REST_IDLE -> {
                setPetAction("idle")
                return
            }

            AutomaticFrameDirective.ALLOW_AUTOMATIC -> Unit
        }
        val inactivityMs = preferences.getInt("inactivity_sleep_minutes", 10).coerceIn(1, 60) * 60_000L
        if (now < sleepUntil || now - lastInteractionAt >= inactivityMs) {
            setPetAction("idle")
            return
        }
        val style = runCatching {
            InteractionStyle.valueOf(preferences.getString("interaction_style", "DAILY") ?: "DAILY")
        }.getOrDefault(InteractionStyle.DAILY)
        val hour = if (preferences.getBoolean("quiet_hours_enabled", true)) LocalDateTime.now().hour else 12
        setPetAction(stateMachine.tick(now, hour, style))
    }

    private fun updateEnergyMode(now: Long) {
        val profile = runCatching {
            EnergyProfile.valueOf(preferences.getString("energy_profile", "ADAPTIVE") ?: "ADAPTIVE")
        }.getOrDefault(EnergyProfile.ADAPTIVE)
        val inactivityMs = preferences.getInt("inactivity_sleep_minutes", 10).coerceIn(1, 60) * 60_000L
        val restDirective = automaticInteractionPause.frameDirective(now)
        val level = when {
            !screenInteractive -> PetActivityLevel.SLEEP
            restDirective == AutomaticFrameDirective.KEEP_MANUAL_ACTION -> PetActivityLevel.INTERACTING
            restDirective == AutomaticFrameDirective.FORCE_REST_IDLE -> PetActivityLevel.SLEEP
            now < sleepUntil || now - lastInteractionAt >= inactivityMs -> PetActivityLevel.SLEEP
            now - lastInteractionAt < 8_000L -> PetActivityLevel.INTERACTING
            currentAction == "walk" || currentAction == "run" -> PetActivityLevel.ACTIVE
            else -> PetActivityLevel.IDLE
        }
        animationView.setTargetFrameRate(EnergyPolicy.frameRate(profile, level, screenInteractive))
    }

    private fun maybeShowAutomaticTask(now: Long) {
        if (playMode != PlayMode.NORMAL) return
        if (!screenInteractive || currentTask != null || panelHost.visibility == View.VISIBLE) return
        if (!preferences.getBoolean("tasks_enabled", true)) return
        if (automaticInteractionPause.isBlocked(now)) return
        val allowGameModes = preferences.getBoolean("game_modes_enabled", true) &&
            preferences.getBoolean("random_mode_tasks", false)
        val reminderDue = taskScheduler.hasDueReminder(
            nowMs = System.currentTimeMillis(),
            allowGameModes = allowGameModes,
        )
        if (reminderDue) {
            if (now < reminderPresentationDeferredUntil) return
            lastAutomaticTaskAt = now
            showNextTask(force = false, allowGameInvites = allowGameModes)
            return
        }
        val inactivityMs = preferences.getInt("inactivity_sleep_minutes", 10).coerceIn(1, 60) * 60_000L
        if (now < sleepUntil || now - lastInteractionAt >= inactivityMs) return
        val date = LocalDateTime.now()
        if (preferences.getBoolean("quiet_hours_enabled", true) && (date.hour >= 23 || date.hour < 7)) return
        val frequency = runCatching {
            InteractionFrequency.valueOf(
                preferences.getString("interaction_frequency", "STANDARD") ?: "STANDARD",
            )
        }.getOrDefault(InteractionFrequency.STANDARD)
        if (now - lastAutomaticTaskAt < frequency.automaticTaskMinutes * 60_000L) return
        if (now - lastInteractionAt < AUTO_TASK_IDLE_REQUIRED_MS) return
        lastAutomaticTaskAt = now
        showNextTask(force = false, allowGameInvites = allowGameModes)
    }

    private fun setPetAction(action: String, restart: Boolean = false) {
        if (action == currentAction && !restart) return
        val oldAnchor = if (petAdded) animationView.groundAnchorInView() else null
        val oldAnchorX = oldAnchor?.x
        val oldAnchorY = oldAnchor?.y
        currentAction = action
        animationView.setAction(action, restart = restart)
        if (petAdded && oldAnchorX != null && oldAnchorY != null) {
            val newAnchor = animationView.groundAnchorInView()
            petParams.x += (oldAnchorX - newAnchor.x).roundToInt()
            petParams.y += (oldAnchorY - newAnchor.y).roundToInt()
            updatePetWindow()
        }
    }

    private fun setPetMotionTransform(
        surfaceRotationDegrees: Float,
        velocityRotationDegrees: Float,
        surfaceFacingLeft: Boolean = false,
    ) {
        if (!petAdded) {
            animationView.setMotionTransform(
                surfaceRotationDegrees,
                velocityRotationDegrees,
                surfaceFacingLeft,
            )
            return
        }
        val oldAnchor = animationView.groundAnchorInView()
        val oldAnchorX = oldAnchor.x
        val oldAnchorY = oldAnchor.y
        animationView.setMotionTransform(
            surfaceRotationDegrees,
            velocityRotationDegrees,
            surfaceFacingLeft,
        )
        val newAnchor = animationView.groundAnchorInView()
        petParams.x += (oldAnchorX - newAnchor.x).roundToInt()
        petParams.y += (oldAnchorY - newAnchor.y).roundToInt()
    }

    private fun desiredPetSize(): Pair<Int, Int> {
        val percent = preferences.getInt("size_percent", 78).coerceIn(45, 100) / 100f
        val width = (dp(220) * percent).roundToInt().coerceAtLeast(dp(92))
        return width to (width * 1.12f).roundToInt()
    }

    private fun clampPetAndUpdate() {
        if (!petAdded) return
        // Keep only about 38% of the pet visible at the extreme edge; action overflow may clip.
        val minX = -(petParams.width * 0.62f).roundToInt()
        val maxX = screenWidth() - (petParams.width * 0.38f).roundToInt()
        val minY = -(petParams.height * 0.28f).roundToInt()
        val maxY = screenHeight() - (petParams.height * 0.40f).roundToInt()
        petParams.x = petParams.x.coerceIn(minX, maxX.coerceAtLeast(minX))
        petParams.y = petParams.y.coerceIn(minY, maxY.coerceAtLeast(minY))
        runCatching { windowManager.updateViewLayout(petHost, petParams) }
        positionLyric()
        if (panelHost.visibility == View.VISIBLE) positionPanel()
    }

    private fun positionLyric() {
        if (!lyricAdded) return
        val safe = safeMotionBounds()
        val width = lyricParams.width.coerceAtLeast(dp(96))
        val height = lyricParams.height.coerceAtLeast(dp(48))
        val minX = (safe.left + dp(3)).roundToInt()
        val maxX = (safe.right - width - dp(3)).roundToInt().coerceAtLeast(minX)
        lyricParams.x = (petParams.x + petParams.width / 2 - width / 2).coerceIn(minX, maxX)
        val minY = (safe.top + dp(2)).roundToInt()
        val maxY = (safe.bottom - height - dp(3)).roundToInt().coerceAtLeast(minY)
        val above = petParams.y - height + dp(7)
        val below = petParams.y + petParams.height - dp(10)
        val tailAtBottom = above >= minY
        lyricParams.y = (if (tailAtBottom) above else below).coerceIn(minY, maxY)
        lyricView.setTail(
            atBottom = tailAtBottom,
            localTargetX = (petParams.x + petParams.width / 2f) - lyricParams.x,
        )
        runCatching { windowManager.updateViewLayout(lyricView, lyricParams) }
    }

    private fun positionPanel() {
        if (!panelAdded || panelHost.visibility != View.VISIBLE) return
        val safe = safeMotionBounds()
        val height = panelParams.height.coerceAtLeast(dp(72))
        val minX = (safe.left + dp(4)).roundToInt()
        val maxX = (safe.right - panelParams.width - dp(4)).roundToInt().coerceAtLeast(minX)
        panelParams.x = (petParams.x + petParams.width / 2 - panelParams.width / 2).coerceIn(minX, maxX)
        val minY = (safe.top + dp(3)).roundToInt()
        val maxY = (safe.bottom - height - dp(4)).roundToInt().coerceAtLeast(minY)
        val above = petParams.y - height - dp(3)
        val below = petParams.y + petParams.height - dp(8)
        val tailAtBottom = above >= minY
        panelParams.y = (if (tailAtBottom) above else below).coerceIn(minY, maxY)
        panelBubble.setTail(
            atBottom = tailAtBottom,
            localTargetX = (petParams.x + petParams.width / 2f) - panelParams.x - panelHost.paddingLeft,
        )
        if (tailAtBottom) {
            panelCard.setPadding(dp(11), dp(9), dp(11), dp(17))
        } else {
            panelCard.setPadding(dp(11), dp(17), dp(11), dp(9))
        }
        runCatching { windowManager.updateViewLayout(panelHost, panelParams) }
    }

    private fun measureDialogueWindow() {
        val safeWidth = safeMotionBounds().width.roundToInt().coerceAtLeast(dp(96))
        val maxWidth = minOf((safeWidth * 0.45f).roundToInt(), dp(210)).coerceAtLeast(dp(64))
        val minWidth = minOf(dp(96), maxWidth)
        lyricView.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(144), View.MeasureSpec.AT_MOST),
        )
        lyricParams.width = lyricView.measuredWidth.coerceIn(minWidth, maxWidth)
        lyricParams.height = lyricView.measuredHeight.coerceIn(dp(48), dp(144))
    }

    private fun panelWidth(): Int = minOf(screenWidth() - dp(12), dp(184)).coerceAtLeast(dp(120))

    private fun optionContainer(@Suppress("UNUSED_PARAMETER") optionCount: Int = 3): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            dividerPadding = 0
        }

    private fun optionParams(@Suppress("UNUSED_PARAMETER") parent: LinearLayout): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(1)
        }

    private fun panelTitle(value: String): TextView = label(
        value = value,
        size = 13f,
        color = 0xFF3F3036.toInt(),
        bold = true,
    ).apply {
        contentDescription = value
        setPadding(dp(5), 0, dp(5), dp(2))
    }

    private fun panelLink(
        value: String,
        enabled: Boolean = true,
        action: (TextView) -> Unit,
    ): TextView = TextView(this).apply {
        text = if (value.startsWith("‹")) value else "›  $value"
        textSize = 13.5f
        setTextColor(if (enabled) 0xFFB43C68.toInt() else 0xFF9D9297.toInt())
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(34)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        contentDescription = value
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.72f
        background = RippleDrawable(
            ColorStateList.valueOf(0x33C9577D),
            null,
            rounded(Color.WHITE, dp(7).toFloat()),
        )
        if (enabled) setOnClickListener { action(this) }
    }

    private fun vibrateChoice() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(34L, 90))
    }

    private fun setOverlayVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        petHost.visibility = visibility
        if (!visible) {
            lyricView.visibility = View.INVISIBLE
            panelHost.visibility = View.INVISIBLE
            currentTask = null
        } else {
            lyricView.visibility = if (SystemClock.uptimeMillis() < lyricUntil) View.VISIBLE else View.GONE
            panelHost.visibility = View.GONE
        }
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also(windowManager.defaultDisplay::getRealSize)
    }

    private fun screenWidth(): Int = screenSize().x
    private fun screenHeight(): Int = screenSize().y

    /**
     * All overlay LayoutParams use full-display coordinates. Automatic physics then targets this
     * safe rectangle, which combines stable system bars and asymmetric display-cutout insets.
     */
    private fun safeMotionBounds(): MotionRect {
        val size = screenSize()
        val displayBounds = MotionRect(0f, 0f, size.x.toFloat(), size.y.toFloat())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowInsets = windowManager.currentWindowMetrics.windowInsets
            val bars = windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            val cutout = windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout())
            return PetMotionGeometry.safeArea(
                display = displayBounds,
                systemBars = MotionInsets(
                    bars.left.toFloat(),
                    bars.top.toFloat(),
                    bars.right.toFloat(),
                    bars.bottom.toFloat(),
                ),
                displayCutout = MotionInsets(
                    cutout.left.toFloat(),
                    cutout.top.toFloat(),
                    cutout.right.toFloat(),
                    cutout.bottom.toFloat(),
                ),
            )
        }
        val rootInsets = petHost.rootWindowInsets
        @Suppress("DEPRECATION")
        val bars = rootInsets?.let {
            MotionInsets(
                it.stableInsetLeft.toFloat(),
                it.stableInsetTop.toFloat(),
                it.stableInsetRight.toFloat(),
                it.stableInsetBottom.toFloat(),
            )
        } ?: MotionInsets()
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            rootInsets?.displayCutout?.let {
                MotionInsets(
                    it.safeInsetLeft.toFloat(),
                    it.safeInsetTop.toFloat(),
                    it.safeInsetRight.toFloat(),
                    it.safeInsetBottom.toFloat(),
                )
            } ?: MotionInsets()
        } else {
            MotionInsets()
        }
        return PetMotionGeometry.safeArea(displayBounds, bars, cutout)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌宠悬浮服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示悬浮桌宠，并提供休息与停止入口"
                setShowBadge(false)
            },
        )
    }

    private fun startInForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayPetService::class.java).setAction(ACTION_TOGGLE_SLEEP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, OverlayPetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("我家女友·悬浮陪伴中")
            .setContentText("可拖动人物；点击通知返回设置")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "休息/唤醒", restIntent).build())
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.1f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun currentPackId(): String =
        preferences.getString("pack_id", "girlfriend-classic") ?: "girlfriend-classic"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun <T : View> T.withTop(value: Int): T = apply {
        val params = layoutParams as? ViewGroup.MarginLayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = value
        layoutParams = params
    }

    private data class DragState(
        val downRawX: Float,
        val downRawY: Float,
        val startX: Int,
        val startY: Int,
        val panelWasVisible: Boolean,
        var moved: Boolean = false,
    )

    companion object {
        const val ACTION_START = "com.sweetgirlfriend.pet.overlay.START"
        const val ACTION_STOP = "com.sweetgirlfriend.pet.overlay.STOP"
        const val ACTION_SHOW_TASK = "com.sweetgirlfriend.pet.overlay.SHOW_TASK"
        const val ACTION_TOGGLE_SLEEP = "com.sweetgirlfriend.pet.overlay.TOGGLE_SLEEP"
        const val ACTION_REFRESH = "com.sweetgirlfriend.pet.overlay.REFRESH"
        const val ACTION_SET_PLAY_MODE = "com.sweetgirlfriend.pet.overlay.SET_PLAY_MODE"
        const val EXTRA_PLAY_MODE = "play_mode"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 2202
        private const val STATE_INTERVAL_MS = 1_000L
        private const val SCREEN_OFF_STATE_INTERVAL_MS = 30_000L
        private const val DOUBLE_TAP_MS = 280L
        private const val MANUAL_ACTION_HOLD_MS = 4_000L
        private const val AUTO_TASK_IDLE_REQUIRED_MS = 90_000L
        private const val MOTION_INTERVAL_MS = 33L
        private const val WEATHER_REFRESH_MS = WeatherCachePolicy.MAX_AGE_MS
        private const val WEATHER_RETRY_MS = 30 * 60_000L
        private const val SENSOR_FALLBACK_MESSAGE_DELAY_MS = 850L
        private const val REMINDER_RETRY_DELAY_MS = 60_000L
        private const val TASK_NAMESPACE_MIGRATED = "task_state_namespace_v2_migrated"
        private const val LEGACY_TASK_RECENT_KEY = "task_recent_ids"
        private const val DEFAULT_FALLBACK_GRAVITY = 6f
        private const val GRAVITY_RESTITUTION = 0.18f
        // Automatic edge snap keeps the complete overlay window inside the safe display area.
        private const val DRAG_SNAP_GAP_DP = 8
        private const val PASS_THROUGH_WINDOW_ALPHA = 0.55f
        private const val INTERACTIVE_GAME_WINDOW_ALPHA = 0.92f
        private val ARCADE_MODES = setOf(PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE)
    }
}

/**
 * Compact manga speech balloon shared by dialogue and interaction overlays.
 *
 * [localTargetX] is updated whenever the pet or window moves, so the tail keeps pointing at the
 * visible character instead of becoming a decorative triangle with no spatial relationship.
 */
private class MangaBubbleDrawable(private val density: Float) : Drawable() {
    private val path = Path()
    private val tailPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFCFE.toInt()
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4B3740.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF91B2.toInt()
        style = Paint.Style.FILL
    }
    private val radius = 10f * density
    private val tailHeight = 9f * density
    private val tailHalfWidth = 8f * density
    private val shadowOffset = 1.5f * density
    private var atBottom = true
    private var localTargetX = 0f

    fun setTail(atBottom: Boolean, localTargetX: Float) {
        if (this.atBottom == atBottom && this.localTargetX == localTargetX) return
        this.atBottom = atBottom
        this.localTargetX = localTargetX
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val halfStroke = outlinePaint.strokeWidth / 2f
        val left = bounds.left + halfStroke
        val top = bounds.top + halfStroke
        val right = bounds.right - halfStroke - shadowOffset
        val bottom = bounds.bottom - halfStroke - shadowOffset
        if (right <= left || bottom <= top) return
        val bodyTop = if (atBottom) top else top + tailHeight
        val bodyBottom = if (atBottom) bottom - tailHeight else bottom
        val body = RectF(left, bodyTop, right, bodyBottom)
        val tailX = localTargetX.coerceIn(left + radius, right - radius)

        path.reset()
        path.addRoundRect(body, radius, radius, Path.Direction.CW)
        tailPath.reset()
        if (atBottom) {
            tailPath.moveTo(tailX - tailHalfWidth, bodyBottom - halfStroke)
            tailPath.lineTo(tailX, bottom)
            tailPath.lineTo(tailX + tailHalfWidth, bodyBottom - halfStroke)
        } else {
            tailPath.moveTo(tailX - tailHalfWidth, bodyTop + halfStroke)
            tailPath.lineTo(tailX, top)
            tailPath.lineTo(tailX + tailHalfWidth, bodyTop + halfStroke)
        }
        tailPath.close()
        path.op(tailPath, Path.Op.UNION)

        canvas.save()
        canvas.translate(shadowOffset, shadowOffset)
        canvas.drawPath(path, accentPaint)
        canvas.restore()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, outlinePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        outlinePaint.alpha = alpha
        accentPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        outlinePaint.colorFilter = colorFilter
        accentPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class MangaBubbleTextView(context: Context) : TextView(context) {
    private val density = resources.displayMetrics.density
    private val bubble = MangaBubbleDrawable(density)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        background = bubble
        applyTailPadding(atBottom = true)
    }

    fun setTail(atBottom: Boolean, localTargetX: Float) {
        bubble.setTail(atBottom, localTargetX)
        applyTailPadding(atBottom)
    }

    private fun applyTailPadding(atBottom: Boolean) {
        val horizontal = (11f * density + 0.5f).toInt()
        val compact = (8f * density + 0.5f).toInt()
        val tailed = (16f * density + 0.5f).toInt()
        if (atBottom) {
            setPadding(horizontal, compact, horizontal, tailed)
        } else {
            setPadding(horizontal, tailed, horizontal, compact)
        }
    }

    override fun draw(canvas: Canvas) {
        // This is a persistent translucent overlay surface. Clear it on every redraw so text
        // reflow, a shorter sentence, or a tail-direction change cannot leave ghost pixels.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        super.draw(canvas)
    }
}

private class SurfaceClearingFrameLayout(context: Context) : FrameLayout(context) {
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        super.draw(canvas)
    }
}

private class PetalBurstView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particles = mutableListOf<Petal>()
    private var progress = 1f
    private var originX = 0f
    private var originY = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun burst(x: Float, y: Float) {
        animator?.cancel()
        originX = x
        originY = y
        particles.clear()
        val colors = intArrayOf(0xFFD85C88.toInt(), 0xFFF093B4.toInt(), 0xFFFFC3D8.toInt(), 0xFFFFE0EB.toInt())
        repeat(24) { index ->
            val angle = (-Math.PI * 0.92 + Random.nextDouble() * Math.PI * 0.84).toFloat()
            particles += Petal(
                angle = angle,
                distance = Random.nextInt(70, 180).toFloat(),
                size = Random.nextInt(5, 11).toFloat(),
                delay = (index % 6) * 0.035f,
                rotation = Random.nextInt(-220, 220).toFloat(),
                color = colors[index % colors.size],
            )
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    particles.clear()
                    progress = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        particles.forEach { petal ->
            val local = ((progress - petal.delay) / (1f - petal.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val distance = petal.distance * local
            val x = originX + cos(petal.angle) * distance
            val y = originY + sin(petal.angle) * distance + 92f * local * local
            paint.color = petal.color
            paint.alpha = ((1f - local) * 255).roundToInt().coerceIn(0, 255)
            canvas.save()
            canvas.rotate(petal.rotation * local, x, y)
            canvas.drawOval(RectF(x - petal.size, y - petal.size * 0.55f, x + petal.size, y + petal.size * 0.55f), paint)
            canvas.restore()
        }
    }

    private data class Petal(
        val angle: Float,
        val distance: Float,
        val size: Float,
        val delay: Float,
        val rotation: Float,
        val color: Int,
    )
}
