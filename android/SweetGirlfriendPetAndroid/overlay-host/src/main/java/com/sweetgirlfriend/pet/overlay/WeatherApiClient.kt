package com.sweetgirlfriend.pet.overlay

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/** Small injectable transport so provider fallback and parsing stay unit-testable. */
internal fun interface WeatherJsonTransport {
    fun get(address: String): JSONObject
}

/**
 * Weather lookup ordered for mainland-China connectivity.
 *
 * UAPI is a no-key, Chinese HTTPS endpoint and needs only one request for a city.
 * Open-Meteo remains a no-key fallback for resilience and international coverage.
 */
internal class WeatherApiClient(
    private val transport: WeatherJsonTransport = HttpWeatherJsonTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val uapiBackoff: WeatherProviderBackoff = SHARED_UAPI_BACKOFF,
) {
    fun fetch(city: String): WeatherSnapshot {
        val requestTime = now()
        val domestic = if (uapiBackoff.canRequest(requestTime)) {
            runCatching { fetchUapi(city) }.also { result ->
                (result.exceptionOrNull() as? WeatherHttpException)
                    ?.takeIf { it.statusCode == 429 || it.statusCode == HttpURLConnection.HTTP_UNAVAILABLE }
                    ?.let { error ->
                        val defaultBackoff = if (error.statusCode == 429) {
                            UAPI_QUOTA_BACKOFF_MS
                        } else {
                            UAPI_OUTAGE_BACKOFF_MS
                        }
                        uapiBackoff.block(
                            nowMs = requestTime,
                            durationMs = maxOf(defaultBackoff, error.retryAfterMs ?: 0L)
                                .coerceAtMost(MAX_UAPI_BACKOFF_MS),
                        )
                    }
            }
        } else {
            Result.failure(WeatherProviderBackoffException())
        }
        domestic.getOrNull()?.let { return it }

        val fallback = runCatching { fetchOpenMeteo(city) }
        fallback.getOrNull()?.let { return it }

        val domesticFailure = domestic.exceptionOrNull() ?: IOException("未知错误")
        val fallbackFailure = fallback.exceptionOrNull() ?: IOException("未知错误")
        if (domesticFailure is CityNotFoundException && fallbackFailure is CityNotFoundException) {
            throw CityNotFoundException("没有找到城市“$city”，请检查城市名")
        }
        throw IOException(
            "天气服务暂时不可用：国内源（${failureLabel(domesticFailure)}）；" +
                "备用源（${failureLabel(fallbackFailure)}）。请检查网络后重试。",
            fallbackFailure,
        )
    }

    private fun fetchUapi(city: String): WeatherSnapshot {
        val encoded = encode(city)
        val json = try {
            transport.get("https://uapis.cn/api/v1/misc/weather?city=$encoded&lang=zh")
        } catch (error: WeatherHttpException) {
            if (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw CityNotFoundException("没有找到城市“$city”")
            }
            throw error
        }
        json.optString("message").takeIf {
            json.optBoolean("success", true).not() && it.isNotBlank()
        }?.let { throw IOException("国内天气服务拒绝了请求") }

        val resolvedCity = json.optString("city").trim().ifBlank { city }
        val district = json.optString("district").trim()
        val resolved = listOf(resolvedCity, district)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        val temperature = json.optDouble("temperature", Double.NaN)
            .takeIf { it.isFinite() }
            ?.toFloat()
            ?: throw IOException("国内天气服务缺少温度数据")
        val weatherText = json.optString("weather").trim()
            .ifBlank { throw IOException("国内天气服务缺少天气现象") }
        val windLevel = parseWindLevel(json.optString("wind_power"))
        val (kind, description) = describeChinese(weatherText, temperature, windLevel)
        return WeatherSnapshot(
            city = resolved,
            temperature = temperature,
            kind = kind,
            description = description,
            updatedAt = now(),
            source = SOURCE_UAPI,
        )
    }

    private fun fetchOpenMeteo(city: String): WeatherSnapshot {
        val encoded = encode(city)
        val geocode = transport.get(
            "https://geocoding-api.open-meteo.com/v1/search?" +
                "name=$encoded&count=1&language=zh&format=json",
        )
        val place = geocode.optJSONArray("results")?.optJSONObject(0)
            ?: throw CityNotFoundException("没有找到城市“$city”")
        val latitude = place.getDouble("latitude")
        val longitude = place.getDouble("longitude")
        val placeName = place.getString("name")
        val resolved = buildString {
            append(placeName)
            place.optString("admin1").takeIf { it.isNotBlank() && it != placeName }
                ?.let { append(" · ").append(it) }
        }
        val forecast = transport.get(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto",
        ).getJSONObject("current")
        val temperature = forecast.getDouble("temperature_2m").toFloat()
        val code = forecast.getInt("weather_code")
        val wind = forecast.optDouble("wind_speed_10m", 0.0)
        val (kind, description) = describeWmo(code, temperature, wind)
        return WeatherSnapshot(
            city = resolved,
            temperature = temperature,
            kind = kind,
            description = description,
            updatedAt = now(),
            source = SOURCE_OPEN_METEO,
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val SOURCE_UAPI = "UAPI 国内天气"
        const val SOURCE_OPEN_METEO = "Open-Meteo 备用源"
        private const val UAPI_QUOTA_BACKOFF_MS = 6L * 60L * 60_000L
        private const val UAPI_OUTAGE_BACKOFF_MS = 30L * 60_000L
        private const val MAX_UAPI_BACKOFF_MS = 24L * 60L * 60_000L
        private val SHARED_UAPI_BACKOFF = WeatherProviderBackoff()

        internal fun parseWindLevel(value: String): Int =
            Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0

        internal fun describeChinese(
            weatherText: String,
            temperature: Float,
            windLevel: Int,
        ): Pair<String, String> {
            val text = weatherText.trim()
            if (listOf("雷", "冰雹", "龙卷", "台风").any(text::contains)) return "storm" to "雷暴天气"
            if (text.contains("雪")) return "snow" to "有雪"
            if (text.contains("雨")) return "rain" to "有雨"
            if (temperature >= 32f) return "hot" to "炎热"
            if (temperature <= 5f) return "cold" to "寒冷"
            if (windLevel >= 5) return "windy" to "风比较大"
            if (listOf("雾", "霾", "沙尘", "浮尘", "扬沙").any(text::contains)) return "fog" to "能见度较低"
            if (text.contains("晴")) return "clear" to "晴朗"
            if (text.contains("云") || text.contains("阴")) return "cloudy" to "多云"
            return "cloudy" to text.ifBlank { "天气多变" }
        }

        internal fun describeWmo(code: Int, temperature: Float, wind: Double): Pair<String, String> {
            // Activity-changing conditions take precedence over temperature.
            if (code in 95..99) return "storm" to "雷雨"
            if (code in 51..67 || code in 80..82) return "rain" to "有雨"
            if (code in 71..77 || code in 85..86) return "snow" to "有雪"
            if (temperature >= 32f) return "hot" to "炎热"
            if (temperature <= 5f) return "cold" to "寒冷"
            if (wind >= 35.0) return "windy" to "风比较大"
            return when (code) {
                0 -> "clear" to "晴朗"
                in 1..3 -> "cloudy" to "多云"
                45, 48 -> "fog" to "有雾"
                else -> "cloudy" to "天气多变"
            }
        }

        private fun failureLabel(error: Throwable): String = when (error) {
            is CityNotFoundException -> "未找到城市"
            is SocketTimeoutException -> "连接超时"
            is UnknownHostException -> "域名解析失败"
            is WeatherHttpException -> "HTTP ${error.statusCode}"
            is WeatherProviderBackoffException -> "额度或故障退避中"
            is IOException -> "网络或数据异常"
            else -> "返回数据异常"
        }
    }
}

internal class CityNotFoundException(message: String) : IOException(message)

internal class WeatherProviderBackoffException : IOException("国内天气源暂处于退避期")

internal class WeatherProviderBackoff(
    initialBlockedUntilMs: Long = 0L,
    private val readBlockedUntil: () -> Long = { 0L },
    private val onBlockedUntil: (Long) -> Unit = {},
) {
    @Volatile
    private var blockedUntilMs: Long = initialBlockedUntilMs.coerceAtLeast(0L)

    fun canRequest(nowMs: Long): Boolean = nowMs >= maxOf(blockedUntilMs, readBlockedUntil())

    @Synchronized
    fun block(nowMs: Long, durationMs: Long) {
        val nextBlockedUntil = maxOf(
            blockedUntilMs,
            readBlockedUntil(),
            nowMs + durationMs.coerceAtLeast(0L),
        )
        if (nextBlockedUntil == blockedUntilMs) return
        blockedUntilMs = nextBlockedUntil
        onBlockedUntil(nextBlockedUntil)
    }
}

internal class WeatherHttpException(
    val statusCode: Int,
    val retryAfterMs: Long? = null,
) : IOException("HTTP $statusCode")

internal class HttpWeatherJsonTransport : WeatherJsonTransport {
    override fun get(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            connection.setRequestProperty("User-Agent", "SweetPet-Android/0.5.4")
            val status = connection.responseCode
            if (status !in 200..299) {
                val retryAfterMs = connection.getHeaderField("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
                    ?.let { seconds -> seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L }
                throw WeatherHttpException(status, retryAfterMs)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4_096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    result.append(buffer, 0, read)
                    if (result.length > MAX_RESPONSE_CHARS) throw IOException("天气响应过大")
                }
                result.toString()
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 7_000
        const val MAX_RESPONSE_CHARS = 512 * 1_024
        const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
    }
}
