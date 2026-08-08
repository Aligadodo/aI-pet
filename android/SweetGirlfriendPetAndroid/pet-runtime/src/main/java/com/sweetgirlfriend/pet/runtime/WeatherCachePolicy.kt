package com.sweetgirlfriend.pet.runtime

/** Shared validity contract for every consumer of persisted weather context. */
object WeatherCachePolicy {
    const val MAX_AGE_MS = 2L * 60L * 60_000L

    fun isUsable(
        dynamicWeatherEnabled: Boolean,
        configuredCity: String,
        cacheQuery: String,
        updatedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!dynamicWeatherEnabled) return false
        val expectedQuery = configuredCity.trim()
        if (expectedQuery.isEmpty() || cacheQuery != expectedQuery) return false
        return nowMs - updatedAtMs in 0L..MAX_AGE_MS
    }
}
