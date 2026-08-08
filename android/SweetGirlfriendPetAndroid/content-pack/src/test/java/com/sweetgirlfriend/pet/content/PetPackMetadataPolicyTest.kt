package com.sweetgirlfriend.pet.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPackMetadataPolicyTest {
    @Test
    fun acceptsShortHumanReadableLabels() {
        assertEquals("海边夏日", PetPackMetadataPolicy.requireName("海边夏日"))
        assertEquals("Sweet Pet", PetPackMetadataPolicy.requireAuthor("Sweet Pet"))
    }

    @Test
    fun rejectsOversizedAndBidirectionalSpoofingLabels() {
        val oversized = runCatching {
            PetPackMetadataPolicy.requireName("a".repeat(PetPackMetadataPolicy.MAX_NAME_LENGTH + 1))
        }.exceptionOrNull()
        val bidi = runCatching {
            PetPackMetadataPolicy.requireAuthor("trusted\u202Eexe")
        }.exceptionOrNull()

        assertTrue(oversized is IllegalArgumentException)
        assertTrue(bidi is IllegalArgumentException)
    }
}
