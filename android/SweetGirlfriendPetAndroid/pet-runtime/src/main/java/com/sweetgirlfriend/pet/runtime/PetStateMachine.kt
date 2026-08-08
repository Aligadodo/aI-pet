package com.sweetgirlfriend.pet.runtime

import java.util.Random

class PetStateMachine(
    availableActions: Set<String>,
    seed: Long = System.nanoTime(),
    private val behavior: BehaviorSpec = BehaviorSpec(),
) {
    private val actions = availableActions.ifEmpty { setOf("idle") }
    private val random = Random(seed)
    private var manualRestUntilMs = 0L
    private var nextDecisionAtMs = 0L

    var currentAction: String = fallback(behavior.fallbackAction, "idle")
        private set

    fun onTap(nowMs: Long): String = setAction(fallback("wave"), nowMs, 3_000L)

    fun onDoubleTap(nowMs: Long): String =
        setAction(fallback("photo_pose", "happy", "wave"), nowMs, 4_000L)

    fun onUserPlaced(nowMs: Long, restDurationMs: Long = 5 * 60_000L): String {
        manualRestUntilMs = nowMs + restDurationMs.coerceAtLeast(0L)
        return setAction(fallback("idle"), nowMs, restDurationMs.coerceAtLeast(2_000L))
    }

    fun resumeAutomaticActivity(nowMs: Long): String {
        manualRestUntilMs = 0L
        nextDecisionAtMs = nowMs
        return tick(nowMs, 12, InteractionStyle.DAILY)
    }

    fun tick(
        nowMs: Long,
        hourOfDay: Int,
        style: InteractionStyle,
    ): String {
        if (nowMs < manualRestUntilMs) {
            currentAction = fallback("idle")
            return currentAction
        }
        if (nowMs < nextDecisionAtMs) return currentAction

        val quietTime = hourOfDay >= 23 || hourOfDay < 7
        if (quietTime) {
            return setAction(fallback("sleep", behavior.fallbackAction, "idle"), nowMs, 8_000L)
        }

        val weights = behavior.weights(style)
        val roll = random.nextInt(weights.total.coerceAtLeast(1))
        val next = when {
            roll < weights.idle -> fallback(behavior.fallbackAction, "idle")
            roll < weights.idle + weights.walk -> fallback("walk", behavior.fallbackAction, "idle")
            roll < weights.idle + weights.walk + weights.run -> fallback("run", "walk", behavior.fallbackAction)
            style == InteractionStyle.SWEET && random.nextBoolean() -> fallback("photo_pose", "happy", "wave")
            else -> fallback("wave", "happy", "photo_pose", behavior.fallbackAction)
        }
        val duration = when (next) {
            "walk", "run" -> 4_500L
            "photo_pose", "wave" -> 3_500L
            else -> 6_000L
        }
        return setAction(next, nowMs, duration)
    }

    private fun setAction(action: String, nowMs: Long, durationMs: Long): String {
        currentAction = action
        nextDecisionAtMs = nowMs + durationMs.coerceAtLeast(500L)
        return currentAction
    }

    private fun fallback(vararg preferred: String): String =
        preferred.firstOrNull(actions::contains) ?: actions.first()
}
