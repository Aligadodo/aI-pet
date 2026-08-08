package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackExtensionRegistryTest {
    @Test
    fun supportsRegisteredExtensionVersion() {
        assertTrue(
            PackExtensionRegistry.supports(
                PackExtensionDescriptor("io.sweetpet.pack-settings", 1, "settings/schema.json", false),
            ),
        )
    }

    @Test
    fun supportsDeclarativeGameKit() {
        assertTrue(
            PackExtensionRegistry.supports(
                PackExtensionDescriptor("io.sweetpet.game-kit", 1, "character/game-kit.json", false),
            ),
        )
    }

    @Test
    fun rejectsUnknownRequiredExtension() {
        val descriptor = descriptor(
            extensions = listOf(
                PackExtensionDescriptor("io.example.native-code", 1, "extension.json", true),
            ),
        )
        assertEquals(1, PackExtensionRegistry.compatibilityErrors(descriptor).size)
    }

    @Test
    fun rejectsNewerRuntimeRequirement() {
        val errors = PackExtensionRegistry.compatibilityErrors(
            descriptor(minRuntimeVersion = "9.0.0"),
        )
        assertTrue(errors.single().contains("9.0.0"))
    }

    @Test
    fun rejectsMalformedOrPrereleaseRuntimeRequirement() {
        listOf("0.5", "next", "0.5.0-rc.1", "2147483648.0.0").forEach { version ->
            val errors = PackExtensionRegistry.compatibilityErrors(
                descriptor(minRuntimeVersion = version),
            )
            assertEquals(version, 1, errors.size)
            assertTrue(version, errors.single().contains(version))
        }
    }

    @Test
    fun rejectsMalformedV2ProtocolVersion() {
        val errors = PackExtensionRegistry.compatibilityErrors(
            descriptor(protocolVersion = "2.foo"),
        )
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("2.foo"))
    }

    private fun descriptor(
        minRuntimeVersion: String = "0.3.0",
        protocolVersion: String = "2.0",
        extensions: List<PackExtensionDescriptor> = emptyList(),
    ) = PackDescriptor(
        id = "test-pack",
        name = "Test",
        version = "1.0.0",
        author = "Test",
        previewPath = "packs/test-pack/preview.png",
        capabilities = emptySet(),
        protocolVersion = protocolVersion,
        minRuntimeVersion = minRuntimeVersion,
        extensions = extensions,
    )
}
