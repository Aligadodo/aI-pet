package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCachePolicyTest {
    @Test
    fun acceptsEnabledMatchingFreshCacheAtInclusiveBounds() {
        val now = 10_000_000L

        assertTrue(WeatherCachePolicy.isUsable(true, " Beijing ", "Beijing", now, now))
        assertTrue(
            WeatherCachePolicy.isUsable(
                true,
                "Beijing",
                "Beijing",
                now - WeatherCachePolicy.MAX_AGE_MS,
                now,
            ),
        )
    }

    @Test
    fun rejectsDisabledMismatchedStaleAndFutureCaches() {
        val now = 10_000_000L

        assertFalse(WeatherCachePolicy.isUsable(false, "Beijing", "Beijing", now, now))
        assertFalse(WeatherCachePolicy.isUsable(true, "Shanghai", "Beijing", now, now))
        assertFalse(
            WeatherCachePolicy.isUsable(
                true,
                "Beijing",
                "Beijing",
                now - WeatherCachePolicy.MAX_AGE_MS - 1L,
                now,
            ),
        )
        assertFalse(WeatherCachePolicy.isUsable(true, "Beijing", "Beijing", now + 1L, now))
    }
}
