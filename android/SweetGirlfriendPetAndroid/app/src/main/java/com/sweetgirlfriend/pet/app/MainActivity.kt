package com.sweetgirlfriend.pet.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import com.sweetgirlfriend.pet.content.ContentPackLoader
import com.sweetgirlfriend.pet.content.ContentPackInstaller
import com.sweetgirlfriend.pet.content.PackInspectionResult
import com.sweetgirlfriend.pet.content.PackInstallResult
import com.sweetgirlfriend.pet.content.PackVersionRelation
import com.sweetgirlfriend.pet.overlay.OverlayPetController
import com.sweetgirlfriend.pet.overlay.WeatherContextProvider
import com.sweetgirlfriend.pet.renderer.FrameAnimationView
import com.sweetgirlfriend.pet.runtime.DisplayMode
import com.sweetgirlfriend.pet.runtime.BackgroundPresentation
import com.sweetgirlfriend.pet.runtime.EnergyProfile
import com.sweetgirlfriend.pet.runtime.InteractionFrequency
import com.sweetgirlfriend.pet.runtime.InteractionStyle
import com.sweetgirlfriend.pet.runtime.PackDescriptor
import com.sweetgirlfriend.pet.runtime.PackSettingDefinition
import com.sweetgirlfriend.pet.runtime.PackSettingType
import com.sweetgirlfriend.pet.runtime.PetTask
import com.sweetgirlfriend.pet.runtime.PlayMode
import com.sweetgirlfriend.pet.runtime.TaskOption
import com.sweetgirlfriend.pet.wallpaper.PetWallpaperService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("pet_settings", MODE_PRIVATE) }
    private val loader by lazy { ContentPackLoader(applicationContext) }
    private val localPackInstaller by lazy { ContentPackInstaller(applicationContext) }
    private val localPackScanner by lazy { LocalPetPackScanner(applicationContext) }
    private val localScanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-petpack-scan").apply { isDaemon = true }
    }
    private lateinit var preview: FrameAnimationView
    private lateinit var actionLabel: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var taskTitle: TextView
    private lateinit var taskPrompt: TextView
    private lateinit var taskOptions: LinearLayout
    private lateinit var packSpinner: Spinner
    private lateinit var packAdapter: ArrayAdapter<String>
    private lateinit var packDynamicSettings: LinearLayout
    private lateinit var displayModeSpinner: Spinner
    private lateinit var lanStatus: TextView
    private lateinit var lanToggleButton: Button
    private lateinit var localScanStatus: TextView
    private lateinit var localScanDirectory: TextView
    private lateinit var localScanCandidates: LinearLayout
    private lateinit var screenScroll: ScrollView
    private lateinit var localPackSection: ViewGroup
    private var actions: List<String> = emptyList()
    private var actionIndex = 0
    private var currentPackId = "girlfriend-classic"
    private var previewTasks: List<PetTask> = emptyList()
    private var previewTaskIndex = 0
    private var pendingOverlayStart = false
    private var availablePacks: List<PackDescriptor> = emptyList()
    private var currentLanUrl: String? = null
    private var currentLanFullUrl: String? = null
    private var currentLanPairingCode: String? = null
    @Volatile
    private var localScanBusy = false
    @Volatile
    private var localInstallInProgress = false
    private var localPickerInFlight = false
    private var localScanGeneration = 0
    private var localScanTask: Future<*>? = null
    private val pendingLocalCandidates = mutableListOf<PreparedLocalPack>()
    private var localConfirmationShowing = false
    private val lanInstallListener: (PackInstallResult) -> Unit = { result ->
        runOnUiThread { handleLanInstallResult(result) }
    }
    private val lanStateListener: (LanUploadSessionManager.Snapshot) -> Unit = { snapshot ->
        runOnUiThread { renderLanSessionState(snapshot) }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PALETTE_BACKGROUND
        window.navigationBarColor = PALETTE_BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildScreen())
        if (savedInstanceState == null) {
            window.decorView.post { handleExternalPetPackIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleExternalPetPackIntent(intent) }
    }

    override fun onResume() {
        super.onResume()
        LanUploadSessionManager.attach(lanInstallListener, lanStateListener)
        val activeMode = runCatching {
            PlayMode.valueOf(preferences.getString("game_mode", PlayMode.NORMAL.name) ?: PlayMode.NORMAL.name)
        }.getOrDefault(PlayMode.NORMAL)
        if (activeMode in setOf(PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE)) {
            OverlayPetController.setPlayMode(this, PlayMode.NORMAL)
        }
        if (::overlayStatus.isInitialized) refreshOverlayStatus()
        if (pendingOverlayStart && OverlayPetController.hasPermission(this)) {
            pendingOverlayStart = false
            requestNotificationThenStart()
        }
        refreshLanUploadState()
        window.decorView.postDelayed(::maybeAutoScanLocalDirectory, 450L)
    }

    override fun onDestroy() {
        LanUploadSessionManager.detach(lanInstallListener, lanStateListener)
        if (!localInstallInProgress && (pendingLocalCandidates.isNotEmpty() || localScanBusy)) {
            cleanupPendingLocalCandidates()
            preferences.edit().remove(LOCAL_SCAN_LAST_AUTO_AT).apply()
        }
        if (localInstallInProgress) localScanExecutor.shutdown() else localScanExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        LanUploadSessionManager.detach(lanInstallListener, lanStateListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST) startOverlayNow()
    }

    @Deprecated("Kept for the framework Storage Access Framework result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (
            requestCode !in setOf(
                LOCAL_PACK_FILES_REQUEST,
                LOCAL_PACK_FILES_COMPAT_REQUEST,
                LOCAL_PACK_TREE_REQUEST,
            )
        ) return
        localPickerInFlight = false
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == LOCAL_PACK_TREE_REQUEST) {
                Toast.makeText(
                    this,
                    "未授权目录。若 vivo 提示“为保护您的隐私请选择其他文件夹”，请改选 PetPacks 子目录，或直接使用“选择文件”。",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        when (requestCode) {
            LOCAL_PACK_FILES_REQUEST, LOCAL_PACK_FILES_COMPAT_REQUEST -> {
                val uris = collectResultUris(data)
                if (uris.isEmpty()) {
                    Toast.makeText(this, "没有收到可读取的文件，请改用兼容文件选择器重试。", Toast.LENGTH_LONG).show()
                    return
                }
                scanLocalDocuments(uris)
            }

            LOCAL_PACK_TREE_REQUEST -> data.data?.let { treeUri ->
                val rejected = rejectedTreeReason(treeUri)
                if (rejected != null) {
                    showRejectedTreeHelp(rejected)
                    return
                }
                if (!takeReadPermission(treeUri, data.flags)) {
                    Toast.makeText(this, "无法保存该目录的读取授权，请重新选择。", Toast.LENGTH_LONG).show()
                    return
                }
                replaceLocalScanTree(treeUri)
                renderLocalScanDirectory()
                scanLocalTree(manual = true)
            }
        }
    }

    private fun buildScreen(): View {
        screenScroll = ScrollView(this).apply {
            setBackgroundColor(PALETTE_BACKGROUND)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(42))
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    topInset = bars.top
                    bottomInset = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    topInset = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottomInset = insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(16) + topInset, dp(18), dp(42) + bottomInset)
                insets
            }
        }
        screenScroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(text("我家女友·甜蜜桌宠", 27f, PALETTE_TITLE, bold = true))
        root.addView(text("Android 手机陪伴版  v0.5.4", 14f, PALETTE_MUTED).withTop(dp(3)))
        root.addView(
            collapsibleSection("运行与模式", "桌面、悬浮窗与权限状态", expanded = true) {
                addView(buildPermissionNotice())
                addView(buildModeCard().withTop(dp(10)))
            }.withTop(dp(14)),
        )
        root.addView(collapsibleSection("桌宠预览", "检查形象、尺寸与动作") {
            addView(buildPreviewCard())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("任务互动", "预览手机任务与分支选项") {
            addView(buildTaskCard())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("角色资源包", "切换形象与资源包扩展参数") {
            addView(buildPackSettings())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("显示与节能", "尺寸、速度、频率与自动帧率") {
            addView(buildDisplayAndEnergySettings())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("悬浮操作", "任务、贴边、休息与夜间偏好") {
            addView(buildOverlayPreferences())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("动态对话与游戏", "天气、随机去重、重力与桌面拟态玩法") {
            addView(buildDynamicPlaySettings())
        }.withTop(dp(10)))
        localPackSection = collapsibleSection(
            "本地资源包",
            "选择、分享或扫描手机中的 .petpack",
            expanded = PetPackImportPolicy.isExternalImportAction(intent?.action),
        ) {
            addView(buildLocalPackScanCard())
        } as ViewGroup
        root.addView(localPackSection.withTop(dp(10)))
        root.addView(collapsibleSection("局域网上传", "从电脑安装 .petpack") {
            addView(buildLanUploadCard())
        }.withTop(dp(10)))
        root.addView(collapsibleSection("高级工具", "资源校验与扩展目录") {
            addView(buildContentTools())
        }.withTop(dp(10)))
        root.addView(
            text(
                "悬浮窗只在你主动开启后运行。拖动人物只改变桌宠位置，不跟随其他屏幕点击；关闭屏幕时停止绘制。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(16)),
        )
        return screenScroll
    }

    private fun buildPermissionNotice(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(13))
        addView(text("权限透明说明", 16f, PALETTE_GREEN, bold = true))
        addView(
            text(
                "悬浮模式只申请上层显示与前台通知；选项反馈使用轻触震动。局域网上传只在本页手动开启时监听，不读取其他应用内容、输入、位置、相册或麦克风。",
                13f,
                PALETTE_TEXT,
            ).withTop(dp(5)),
        )
    }

    private fun buildModeCard(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        addView(fieldLabel("界面层级"))
        val modes = DisplayMode.entries
        displayModeSpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("背景模式（只显示动态壁纸桌宠）", "悬浮窗模式（只显示上层桌宠）", "双层测试模式（同时显示两个）"),
            )
            val stored = runCatching {
                DisplayMode.valueOf(preferences.getString("display_mode", "WALLPAPER") ?: "WALLPAPER")
            }.getOrDefault(DisplayMode.WALLPAPER)
            setSelection(modes.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyDisplayMode(
                        modes[position],
                        showFeedback = currentDisplayMode() != modes[position],
                    )
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(displayModeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        addView(fieldLabel("动态壁纸画布（仅背景模式）").withTop(dp(7)))
        val presentations = BackgroundPresentation.entries
        val presentationSpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("资源包柔粉背景", "透明画布（效果取决于桌面系统）"),
            )
            val stored = runCatching {
                BackgroundPresentation.valueOf(
                    preferences.getString("background_presentation", "REPLACE_BACKGROUND")
                        ?: "REPLACE_BACKGROUND",
                )
            }.getOrDefault(BackgroundPresentation.REPLACE_BACKGROUND)
            setSelection(presentations.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString("background_presentation", presentations[position].name).apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(presentationSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(
            text(
                "悬浮窗模式不会使用这里的画布，并会关闭本应用动态壁纸，避免桌面出现第二只桌宠。",
                12f,
                PALETTE_MUTED,
            ),
        )

        overlayStatus = text("", 13f, PALETTE_TEXT, bold = true).withTop(dp(5))
        addView(overlayStatus)
        addView(
            text(
                "悬浮模式：直接拖动人物移动位置；轻点打开任务/休息/收起操作栏；双击触发亲密动作。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(5)),
        )

        val overlayRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        overlayRow.addView(primaryButton("开启悬浮") { startOverlayFlow() }, rowButtonParams(end = dp(5)))
        overlayRow.addView(secondaryButton("停止悬浮") {
            OverlayPetController.stop(this@MainActivity)
            overlayStatus.postDelayed(::refreshOverlayStatus, 350L)
        }, rowButtonParams(start = dp(5)))
        addView(overlayRow.withTop(dp(11)))
        addView(secondaryButton("设置背景动态壁纸") { openWallpaperPreview() }.withTop(dp(9)))
        refreshOverlayStatus()
    }

    private fun buildPreviewCard(): View = card().apply {
        setPadding(dp(9), dp(9), dp(9), dp(12))
        preview = FrameAnimationView(this@MainActivity).apply {
            background = rounded(0xFFFFEEF4.toInt(), dp(20).toFloat())
            setTargetFrameRate(12)
        }
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))
        actionLabel = text("动作：idle", 14f, PALETTE_MUTED).apply { gravity = Gravity.CENTER }
        addView(actionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondaryButton("上一个动作") { selectRelativeAction(-1) }, rowButtonParams(end = dp(5)))
        row.addView(secondaryButton("下一个动作") { selectRelativeAction(1) }, rowButtonParams(start = dp(5)))
        addView(row)
        configurePreview(preferences.getString("pack_id", "girlfriend-classic") ?: "girlfriend-classic")
    }

    private fun configurePreview(packId: String) {
        currentPackId = packId
        val errors = loader.validate(packId)
        if (errors.isNotEmpty()) Toast.makeText(this, "内容包校验失败：${errors.first()}", Toast.LENGTH_LONG).show()
        actions = loader.availableActions(packId).sortedWith(
            compareBy<String> { ACTION_ORDER.indexOf(it).let { index -> if (index < 0) 999 else index } }
                .thenBy { it },
        )
        actionIndex = actions.indexOf("idle").coerceAtLeast(0)
        preview.configure(loader, packId, actions[actionIndex])
        preview.setCharacterScale(preferences.getInt("size_percent", 78) / 100f)
        preview.setSpeedMultiplier(preferences.getInt("speed_percent", 100) / 100f)
        updateActionLabel()
        previewTasks = loader.loadTasks(packId)
        previewTaskIndex = 0
        if (::taskTitle.isInitialized) refreshTaskCard()

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                selectAction("wave")
                Toast.makeText(this@MainActivity, loader.randomDialogue(packId, "tap"), Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                selectAction("photo_pose")
                Toast.makeText(this@MainActivity, loader.randomDialogue(packId, "double_tap"), Toast.LENGTH_SHORT).show()
                return true
            }
        })
        preview.setOnTouchListener { view, event ->
            val handled = detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            handled
        }
    }

    private fun buildTaskCard(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        taskTitle = fieldLabel("")
        taskPrompt = text("", 14f, PALETTE_TEXT).withTop(dp(5))
        taskOptions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        addView(taskTitle)
        addView(taskPrompt)
        addView(taskOptions.withTop(dp(10)))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondaryButton("换一个任务") {
            if (previewTasks.isNotEmpty()) previewTaskIndex = (previewTaskIndex + 1) % previewTasks.size
            refreshTaskCard()
        }, rowButtonParams(end = dp(5)))
        row.addView(primaryButton("发送到悬浮窗") {
            if (OverlayPetController.isRunning(this@MainActivity)) {
                OverlayPetController.showTask(this@MainActivity)
            } else {
                Toast.makeText(this@MainActivity, "请先开启悬浮桌宠", Toast.LENGTH_SHORT).show()
            }
        }, rowButtonParams(start = dp(5)))
        addView(row.withTop(dp(10)))
        refreshTaskCard()
    }

    private fun refreshTaskCard() {
        val task = previewTasks.getOrNull(previewTaskIndex)
        if (task == null) {
            taskTitle.text = "暂无任务"
            taskPrompt.text = "当前内容包没有提供手机任务。"
            taskOptions.removeAllViews()
            return
        }
        taskTitle.text = task.title
        taskPrompt.text = task.prompt
        taskOptions.removeAllViews()
        task.options.take(2).forEach { option ->
            taskOptions.addView(taskOptionButton(option) { previewTaskChoice(task, option) }, rowButtonParams(dp(4), dp(4), dp(42)))
        }
    }

    private fun previewTaskChoice(task: PetTask, option: TaskOption) {
        selectAction(option.action)
        taskPrompt.text = option.response
        Toast.makeText(this, "${task.title}：${option.label}", Toast.LENGTH_SHORT).show()
    }

    private fun buildPackSettings(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        addView(fieldLabel("角色资源包"))
        availablePacks = loader.listPacks()
        packAdapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            availablePacks.map(::packLabel),
        )
        packSpinner = Spinner(this@MainActivity).apply {
            adapter = packAdapter
            val stored = preferences.getString("pack_id", availablePacks.first().id)
            setSelection(availablePacks.indexOfFirst { it.id == stored }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = availablePacks.getOrNull(position)?.id ?: return
                    if (selected != currentPackId) configurePreview(selected)
                    preferences.edit().putString("pack_id", selected).apply()
                    refreshPackDynamicSettings()
                    OverlayPetController.refresh(this@MainActivity)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(packSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        addView(fieldLabel("互动风格").withTop(dp(8)))
        val styles = InteractionStyle.entries
        val styleSpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("日常陪伴", "甜蜜互动", "安静陪伴"),
            )
            val stored = runCatching {
                InteractionStyle.valueOf(preferences.getString("interaction_style", "DAILY") ?: "DAILY")
            }.getOrDefault(InteractionStyle.DAILY)
            setSelection(styles.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString("interaction_style", styles[position].name).apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(styleSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(
            text(
                "APK 与 .petpack 独立发布。运行程序通过 io.sweetpet.pack/2.x 协议加载资源，安装新形象或互动逻辑不需要重新编译 App。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(7)),
        )
        packDynamicSettings = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(packDynamicSettings.withTop(dp(10)))
        refreshPackDynamicSettings()
    }

    private fun packLabel(pack: PackDescriptor): String =
        "${pack.name}  v${pack.version}${if (pack.installed) " · 已安装" else " · 内置"}"

    private fun refreshPackList(selectPackId: String = currentPackId) {
        availablePacks = loader.listPacks()
        packAdapter.clear()
        packAdapter.addAll(availablePacks.map(::packLabel))
        packAdapter.notifyDataSetChanged()
        val selectedIndex = availablePacks.indexOfFirst { it.id == selectPackId }.coerceAtLeast(0)
        packSpinner.setSelection(selectedIndex)
        val selected = availablePacks.getOrNull(selectedIndex)?.id ?: return
        preferences.edit().putString("pack_id", selected).apply()
        configurePreview(selected)
        refreshPackDynamicSettings()
        OverlayPetController.refresh(this)
    }

    private fun refreshPackDynamicSettings() {
        if (!::packDynamicSettings.isInitialized) return
        packDynamicSettings.removeAllViews()
        val descriptor = availablePacks.firstOrNull { it.id == currentPackId }
        if (descriptor != null) {
            packDynamicSettings.addView(
                text(
                    "协议 ${descriptor.protocolVersion} · ${descriptor.extensions.size} 个扩展点",
                    12f,
                    PALETTE_MUTED,
                ),
            )
        }
        val settings = runCatching { loader.loadPackSettings(currentPackId) }.getOrDefault(emptyList())
        if (settings.isEmpty()) return
        packDynamicSettings.addView(fieldLabel("资源包扩展参数").withTop(dp(9)))
        settings.forEach { definition ->
            packDynamicSettings.addView(buildPackSetting(definition).withTop(dp(6)))
        }
    }

    private fun buildPackSetting(definition: PackSettingDefinition): View {
        val storageKey = ContentPackLoader.packSettingStorageKey(currentPackId, definition.key)
        return when (definition.type) {
            PackSettingType.BOOLEAN -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val switch = Switch(this@MainActivity).apply {
                    text = definition.label
                    isChecked = preferences.getString(storageKey, definition.defaultValue).toBoolean()
                    setOnCheckedChangeListener { _, checked ->
                        preferences.edit().putString(storageKey, checked.toString()).apply()
                    }
                }
                addView(switch)
                if (definition.description.isNotBlank()) {
                    addView(text(definition.description, 11f, PALETTE_MUTED))
                }
            }

            PackSettingType.INTEGER -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val value = preferences.getString(storageKey, definition.defaultValue)
                    ?.toIntOrNull()
                    ?.coerceIn(definition.min, definition.max)
                    ?: definition.min
                val label = fieldLabel("${definition.label}  $value")
                addView(label)
                val steps = ((definition.max - definition.min) / definition.step).coerceAtLeast(1)
                addView(SeekBar(this@MainActivity).apply {
                    max = steps
                    progress = ((value - definition.min) / definition.step).coerceIn(0, steps)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val selected = definition.min + progress * definition.step
                            label.text = "${definition.label}  $selected"
                            preferences.edit().putString(storageKey, selected.toString()).apply()
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                })
                if (definition.description.isNotBlank()) addView(text(definition.description, 11f, PALETTE_MUTED))
            }

            PackSettingType.CHOICE -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(fieldLabel(definition.label))
                val stored = preferences.getString(storageKey, definition.defaultValue) ?: definition.defaultValue
                addView(Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        definition.options.map { it.label },
                    )
                    setSelection(definition.options.indexOfFirst { it.value == stored }.coerceAtLeast(0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            definition.options.getOrNull(position)?.let { option ->
                                preferences.edit().putString(storageKey, option.value).apply()
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                if (definition.description.isNotBlank()) addView(text(definition.description, 11f, PALETTE_MUTED))
            }
        }
    }

    private fun buildLanUploadCard(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        addView(text("浏览器上传 .petpack", 16f, PALETTE_GREEN, bold = true))
        addView(
            text(
                "开启后，在同一局域网电脑输入简短地址，再输入手机显示的 6 位配对码即可上传。会话 15 分钟后关闭；无需保持手机屏幕常亮，也可从通知立即关闭。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(5)),
        )
        lanStatus = text("○ 局域网上传未开启", 13f, PALETTE_TEXT, bold = true).withTop(dp(10))
        addView(lanStatus)
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        lanToggleButton = primaryButton("开启局域网上传") { toggleLanUpload() }
        row.addView(lanToggleButton, rowButtonParams(end = dp(5)))
        row.addView(secondaryButton("复制电脑地址") {
            val url = currentLanUrl
            if (url == null) {
                Toast.makeText(this@MainActivity, "未检测到可复制的局域网地址，请检查 Wi-Fi", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("SweetPet 电脑地址", url))
                Toast.makeText(this@MainActivity, "电脑地址已复制", Toast.LENGTH_SHORT).show()
            }
        }, rowButtonParams(start = dp(5)))
        addView(row.withTop(dp(11)))
        val credentialRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        credentialRow.addView(secondaryButton("复制配对码") {
            val code = currentLanPairingCode
            if (code == null) {
                Toast.makeText(this@MainActivity, "上传服务尚未就绪", Toast.LENGTH_SHORT).show()
            } else {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SweetPet 配对码", code))
                Toast.makeText(this@MainActivity, "6 位配对码已复制", Toast.LENGTH_SHORT).show()
            }
        }, rowButtonParams(end = dp(5), height = dp(46)))
        credentialRow.addView(secondaryButton("复制完整链接") {
            val url = currentLanFullUrl
            if (url == null) {
                Toast.makeText(this@MainActivity, "未检测到可复制的局域网地址", Toast.LENGTH_SHORT).show()
            } else {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SweetPet 完整上传链接", url))
                Toast.makeText(this@MainActivity, "兼容完整链接已复制，请勿转发", Toast.LENGTH_SHORT).show()
            }
        }, rowButtonParams(start = dp(5), height = dp(46)))
        addView(credentialRow.withTop(dp(8)))
        addView(
            text(
                "安全限制：仅接受 v2 协议；最大 96 MB、2000 个文件、展开 256 MB；拒绝路径越界和可执行文件，并逐项核对 SHA-256。",
                11f,
                PALETTE_MUTED,
            ).withTop(dp(8)),
        )
    }

    private fun toggleLanUpload() {
        val snapshot = LanUploadSessionManager.snapshot()
        if (snapshot.running || snapshot.starting) {
            lanStatus.text = "○ 正在关闭局域网上传…"
            LanUploadSessionManager.requestStop(applicationContext)
        } else {
            lanStatus.text = "○ 正在启动局域网上传…"
            lanStatus.setTextColor(PALETTE_TEXT)
            lanToggleButton.text = "关闭局域网上传"
            LanUploadSessionManager.requestStart(applicationContext)
        }
    }

    private fun refreshLanUploadState() {
        renderLanSessionState(LanUploadSessionManager.snapshot())
    }

    private fun renderLanSessionState(snapshot: LanUploadSessionManager.Snapshot) {
        if (!::lanStatus.isInitialized) return
        when {
            snapshot.starting -> {
                clearLanEndpointDetails()
                lanStatus.text = "○ 正在启动安全上传服务…"
                lanStatus.setTextColor(PALETTE_TEXT)
                lanToggleButton.text = "关闭局域网上传"
            }

            snapshot.running && snapshot.endpoint != null -> renderLanEndpoint(snapshot.endpoint)
            snapshot.error != null -> {
                clearLanEndpointDetails()
                lanStatus.text = "启动失败：${snapshot.error}"
                lanStatus.setTextColor(0xFFB3261E.toInt())
                lanToggleButton.text = "重新开启"
            }

            else -> {
                clearLanEndpointDetails()
                lanStatus.text = "○ ${snapshot.message ?: "局域网上传未开启"}"
                lanStatus.setTextColor(PALETTE_TEXT)
                lanToggleButton.text = "开启局域网上传"
            }
        }
    }

    private fun renderLanEndpoint(endpoint: LanPackUploadServer.Endpoint) {
        currentLanUrl = endpoint.browserUrls.firstOrNull()
        currentLanFullUrl = endpoint.urls.firstOrNull()
        currentLanPairingCode = endpoint.pairingCode
        if (!::lanStatus.isInitialized) return
        lanStatus.text = buildString {
            append("● 上传服务运行中\n")
            if (endpoint.browserUrls.isEmpty()) {
                append("暂未找到电脑可访问的地址（监听端口 ${endpoint.port}）\n")
            } else {
                endpoint.browserUrls.forEach { append("电脑地址：").append(it).append('\n') }
                append("配对码：").append(LanPairingPolicy.displayCode(endpoint.pairingCode)).append('\n')
            }
            endpoint.diagnostics.forEach { append("· ").append(it).append('\n') }
            append("电脑先打开裸地址再输入配对码；服务将在 15 分钟后自动关闭")
        }
        lanStatus.setTextColor(PALETTE_GREEN)
        lanToggleButton.text = "关闭局域网上传"
    }

    private fun clearLanEndpointDetails() {
        currentLanUrl = null
        currentLanFullUrl = null
        currentLanPairingCode = null
    }

    private fun handleLanInstallResult(result: PackInstallResult) {
        when (result) {
            is PackInstallResult.Success -> {
                Toast.makeText(
                    this,
                    if (result.unchanged) {
                        "${result.name} v${result.version} 内容相同，无需重复安装"
                    } else {
                        "已${if (result.replaced) "更新" else "安装"} ${result.name} v${result.version}"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                refreshPackList(result.packId)
            }

            is PackInstallResult.Failure ->
                Toast.makeText(this, "资源包安装失败：${result.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildDisplayAndEnergySettings(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        addView(sliderSetting("人物尺寸", "size_percent", 45, 100, 78, "%") { value ->
            preview.setCharacterScale(value / 100f)
            OverlayPetController.refresh(this@MainActivity)
        })
        addView(sliderSetting("动画速度", "speed_percent", 50, 160, 100, "%") { value ->
            preview.setSpeedMultiplier(value / 100f)
            OverlayPetController.refresh(this@MainActivity)
        }.withTop(dp(9)))

        addView(fieldLabel("互动频次").withTop(dp(9)))
        val frequencies = InteractionFrequency.entries
        val frequencySpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("轻陪伴（约 30 分钟）", "标准（约 8 分钟）", "活跃（约 3 分钟）"),
            )
            val stored = runCatching {
                InteractionFrequency.valueOf(
                    preferences.getString("interaction_frequency", "STANDARD") ?: "STANDARD",
                )
            }.getOrDefault(InteractionFrequency.STANDARD)
            setSelection(frequencies.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString("interaction_frequency", frequencies[position].name).apply()
                    OverlayPetController.refresh(this@MainActivity)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(frequencySpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        addView(fieldLabel("能耗策略").withTop(dp(9)))
        val profiles = EnergyProfile.entries
        val energySpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("自适应（推荐）", "流畅优先", "省电优先"),
            )
            val stored = runCatching {
                EnergyProfile.valueOf(preferences.getString("energy_profile", "ADAPTIVE") ?: "ADAPTIVE")
            }.getOrDefault(EnergyProfile.ADAPTIVE)
            setSelection(profiles.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString("energy_profile", profiles[position].name).apply()
                    OverlayPetController.refresh(this@MainActivity)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(energySpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(
            text("自适应帧率：互动 16 FPS、活动 12 FPS、待机 4 FPS、休眠 1 FPS、熄屏 0 FPS。", 12f, PALETTE_MUTED),
        )
        addView(sliderSetting("无操作后休眠", "inactivity_sleep_minutes", 1, 30, 10, " 分钟") {
            OverlayPetController.refresh(this@MainActivity)
        }.withTop(dp(9)))
    }

    private fun buildOverlayPreferences(): View = card().apply {
        setPadding(dp(15), dp(9), dp(15), dp(9))
        addView(toggle("启用任务互动", "tasks_enabled", true) { OverlayPetController.refresh(this@MainActivity) })
        addView(toggle("主动台词", "dialogue_enabled", true))
        addView(toggle("拖动结束自动贴边", "snap_to_edge", false))
        addView(toggle("夜间安静模式", "quiet_hours_enabled", true))
        addView(sliderSetting("拖动后原地休息", "manual_rest_minutes", 1, 30, 5, " 分钟") {
            OverlayPetController.refresh(this@MainActivity)
        }.withTop(dp(5)))
        addView(sliderSetting("快捷休息时长", "overlay_rest_minutes", 5, 60, 15, " 分钟") {
            OverlayPetController.refresh(this@MainActivity)
        }.withTop(dp(5)))
    }

    private fun buildDynamicPlaySettings(): View = card().apply {
        setPadding(dp(15), dp(11), dp(15), dp(13))
        addView(toggle("启用联网天气增强（仍保留时间台词）", "dynamic_weather_enabled", true) { enabled ->
            if (enabled) OverlayPetController.refresh(this@MainActivity)
        })
        addView(fieldLabel("天气城市（不申请定位）").withTop(dp(7)))
        val cityInput = EditText(this@MainActivity).apply {
            setText(preferences.getString("weather_city", "北京") ?: "北京")
            hint = "例如：上海、成都、Shenzhen"
            textSize = 14f
            setTextColor(PALETTE_TEXT)
            setHintTextColor(PALETTE_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            background = rounded(0xFFFFF7FA.toInt(), dp(14).toFloat(), 0x33C65C82)
            setPadding(dp(12), 0, dp(12), 0)
        }
        addView(cityInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        val cachedWeather = WeatherContextProvider(applicationContext).cached()
        val weatherStatus = text(
            cachedWeather?.let(::formatWeatherStatus)
                ?: "尚无当前城市的有效天气数据",
            12f,
            PALETTE_MUTED,
        ).withTop(dp(5))
        addView(weatherStatus)
        addView(secondaryButton("立即更新天气") {
            val city = cityInput.text.toString().trim()
            preferences.edit().putString("weather_city", city).apply()
            weatherStatus.text = "正在获取天气…"
            WeatherContextProvider(applicationContext).refresh(city, force = true) { result ->
                result.onSuccess { weather ->
                    weatherStatus.text = formatWeatherStatus(weather)
                    OverlayPetController.refresh(this@MainActivity)
                }.onFailure { error ->
                    weatherStatus.text = "更新失败：${error.message}"
                }
            }
        }.withTop(dp(7)))
        addView(
            text(
                "天气源：UAPI 国内免密接口优先，Open-Meteo 自动备用。只发送你填写的城市名和网络请求必需的 IP，不读取定位；Open-Meteo 数据采用 CC BY 4.0。",
                11f,
                PALETTE_MUTED,
            ).withTop(dp(6)),
        )

        addView(toggle("允许高级游戏模式", "game_modes_enabled", true).withTop(dp(10)))
        addView(toggle("游戏音效（仅显式玩法）", "game_sound_enabled", true))
        addView(toggle("游戏震动反馈", "game_haptics_enabled", true))
        addView(toggle("允许任务随机邀请玩法（默认关闭）", "random_mode_tasks", false))
        addView(fieldLabel("选择玩法").withTop(dp(8)))
        val modes = PlayMode.entries
        val modeSpinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("普通陪伴", "重力感应", "边框散步", "边框跑酷", "主页捉迷藏", "炸弹人拟态", "贪吃蛇拟态"),
            )
            val stored = runCatching {
                PlayMode.valueOf(preferences.getString("selected_play_mode", "GRAVITY") ?: "GRAVITY")
            }.getOrDefault(PlayMode.GRAVITY)
            setSelection(modes.indexOf(stored))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString("selected_play_mode", modes[position].name).apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(primaryButton("开始玩法") {
            val mode = modes.getOrElse(modeSpinner.selectedItemPosition) { PlayMode.GRAVITY }
            startSelectedPlayMode(mode)
        }, rowButtonParams(end = dp(5), height = dp(46)))
        row.addView(secondaryButton("结束玩法") {
            OverlayPetController.setPlayMode(this@MainActivity, PlayMode.NORMAL)
        }, rowButtonParams(start = dp(5), height = dp(46)))
        addView(row.withTop(dp(7)))
        addView(
            text(
                "重力与边框巡游直接作用于人物。捉迷藏、炸弹人和贪吃蛇会返回系统主页，但不会读取、隐藏或移动真实桌面图标；贪吃蛇与炸弹人只在显式玩法期间接管游戏场内的滑动/点按，退出后立即恢复桌面触摸。",
                11f,
                PALETTE_MUTED,
            ).withTop(dp(7)),
        )
    }

    private fun formatWeatherStatus(weather: com.sweetgirlfriend.pet.overlay.WeatherSnapshot): String =
        buildString {
            append(weather.city)
            append(" · ").append(weather.description)
            append(" · ").append(weather.temperature.toInt()).append("℃")
            weather.source.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }

    private fun buildLocalPackScanCard(): View = card().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        addView(text("从手机导入资源包", 16f, PALETTE_GREEN, bold = true))
        addView(
            text(
                "推荐直接选择一个或多个 .petpack：不需要目录权限。也可以在 vivo 文件管理器中对 PetPack 使用“打开方式”或“分享”发送到本应用。所有入口都只生成私有快照并预检，绝不会自动安装；每个资源包仍要再次确认。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(5)),
        )
        localScanDirectory = text("", 12f, PALETTE_TEXT).withTop(dp(9))
        addView(localScanDirectory)
        val sourceRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        sourceRow.addView(
            primaryButton("选择文件（推荐）") { openLocalPackFiles() },
            rowButtonParams(end = dp(5), height = dp(46)),
        )
        sourceRow.addView(
            secondaryButton("扫描目录（可选）") { openLocalPackTree() },
            rowButtonParams(start = dp(5), height = dp(46)),
        )
        addView(sourceRow.withTop(dp(9)))
        addView(
            secondaryButton("vivo / 文件管理器兼容选择") { openLocalPackFilesCompat() }
                .withTop(dp(7)),
        )
        val directoryRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        directoryRow.addView(
            secondaryButton("重新扫描") { scanLocalTree(manual = true) },
            rowButtonParams(end = dp(5), height = dp(44)),
        )
        directoryRow.addView(
            secondaryButton("撤销目录授权") { revokeLocalScanTree() },
            rowButtonParams(start = dp(5), height = dp(44)),
        )
        addView(directoryRow.withTop(dp(8)))
        addView(secondaryButton("取消当前扫描") { cancelLocalPackScan() }.withTop(dp(7)))
        addView(secondaryButton("清除已确认/忽略记录") { clearLocalPackDedupeHistory() }.withTop(dp(7)))
        addView(
            toggle(
                title = "返回设置页时自动检查授权目录",
                key = LOCAL_SCAN_AUTO_ENABLED,
                defaultValue = true,
            ),
        )
        addView(
            text(
                "Android 11 及以上（包括 vivo）会禁止授权内部存储根目录、Download 根目录以及 Android/data、Android/obb。这是系统隐私保护，不是应用故障。请先创建 Download/PetPacks，再选择 PetPacks 子目录。扫描上限：5 层、1200 个条目、80 个候选、累计 384 MB。",
                11f,
                PALETTE_MUTED,
            ).withTop(dp(3)),
        )
        localScanStatus = text("○ 尚未扫描本地资源包", 13f, PALETTE_TEXT, bold = true).withTop(dp(10))
        addView(localScanStatus)
        localScanCandidates = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(localScanCandidates.withTop(dp(8)))
        renderLocalScanDirectory()
    }

    private fun openLocalPackFiles() {
        if (localScanBusy) {
            Toast.makeText(this, "正在处理上一批资源包，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchLocalFilePicker(intent, LOCAL_PACK_FILES_REQUEST) {
            Toast.makeText(this, "系统文档选择器不可用，正在打开兼容文件选择器。", Toast.LENGTH_LONG).show()
            openLocalPackFilesCompat()
        }
    }

    private fun openLocalPackFilesCompat() {
        if (localScanBusy) {
            Toast.makeText(this, "正在处理上一批资源包，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(contentIntent, "选择一个或多个 PetPack 资源包")
        launchLocalFilePicker(chooser, LOCAL_PACK_FILES_COMPAT_REQUEST) {
            Toast.makeText(this, "手机上没有可用的文件选择器。也可在文件管理器中用“打开方式/分享”导入。", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchLocalFilePicker(intent: Intent, requestCode: Int, onFailure: () -> Unit) {
        localPickerInFlight = true
        runCatching { startActivityForResult(intent, requestCode) }.onFailure {
            localPickerInFlight = false
            onFailure()
        }
    }

    private fun openLocalPackTree() {
        if (localScanBusy) {
            Toast.makeText(this, "正在处理上一批资源包，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("请选择 PetPacks 子目录")
            .setMessage(
                "Android 11+ 会禁止选择内部存储或 Download 根目录；vivo 可能提示“为保护您的隐私请选择其他文件夹”。\n\n" +
                    "请先在文件管理器创建 Download/PetPacks，再在下一页选中 PetPacks。仅导入一两个包时，建议直接选择文件，无需目录授权。",
            )
            .setNegativeButton("改用文件选择") { _, _ -> openLocalPackFiles() }
            .setPositiveButton("继续选子目录") { _, _ -> launchLocalPackTreePicker() }
            .show()
    }

    private fun launchLocalPackTreePicker() {
        localPickerInFlight = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, preferredLocalPackTreeStartUri())
        }
        runCatching { startActivityForResult(intent, LOCAL_PACK_TREE_REQUEST) }.onFailure { error ->
            localPickerInFlight = false
            Toast.makeText(this, "无法打开系统目录选择器：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun preferredLocalPackTreeStartUri(): Uri {
        val persisted = preferences.getString(LOCAL_SCAN_TREE_URI, null)?.let(Uri::parse)
        if (
            persisted != null &&
            contentResolver.persistedUriPermissions.any { it.uri == persisted && it.isReadPermission }
        ) {
            return persisted
        }
        return Uri.Builder()
            .scheme("content")
            .authority("com.android.externalstorage.documents")
            .appendPath("document")
            .appendPath(DEFAULT_LOCAL_PACK_DOCUMENT_ID)
            .build()
    }

    private fun rejectedTreeReason(uri: Uri): RejectedTreeReason? {
        if (!PetPackImportPolicy.acceptsIncomingScheme(uri.scheme)) {
            return RejectedTreeReason.STORAGE_ROOT
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return RejectedTreeReason.STORAGE_ROOT
        return PetPackImportPolicy.rejectedTreeReason(uri.authority, documentId)
    }

    private fun showRejectedTreeHelp(reason: RejectedTreeReason) {
        val explanation = when (reason) {
            RejectedTreeReason.STORAGE_ROOT,
            RejectedTreeReason.DOWNLOAD_ROOT ->
                "系统不允许应用获得整个内部存储或 Download 根目录。请在 Download 下新建 PetPacks，并只选择 PetPacks 子目录。"

            RejectedTreeReason.ANDROID_PRIVATE_DIRECTORY ->
                "Android/data 与 Android/obb 是系统保护目录，不能用于 PetPack 扫描。请把资源包放到 Download/PetPacks。"
        }
        AlertDialog.Builder(this)
            .setTitle("这个目录受 Android 隐私保护")
            .setMessage("$explanation\n\n只导入一个或多个资源包时，可直接选择文件，无需目录授权。")
            .setNegativeButton("改用文件选择") { _, _ -> openLocalPackFiles() }
            .setPositiveButton("重新选子目录") { _, _ -> launchLocalPackTreePicker() }
            .show()
    }

    private fun collectResultUris(result: Intent): List<Uri> =
        collectIntentUris(result, includeStreams = false)

    @Suppress("DEPRECATION")
    private fun collectIntentUris(source: Intent, includeStreams: Boolean): List<Uri> {
        val uris = linkedSetOf<Uri>()
        fun add(uri: Uri?) {
            if (
                uri != null && uris.size < MAX_LOCAL_IMPORT_URIS &&
                PetPackImportPolicy.acceptsIncomingScheme(uri.scheme)
            ) {
                uris += uri
            }
        }

        add(source.data)
        runCatching {
            source.clipData?.let { clip ->
                repeat(minOf(clip.itemCount, MAX_LOCAL_IMPORT_URIS)) { index ->
                    add(clip.getItemAt(index).uri)
                }
            }
        }
        if (includeStreams) {
            when (source.action) {
                Intent.ACTION_SEND -> runCatching {
                    val stream = source.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                    add(stream as? Uri)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    runCatching {
                        source.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                            ?.take(MAX_LOCAL_IMPORT_URIS)
                            ?.forEach { stream -> add(stream as? Uri) }
                    }
                }
            }
        }
        return uris.toList()
    }

    private fun handleExternalPetPackIntent(incoming: Intent?) {
        val source = incoming ?: return
        if (!PetPackImportPolicy.isExternalImportAction(source.action)) return

        // Consume the launch intent once so a configuration change cannot re-import it.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        revealLocalPackSection()
        val uris = collectIntentUris(source, includeStreams = true)
        if (uris.isEmpty()) {
            localScanStatus.text = "未收到可读取的 PetPack。请在文件管理器中选择 .petpack 后使用“打开方式/分享”。"
            localScanStatus.setTextColor(0xFFB3261E.toInt())
            Toast.makeText(this, "文件管理器没有提供可读取的 content:// 文件，请改用应用内“选择文件”。", Toast.LENGTH_LONG).show()
            return
        }
        if (localScanBusy || localConfirmationShowing || pendingLocalCandidates.isNotEmpty()) {
            Toast.makeText(this, "已有资源包正在预检或等待确认，请处理完后重新打开/分享。", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已接收 ${uris.size} 个文件，将先安全预检，不会自动安装。", Toast.LENGTH_LONG).show()
        scanLocalDocuments(uris)
    }

    private fun revealLocalPackSection() {
        if (!::localPackSection.isInitialized || !::screenScroll.isInitialized) return
        val content = localPackSection.getChildAt(1)
        if (content?.visibility != View.VISIBLE) {
            localPackSection.getChildAt(0)?.performClick()
        }
        screenScroll.postDelayed({
            screenScroll.smoothScrollTo(0, localPackSection.top)
        }, 120L)
    }

    private fun takeReadPermission(uri: Uri, resultFlags: Int): Boolean {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (resultFlags and readFlag == 0) return false
        return runCatching {
            contentResolver.takePersistableUriPermission(uri, readFlag)
            true
        }.getOrDefault(false)
    }

    private fun replaceLocalScanTree(treeUri: Uri) {
        val previous = preferences.getString(LOCAL_SCAN_TREE_URI, null)?.let(Uri::parse)
        preferences.edit().putString(LOCAL_SCAN_TREE_URI, treeUri.toString()).apply()
        if (previous != null && previous != treeUri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun revokeLocalScanTree() {
        if (localScanBusy) {
            Toast.makeText(this, "扫描结束后再撤销目录授权。", Toast.LENGTH_SHORT).show()
            return
        }
        val treeUri = preferences.getString(LOCAL_SCAN_TREE_URI, null)?.let(Uri::parse)
        preferences.edit().remove(LOCAL_SCAN_TREE_URI).remove(LOCAL_SCAN_LAST_AUTO_AT).apply()
        if (treeUri != null) {
            runCatching {
                contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        localScanGeneration += 1
        cleanupPendingLocalCandidates()
        localScanCandidates.removeAllViews()
        localScanStatus.text = "○ 已撤销扫描目录；已选文件的临时快照不会自动安装。"
        localScanStatus.setTextColor(PALETTE_TEXT)
        renderLocalScanDirectory()
    }

    private fun renderLocalScanDirectory() {
        if (!::localScanDirectory.isInitialized) return
        val tree = preferences.getString(LOCAL_SCAN_TREE_URI, null)?.let(Uri::parse)
        if (tree == null) {
            localScanDirectory.text = "扫描目录：未授权"
            return
        }
        val stillGranted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        val label = runCatching { Uri.decode(DocumentsContract.getTreeDocumentId(tree)) }
            .getOrDefault(tree.lastPathSegment ?: tree.toString())
        localScanDirectory.text = if (stillGranted) {
            "扫描目录：$label（只读授权）"
        } else {
            "扫描目录授权已被系统或文件管理器撤销，请重新授权。"
        }
        localScanDirectory.setTextColor(if (stillGranted) PALETTE_TEXT else 0xFFB3261E.toInt())
    }

    private fun scanLocalDocuments(uris: List<Uri>) {
        runLocalPackScan("正在读取 ${uris.size} 个所选文件…") {
            localPackScanner.scanDocuments(uris)
        }
    }

    private fun cancelLocalPackScan() {
        val task = localScanTask
        if (task == null || task.isDone || localInstallInProgress) {
            Toast.makeText(this, "当前没有可取消的目录/文件扫描。", Toast.LENGTH_SHORT).show()
            return
        }
        localScanGeneration += 1
        localScanTask = null
        localScanBusy = false
        task.cancel(true)
        cleanupPendingLocalCandidates()
        localScanCandidates.removeAllViews()
        localScanStatus.text = "○ 已取消扫描；没有安装任何候选。"
        localScanStatus.setTextColor(PALETTE_TEXT)
    }

    private fun scanLocalTree(manual: Boolean) {
        val tree = preferences.getString(LOCAL_SCAN_TREE_URI, null)?.let(Uri::parse)
        if (tree == null) {
            if (manual) Toast.makeText(this, "请先授权一个 PetPacks 子目录。", Toast.LENGTH_SHORT).show()
            return
        }
        val granted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!granted) {
            renderLocalScanDirectory()
            if (manual) Toast.makeText(this, "目录授权已失效，请重新授权。", Toast.LENGTH_LONG).show()
            return
        }
        preferences.edit().putLong(LOCAL_SCAN_LAST_AUTO_AT, System.currentTimeMillis()).apply()
        runLocalPackScan("正在安全扫描授权目录…") { localPackScanner.scanTree(tree) }
    }

    private fun maybeAutoScanLocalDirectory() {
        if (
            !::localScanStatus.isInitialized || localScanBusy || localPickerInFlight ||
            localConfirmationShowing || pendingLocalCandidates.isNotEmpty()
        ) return
        if (!preferences.getBoolean(LOCAL_SCAN_AUTO_ENABLED, true)) return
        if (preferences.getString(LOCAL_SCAN_TREE_URI, null) == null) return
        val last = preferences.getLong(LOCAL_SCAN_LAST_AUTO_AT, 0L)
        if (System.currentTimeMillis() - last < LOCAL_SCAN_AUTO_INTERVAL_MS) return
        scanLocalTree(manual = false)
    }

    private fun runLocalPackScan(
        progress: String,
        scan: () -> LocalPackScanReport,
    ) {
        if (localScanBusy) {
            Toast.makeText(this, "正在处理上一批资源包，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        localScanBusy = true
        localScanGeneration += 1
        val generation = localScanGeneration
        cleanupPendingLocalCandidates()
        localScanStatus.text = "○ $progress"
        localScanStatus.setTextColor(PALETTE_TEXT)
        localScanCandidates.removeAllViews()
        localScanTask = localScanExecutor.submit {
            val result = runCatching {
                val report = scan()
                try {
                    prepareLocalCandidates(report)
                } catch (error: Throwable) {
                    report.snapshots.forEach(::deleteLocalSnapshot)
                    throw error
                }
            }
            runOnUiThread {
                if (generation != localScanGeneration || isFinishing || isDestroyed) {
                    result.getOrNull()?.candidates?.forEach { candidate ->
                        deleteLocalSnapshot(candidate.snapshot)
                    }
                    return@runOnUiThread
                }
                localScanTask = null
                localScanBusy = false
                result.onSuccess(::renderLocalCandidates).onFailure { error ->
                    localScanStatus.text = "扫描失败：${error.message ?: error.javaClass.simpleName}"
                    localScanStatus.setTextColor(0xFFB3261E.toInt())
                    renderLocalScanDirectory()
                }
            }
        }
    }

    private fun prepareLocalCandidates(report: LocalPackScanReport): LocalCandidateBatch {
        val diagnostics = report.diagnostics.toMutableList()
        val confirmedHashes = preferences.getStringSet(LOCAL_SCAN_CONFIRMED_HASHES, emptySet()).orEmpty().toSet()
        val confirmedSources = preferences.getStringSet(LOCAL_SCAN_CONFIRMED_SOURCES, emptySet()).orEmpty().toSet()
        var confirmedDuplicates = 0
        var invalid = 0
        var refusedDowngrades = 0
        val candidates = mutableListOf<PreparedLocalPack>()
        report.snapshots.forEach { snapshot ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
            if (
                LocalPackScanPolicy.wasConfirmed(
                    snapshot.sha256,
                    snapshot.sourceFingerprint,
                    confirmedHashes,
                    confirmedSources,
                )
            ) {
                confirmedDuplicates += 1
                snapshot.file.delete()
                return@forEach
            }
            val inspection = localPackInstaller.inspect(snapshot.file)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
            when (inspection) {
                is PackInspectionResult.Failure -> {
                    invalid += 1
                    diagnostics += "${snapshot.sourcePath} 预检失败：${LocalPackScanPolicy.safeUiText(inspection.message, 240)}"
                    snapshot.file.delete()
                }

                is PackInspectionResult.Ready -> {
                    if (!inspection.archiveSha256.equals(snapshot.sha256, ignoreCase = true)) {
                        invalid += 1
                        diagnostics += "${snapshot.sourcePath} 的预检哈希不一致，已拒绝。"
                        snapshot.file.delete()
                        return@forEach
                    }
                    if (inspection.relationToAvailable == PackVersionRelation.DOWNGRADE) {
                        refusedDowngrades += 1
                        diagnostics += "已拒绝降级：${inspection.name} v${inspection.version} 低于当前可用版本。"
                        snapshot.file.delete()
                        return@forEach
                    }
                    if (inspection.isExactArchiveDuplicate || inspection.isDuplicateContent) {
                        confirmedDuplicates += 1
                        rememberConfirmedSnapshot(snapshot)
                        diagnostics += "已跳过重复包：${inspection.name} v${inspection.version}（内容相同）。"
                        snapshot.file.delete()
                        return@forEach
                    }
                    candidates += PreparedLocalPack(snapshot, inspection, inspection.sameVersionDifferentContent)
                }
            }
        }
        return LocalCandidateBatch(
            candidates = candidates,
            diagnostics = diagnostics,
            visitedEntries = report.visitedEntries,
            contentDuplicates = report.duplicateContents + confirmedDuplicates,
            invalid = invalid,
            refusedDowngrades = refusedDowngrades,
        )
    }

    private fun renderLocalCandidates(batch: LocalCandidateBatch) {
        cleanupPendingLocalCandidates()
        pendingLocalCandidates.addAll(batch.candidates)
        localScanCandidates.removeAllViews()
        localScanStatus.text = buildString {
            append("● 扫描完成：${batch.candidates.size} 个待确认候选")
            if (batch.contentDuplicates > 0) append("，${batch.contentDuplicates} 个重复已跳过")
            if (batch.invalid > 0) append("，${batch.invalid} 个无效")
            if (batch.refusedDowngrades > 0) append("，${batch.refusedDowngrades} 个降级已拒绝")
            append("\n检查了 ${batch.visitedEntries} 个目录条目/所选文件")
            batch.diagnostics.take(8).forEach { append("\n· ").append(it) }
            if (batch.diagnostics.size > 8) append("\n· 另有 ${batch.diagnostics.size - 8} 条诊断未展开")
        }
        localScanStatus.setTextColor(if (batch.candidates.isNotEmpty()) PALETTE_GREEN else PALETTE_TEXT)
        renderPendingLocalCandidateViews()
        renderLocalScanDirectory()
    }

    private fun renderPendingLocalCandidateViews() {
        localScanCandidates.removeAllViews()
        pendingLocalCandidates.forEach { candidate ->
            localScanCandidates.addView(localCandidateView(candidate).withTop(dp(7)))
        }
    }

    private fun localCandidateView(candidate: PreparedLocalPack): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(
            if (candidate.sameVersionConflict) 0xFFFFF1E8.toInt() else 0xFFF8F4F6.toInt(),
            dp(14).toFloat(),
            if (candidate.sameVersionConflict) 0xFFFF8A50.toInt() else 0x20C65C82,
        )
        val ready = candidate.inspection
        val safeName = LocalPackScanPolicy.safeUiText(ready.name)
        val safeAuthor = LocalPackScanPolicy.safeUiText(ready.author ?: "未声明")
        addView(text("$safeName  v${ready.version}", 15f, PALETTE_TITLE, bold = true))
        addView(
            text(
                buildString {
                    append("ID：${ready.packId}")
                    append("\n声明作者（未验证）：").append(safeAuthor)
                    append("\n来源：${candidate.snapshot.sourcePath}")
                    append("\n大小：${formatBytes(candidate.snapshot.sourceSize ?: ready.archiveSizeBytes)}")
                    append(" · SHA-256：${candidate.snapshot.sha256.take(12)}…")
                    append("\n状态：")
                    when {
                        candidate.sameVersionConflict -> append("同版本内容不同，将覆盖当前可用内容（强提醒）")
                        ready.replacesExisting -> append("将更新现有资源包")
                        else -> append("新资源包")
                    }
                },
                12f,
                if (candidate.sameVersionConflict) 0xFFB43B14.toInt() else PALETTE_TEXT,
                bold = candidate.sameVersionConflict,
            ).withTop(dp(4)),
        )
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val confirm = if (candidate.sameVersionConflict) {
            primaryButton("风险确认") { confirmLocalPackInstall(candidate) }
        } else {
            secondaryButton("确认安装") { confirmLocalPackInstall(candidate) }
        }
        actions.addView(confirm, rowButtonParams(end = dp(4), height = dp(44)))
        actions.addView(
            secondaryButton("忽略此内容") { confirmIgnoreLocalPack(candidate) },
            rowButtonParams(start = dp(4), height = dp(44)),
        )
        addView(actions.withTop(dp(8)))
    }

    private fun confirmLocalPackInstall(candidate: PreparedLocalPack) {
        if (localScanBusy || !candidate.snapshot.file.isFile) {
            Toast.makeText(this, "候选快照已失效，请重新扫描。", Toast.LENGTH_LONG).show()
            return
        }
        val ready = candidate.inspection
        val warning = if (candidate.sameVersionConflict) {
            "\n\n⚠ 当前可用资源包版本相同，但校验内容不同。继续会覆盖现有内容，请确认来源可信。"
        } else {
            "\n\n安装只会在你点击下方确认后执行。"
        }
        localConfirmationShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (candidate.sameVersionConflict) "确认覆盖同版本资源包？" else "确认安装资源包？")
            .setMessage(
                    "${LocalPackScanPolicy.safeUiText(ready.name)}  v${ready.version}\n" +
                    "ID：${ready.packId}\n" +
                    "声明作者（未验证）：${LocalPackScanPolicy.safeUiText(ready.author ?: "未声明")}\n" +
                    "来源：${candidate.snapshot.sourcePath}\n" +
                    "大小：${formatBytes(candidate.snapshot.sourceSize ?: ready.archiveSizeBytes)}\n" +
                    "SHA-256：${candidate.snapshot.sha256}" + warning,
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(if (candidate.sameVersionConflict) "确认覆盖" else "确认安装") { _, _ ->
                installLocalPackSnapshot(candidate)
            }
            .create()
        dialog.setOnDismissListener { localConfirmationShowing = false }
        dialog.show()
    }

    private fun confirmIgnoreLocalPack(candidate: PreparedLocalPack) {
        localConfirmationShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("不再提示这份内容？")
            .setMessage(
                "将记录此候选的 SHA-256。以后即使文件移动到其他目录，只要内容完全相同，都不会再次提示；内容变化后仍会重新出现。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认忽略") { _, _ ->
                rememberConfirmedSnapshot(candidate.snapshot)
                removePendingLocalCandidate(candidate, deleteSnapshot = true)
                localScanStatus.text = "○ 已忽略 ${LocalPackScanPolicy.safeUiText(candidate.inspection.name)}；内容变化后会重新提示。"
                localScanStatus.setTextColor(PALETTE_TEXT)
            }
            .create()
        dialog.setOnDismissListener { localConfirmationShowing = false }
        dialog.show()
    }

    private fun installLocalPackSnapshot(candidate: PreparedLocalPack) {
        if (localScanBusy) return
        localScanBusy = true
        localInstallInProgress = true
        localScanStatus.text = "○ 正在安装缓存快照并复核 SHA-256…"
        val generation = localScanGeneration
        localScanExecutor.execute {
            val result = localPackInstaller.install(candidate.snapshot.file, candidate.inspection)
            runOnUiThread {
                localScanBusy = false
                localInstallInProgress = false
                if (generation != localScanGeneration || isFinishing || isDestroyed) {
                    cleanupPendingLocalCandidates()
                    return@runOnUiThread
                }
                when (result) {
                    is PackInstallResult.Success -> {
                        rememberConfirmedSnapshot(candidate.snapshot)
                        Toast.makeText(
                            this,
                            if (result.unchanged) {
                                "${result.name} v${result.version} 内容相同，无需重复安装"
                            } else {
                                "已${if (result.replaced) "更新" else "安装"} ${result.name} v${result.version}"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                        // Multiple local candidates must not unexpectedly switch the active character.
                        refreshPackList(currentPackId)
                        removePendingLocalCandidate(candidate, deleteSnapshot = true)
                        localScanStatus.text = if (pendingLocalCandidates.isEmpty()) {
                            "● 安装完成；当前没有待确认候选。"
                        } else {
                            "● 安装完成；其余 ${pendingLocalCandidates.size} 个候选仍需分别确认。"
                        }
                        localScanStatus.setTextColor(PALETTE_GREEN)
                    }

                    is PackInstallResult.Failure -> {
                        val safeMessage = LocalPackScanPolicy.safeUiText(result.message, 240)
                        localScanStatus.text = "安装失败：$safeMessage"
                        localScanStatus.setTextColor(0xFFB3261E.toInt())
                        Toast.makeText(this, "资源包安装失败：$safeMessage", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun rememberConfirmedSnapshot(snapshot: LocalPackSnapshot) {
        val hashes = preferences.getStringSet(LOCAL_SCAN_CONFIRMED_HASHES, emptySet()).orEmpty().toMutableSet()
        val sources = preferences.getStringSet(LOCAL_SCAN_CONFIRMED_SOURCES, emptySet()).orEmpty().toMutableSet()
        hashes += snapshot.sha256
        sources += "${snapshot.sourceFingerprint}:${snapshot.sha256}"
        preferences.edit()
            .putStringSet(LOCAL_SCAN_CONFIRMED_HASHES, hashes.toList().takeLast(256).toSet())
            .putStringSet(LOCAL_SCAN_CONFIRMED_SOURCES, sources.toList().takeLast(256).toSet())
            .apply()
    }

    private fun removePendingLocalCandidate(candidate: PreparedLocalPack, deleteSnapshot: Boolean) {
        pendingLocalCandidates.remove(candidate)
        if (deleteSnapshot) deleteLocalSnapshot(candidate.snapshot)
        renderPendingLocalCandidateViews()
    }

    private fun cleanupPendingLocalCandidates() {
        pendingLocalCandidates.forEach { deleteLocalSnapshot(it.snapshot) }
        pendingLocalCandidates.clear()
    }

    private fun deleteLocalSnapshot(snapshot: LocalPackSnapshot) {
        snapshot.file.delete()
        val session = snapshot.file.parentFile
        if (
            session?.parentFile?.name == "local-petpack-scan" &&
            session.listFiles()?.isEmpty() == true
        ) {
            session.delete()
        }
    }

    private fun clearLocalPackDedupeHistory() {
        AlertDialog.Builder(this)
            .setTitle("清除去重记录？")
            .setMessage("只会清除本地扫描的“已确认/忽略”哈希记录，不会卸载任何资源包或撤销目录授权。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                preferences.edit()
                    .remove(LOCAL_SCAN_CONFIRMED_HASHES)
                    .remove(LOCAL_SCAN_CONFIRMED_SOURCES)
                    .apply()
                Toast.makeText(this, "已清除本地扫描去重记录", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun startSelectedPlayMode(mode: PlayMode) {
        if (!OverlayPetController.isRunning(this)) startOverlayFlow()
        window.decorView.postDelayed({
            if (!OverlayPetController.isRunning(this)) {
                Toast.makeText(this, "请先完成悬浮窗授权，再点击开始玩法", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            OverlayPetController.setPlayMode(this, mode)
            if (mode in setOf(PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE)) {
                moveTaskToBack(true)
            }
        }, 700L)
    }

    private fun buildContentTools(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(secondaryButton("校验当前资源包") {
            val errors = loader.validate(currentPackId)
            val message = if (errors.isEmpty()) {
                "校验通过：${loader.availableActions(currentPackId).size} 个动作，${loader.loadTasks(currentPackId).size} 个任务"
            } else {
                errors.joinToString("\n")
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        })
        addView(
            text(
                "扩展资源目录：应用私有 files/content-packs/<pack-id>。资源包通过角色、动作、对话、行为、任务五份清单组合。",
                12f,
                PALETTE_MUTED,
            ).withTop(dp(9)),
        )
    }

    private fun startOverlayFlow() {
        if (currentDisplayMode() != DisplayMode.BOTH) {
            displayModeSpinner.setSelection(DisplayMode.entries.indexOf(DisplayMode.OVERLAY))
            applyDisplayMode(DisplayMode.OVERLAY, showFeedback = false)
        }
        if (!OverlayPetController.hasPermission(this)) {
            pendingOverlayStart = true
            runCatching { startActivity(OverlayPetController.permissionIntent(this)) }
                .onFailure { Toast.makeText(this, "无法打开系统悬浮窗授权页", Toast.LENGTH_LONG).show() }
            return
        }
        requestNotificationThenStart()
    }

    private fun requestNotificationThenStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else {
            startOverlayNow()
        }
    }

    private fun startOverlayNow() {
        if (currentDisplayMode() == DisplayMode.OVERLAY) restoreSystemBackgroundIfNeeded(showFeedback = false)
        if (OverlayPetController.start(this)) {
            Toast.makeText(this, "悬浮桌宠正在启动", Toast.LENGTH_SHORT).show()
            overlayStatus.postDelayed(::refreshOverlayStatus, 450L)
        }
    }

    private fun refreshOverlayStatus() {
        val permission = OverlayPetController.hasPermission(this)
        val running = OverlayPetController.isRunning(this)
        overlayStatus.text = when {
            running -> "● 悬浮桌宠运行中 · 可直接拖动人物"
            permission -> "○ 已授权，当前未运行"
            else -> "○ 尚未授予“显示在其他应用上层”权限"
        }
        overlayStatus.setTextColor(if (running) PALETTE_GREEN else PALETTE_TEXT)
    }

    private fun openWallpaperPreview() {
        if (currentDisplayMode() != DisplayMode.BOTH) {
            displayModeSpinner.setSelection(DisplayMode.entries.indexOf(DisplayMode.WALLPAPER))
            applyDisplayMode(DisplayMode.WALLPAPER, showFeedback = false)
        }
        val component = ComponentName(this, PetWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        runCatching { startActivity(direct) }.onFailure {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun currentDisplayMode(): DisplayMode = runCatching {
        DisplayMode.valueOf(preferences.getString("display_mode", "WALLPAPER") ?: "WALLPAPER")
    }.getOrDefault(DisplayMode.WALLPAPER)

    private fun applyDisplayMode(mode: DisplayMode, showFeedback: Boolean) {
        val changed = currentDisplayMode() != mode
        preferences.edit().putString("display_mode", mode.name).apply()
        when (mode) {
            DisplayMode.WALLPAPER -> {
                OverlayPetController.stop(this)
                if (changed && showFeedback) Toast.makeText(this, "已关闭悬浮桌宠，仅保留背景模式", Toast.LENGTH_SHORT).show()
            }

            DisplayMode.OVERLAY -> {
                val restored = restoreSystemBackgroundIfNeeded(showFeedback)
                if (changed && showFeedback && !restored) {
                    Toast.makeText(this, "已切换为单悬浮窗模式", Toast.LENGTH_SHORT).show()
                }
            }

            DisplayMode.BOTH -> if (changed && showFeedback) {
                Toast.makeText(this, "双层测试模式允许桌面与悬浮窗同时显示", Toast.LENGTH_SHORT).show()
            }
        }
        if (::overlayStatus.isInitialized) overlayStatus.postDelayed(::refreshOverlayStatus, 250L)
    }

    private fun restoreSystemBackgroundIfNeeded(showFeedback: Boolean): Boolean {
        val manager = WallpaperManager.getInstance(this)
        val currentInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            } else {
                @Suppress("DEPRECATION")
                manager.wallpaperInfo
            }
        }.getOrNull()
        if (currentInfo?.serviceInfo?.packageName != packageName) return false
        return runCatching {
            manager.clear(WallpaperManager.FLAG_SYSTEM)
            if (showFeedback) {
                Toast.makeText(this, "已退出本应用动态壁纸并恢复系统背景", Toast.LENGTH_SHORT).show()
            }
            true
        }.getOrElse { error ->
            if (showFeedback) {
                Toast.makeText(this, "系统背景恢复失败：${error.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    private fun selectRelativeAction(delta: Int) {
        if (actions.isEmpty()) return
        actionIndex = (actionIndex + delta + actions.size) % actions.size
        preview.setAction(actions[actionIndex])
        updateActionLabel()
    }

    private fun selectAction(action: String) {
        val index = actions.indexOf(action)
        if (index < 0) return
        actionIndex = index
        preview.setAction(action)
        updateActionLabel()
    }

    private fun updateActionLabel() {
        if (actions.isNotEmpty()) actionLabel.text = "动作：${actions[actionIndex]}  ·  轻点/双击人物试试"
    }

    private fun sliderSetting(
        title: String,
        key: String,
        minimum: Int,
        maximum: Int,
        defaultValue: Int,
        suffix: String,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val valueLabel = fieldLabel("")
        val stored = preferences.getInt(key, defaultValue).coerceIn(minimum, maximum)
        valueLabel.text = "$title  $stored$suffix"
        addView(valueLabel)
        val seek = SeekBar(this@MainActivity).apply {
            max = maximum - minimum
            progress = stored - minimum
            var changedByUser = false
            var pendingValue = stored
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + minimum
                    valueLabel.text = "$title  $value$suffix"
                    if (fromUser) {
                        changedByUser = true
                        pendingValue = value
                        preferences.edit().putInt(key, value).apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!changedByUser) return
                    changedByUser = false
                    onChanged(pendingValue)
                }
            })
        }
        addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    }

    @Suppress("DEPRECATION")
    private fun toggle(
        title: String,
        key: String,
        defaultValue: Boolean,
        onChanged: (Boolean) -> Unit = {},
    ): View = Switch(this).apply {
        text = title
        textSize = 15f
        setTextColor(PALETTE_TEXT)
        gravity = Gravity.CENTER_VERTICAL
        isChecked = preferences.getBoolean(key, defaultValue)
        setPadding(0, dp(7), 0, dp(7))
        setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean(key, enabled).apply()
            onChanged(enabled)
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, dp(22).toFloat(), 0x18C75F83)
        elevation = dp(2).toFloat()
    }

    private fun collapsibleSection(
        title: String,
        summary: String,
        expanded: Boolean = false,
        buildContent: LinearLayout.() -> Unit,
    ): View = card().apply {
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
        }
        val labels = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, PALETTE_TITLE, bold = true))
            addView(text(summary, 12f, PALETTE_MUTED).withTop(dp(2)))
        }
        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = text(if (expanded) "⌃" else "⌄", 23f, PALETTE_ACCENT_DARK, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = if (expanded) "收起$title" else "展开$title"
        }
        header.addView(chevron, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(header)

        val content = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
            visibility = if (expanded) View.VISIBLE else View.GONE
            buildContent()
        }
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.setOnClickListener {
            val opening = content.visibility != View.VISIBLE
            content.visibility = if (opening) View.VISIBLE else View.GONE
            chevron.text = if (opening) "⌃" else "⌄"
            chevron.contentDescription = if (opening) "收起$title" else "展开$title"
            header.animate().alpha(0.72f).setDuration(70L).withEndAction {
                header.animate().alpha(1f).setDuration(110L).start()
            }.start()
        }
    }

    private fun sectionTitle(value: String): TextView = text(value, 18f, PALETTE_TITLE, bold = true)
    private fun fieldLabel(value: String): TextView = text(value, 14f, PALETTE_TEXT, bold = true)

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.16f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun primaryButton(label: String, action: () -> Unit): Button = button(
        label,
        Color.WHITE,
        PALETTE_ACCENT,
        action,
    )

    private fun secondaryButton(label: String, action: () -> Unit): Button = button(
        label,
        PALETTE_ACCENT_DARK,
        0xFFFFEAF1.toInt(),
        action,
    )

    private fun taskOptionButton(option: TaskOption, action: () -> Unit): Button =
        secondaryButton(option.label, action).apply { textSize = 13f }

    private fun button(label: String, textColor: Int, backgroundColor: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            isAllCaps = false
            background = rounded(backgroundColor, dp(16).toFloat())
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        }

    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun rowButtonParams(
        start: Int = 0,
        end: Int = 0,
        height: Int = dp(50),
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
        marginStart = start
        marginEnd = end
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun <T : View> T.withTop(value: Int): T = apply {
        val current = layoutParams as? ViewGroup.MarginLayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        current.topMargin = value
        layoutParams = current
    }

    companion object {
        private const val NOTIFICATION_REQUEST = 2202
        private const val LOCAL_PACK_FILES_REQUEST = 2301
        private const val LOCAL_PACK_TREE_REQUEST = 2302
        private const val LOCAL_PACK_FILES_COMPAT_REQUEST = 2303
        private const val MAX_LOCAL_IMPORT_URIS = 80
        private const val DEFAULT_LOCAL_PACK_DOCUMENT_ID = "primary:Download/PetPacks"
        private const val LOCAL_SCAN_TREE_URI = "local_pack_scan_tree_uri"
        private const val LOCAL_SCAN_AUTO_ENABLED = "local_pack_scan_auto_enabled"
        private const val LOCAL_SCAN_LAST_AUTO_AT = "local_pack_scan_last_auto_at"
        private const val LOCAL_SCAN_CONFIRMED_HASHES = "local_pack_confirmed_hashes"
        private const val LOCAL_SCAN_CONFIRMED_SOURCES = "local_pack_confirmed_sources"
        private const val LOCAL_SCAN_AUTO_INTERVAL_MS = 5L * 60 * 1000
        private val PALETTE_BACKGROUND = 0xFFFFF7FA.toInt()
        private val PALETTE_TITLE = 0xFF4E3440.toInt()
        private val PALETTE_TEXT = 0xFF654D58.toInt()
        private val PALETTE_MUTED = 0xFF927883.toInt()
        private val PALETTE_ACCENT = 0xFFC65C82.toInt()
        private val PALETTE_ACCENT_DARK = 0xFF9D3E62.toInt()
        private val PALETTE_GREEN = 0xFF427A62.toInt()
        private val ACTION_ORDER = listOf("idle", "walk", "run", "wave", "photo_pose")
    }
}

private data class PreparedLocalPack(
    val snapshot: LocalPackSnapshot,
    val inspection: PackInspectionResult.Ready,
    val sameVersionConflict: Boolean,
)

private data class LocalCandidateBatch(
    val candidates: List<PreparedLocalPack>,
    val diagnostics: List<String>,
    val visitedEntries: Int,
    val contentDuplicates: Int,
    val invalid: Int,
    val refusedDowngrades: Int,
)
