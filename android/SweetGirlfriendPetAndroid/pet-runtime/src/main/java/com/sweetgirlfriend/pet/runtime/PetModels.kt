package com.sweetgirlfriend.pet.runtime

data class AnimationClip(
    val action: String,
    val fps: Int,
    val loop: Boolean,
    val framePaths: List<String>,
    val motion: MotionSpec = MotionSpec(),
)

data class MotionSpec(
    val defaultFacing: String = "front",
    val supportsHorizontalMirror: Boolean = true,
    val rotationPolicy: String = "upright",
    val groundAnchorX: Float = 0.5f,
    val groundAnchorY: Float = 0.94f,
    val sceneTags: Set<String> = emptySet(),
)

data class AvatarSpec(
    val source: String,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float,
    val shape: String = "circle",
)

data class CharacterGameKit(
    val avatar: AvatarSpec? = null,
    val supportedModes: Set<String> = emptySet(),
    val accentColor: Int = 0xFFC9577D.toInt(),
    val foodColor: Int = 0xFFFFC3D8.toInt(),
    val bombColor: Int = 0xFF6D5360.toInt(),
)

data class BehaviorWeights(
    val idle: Int = 40,
    val walk: Int = 30,
    val run: Int = 10,
    val social: Int = 20,
) {
    val total: Int get() = idle + walk + run + social
}

data class BehaviorSpec(
    val profiles: Map<InteractionStyle, BehaviorWeights> = InteractionStyle.entries.associateWith {
        when (it) {
            InteractionStyle.DAILY -> BehaviorWeights()
            InteractionStyle.SWEET -> BehaviorWeights(idle = 28, walk = 22, run = 12, social = 38)
            InteractionStyle.QUIET -> BehaviorWeights(idle = 80, walk = 15, run = 0, social = 5)
        }
    },
    val fallbackAction: String = "idle",
    val manualPlacementRestSeconds: Int = 300,
) {
    fun weights(style: InteractionStyle): BehaviorWeights =
        profiles[style] ?: profiles[InteractionStyle.DAILY] ?: BehaviorWeights()
}

data class PackDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val previewPath: String,
    val capabilities: Set<String>,
    val protocolVersion: String = "1.0",
    val minRuntimeVersion: String = "0.1.0",
    val extensions: List<PackExtensionDescriptor> = emptyList(),
    val installed: Boolean = false,
)

data class PackExtensionDescriptor(
    val id: String,
    val apiVersion: Int,
    val entrypoint: String,
    val required: Boolean,
)

enum class PackSettingType {
    BOOLEAN,
    INTEGER,
    CHOICE,
}

data class PackSettingOption(
    val value: String,
    val label: String,
)

data class PackSettingDefinition(
    val key: String,
    val label: String,
    val description: String,
    val type: PackSettingType,
    val defaultValue: String,
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val options: List<PackSettingOption> = emptyList(),
)

enum class InteractionStyle(val storageValue: String) {
    DAILY("daily"),
    SWEET("sweet"),
    QUIET("quiet"),
}

enum class DisplayMode {
    WALLPAPER,
    OVERLAY,
    BOTH,
}

enum class InteractionFrequency(val automaticTaskMinutes: Int) {
    GENTLE(30),
    STANDARD(8),
    ACTIVE(3),
}

enum class BackgroundPresentation {
    REPLACE_BACKGROUND,
    TRANSPARENT_OVERLAY,
}

enum class PlayMode {
    NORMAL,
    GRAVITY,
    BORDER_WALK,
    BORDER_RUN,
    HIDE_SEEK,
    BOMBER,
    SNAKE,
}

enum class EnergyProfile {
    ADAPTIVE,
    SMOOTH,
    SAVER,
}

enum class PetActivityLevel {
    INTERACTING,
    ACTIVE,
    IDLE,
    SLEEP,
}

data class TaskOption(
    val id: String,
    val label: String,
    val response: String,
    val action: String = "wave",
    val snoozeMinutes: Int = 0,
    val playMode: PlayMode? = null,
)

data class PetTask(
    val id: String,
    val title: String,
    val prompt: String,
    val action: String,
    val cooldownMinutes: Int,
    val options: List<TaskOption>,
    val hourStart: Int = 0,
    val hourEnd: Int = 23,
    val weatherKinds: Set<String> = emptySet(),
)

data class RuntimeSettings(
    val packId: String = "girlfriend-classic",
    val interactionStyle: InteractionStyle = InteractionStyle.DAILY,
    val sizePercent: Int = 78,
    val speedPercent: Int = 100,
    val dialogueEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = true,
    val displayMode: DisplayMode = DisplayMode.WALLPAPER,
    val interactionFrequency: InteractionFrequency = InteractionFrequency.STANDARD,
    val backgroundPresentation: BackgroundPresentation = BackgroundPresentation.REPLACE_BACKGROUND,
    val energyProfile: EnergyProfile = EnergyProfile.ADAPTIVE,
    val tasksEnabled: Boolean = true,
    val snapToEdge: Boolean = false,
    val inactivitySleepMinutes: Int = 10,
    val manualRestMinutes: Int = 5,
)
