package com.sweetgirlfriend.pet.app

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal object LanPairingPolicy {
    const val COOKIE_NAME = "SweetPet-Session"
    private const val CODE_DIGITS = 6

    fun generateCode(random: SecureRandom = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(CODE_DIGITS, '0')

    fun displayCode(code: String): String = code.chunked(3).joinToString(" ")

    fun browserUrl(ipv4Host: String, port: Int): String = "http://$ipv4Host:$port/"

    fun fullUrl(ipv4Host: String, port: Int, token: String): String =
        "${browserUrl(ipv4Host, port)}?token=$token"

    fun normalizeCode(value: String?): String? {
        if (value == null) return null
        val normalized = value.filterNot { it == ' ' || it == '-' }
        return normalized.takeIf { it.length == CODE_DIGITS && it.all { digit -> digit in '0'..'9' } }
    }

    fun codeMatches(provided: String?, expected: String): Boolean {
        val normalized = normalizeCode(provided) ?: return false
        return MessageDigest.isEqual(
            normalized.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    fun queryValue(target: String, name: String): String? =
        decodePairs(target.substringAfter('?', "")).firstOrNull { it.first == name }?.second

    fun formValue(body: String, name: String): String? =
        decodePairs(body).firstOrNull { it.first == name }?.second

    fun cookieValue(cookieHeader: String?, name: String): String? = cookieHeader
        ?.split(';')
        ?.asSequence()
        ?.map { it.trim().split('=', limit = 2) }
        ?.firstOrNull { it.size == 2 && it[0] == name }
        ?.get(1)

    fun credentialMatches(target: String, cookieHeader: String?, expectedToken: String): Boolean {
        val provided = queryValue(target, "token") ?: cookieValue(cookieHeader, COOKIE_NAME) ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun isExpired(nowMillis: Long, expiresAtMillis: Long): Boolean = nowMillis >= expiresAtMillis

    private fun decodePairs(encoded: String): List<Pair<String, String>> {
        if (encoded.isBlank() || encoded.length > 2_048) return emptyList()
        return encoded.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2) return@mapNotNull null
            val key = decodeComponent(pieces[0]) ?: return@mapNotNull null
            val value = decodeComponent(pieces[1]) ?: return@mapNotNull null
            key to value
        }
    }

    private fun decodeComponent(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

internal class LanPairingAttemptLimiter(
    private val maxFailures: Int = 6,
    private val lockoutMillis: Long = 60_000L,
) {
    data class Decision(val allowed: Boolean, val retryAfterMillis: Long = 0L)

    private data class State(var failures: Int, var lockedUntilMillis: Long)

    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun check(peer: String, nowMillis: Long): Decision {
        cleanup(nowMillis)
        val state = states[peer] ?: return Decision(allowed = true)
        if (state.lockedUntilMillis > nowMillis) {
            return Decision(allowed = false, retryAfterMillis = state.lockedUntilMillis - nowMillis)
        }
        if (state.lockedUntilMillis != 0L) states.remove(peer)
        return Decision(allowed = true)
    }

    @Synchronized
    fun recordFailure(peer: String, nowMillis: Long): Decision {
        cleanup(nowMillis)
        val state = states.getOrPut(peer) { State(0, 0L) }
        trimToBudget()
        state.failures += 1
        if (state.failures >= maxFailures) {
            state.lockedUntilMillis = nowMillis + lockoutMillis
            return Decision(allowed = false, retryAfterMillis = lockoutMillis)
        }
        return Decision(allowed = true)
    }

    @Synchronized
    fun recordSuccess(peer: String) {
        states.remove(peer)
    }

    @Synchronized
    private fun cleanup(nowMillis: Long) {
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (state.lockedUntilMillis != 0L && state.lockedUntilMillis <= nowMillis) iterator.remove()
        }
        trimToBudget()
    }

    private fun trimToBudget() {
        while (states.size > MAX_TRACKED_PEERS) states.remove(states.keys.first())
    }

    companion object {
        private const val MAX_TRACKED_PEERS = 64
    }
}
