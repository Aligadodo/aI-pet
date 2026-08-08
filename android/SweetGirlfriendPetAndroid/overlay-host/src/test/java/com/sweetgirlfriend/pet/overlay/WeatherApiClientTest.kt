package com.sweetgirlfriend.pet.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WeatherApiClientTest {
    @Test
    fun domesticProviderIsPreferredAndNeedsOneRequest() {
        val calls = mutableListOf<String>()
        val client = WeatherApiClient(
            transport = WeatherJsonTransport { address ->
                calls += address
                JSONObject(
                    """{
                      "province":"广东省","city":"深圳市","district":"南山区",
                      "weather":"多云","temperature":28,"wind_power":"3级"
                    }""",
                )
            },
            now = { 123_456L },
            uapiBackoff = WeatherProviderBackoff(),
        )

        val result = client.fetch("深圳市南山区")

        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("https://uapis.cn/"))
        assertEquals("深圳市 · 南山区", result.city)
        assertEquals("cloudy", result.kind)
        assertEquals(WeatherApiClient.SOURCE_UAPI, result.source)
        assertEquals(123_456L, result.updatedAt)
    }

    @Test
    fun openMeteoIsUsedWhenDomesticProviderFails() {
        val calls = mutableListOf<String>()
        val client = WeatherApiClient(
            transport = WeatherJsonTransport { address ->
                calls += address
                when {
                    address.startsWith("https://uapis.cn/") -> throw SocketTimeoutException()
                    address.startsWith("https://geocoding-api.open-meteo.com/") -> JSONObject(
                        """{"results":[{"name":"北京","admin1":"北京市","latitude":39.9,"longitude":116.4}]}""",
                    )
                    else -> JSONObject(
                        """{"current":{"temperature_2m":26.5,"weather_code":0,"wind_speed_10m":5.0}}""",
                    )
                }
            },
            now = { 456_789L },
            uapiBackoff = WeatherProviderBackoff(),
        )

        val result = client.fetch("北京")

        assertEquals(3, calls.size)
        assertEquals("clear", result.kind)
        assertEquals("北京 · 北京市", result.city)
        assertEquals(WeatherApiClient.SOURCE_OPEN_METEO, result.source)
    }

    @Test
    fun aggregatedFailureNamesSourcesWithoutLeakingTransportDetails() {
        val client = WeatherApiClient(
            transport = WeatherJsonTransport { address ->
                if (address.startsWith("https://uapis.cn/")) throw SocketTimeoutException("https://secret.example")
                throw IOException("https://secret.example?token=do-not-leak")
            },
            uapiBackoff = WeatherProviderBackoff(),
        )

        val message = runCatching { client.fetch("北京") }.exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("国内源（连接超时）"))
        assertTrue(message.contains("备用源（网络或数据异常）"))
        assertTrue(!message.contains("secret"))
        assertTrue(!message.contains("token"))
    }

    @Test
    fun rateLimitOpensDomesticCircuitWhileFallbackKeepsWorking() {
        var now = 1_000L
        var domesticCalls = 0
        var persistedBlockedUntil = 0L
        val backoff = WeatherProviderBackoff(onBlockedUntil = { persistedBlockedUntil = it })
        val client = WeatherApiClient(
            transport = WeatherJsonTransport { address ->
                when {
                    address.startsWith("https://uapis.cn/") -> {
                        domesticCalls += 1
                        throw WeatherHttpException(429, retryAfterMs = 4L * 60L * 60_000L)
                    }
                    address.startsWith("https://geocoding-api.open-meteo.com/") -> JSONObject(
                        """{"results":[{"name":"北京","latitude":39.9,"longitude":116.4}]}""",
                    )
                    else -> JSONObject(
                        """{"current":{"temperature_2m":26.5,"weather_code":0,"wind_speed_10m":5.0}}""",
                    )
                }
            },
            now = { now },
            uapiBackoff = backoff,
        )

        assertEquals(WeatherApiClient.SOURCE_OPEN_METEO, client.fetch("北京").source)
        assertTrue(persistedBlockedUntil >= now + 6L * 60L * 60_000L)
        now += 60_000L
        assertEquals(WeatherApiClient.SOURCE_OPEN_METEO, client.fetch("北京").source)
        assertEquals(1, domesticCalls)
    }

    @Test
    fun persistedCircuitIsObservedByExistingProviderInstances() {
        var persistedUntil = 0L
        val first = WeatherProviderBackoff(
            readBlockedUntil = { persistedUntil },
            onBlockedUntil = { persistedUntil = it },
        )
        val second = WeatherProviderBackoff(readBlockedUntil = { persistedUntil })

        assertTrue(second.canRequest(1_000L))
        first.block(nowMs = 1_000L, durationMs = 60_000L)
        assertTrue(!second.canRequest(1_001L))
        assertTrue(second.canRequest(61_000L))
    }

    @Test
    fun chineseConditionsMapToStableRuntimeKinds() {
        assertEquals("storm", WeatherApiClient.describeChinese("雷阵雨", 35f, 2).first)
        assertEquals("snow", WeatherApiClient.describeChinese("雨夹雪", 1f, 2).first)
        assertEquals("rain", WeatherApiClient.describeChinese("中雨", 18f, 2).first)
        assertEquals("hot", WeatherApiClient.describeChinese("晴", 35f, 2).first)
        assertEquals("cold", WeatherApiClient.describeChinese("晴", 3f, 2).first)
        assertEquals("windy", WeatherApiClient.describeChinese("阴", 18f, 6).first)
        assertEquals("fog", WeatherApiClient.describeChinese("轻度霾", 18f, 2).first)
        assertEquals("clear", WeatherApiClient.describeChinese("晴", 18f, 2).first)
        assertEquals("cloudy", WeatherApiClient.describeChinese("阴", 18f, 2).first)
        assertEquals(5, WeatherApiClient.parseWindLevel("3-5级"))
    }
}
