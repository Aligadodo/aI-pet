package com.sweetgirlfriend.pet.runtime

import java.util.Random

/** Collision-free SharedPreferences keys for state that belongs to one content pack. */
object TaskPersistenceKeys {
    private const val VERSION = "v2"

    fun nextEligible(packId: String, taskId: String): String = "task_next_$VERSION:$packId:$taskId"

    fun reminderPending(packId: String, taskId: String): String = "task_snoozed_$VERSION:$packId:$taskId"

    fun recent(packId: String): String = "task_recent_ids_$VERSION:$packId"
}

/** What the renderer should do when its periodic automatic-state tick runs. */
enum class AutomaticFrameDirective {
    /** Automatic state-machine decisions may run normally. */
    ALLOW_AUTOMATIC,

    /** Keep the current action because it was triggered directly by the user. */
    KEEP_MANUAL_ACTION,

    /** Do not tick the state machine; render the pack's idle action at the rest frame rate. */
    FORCE_REST_IDLE,
}

/**
 * Process-lifetime pause used to keep every automatic interaction quiet after explicit rest.
 *
 * This object deliberately lives outside [PetStateMachine]. Rebuilding the state machine after a
 * settings refresh or content-pack switch must not discard the remaining rest time. Short manual
 * actions can still be displayed without reopening automatic movement or dialogue.
 */
class AutomaticInteractionPause {
    var untilUptimeMs: Long = 0L
        private set
    private var manualActionUntilUptimeMs: Long = 0L

    fun blockFor(nowUptimeMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val deadline = if (Long.MAX_VALUE - nowUptimeMs < safeDuration) {
            Long.MAX_VALUE
        } else {
            nowUptimeMs + safeDuration
        }
        blockUntil(deadline)
    }

    fun blockUntil(deadlineUptimeMs: Long) {
        untilUptimeMs = maxOf(untilUptimeMs, deadlineUptimeMs.coerceAtLeast(0L))
    }

    fun clear() {
        untilUptimeMs = 0L
        manualActionUntilUptimeMs = 0L
    }

    fun isBlocked(nowUptimeMs: Long): Boolean = nowUptimeMs < untilUptimeMs

    fun cancelManualAction() {
        manualActionUntilUptimeMs = 0L
    }

    /** Keeps an explicit user-triggered pose visible while automatic interactions remain blocked. */
    fun allowManualActionFor(nowUptimeMs: Long, durationMs: Long) {
        if (!isBlocked(nowUptimeMs)) return
        val safeDuration = durationMs.coerceAtLeast(0L)
        val deadline = if (Long.MAX_VALUE - nowUptimeMs < safeDuration) {
            Long.MAX_VALUE
        } else {
            nowUptimeMs + safeDuration
        }
        manualActionUntilUptimeMs = maxOf(manualActionUntilUptimeMs, deadline)
    }

    fun frameDirective(nowUptimeMs: Long): AutomaticFrameDirective = when {
        !isBlocked(nowUptimeMs) -> AutomaticFrameDirective.ALLOW_AUTOMATIC
        nowUptimeMs < manualActionUntilUptimeMs -> AutomaticFrameDirective.KEEP_MANUAL_ACTION
        else -> AutomaticFrameDirective.FORCE_REST_IDLE
    }

    fun remainingMs(nowUptimeMs: Long): Long =
        (untilUptimeMs - nowUptimeMs).coerceAtLeast(0L)
}

class TaskScheduler(tasks: List<PetTask>, seed: Long = System.nanoTime()) {
    private val tasks = tasks.distinctBy(PetTask::id)
    private val nextEligibleAt = mutableMapOf<String, Long>()
    private val snoozedUntil = mutableMapOf<String, Long>()
    private val recent = ArrayDeque<String>()
    private val random = Random(seed)

    fun next(
        nowMs: Long,
        force: Boolean = false,
        hourOfDay: Int = 12,
        weatherKind: String = "unknown",
        allowGameModes: Boolean = true,
    ): PetTask? {
        if (tasks.isEmpty()) return null
        val allowed = tasks.filter { task ->
            allowGameModes || task.options.none { option -> option.playMode != null }
        }
        val dueReminders = dueReminders(nowMs, allowed)
        if (dueReminders.isNotEmpty()) {
            return select(dueReminders, allowed)
        }
        val eligible = allowed
            .filter { task ->
                val reminderAt = snoozedUntil[task.id]
                when {
                    reminderAt != null -> nowMs >= reminderAt
                    force -> true
                    else -> nowMs >= (nextEligibleAt[task.id] ?: 0L)
                }
            }
        if (eligible.isEmpty()) return null
        val contextual = eligible.filter { task ->
            val hourMatches = if (task.hourStart <= task.hourEnd) {
                hourOfDay in task.hourStart..task.hourEnd
            } else {
                hourOfDay >= task.hourStart || hourOfDay <= task.hourEnd
            }
            val weatherMatches = task.weatherKinds.isEmpty() || weatherKind in task.weatherKinds
            hourMatches && weatherMatches
        }
        if (contextual.isEmpty()) return null
        val pool = contextual.filterNot { it.id in recent }.ifEmpty { contextual }
        return select(pool, contextual)
    }

    /** Due reminders deliberately ignore their original time/weather context. */
    fun hasDueReminder(nowMs: Long, allowGameModes: Boolean = true): Boolean {
        val allowed = tasks.filter { task ->
            allowGameModes || task.options.none { option -> option.playMode != null }
        }
        return dueReminders(nowMs, allowed).isNotEmpty()
    }

    private fun dueReminders(nowMs: Long, allowed: List<PetTask>): List<PetTask> {
        val due = allowed.filter { task ->
            (snoozedUntil[task.id] ?: Long.MAX_VALUE) <= nowMs
        }
        if (due.isEmpty()) return emptyList()
        val earliest = due.minOf { snoozedUntil[it.id] ?: Long.MAX_VALUE }
        return due.filter { snoozedUntil[it.id] == earliest }
    }

    private fun select(pool: List<PetTask>, recentCandidates: List<PetTask>): PetTask {
        val selected = pool[random.nextInt(pool.size)]
        recent.remove(selected.id)
        recent.addLast(selected.id)
        val recentCapacity = minOf(3, (recentCandidates.size - 1).coerceAtLeast(0))
        while (recent.size > recentCapacity) recent.removeFirst()
        return selected
    }

    fun recordChoice(task: PetTask, option: TaskOption, nowMs: Long) {
        val isReminder = option.snoozeMinutes > 0
        val delayMinutes = if (isReminder) option.snoozeMinutes else task.cooldownMinutes
        nextEligibleAt[task.id] = nowMs + delayMinutes.coerceAtLeast(1) * 60_000L
        if (isReminder) {
            snoozedUntil[task.id] = nextEligibleAt.getValue(task.id)
        } else {
            snoozedUntil.remove(task.id)
        }
    }

    fun restore(taskId: String, nextAtMs: Long, reminderPending: Boolean = false) {
        if (tasks.none { it.id == taskId }) return
        val restored = nextAtMs.coerceAtLeast(0L)
        nextEligibleAt[taskId] = restored
        if (reminderPending) {
            snoozedUntil[taskId] = restored
        } else {
            snoozedUntil.remove(taskId)
        }
    }

    fun restoreRecent(taskIds: List<String>) {
        recent.clear()
        taskIds.filter { id -> tasks.any { it.id == id } }.distinct().takeLast(3).forEach(recent::addLast)
    }

    fun recentTaskIds(): List<String> = recent.toList()

    fun reminderPending(taskId: String): Boolean = taskId in snoozedUntil

    fun nextEligibleAt(taskId: String): Long = nextEligibleAt[taskId] ?: 0L
}
