package com.sweetgirlfriend.pet.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PetPackJsonIOTest {
    @Test
    fun readsSmallManifestWithinRoleLimit() {
        val json = PetPackJsonIO.read(
            ByteArrayInputStream("{\"id\":\"safe-pack\"}".toByteArray()),
            "pack.json",
        )

        assertEquals("safe-pack", json.getString("id"))
    }

    @Test
    fun boundedReaderRejectsLegacyOversizedInstalledManifestBeforeParsing() {
        val bytes = ByteArray(PetPackJsonIO.MAX_MANIFEST_BYTES.toInt() + 1) { ' '.code.toByte() }

        val error = runCatching {
            PetPackJsonIO.read(ByteArrayInputStream(bytes), "packs/legacy/pack.json")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("256 KiB"))
    }

    @Test
    fun appliesFourMegabyteCeilingToAllOtherStructuredJson() {
        assertEquals(4L * 1024 * 1024, PetPackJsonIO.limitForPath("dialogue/rules.json"))
        assertEquals(4L * 1024 * 1024, PetPackJsonIO.limitForPath("tasks/custom.JSON"))
    }
}
