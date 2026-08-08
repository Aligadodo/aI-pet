package com.sweetgirlfriend.pet.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRequestCoordinatorTest {
    @Test
    fun sameQueryJoinsInflightBeforeUsingCacheAndAllCallbacksReceiveFinalResult() {
        val coordinator = WeatherRequestCoordinator<String>()
        val received = mutableListOf<String>()
        val first = coordinator.begin("Beijing", cached = null, allowCached = false) {
            received += "force:${it.getOrThrow()}"
        }
        val request = (first as WeatherRequestCoordinator.Decision.Start).request

        val second = coordinator.begin("Beijing", cached = "old-cache", allowCached = true) {
            received += "normal:${it.getOrThrow()}"
        }

        assertTrue(second is WeatherRequestCoordinator.Decision.Joined)
        val completion = coordinator.completeIfCurrent(request) { "network-final" }!!
        completion.callbacks.forEach { it(Result.success(completion.value)) }
        assertEquals(listOf("force:network-final", "normal:network-final"), received)
    }

    @Test
    fun newerDifferentQueryRejectsOldCompletionBeforeCacheWrite() {
        val coordinator = WeatherRequestCoordinator<String>()
        val old = coordinator.begin("Beijing", null, false) { error("must not be delivered") }
            as WeatherRequestCoordinator.Decision.Start
        val current = coordinator.begin("Shanghai", null, false) { }
            as WeatherRequestCoordinator.Decision.Start
        var writes = 0

        assertNull(coordinator.completeIfCurrent(old.request) { ++writes })
        assertEquals(0, writes)
        assertTrue(coordinator.completeIfCurrent(current.request) { ++writes } != null)
        assertEquals(1, writes)
    }

    @Test
    fun cacheIsUsedWhenNoSameQueryRequestIsInflight() {
        val coordinator = WeatherRequestCoordinator<String>()

        val decision = coordinator.begin("Beijing", "fresh-cache", true) { }

        assertTrue(decision is WeatherRequestCoordinator.Decision.Cached)
        assertTrue(coordinator.isCurrent("Beijing"))
    }
}
