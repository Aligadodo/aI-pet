package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PetPackManifestProtocolTest {
    @Test
    fun `v2 rejects a missing minimum runtime`() {
        assertThrows(IllegalArgumentException::class.java) {
            PetPackManifestProtocol.resolveMinRuntime(
                schemaVersion = 2,
                protocolId = PackExtensionRegistry.PACK_PROTOCOL_ID,
                protocolVersion = "2.0",
                protocolMinRuntime = null,
                legacyMinRuntime = "0.1.0",
            )
        }
    }

    @Test
    fun `v2 accepts an explicit stable minimum runtime`() {
        assertEquals(
            "0.5.0",
            PetPackManifestProtocol.resolveMinRuntime(
                schemaVersion = 2,
                protocolId = PackExtensionRegistry.PACK_PROTOCOL_ID,
                protocolVersion = "2.0",
                protocolMinRuntime = "0.5.0",
                legacyMinRuntime = "0.1.0",
            ),
        )
    }

    @Test
    fun `v2 rejects a non-stable minimum runtime`() {
        assertThrows(IllegalArgumentException::class.java) {
            PetPackManifestProtocol.resolveMinRuntime(
                schemaVersion = 2,
                protocolId = PackExtensionRegistry.PACK_PROTOCOL_ID,
                protocolVersion = "2.0",
                protocolMinRuntime = "0.5.0-beta.1",
                legacyMinRuntime = "0.1.0",
            )
        }
    }

    @Test
    fun `v1 retains its legacy minimum runtime behavior`() {
        assertEquals(
            "0.3.0",
            PetPackManifestProtocol.resolveMinRuntime(
                schemaVersion = 1,
                protocolId = null,
                protocolVersion = null,
                protocolMinRuntime = null,
                legacyMinRuntime = "0.3.0",
            ),
        )
    }
}
