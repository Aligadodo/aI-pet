package com.sweetgirlfriend.pet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPackScanPolicyTest {
    @Test
    fun `unsupported last modified metadata is optional and projections stay minimal`() {
        assertEquals(
            listOf("document_id", "_display_name", "mime_type"),
            LocalPackMetadataPolicy.treeRequiredProjection().toList(),
        )
        assertEquals(listOf("_display_name"), LocalPackMetadataPolicy.selectedNameProjection().toList())
        assertEquals(listOf("last_modified"), LocalPackMetadataPolicy.optionalProjection("last_modified").toList())

        var fallbackReached = false
        var reportedFailure = false
        val modified = LocalPackMetadataPolicy.readOptional<Long>(
            query = { throw IllegalArgumentException("Invalid column last modified") },
            onFailure = { reportedFailure = true },
        )
        fallbackReached = true

        assertNull(modified)
        assertTrue(reportedFailure)
        assertTrue(fallbackReached)
    }

    @Test
    fun `optional metadata fallback never swallows cancellation`() {
        assertThrows(InterruptedException::class.java) {
            LocalPackMetadataPolicy.readOptional<Long>(
                query = { throw InterruptedException("扫描已取消") },
            )
        }
    }

    @Test
    fun `only petpack extension is accepted case insensitively`() {
        assertTrue(LocalPackScanPolicy.isPetPackName("summer.petpack"))
        assertTrue(LocalPackScanPolicy.isPetPackName("SUMMER.PETPACK"))
        assertFalse(LocalPackScanPolicy.isPetPackName(".petpack"))
        assertFalse(LocalPackScanPolicy.isPetPackName("summer.petpack.zip"))
        assertFalse(LocalPackScanPolicy.isPetPackName("summer.apk"))
        assertTrue(LocalPackScanPolicy.isPetPackName("x".repeat(500) + ".petpack"))
    }

    @Test
    fun `source fingerprint changes with uri metadata or size`() {
        val original = LocalPackScanPolicy.sourceFingerprint("content://packs/summer", 100L, 12L)
        assertNotEquals(original, LocalPackScanPolicy.sourceFingerprint("content://packs/summer2", 100L, 12L))
        assertNotEquals(original, LocalPackScanPolicy.sourceFingerprint("content://packs/summer", 101L, 12L))
        assertNotEquals(original, LocalPackScanPolicy.sourceFingerprint("content://packs/summer", 100L, 13L))
    }

    @Test
    fun `confirmed content hash is suppressed even when source moves`() {
        assertTrue(
            LocalPackScanPolicy.wasConfirmed(
                sha256 = "abc",
                sourceFingerprint = "new-source",
                confirmedHashes = setOf("abc"),
                confirmedSources = emptySet(),
            ),
        )
    }

    @Test
    fun `source record requires the same hash so changed content is prompted`() {
        val source = "stable-source"
        assertTrue(
            LocalPackScanPolicy.wasConfirmed(
                sha256 = "old-hash",
                sourceFingerprint = source,
                confirmedHashes = emptySet(),
                confirmedSources = setOf("$source:old-hash"),
            ),
        )
        assertFalse(
            LocalPackScanPolicy.wasConfirmed(
                sha256 = "new-hash",
                sourceFingerprint = source,
                confirmedHashes = emptySet(),
                confirmedSources = setOf("$source:old-hash"),
            ),
        )
    }

    @Test
    fun `provider metadata is bounded and control characters are removed`() {
        val raw = "  summer\n\u0000\tpack\u202E  " + "x".repeat(300)
        val safe = LocalPackScanPolicy.safeUiText(raw)
        assertFalse(safe.any { it.isISOControl() })
        assertFalse(safe.contains('\u202E'))
        assertFalse(safe.contains("  "))
        assertTrue(safe.startsWith("summer pack"))
        assertTrue(safe.length <= 120)
    }

    @Test
    fun `only old abandoned sessions are eligible for cleanup`() {
        val now = 200_000_000L
        val day = 24L * 60 * 60 * 1000
        assertTrue(LocalPackScanPolicy.isStaleSession(now - day - 1, now, day))
        assertFalse(LocalPackScanPolicy.isStaleSession(now - day, now, day))
        assertFalse(LocalPackScanPolicy.isStaleSession(now - 1, now, day))
        assertFalse(LocalPackScanPolicy.isStaleSession(0, now, day))
        assertFalse(LocalPackScanPolicy.isStaleSession(now + 1, now, day))
    }
}
