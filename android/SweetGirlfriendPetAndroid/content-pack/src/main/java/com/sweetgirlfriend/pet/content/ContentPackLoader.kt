package com.sweetgirlfriend.pet.content

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import com.sweetgirlfriend.pet.runtime.AnimationClip
import com.sweetgirlfriend.pet.runtime.AvatarSpec
import com.sweetgirlfriend.pet.runtime.BehaviorSpec
import com.sweetgirlfriend.pet.runtime.BehaviorWeights
import com.sweetgirlfriend.pet.runtime.CharacterGameKit
import com.sweetgirlfriend.pet.runtime.MotionSpec
import com.sweetgirlfriend.pet.runtime.InteractionStyle
import com.sweetgirlfriend.pet.runtime.PackDescriptor
import com.sweetgirlfriend.pet.runtime.PackExtensionDescriptor
import com.sweetgirlfriend.pet.runtime.PackExtensionRegistry
import com.sweetgirlfriend.pet.runtime.PetPackManifestProtocol
import com.sweetgirlfriend.pet.runtime.PackSettingDefinition
import com.sweetgirlfriend.pet.runtime.PackSettingOption
import com.sweetgirlfriend.pet.runtime.PackSettingType
import com.sweetgirlfriend.pet.runtime.PetTask
import com.sweetgirlfriend.pet.runtime.TaskOption
import com.sweetgirlfriend.pet.runtime.PlayMode
import com.sweetgirlfriend.pet.runtime.StablePackVersion
import com.sweetgirlfriend.pet.runtime.WeatherCachePolicy
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.InputStream
import java.time.LocalDateTime
import kotlin.math.roundToInt

class ContentPackLoader private constructor(
    private val context: Context,
    private val installedRoot: File,
) : ContentPackRepository {
    private val assets get() = context.assets

    constructor(context: Context) : this(context, File(context.filesDir, "content-packs"))

    override fun listPacks(): List<PackDescriptor> {
        val index = readJson("packs/index.json")
        val ids = index.getJSONArray("packs")
        val bundledIds = buildList {
            for (position in 0 until ids.length()) {
                add(ids.getString(position))
            }
        }
        val installedIds = installedRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.filter { File(it, INSTALL_MARKER).isFile }
            ?.map(File::getName)
            ?.filter(::isSafeId)
            ?.toList()
            .orEmpty()
        return (bundledIds + installedIds)
            .distinct()
            .mapNotNull { packId -> runCatching { loadDescriptor(packId) }.getOrNull() }
    }

    fun loadDescriptor(packId: String): PackDescriptor {
        requireSafeId(packId)
        val root = "packs/$packId"
        val manifest = readJson("$root/pack.json")
        val schemaVersion = manifest.getInt("schemaVersion")
        require(schemaVersion in 1..2) { "Unsupported pack schema: $schemaVersion" }
        require(manifest.getString("id") == packId) { "Pack id does not match directory" }
        val version = manifest.getString("version")
        StablePackVersion.parseRequired(version)

        val protocol = manifest.optJSONObject("protocol")
        val legacyMinRuntime = protocol?.optString("minRuntime", "0.1.0")
            ?: manifest.optString("minRuntimeVersion", "0.1.0")
        val minRuntimeVersion = PetPackManifestProtocol.resolveMinRuntime(
            schemaVersion = schemaVersion,
            protocolId = protocol?.opt("id") as? String,
            protocolVersion = protocol?.opt("version") as? String,
            protocolMinRuntime = protocol?.opt("minRuntime") as? String,
            legacyMinRuntime = legacyMinRuntime,
        )
        val extensionArray = manifest.optJSONArray("extensions")
        val extensions = buildList {
            if (extensionArray != null) {
                for (position in 0 until extensionArray.length()) {
                    val node = extensionArray.getJSONObject(position)
                    val extension = PackExtensionDescriptor(
                        id = node.getString("id"),
                        apiVersion = node.getInt("apiVersion"),
                        entrypoint = node.getString("entrypoint"),
                        required = node.optBoolean("required", false),
                    )
                    requireSafeExtensionId(extension.id)
                    requireSafeRelativePath(extension.entrypoint)
                    add(extension)
                }
            }
        }

        val capabilityArray = manifest.optJSONArray("capabilities")
        val capabilities = buildSet {
            if (capabilityArray != null) {
                for (position in 0 until capabilityArray.length()) {
                    add(capabilityArray.getString(position))
                }
            }
        }
        return PackDescriptor(
            id = packId,
            name = PetPackMetadataPolicy.requireName(manifest.getString("name")),
            version = version,
            author = manifest.optString("author", "")
                .takeIf(String::isNotBlank)
                ?.let(PetPackMetadataPolicy::requireAuthor)
                ?: "Unknown",
            previewPath = "$root/${manifest.getString("preview")}",
            capabilities = capabilities,
            protocolVersion = protocol?.optString("version", "2.0") ?: "1.0",
            minRuntimeVersion = minRuntimeVersion,
            extensions = extensions,
            installed = installedPackRoot(packId) != null,
        )
    }

    override fun availableActions(packId: String): Set<String> {
        val actions = animationRoot(packId).getJSONObject("actions")
        return actions.keys().asSequence().toSet()
    }

    override fun loadClip(packId: String, action: String): AnimationClip {
        requireSafeId(packId)
        requireSafeId(action)
        val actions = animationRoot(packId).getJSONObject("actions")
        val resolvedAction = if (actions.has(action)) action else "idle"
        require(actions.has(resolvedAction)) { "Pack $packId must provide idle" }
        val node = actions.getJSONObject(resolvedAction)
        val frames = node.getJSONArray("frames")
        val base = "packs/$packId/character"
        val paths = buildList {
            for (position in 0 until frames.length()) {
                val relative = frames.getString(position)
                require(relative.startsWith("animations/") && !relative.contains("..")) {
                    "Unsafe frame path: $relative"
                }
                add("$base/$relative")
            }
        }
        require(paths.isNotEmpty()) { "Animation $resolvedAction has no frames" }
        return AnimationClip(
            action = resolvedAction,
            fps = node.optInt("fps", 4).coerceIn(1, 30),
            loop = node.optBoolean("loop", true),
            framePaths = paths,
            motion = node.optJSONObject("motion")?.let { motion ->
                val anchor = motion.optJSONArray("groundAnchor")
                val tags = motion.optJSONArray("sceneTags")
                val defaultFacing = motion.optString("defaultFacing", "front")
                val rotationPolicy = motion.optString("rotationPolicy", "upright")
                val groundAnchorX = anchor?.optDouble(0, 0.5)?.toFloat() ?: 0.5f
                val groundAnchorY = anchor?.optDouble(1, 0.94)?.toFloat() ?: 0.94f
                require(defaultFacing in setOf("front", "left", "right")) {
                    "Unsupported defaultFacing: $defaultFacing"
                }
                require(rotationPolicy in setOf("upright", "align-surface", "align-velocity")) {
                    "Unsupported rotationPolicy: $rotationPolicy"
                }
                require(groundAnchorX in 0f..1f && groundAnchorY in 0f..1f) {
                    "groundAnchor must use normalized coordinates"
                }
                MotionSpec(
                    defaultFacing = defaultFacing,
                    supportsHorizontalMirror = motion.optBoolean("supportsHorizontalMirror", true),
                    rotationPolicy = rotationPolicy,
                    groundAnchorX = groundAnchorX,
                    groundAnchorY = groundAnchorY,
                    sceneTags = buildSet {
                        if (tags != null) for (index in 0 until tags.length()) {
                            val tag = tags.getString(index)
                            requireSafeId(tag)
                            add(tag)
                        }
                    },
                )
            } ?: MotionSpec(),
        )
    }

    override fun loadGameKit(packId: String): CharacterGameKit {
        val extension = loadDescriptor(packId).extensions.firstOrNull {
            it.id == GAME_KIT_EXTENSION && PackExtensionRegistry.supports(it)
        } ?: return CharacterGameKit()
        val root = readJson("packs/$packId/${extension.entrypoint}")
        val avatarNode = root.optJSONObject("avatar")
        val avatar = avatarNode?.let { node ->
            val source = node.getString("source")
            requireSafeRelativePath(source)
            val crop = node.getJSONArray("crop")
            require(crop.length() == 4) { "Avatar crop must contain four values" }
            val left = crop.getDouble(0).toFloat().coerceIn(0f, 1f)
            val top = crop.getDouble(1).toFloat().coerceIn(0f, 1f)
            val right = crop.getDouble(2).toFloat().coerceIn(0f, 1f)
            val bottom = crop.getDouble(3).toFloat().coerceIn(0f, 1f)
            require(left < right && top < bottom) { "Avatar crop must have positive area" }
            val shape = node.optString("shape", "circle")
            require(shape == "circle" || shape == "rect") { "Unsupported avatar shape: $shape" }
            AvatarSpec(
                source = "packs/$packId/character/$source",
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom,
                shape = shape,
            )
        }
        val modeNodes = root.optJSONArray("supportedModes")
        val supportedModes = buildSet {
            if (modeNodes != null) for (index in 0 until modeNodes.length()) {
                val mode = modeNodes.getString(index)
                require(runCatching { PlayMode.valueOf(mode) }.isSuccess && mode != PlayMode.NORMAL.name) {
                    "Unsupported game mode: $mode"
                }
                add(mode)
            }
        }
        val arcadeModes = setOf(PlayMode.HIDE_SEEK, PlayMode.BOMBER, PlayMode.SNAKE).map(PlayMode::name)
        require(avatar != null || supportedModes.none { it in arcadeModes }) {
            "Arcade modes require an avatar definition"
        }
        return CharacterGameKit(
            avatar = avatar,
            supportedModes = supportedModes,
            accentColor = parseColor(root.optString("accentColor", "#C9577D"), 0xFFC9577D.toInt()),
            foodColor = parseColor(root.optString("foodColor", "#FFC3D8"), 0xFFFFC3D8.toInt()),
            bombColor = parseColor(root.optString("bombColor", "#6D5360"), 0xFF6D5360.toInt()),
        )
    }

    override fun loadBehavior(packId: String): BehaviorSpec {
        requireSafeId(packId)
        val pack = readJson("packs/$packId/pack.json")
        val entry = pack.getJSONObject("entrypoints").getString("behavior")
        requireSafeRelativePath(entry)
        val root = readJson("packs/$packId/$entry")
        require(root.optInt("schemaVersion", 1) == 1) { "Unsupported behavior schema" }
        val profileRoot = root.getJSONObject("profiles")
        val profiles = InteractionStyle.entries.associateWith { style ->
            val node = profileRoot.getJSONObject(style.storageValue)
            val weights = BehaviorWeights(
                idle = node.getInt("idleWeight"),
                walk = node.getInt("walkWeight"),
                run = node.getInt("runWeight"),
                social = node.getInt("socialWeight"),
            )
            require(listOf(weights.idle, weights.walk, weights.run, weights.social).all { it in 0..1_000 }) {
                "Behavior weights must be between 0 and 1000"
            }
            require(weights.total > 0) { "Behavior profile ${style.storageValue} has no selectable action" }
            weights
        }
        val fallbackAction = root.optString("fallbackAction", "idle")
        requireSafeId(fallbackAction)
        val restSeconds = root.optInt("manualPlacementRestSeconds", 300)
        require(restSeconds in 10..86_400) { "manualPlacementRestSeconds is out of range" }
        return BehaviorSpec(profiles, fallbackAction, restSeconds)
    }

    override fun loadTasks(packId: String): List<PetTask> {
        requireSafeId(packId)
        val pack = readJson("packs/$packId/pack.json")
        val entry = pack.getJSONObject("entrypoints").getString("tasks")
        requireSafeRelativePath(entry)
        val array = readJson("packs/$packId/$entry").getJSONArray("tasks")
        require(array.length() <= MAX_TASKS) { "Too many tasks" }
        val seenTaskIds = mutableSetOf<String>()
        return buildList {
            for (position in 0 until array.length()) {
                val node = array.getJSONObject(position)
                val id = node.getString("id")
                requireSafeId(id)
                require(seenTaskIds.add(id)) { "Duplicate task id: $id" }
                val action = node.optString("action", "wave")
                requireSafeId(action)
                val optionNodes = node.optJSONArray("options")
                if (optionNodes != null) {
                    require(optionNodes.length() <= MAX_TASK_OPTIONS) { "Task $id has too many options" }
                }
                val options = buildList {
                    if (optionNodes != null) {
                        for (optionIndex in 0 until optionNodes.length()) {
                            val option = optionNodes.getJSONObject(optionIndex)
                            val optionId = option.getString("id")
                            requireSafeId(optionId)
                            val optionAction = option.optString("action", action)
                            requireSafeId(optionAction)
                            val playModeName = option.optString("playMode", "")
                            val playMode = playModeName.takeIf(String::isNotBlank)?.let {
                                require(runCatching { PlayMode.valueOf(it) }.isSuccess && it != PlayMode.NORMAL.name) {
                                    "Unsupported task playMode: $it"
                                }
                                PlayMode.valueOf(it)
                            }
                            add(
                                TaskOption(
                                    id = optionId,
                                    label = option.getString("label"),
                                    response = option.optString("response", "我记住啦。"),
                                    action = optionAction,
                                    snoozeMinutes = option.optInt("snoozeMinutes", 0).coerceIn(0, 1440),
                                    playMode = playMode,
                                ),
                            )
                        }
                    }
                }.ifEmpty {
                    listOf(
                        TaskOption("done", "完成啦", "真棒，给你一个小小的拥抱。", action),
                        TaskOption("later", "稍后提醒", "好呀，等一会儿再来提醒你。", "idle", 15),
                    )
                }
                add(
                    PetTask(
                        id = id,
                        title = node.optString("title", "陪伴任务"),
                        prompt = node.getString("prompt"),
                        action = action,
                        cooldownMinutes = node.optInt("cooldownMinutes", 90).coerceIn(1, 10_080),
                        options = options,
                        hourStart = node.optJSONObject("when")?.optInt("hourStart", 0)?.coerceIn(0, 23) ?: 0,
                        hourEnd = node.optJSONObject("when")?.optInt("hourEnd", 23)?.coerceIn(0, 23) ?: 23,
                        weatherKinds = buildSet {
                            val values = node.optJSONObject("when")?.optJSONArray("weatherIn")
                            if (values != null) for (index in 0 until values.length()) add(values.getString(index))
                        },
                    ),
                )
            }
        }
    }

    override fun loadPackSettings(packId: String): List<PackSettingDefinition> {
        val extension = loadDescriptor(packId).extensions.firstOrNull {
            it.id == SETTINGS_EXTENSION && PackExtensionRegistry.supports(it)
        } ?: return emptyList()
        val nodes = readJson("packs/$packId/${extension.entrypoint}").getJSONArray("settings")
        return buildList {
            for (position in 0 until nodes.length()) {
                val node = nodes.getJSONObject(position)
                val key = node.getString("key")
                requireSafeSettingKey(key)
                val type = when (node.getString("type")) {
                    "boolean" -> PackSettingType.BOOLEAN
                    "integer" -> PackSettingType.INTEGER
                    "choice" -> PackSettingType.CHOICE
                    else -> error("Unsupported pack setting type")
                }
                val options = buildList {
                    val optionNodes = node.optJSONArray("options")
                    if (optionNodes != null) {
                        for (optionIndex in 0 until optionNodes.length()) {
                            val option = optionNodes.getJSONObject(optionIndex)
                            add(PackSettingOption(option.getString("value"), option.getString("label")))
                        }
                    }
                }
                add(
                    PackSettingDefinition(
                        key = key,
                        label = node.getString("label"),
                        description = node.optString("description", ""),
                        type = type,
                        defaultValue = node.get("default").toString(),
                        min = node.optInt("min", 0),
                        max = node.optInt("max", 100),
                        step = node.optInt("step", 1).coerceAtLeast(1),
                        options = options,
                    ),
                )
            }
        }
    }

    override fun randomDialogue(
        packId: String,
        event: String,
        fallback: String,
    ): String {
        requireSafeId(packId)
        requireSafeId(event)
        val root = dialogueRoot(packId)
        val preferences = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
        val effectiveEvent = if (event == "weather" && !weatherCacheUsable(preferences)) "idle" else event
        val candidates = buildList {
            addAll(dynamicDialogueLines(packId, effectiveEvent))
            val lines = root.optJSONArray(effectiveEvent) ?: root.optJSONArray("idle")
            if (lines != null) for (index in 0 until lines.length()) add(lines.getString(index))
        }.distinct()
        if (candidates.isEmpty()) return fallback
        return chooseNonRepeating(packId, effectiveEvent, candidates).expandContext()
    }

    override fun openAsset(path: String): InputStream {
        requireSafeRelativePath(path)
        val parts = path.split('/')
        if (parts.size >= 3 && parts.first() == "packs" && isSafeId(parts[1])) {
            val installedPack = installedPackRoot(parts[1])
            if (installedPack != null) {
                val relative = parts.drop(2).joinToString("/")
                val candidate = File(installedPack, relative)
                val canonicalRoot = installedPack.canonicalFile
                val canonicalCandidate = candidate.canonicalFile
                require(canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Unsafe installed resource path"
                }
                if (!canonicalCandidate.isFile) {
                    throw FileNotFoundException("Installed pack ${parts[1]} is missing $relative")
                }
                return FileInputStream(canonicalCandidate)
            }
        }
        return assets.open(path)
    }

    override fun validate(packId: String): List<String> {
        val errors = mutableListOf<String>()
        var totalBitmapPixels = 0L
        val descriptor = runCatching { loadDescriptor(packId) }
            .onFailure { errors += it.message ?: "Invalid pack" }
            .getOrNull()
        if (descriptor != null) {
            errors += PackExtensionRegistry.compatibilityErrors(descriptor)
            descriptor.extensions.forEach { extension ->
                runCatching { openAsset("packs/$packId/${extension.entrypoint}").use { it.read() } }
                    .onFailure { errors += "extension ${extension.id}: ${it.message}" }
            }
            runCatching { loadPackSettings(packId) }
                .onFailure { errors += "settings: ${it.message}" }
            runCatching { loadBehavior(packId) }
                .onFailure { errors += "behavior: ${it.message}" }
            runCatching {
                val gameKit = loadGameKit(packId)
                gameKit.avatar?.let { avatar ->
                    totalBitmapPixels += validateBitmap(avatar.source)
                }
            }.onFailure { errors += "game-kit: ${it.message}" }
            runCatching { validateDialogueRules(packId) }
                .onFailure { errors += "dialogue rules: ${it.message}" }
            errors += validateIntegrity(packId)
        }
        val actions = runCatching { availableActions(packId) }.getOrElse {
            errors += it.message ?: "Invalid animations manifest"
            emptySet()
        }
        if ("idle" !in actions) errors += "Missing required idle action"
        runCatching {
            val behavior = loadBehavior(packId)
            require(behavior.fallbackAction in actions) {
                "Behavior fallback action ${behavior.fallbackAction} is missing"
            }
        }.onFailure { errors += "behavior actions: ${it.message}" }
        actions.forEach { action ->
            runCatching {
                var clipPixels = 0L
                loadClip(packId, action).framePaths.forEach { path ->
                    val pixels = validateBitmap(path)
                    clipPixels += pixels
                    totalBitmapPixels += pixels
                    require(clipPixels <= MAX_CLIP_BITMAP_PIXELS) {
                        "Animation $action exceeds decoded pixel budget"
                    }
                    require(totalBitmapPixels <= MAX_PACK_BITMAP_PIXELS) {
                        "Pack exceeds decoded pixel budget"
                    }
                }
            }.onFailure { errors += "$action: ${it.message}" }
        }
        runCatching { validateDialogueManifest(packId) }
            .onFailure { errors += "dialogue: ${it.message}" }
        runCatching {
            val gameKit = loadGameKit(packId)
            val loadedTasks = loadTasks(packId)
            loadedTasks.forEach { task ->
                require(task.action in actions) { "Task ${task.id} references missing action ${task.action}" }
                task.options.forEach { option ->
                    require(option.action in actions) {
                        "Task ${task.id}/${option.id} references missing action ${option.action}"
                    }
                    option.playMode?.let { mode ->
                        require(mode.name in gameKit.supportedModes) {
                            "Task ${task.id}/${option.id} invites unsupported mode ${mode.name}"
                        }
                    }
                }
            }
        }.onFailure { errors += "tasks: ${it.message}" }
        return errors
    }

    private fun animationRoot(packId: String): JSONObject {
        requireSafeId(packId)
        val pack = readJson("packs/$packId/pack.json")
        val entry = pack.getJSONObject("entrypoints").getString("animations")
        requireSafeRelativePath(entry)
        return readJson("packs/$packId/$entry")
    }

    private fun dialogueRoot(packId: String): JSONObject {
        requireSafeId(packId)
        val pack = readJson("packs/$packId/pack.json")
        val entry = pack.getJSONObject("entrypoints").getString("dialogue")
        requireSafeRelativePath(entry)
        return readJson("packs/$packId/$entry")
    }

    private fun readJson(path: String): JSONObject =
        openAsset(path).use { PetPackJsonIO.read(it, path) }

    private fun dynamicDialogueLines(packId: String, event: String): List<String> {
        val descriptor = runCatching { loadDescriptor(packId) }.getOrNull() ?: return emptyList()
        val extension = descriptor.extensions.firstOrNull {
            it.id == DIALOGUE_RULES_EXTENSION && PackExtensionRegistry.supports(it)
        } ?: return emptyList()
        val rules = runCatching {
            readJson("packs/$packId/${extension.entrypoint}").getJSONArray("rules")
        }.getOrNull() ?: return emptyList()
        val date = LocalDateTime.now()
        val hour = date.hour
        val settings = loadPackSettings(packId).associateBy(PackSettingDefinition::key)
        val preferences = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
        val weatherUsable = weatherCacheUsable(preferences)
        if (event == "weather" && !weatherUsable) return emptyList()
        val matchingLines = buildList {
            for (position in 0 until rules.length()) {
                val rule = rules.getJSONObject(position)
                if (rule.optString("event") != event) continue
                val condition = rule.optJSONObject("when") ?: JSONObject()
                val start = condition.optInt("hourStart", 0).coerceIn(0, 23)
                val end = condition.optInt("hourEnd", 23).coerceIn(0, 23)
                val hourMatches = if (start <= end) hour in start..end else hour >= start || hour <= end
                if (!hourMatches) continue
                val weekday = condition.optInt("dayOfWeek", 0)
                if (weekday != 0 && date.dayOfWeek.value != weekday) continue
                val weather = if (weatherUsable) {
                    preferences.getString("weather_kind", "unknown") ?: "unknown"
                } else {
                    "unknown"
                }
                val weatherEquals = condition.optString("weatherEquals", "")
                val weatherIn = condition.optJSONArray("weatherIn")
                val requiresWeather = weatherEquals.isNotBlank() || weatherIn != null ||
                    condition.has("temperatureMin") || condition.has("temperatureMax")
                if (requiresWeather && !weatherUsable) continue
                if (weatherEquals.isNotBlank() && weather != weatherEquals) continue
                if (weatherIn != null && (0 until weatherIn.length()).none { weatherIn.getString(it) == weather }) continue
                val temperature = preferences.getFloat("weather_temperature", Float.NaN)
                if (condition.has("temperatureMin") && (temperature.isNaN() || temperature < condition.getDouble("temperatureMin"))) continue
                if (condition.has("temperatureMax") && (temperature.isNaN() || temperature > condition.getDouble("temperatureMax"))) continue
                val chance = condition.optDouble("chance", 1.0).coerceIn(0.0, 1.0)
                if (Math.random() > chance) continue
                val settingKey = condition.optString("settingKey", "")
                if (settingKey.isNotEmpty()) {
                    val definition = settings[settingKey] ?: continue
                    val storageKey = packSettingStorageKey(packId, settingKey)
                    val actual = preferences.getString(storageKey, definition.defaultValue) ?: definition.defaultValue
                    if (actual != condition.optString("settingEquals")) continue
                }
                val lines = rule.optJSONArray("lines") ?: continue
                for (lineIndex in 0 until lines.length()) add(lines.getString(lineIndex))
            }
        }
        return matchingLines
    }

    private fun validateDialogueManifest(packId: String) {
        val root = dialogueRoot(packId)
        require(root.optInt("schemaVersion", 1) in 1..2) { "Unsupported dialogue schema" }
        val idle = root.optJSONArray("idle")
        require(idle != null && idle.length() > 0) { "Dialogue must provide idle lines" }
        for (key in root.keys()) {
            if (key == "schemaVersion") continue
            requireSafeId(key)
            val lines = root.optJSONArray(key) ?: error("Dialogue event $key must be an array")
            require(lines.length() in 1..MAX_DIALOGUE_LINES_PER_EVENT) { "Invalid line count for $key" }
            for (index in 0 until lines.length()) {
                val line = lines.getString(index)
                require(line.isNotBlank() && line.length <= MAX_DIALOGUE_LINE_LENGTH) {
                    "Invalid dialogue line in $key"
                }
            }
        }
    }

    private fun validateDialogueRules(packId: String) {
        val descriptor = loadDescriptor(packId)
        val extension = descriptor.extensions.firstOrNull {
            it.id == DIALOGUE_RULES_EXTENSION && PackExtensionRegistry.supports(it)
        } ?: return
        val root = readJson("packs/$packId/${extension.entrypoint}")
        val rules = root.getJSONArray("rules")
        require(rules.length() <= MAX_DIALOGUE_RULES) { "Too many dialogue rules" }
        for (position in 0 until rules.length()) {
            val rule = rules.getJSONObject(position)
            requireSafeId(rule.getString("id"))
            requireSafeId(rule.getString("event"))
            val condition = rule.optJSONObject("when") ?: JSONObject()
            for (key in listOf("hourStart", "hourEnd")) {
                if (condition.has(key)) require(condition.getInt(key) in 0..23) { "Invalid $key" }
            }
            if (condition.has("dayOfWeek")) {
                require(condition.getInt("dayOfWeek") in 1..7) { "Invalid dayOfWeek" }
            }
            if (condition.has("chance")) {
                require(condition.getDouble("chance") in 0.0..1.0) { "Invalid chance" }
            }
            val lines = rule.getJSONArray("lines")
            require(lines.length() in 1..MAX_DIALOGUE_LINES_PER_EVENT) { "Invalid rule line count" }
            for (index in 0 until lines.length()) {
                require(lines.getString(index).isNotBlank()) { "Rule line cannot be blank" }
            }
        }
    }

    private fun validateBitmap(path: String): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openAsset(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid bitmap: $path" }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        require(pixels <= MAX_SINGLE_BITMAP_PIXELS) { "Bitmap dimensions are too large: $path" }
        val bitmap = openAsset(path).use { BitmapFactory.decodeStream(it) }
            ?: error("Unable to decode bitmap: $path")
        bitmap.recycle()
        return pixels
    }

    private fun chooseNonRepeating(packId: String, event: String, lines: List<String>): String {
        val preferences = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
        val key = "dialogue_recent_${packId}_$event"
        val recent = preferences.getString(key, "")
            .orEmpty()
            .split(RECENT_SEPARATOR)
            .filter(String::isNotBlank)
        val pool = lines.filterNot(recent::contains).ifEmpty { lines }
        val selected = pool.random()
        preferences.edit().putString(key, (listOf(selected) + recent).distinct().take(4).joinToString(RECENT_SEPARATOR)).apply()
        return selected
    }

    private fun String.expandContext(): String {
        val preferences = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
        val date = LocalDateTime.now()
        val period = when (date.hour) {
            in 5..10 -> "早上"
            in 11..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        val weatherUsable = weatherCacheUsable(preferences)
        val city = if (weatherUsable) {
            preferences.getString("weather_city_resolved", "").orEmpty().ifBlank { DEFAULT_CONTEXT_CITY }
        } else {
            DEFAULT_CONTEXT_CITY
        }
        val temperature = preferences.getFloat("weather_temperature", Float.NaN)
            .takeIf { weatherUsable && it.isFinite() }
            ?.roundToInt()
            ?.toString()
            ?: "--"
        return replace("{city}", city)
            .replace("{temperature}", temperature)
            .replace("{timePeriod}", period)
            .replace("{weekday}", "星期" + "一二三四五六日"[date.dayOfWeek.value - 1])
    }

    private fun weatherCacheUsable(preferences: SharedPreferences): Boolean =
        WeatherCachePolicy.isUsable(
            dynamicWeatherEnabled = preferences.getBoolean("dynamic_weather_enabled", true),
            configuredCity = preferences.getString("weather_city", DEFAULT_WEATHER_CITY).orEmpty(),
            cacheQuery = preferences.getString("weather_cache_query", "").orEmpty(),
            updatedAtMs = preferences.getLong("weather_updated_at", 0L),
        )

    private fun parseColor(value: String, fallback: Int): Int = runCatching {
        android.graphics.Color.parseColor(value)
    }.getOrDefault(fallback)

    private fun validateIntegrity(packId: String): List<String> {
        val manifest = runCatching { readJson("packs/$packId/pack.json") }.getOrNull() ?: return emptyList()
        val integrityPath = manifest.optString("integrity", "")
        if (integrityPath.isBlank()) return emptyList()
        requireSafeRelativePath(integrityPath)
        val integrity = runCatching { readJson("packs/$packId/$integrityPath") }
            .getOrElse { return listOf("integrity: ${it.message}") }
        val actualFiles = runCatching {
            listPackFiles(packId).filterNotTo(mutableSetOf()) { it in INTERNAL_PACK_FILES }
        }.getOrElse { return listOf(it.message ?: "Integrity file-set validation failed") }
        return PackIntegrityValidator.validate(
            integrity = integrity,
            integrityPath = integrityPath,
            actualFiles = actualFiles,
            openFile = { relative -> openAsset("packs/$packId/$relative") },
        )
    }

    private fun listPackFiles(packId: String): Set<String> {
        val installed = installedPackRoot(packId)
        if (installed != null) {
            val canonicalRoot = installed.canonicalFile
            return installed.walkTopDown()
                .filter(File::isFile)
                .map { file ->
                    file.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                }
                .toSet()
        }
        val result = mutableSetOf<String>()
        val assetRoot = "packs/$packId"
        fun visit(path: String, relative: String) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                if (relative.isNotEmpty()) result += relative
                return
            }
            children.forEach { child ->
                visit("$path/$child", if (relative.isEmpty()) child else "$relative/$child")
            }
        }
        visit(assetRoot, "")
        return result
    }

    private fun requireSafeId(value: String) {
        require(isSafeId(value)) { "Unsafe id: $value" }
    }

    private fun isSafeId(value: String): Boolean =
        value.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))

    private fun requireSafeExtensionId(value: String) {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{2,95}"))) { "Unsafe extension id: $value" }
    }

    private fun requireSafeSettingKey(value: String) {
        require(value.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Unsafe setting key: $value" }
    }

    /**
     * A user-installed pack normally overrides its bundled counterpart. On an APK upgrade the
     * bundled pack can itself become newer, so an older installed copy must not mask new protocol
     * data (for example a newly added game-kit). The user's files remain untouched and become
     * active again only after they are updated to the same or a newer semantic version.
     */
    private fun installedPackRoot(packId: String): File? {
        val installed = File(installedRoot, packId)
            .takeIf { it.isDirectory && File(it, INSTALL_MARKER).isFile }
            ?: return null
        val installedVersion = runCatching {
            StablePackVersion.parseRequired(
                PetPackJsonIO.read(
                    File(installed, "pack.json"),
                    "packs/$packId/pack.json",
                ).getString("version"),
                "Installed pack version",
            )
        }.getOrNull() ?: return null
        val bundledVersionText = runCatching {
            assets.open("packs/$packId/pack.json").use {
                PetPackJsonIO.read(it, "packs/$packId/pack.json").getString("version")
            }
        }.getOrNull() ?: return installed
        val bundledVersion = StablePackVersion.parseOrNull(bundledVersionText) ?: return null
        return installed.takeIf { installedVersion >= bundledVersion }
    }

    private fun requireSafeRelativePath(path: String) {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                !path.contains('\\') &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." },
        ) { "Unsafe resource path: $path" }
    }

    companion object {
        const val INSTALL_MARKER = ".installed-ok"
        const val INSTALL_RECEIPT = ".install-receipt.json"
        const val SETTINGS_EXTENSION = "io.sweetpet.pack-settings"
        const val DIALOGUE_RULES_EXTENSION = "io.sweetpet.dialogue-rules"
        const val GAME_KIT_EXTENSION = "io.sweetpet.game-kit"
        private const val RECENT_SEPARATOR = "\u001F"
        private const val MAX_SINGLE_BITMAP_PIXELS = 4_194_304L
        private const val MAX_CLIP_BITMAP_PIXELS = 16_777_216L
        private const val MAX_PACK_BITMAP_PIXELS = 67_108_864L
        private const val MAX_DIALOGUE_LINES_PER_EVENT = 256
        private const val MAX_DIALOGUE_LINE_LENGTH = 500
        private const val MAX_DIALOGUE_RULES = 256
        private const val MAX_TASKS = 512
        private const val MAX_TASK_OPTIONS = 4
        private const val DEFAULT_WEATHER_CITY = "北京"
        private const val DEFAULT_CONTEXT_CITY = "你这里"
        private val INTERNAL_PACK_FILES = setOf(INSTALL_MARKER, INSTALL_RECEIPT)

        fun packSettingStorageKey(packId: String, key: String): String = "pack_setting_${packId}_$key"

        /** Creates a loader that treats [installedRoot] as its app-private content-packs root. */
        internal fun forInstalledRoot(context: Context, installedRoot: File): ContentPackLoader =
            ContentPackLoader(context, installedRoot)
    }
}
