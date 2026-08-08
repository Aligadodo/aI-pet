package com.sweetgirlfriend.pet.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanUploadLimitsTest {
    @Test
    fun `client executor rejects connections after bounded workers and queue are full`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(LanClientExecutorPolicy.WORKER_COUNT)
        val executor = LanClientExecutorPolicy.create { runnable -> Thread(runnable) }
        try {
            repeat(LanClientExecutorPolicy.WORKER_COUNT) {
                executor.execute {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(LanClientExecutorPolicy.QUEUE_CAPACITY) {
                executor.execute { release.await(5, TimeUnit.SECONDS) }
            }
            assertThrows(RejectedExecutionException::class.java) {
                executor.execute { Unit }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `header budget counts repeated names actual lines and aggregate bytes`() {
        val duplicateBudget = LanHttpHeaderBudget(maxLines = 64, maxBytes = 32_768, initialBytes = 16)
        assertEquals(LanHeaderDecision.ACCEPT, duplicateBudget.record("host", 20))
        assertEquals(LanHeaderDecision.DUPLICATE, duplicateBudget.record("host", 20))

        val lineBudget = LanHttpHeaderBudget(maxLines = 2, maxBytes = 32_768, initialBytes = 16)
        assertEquals(LanHeaderDecision.ACCEPT, lineBudget.record("a", 4))
        assertEquals(LanHeaderDecision.ACCEPT, lineBudget.record("b", 4))
        assertEquals(LanHeaderDecision.TOO_MANY, lineBudget.record("c", 4))

        val byteBudget = LanHttpHeaderBudget(maxLines = 64, maxBytes = 32, initialBytes = 16)
        assertEquals(LanHeaderDecision.ACCEPT, byteBudget.record("a", 8))
        assertEquals(LanHeaderDecision.TOO_LARGE, byteBudget.record("b", 9))
    }
}
