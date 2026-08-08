package com.sweetgirlfriend.pet.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sweetgirlfriend.pet.runtime.WeatherCachePolicy

data class WeatherSnapshot(
    val city: String,
    val temperature: Float,
    val kind: String,
    val description: String,
    val updatedAt: Long,
    val source: String = "",
)

/** Weather context without location permission: the user explicitly supplies a city name. */
class WeatherContextProvider(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(OverlayPetController.PREFERENCES, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val apiClient = WeatherApiClient(
        uapiBackoff = WeatherProviderBackoff(
            initialBlockedUntilMs = preferences.getLong(KEY_UAPI_BACKOFF_UNTIL, 0L),
            readBlockedUntil = { preferences.getLong(KEY_UAPI_BACKOFF_UNTIL, 0L) },
            onBlockedUntil = { blockedUntil ->
                preferences.edit().putLong(KEY_UAPI_BACKOFF_UNTIL, blockedUntil).apply()
            },
        ),
    )

    fun cached(nowMs: Long = System.currentTimeMillis()): WeatherSnapshot? {
        val configuredCity = preferences.getString(KEY_CONFIGURED_CITY, DEFAULT_CITY).orEmpty()
        if (!WeatherCachePolicy.isUsable(
                dynamicWeatherEnabled = preferences.getBoolean(KEY_DYNAMIC_ENABLED, true),
                configuredCity = configuredCity,
                cacheQuery = preferences.getString(KEY_CACHE_QUERY, "").orEmpty(),
                updatedAtMs = preferences.getLong("weather_updated_at", 0L),
                nowMs = nowMs,
            )
        ) return null
        val city = preferences.getString("weather_city_resolved", "").orEmpty()
        val updatedAt = preferences.getLong("weather_updated_at", 0L)
        if (city.isBlank() || updatedAt <= 0L) return null
        return WeatherSnapshot(
            city = city,
            temperature = preferences.getFloat("weather_temperature", Float.NaN),
            kind = preferences.getString("weather_kind", "unknown") ?: "unknown",
            description = preferences.getString("weather_description", "天气未知") ?: "天气未知",
            updatedAt = updatedAt,
            source = preferences.getString("weather_source", "").orEmpty(),
        )
    }

    fun refresh(city: String, force: Boolean = false, callback: (Result<WeatherSnapshot>) -> Unit = {}) {
        val normalized = city.trim()
        if (!preferences.getBoolean(KEY_DYNAMIC_ENABLED, true)) {
            REQUESTS.activate(normalized)
            deliverIfCurrent(
                normalized,
                Result.failure(IllegalStateException("联网天气增强已关闭")),
                callback,
            )
            return
        }
        if (normalized.length < 2) {
            REQUESTS.activate(normalized)
            deliverIfCurrent(
                normalized,
                Result.failure(IllegalArgumentException("请填写至少两个字的城市名")),
                callback,
            )
            return
        }
        val now = System.currentTimeMillis()
        val cache = cached(now)?.takeIf {
            now - it.updatedAt < CACHE_MS && preferences.getString(KEY_CACHE_QUERY, "") == normalized
        }
        when (val decision = REQUESTS.begin(normalized, cache, allowCached = !force, callback)) {
            is WeatherRequestCoordinator.Decision.Cached ->
                deliverIfCurrent(normalized, Result.success(decision.value), decision.callback)
            is WeatherRequestCoordinator.Decision.Joined -> Unit
            is WeatherRequestCoordinator.Decision.Start ->
                Thread(
                    { completeRequest(decision.request, runCatching { apiClient.fetch(normalized) }) },
                    "sweetpet-weather",
                ).start()
        }
    }

    private fun completeRequest(
        request: WeatherRequestCoordinator.Pending<WeatherSnapshot>,
        result: Result<WeatherSnapshot>,
    ) {
        val completion = REQUESTS.completeIfCurrent(request) {
            val acceptedResult = if (
                preferences.getBoolean(KEY_DYNAMIC_ENABLED, true) &&
                preferences.getString(KEY_CONFIGURED_CITY, DEFAULT_CITY).orEmpty().trim() == request.query
            ) {
                result
            } else {
                Result.failure(IllegalStateException("天气设置已变化，本次结果已忽略"))
            }

            // A failed or superseded response must never change the cache-query marker.
            // SharedPreferences.apply updates the in-process view synchronously while the
            // request lock prevents a newer generation from being registered mid-write.
            acceptedResult.getOrNull()?.let { snapshot ->
                preferences.edit()
                    .putString(KEY_CACHE_QUERY, request.query)
                    .putString("weather_city_resolved", snapshot.city)
                    .putFloat("weather_temperature", snapshot.temperature)
                    .putString("weather_kind", snapshot.kind)
                    .putString("weather_description", snapshot.description)
                    .putString("weather_source", snapshot.source)
                    .putLong("weather_updated_at", snapshot.updatedAt)
                    .apply()
            }
            acceptedResult
        } ?: return
        completion.callbacks.forEach { callback ->
            deliverIfCurrent(request.query, completion.value, callback)
        }
    }

    private fun deliverIfCurrent(
        query: String,
        result: Result<WeatherSnapshot>,
        callback: (Result<WeatherSnapshot>) -> Unit,
    ) {
        mainHandler.post {
            if (REQUESTS.isCurrent(query)) callback(result)
        }
    }

    companion object {
        // The domestic no-key endpoint has a per-IP free quota. Two-hour refreshes
        // are sufficient for dialogue context and avoid wasting shared carrier-NAT quota.
        private const val CACHE_MS = WeatherCachePolicy.MAX_AGE_MS
        private const val KEY_CACHE_QUERY = "weather_cache_query"
        private const val KEY_CONFIGURED_CITY = "weather_city"
        private const val KEY_DYNAMIC_ENABLED = "dynamic_weather_enabled"
        private const val KEY_UAPI_BACKOFF_UNTIL = "weather_uapi_backoff_until"
        private const val DEFAULT_CITY = "北京"
        private val REQUESTS = WeatherRequestCoordinator<WeatherSnapshot>()
    }
}
