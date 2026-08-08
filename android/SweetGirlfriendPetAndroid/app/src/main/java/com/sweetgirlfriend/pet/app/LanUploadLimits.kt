package com.sweetgirlfriend.pet.app

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class LanHeaderDecision {
    ACCEPT,
    TOO_MANY,
    TOO_LARGE,
    DUPLICATE,
}

internal class LanHttpHeaderBudget(
    private val maxLines: Int,
    private val maxBytes: Int,
    initialBytes: Int,
) {
    private val names = hashSetOf<String>()
    private var lineCount = 0
    private var totalBytes = initialBytes

    fun record(name: String, rawLineBytes: Int): LanHeaderDecision {
        lineCount += 1
        totalBytes += rawLineBytes
        return when {
            lineCount > maxLines -> LanHeaderDecision.TOO_MANY
            totalBytes > maxBytes -> LanHeaderDecision.TOO_LARGE
            !names.add(name) -> LanHeaderDecision.DUPLICATE
            else -> LanHeaderDecision.ACCEPT
        }
    }
}

internal object LanClientExecutorPolicy {
    const val WORKER_COUNT = 2
    const val QUEUE_CAPACITY = 4

    fun create(threadFactory: ThreadFactory): ThreadPoolExecutor = ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )
}
