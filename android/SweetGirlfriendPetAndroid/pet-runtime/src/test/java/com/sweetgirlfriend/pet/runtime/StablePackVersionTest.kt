package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePackVersionTest {
    @Test
    fun acceptsAndOrdersStableThreePartVersions() {
        val base = StablePackVersion.parseRequired("1.2.3")
        assertEquals("1.2.3", base.toString())
        assertTrue(StablePackVersion.parseRequired("2.0.0") > base)
        assertTrue(StablePackVersion.parseRequired("1.3.0") > base)
        assertTrue(StablePackVersion.parseRequired("1.2.4") > base)
        assertEquals(base, StablePackVersion.parseRequired("1.2.3"))
    }

    @Test
    fun acceptsLargestNonOverflowingComponent() {
        assertEquals("2147483647.0.0", StablePackVersion.parseRequired("2147483647.0.0").toString())
    }

    @Test
    fun rejectsPrereleaseAndBuildMetadata() {
        listOf("1.2.3-alpha", "1.2.3+build.7", "1.2.3-rc.1+build.7")
            .forEach { assertNull(it, StablePackVersion.parseOrNull(it)) }
    }

    @Test
    fun rejectsMalformedComponents() {
        listOf("1", "1.2", "1.2.3.4", "v1.2.3", "1.x.3", "1..3", " 1.2.3", "1.2.3 ")
            .forEach { assertNull(it, StablePackVersion.parseOrNull(it)) }
    }

    @Test
    fun rejectsOverflowSignsAndLeadingZeroes() {
        listOf(
            "2147483648.0.0", "0.2147483648.0", "0.0.2147483648",
            "-1.2.3", "+1.2.3", "01.2.3", "1.02.3", "1.2.03",
        ).forEach { assertNull(it, StablePackVersion.parseOrNull(it)) }
    }

    @Test
    fun protocolVersionRequiresMajorTwoAndNumericMinor() {
        listOf("2.0", "2.1", "2.999999999999999999999").forEach {
            assertTrue(it, PetPackProtocolVersion.isSupported(it))
        }
        listOf("2.foo", "2", "2.0.0", "3.0", " 2.0", "2.0 ").forEach {
            assertTrue(it, !PetPackProtocolVersion.isSupported(it))
        }
    }
}
