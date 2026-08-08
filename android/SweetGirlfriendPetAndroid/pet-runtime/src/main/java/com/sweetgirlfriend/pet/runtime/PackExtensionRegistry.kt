package com.sweetgirlfriend.pet.runtime

object PackExtensionRegistry {
    const val PACK_PROTOCOL_ID = "io.sweetpet.pack"
    const val PACK_PROTOCOL_MAJOR = 2
    const val RUNTIME_VERSION = "0.5.0"

    private val supportedExtensions = mapOf(
        "io.sweetpet.pack-settings" to setOf(1),
        "io.sweetpet.dialogue-rules" to setOf(1),
        "io.sweetpet.game-kit" to setOf(1),
    )

    fun supports(extension: PackExtensionDescriptor): Boolean =
        extension.apiVersion in supportedExtensions[extension.id].orEmpty()

    fun compatibilityErrors(descriptor: PackDescriptor): List<String> = buildList {
        if (descriptor.protocolVersion != LEGACY_PROTOCOL_VERSION &&
            !PetPackProtocolVersion.isSupported(descriptor.protocolVersion)
        ) {
            add("资源包协议 ${descriptor.protocolVersion} 不受支持；PetPack v2 必须使用 2.<数字>")
        }
        val minimumRuntime = StablePackVersion.parseOrNull(descriptor.minRuntimeVersion)
        if (minimumRuntime == null) {
            add("资源包要求的运行程序版本必须是稳定的 x.y.z：${descriptor.minRuntimeVersion}")
        } else if (minimumRuntime > StablePackVersion.parseRequired(RUNTIME_VERSION, "Runtime version")) {
            add("资源包要求运行程序 ${descriptor.minRuntimeVersion}，当前为 $RUNTIME_VERSION")
        }
        descriptor.extensions
            .filter { it.required && !supports(it) }
            .forEach { add("不支持必需扩展 ${it.id}/${it.apiVersion}") }
    }

    private const val LEGACY_PROTOCOL_VERSION = "1.0"
}
