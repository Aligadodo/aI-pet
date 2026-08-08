package com.sweetgirlfriend.pet.runtime

/**
 * Shared manifest-level protocol policy used by both loading and installation.
 *
 * Schema v1 keeps its caller-resolved legacy minimum. Schema v2 must declare a
 * complete protocol block and an explicit, stable runtime version.
 */
object PetPackManifestProtocol {
    fun resolveMinRuntime(
        schemaVersion: Int,
        protocolId: String?,
        protocolVersion: String?,
        protocolMinRuntime: String?,
        legacyMinRuntime: String,
    ): String = when (schemaVersion) {
        1 -> legacyMinRuntime
        2 -> {
            require(protocolId == PackExtensionRegistry.PACK_PROTOCOL_ID) {
                "Unsupported pack protocol"
            }
            require(protocolVersion != null && PetPackProtocolVersion.isSupported(protocolVersion)) {
                "Unsupported pack protocol version"
            }
            val minimumRuntime = requireNotNull(protocolMinRuntime) {
                "protocol.minRuntime is required for PetPack v2"
            }
            StablePackVersion.parseRequired(minimumRuntime, "protocol.minRuntime")
            minimumRuntime
        }
        else -> throw IllegalArgumentException("Unsupported pack schema: $schemaVersion")
    }
}
