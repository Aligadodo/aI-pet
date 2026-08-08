package com.sweetgirlfriend.pet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingPolicyTest {
    @Test
    fun `generated and displayed pairing codes keep leading zeroes`() {
        repeat(100) {
            val code = LanPairingPolicy.generateCode()
            assertTrue(code.matches(Regex("[0-9]{6}")))
            assertEquals(7, LanPairingPolicy.displayCode(code).length)
            assertEquals(code, LanPairingPolicy.normalizeCode(LanPairingPolicy.displayCode(code)))
        }
        assertEquals("001234", LanPairingPolicy.normalizeCode("001-234"))
        assertNull(LanPairingPolicy.normalizeCode("12345"))
        assertNull(LanPairingPolicy.normalizeCode("12345x"))
        assertNull(LanPairingPolicy.normalizeCode("１２３４５６"))
    }

    @Test
    fun `query form and cookie parsing handle encoding without crashing`() {
        val bare = LanPairingPolicy.browserUrl("192.168.1.23", 4567)
        val full = LanPairingPolicy.fullUrl("192.168.1.23", 4567, "abc-_")
        assertEquals("http://192.168.1.23:4567/", bare)
        assertEquals("http://192.168.1.23:4567/?token=abc-_", full)
        assertEquals("abc-_", LanPairingPolicy.queryValue("/?x=1&token=abc-_", "token"))
        assertEquals("123 456", LanPairingPolicy.formValue("code=123+456", "code"))
        assertEquals("secret", LanPairingPolicy.cookieValue("theme=pink; SweetPet-Session=secret", "SweetPet-Session"))
        assertNull(LanPairingPolicy.queryValue("/?token=%ZZ", "token"))
    }

    @Test
    fun `full token and paired cookie authenticate independently`() {
        assertTrue(LanPairingPolicy.credentialMatches("/?token=secret", null, "secret"))
        assertTrue(
            LanPairingPolicy.credentialMatches(
                "/upload",
                "SweetPet-Session=secret; theme=pink",
                "secret",
            ),
        )
        assertFalse(LanPairingPolicy.credentialMatches("/?token=wrong", "SweetPet-Session=secret", "secret"))
        assertFalse(LanPairingPolicy.credentialMatches("/", null, "secret"))
    }

    @Test
    fun `six failed codes lock one peer temporarily and success resets failures`() {
        val limiter = LanPairingAttemptLimiter(maxFailures = 6, lockoutMillis = 60_000L)
        repeat(5) { assertTrue(limiter.recordFailure("192.168.1.8", 1_000L).allowed) }
        val locked = limiter.recordFailure("192.168.1.8", 1_000L)
        assertFalse(locked.allowed)
        assertEquals(60_000L, locked.retryAfterMillis)
        assertFalse(limiter.check("192.168.1.8", 30_000L).allowed)
        assertTrue(limiter.check("192.168.1.9", 30_000L).allowed)
        assertTrue(limiter.check("192.168.1.8", 61_001L).allowed)

        limiter.recordFailure("192.168.1.8", 70_000L)
        limiter.recordSuccess("192.168.1.8")
        assertTrue(limiter.check("192.168.1.8", 70_000L).allowed)
    }

    @Test
    fun `expiry boundary is fail closed`() {
        assertFalse(LanPairingPolicy.isExpired(999L, 1_000L))
        assertTrue(LanPairingPolicy.isExpired(1_000L, 1_000L))
        assertTrue(LanPairingPolicy.isExpired(1_001L, 1_000L))
    }
}
