package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulerTest {
    @Test
    fun avoidsRepeatingRecentTasks() {
        val scheduler = TaskScheduler(
            listOf(task("one"), task("two"), task("three"), task("four")),
            seed = 7L,
        )

        val selected = List(4) { scheduler.next(nowMs = 1_000L, force = true)!!.id }

        assertEquals(4, selected.distinct().size)
    }

    @Test
    fun onlyReturnsTasksMatchingTimeAndWeather() {
        val rainMorning = task("rain-morning", hourStart = 6, hourEnd = 10, weather = setOf("rain"))
        val scheduler = TaskScheduler(listOf(rainMorning), seed = 1L)

        assertEquals("rain-morning", scheduler.next(1_000L, hourOfDay = 8, weatherKind = "rain")?.id)
        assertNull(scheduler.next(1_000L, hourOfDay = 18, weatherKind = "rain"))
        assertNull(scheduler.next(1_000L, hourOfDay = 8, weatherKind = "clear"))
    }

    @Test
    fun gameInvitationsRespectUserSwitch() {
        val game = task(
            "gravity-invite",
            options = listOf(TaskOption("play", "开始", "出发", playMode = PlayMode.GRAVITY)),
        )
        val ordinary = task("ordinary")
        val scheduler = TaskScheduler(listOf(game, ordinary), seed = 2L)

        repeat(8) {
            assertTrue(scheduler.next(1_000L, force = true, allowGameModes = false)?.id != "gravity-invite")
        }
    }

    @Test
    fun dueSnoozeIsDeliveredBeforeOtherTasks() {
        val reminded = task("reminded")
        val scheduler = TaskScheduler(listOf(reminded, task("other")), seed = 4L)
        scheduler.recordChoice(
            reminded,
            TaskOption("later", "稍后", "好", snoozeMinutes = 15),
            nowMs = 1_000L,
        )

        assertEquals("other", scheduler.next(1_000L, force = true)?.id)
        assertEquals("reminded", scheduler.next(901_000L)?.id)
        assertTrue(scheduler.reminderPending("reminded"))

        scheduler.recordChoice(
            reminded,
            TaskOption("done", "done", "okay"),
            nowMs = 901_000L,
        )
        assertTrue(!scheduler.reminderPending("reminded"))
    }

    @Test
    fun dueSnoozeIgnoresChangedTimeAndWeatherContext() {
        val reminded = task("rain-morning", hourStart = 6, hourEnd = 8, weather = setOf("rain"))
        val scheduler = TaskScheduler(listOf(reminded, task("ordinary")), seed = 9L)
        scheduler.recordChoice(
            reminded,
            TaskOption("later", "later", "okay", snoozeMinutes = 5),
            nowMs = 1_000L,
        )

        assertTrue(scheduler.hasDueReminder(nowMs = 301_000L))
        assertEquals(
            "rain-morning",
            scheduler.next(
                nowMs = 301_000L,
                hourOfDay = 22,
                weatherKind = "clear",
            )?.id,
        )
    }

    @Test
    fun dueGameReminderStillRespectsGameModePermission() {
        val game = task(
            "game",
            options = listOf(TaskOption("play", "play", "go", playMode = PlayMode.GRAVITY)),
        )
        val scheduler = TaskScheduler(listOf(game), seed = 3L)
        scheduler.recordChoice(
            game,
            TaskOption("later", "later", "okay", snoozeMinutes = 1),
            nowMs = 1_000L,
        )

        assertTrue(!scheduler.hasDueReminder(nowMs = 61_000L, allowGameModes = false))
        assertNull(scheduler.next(nowMs = 61_000L, allowGameModes = false))
        assertTrue(scheduler.hasDueReminder(nowMs = 61_000L, allowGameModes = true))
    }

    @Test
    fun twoContextualTasksAlternateWithoutImmediateRepeat() {
        val scheduler = TaskScheduler(
            listOf(
                task("rain-one", weather = setOf("rain")),
                task("rain-two", weather = setOf("rain")),
                task("clear-only", weather = setOf("clear")),
            ),
            seed = 8L,
        )

        val selections = List(6) {
            scheduler.next(1_000L, force = true, weatherKind = "rain")!!.id
        }

        selections.zipWithNext().forEach { (left, right) -> assertTrue(left != right) }
    }

    @Test
    fun displayingOrRestoringDueReminderDoesNotConsumeIt() {
        val reminded = task("reminded")
        val first = TaskScheduler(listOf(reminded), seed = 12L)
        first.restore(reminded.id, nextAtMs = 5_000L, reminderPending = true)

        assertEquals(reminded.id, first.next(nowMs = 5_000L)?.id)
        assertTrue(first.reminderPending(reminded.id))
        assertEquals(reminded.id, first.next(nowMs = 5_001L)?.id)
        assertTrue(first.reminderPending(reminded.id))

        val restarted = TaskScheduler(listOf(reminded), seed = 13L)
        restarted.restore(
            reminded.id,
            nextAtMs = first.nextEligibleAt(reminded.id),
            reminderPending = first.reminderPending(reminded.id),
        )
        assertEquals(reminded.id, restarted.next(nowMs = 6_000L)?.id)
        assertTrue(restarted.reminderPending(reminded.id))
    }

    @Test
    fun redisplayingDueReminderKeepsRecentHistoryUnique() {
        val reminded = task("reminded")
        val scheduler = TaskScheduler(
            listOf(reminded, task("one"), task("two"), task("three")),
            seed = 14L,
        )
        scheduler.restore(reminded.id, nextAtMs = 5_000L, reminderPending = true)
        scheduler.restoreRecent(listOf("one", "two", "three"))

        assertEquals(reminded.id, scheduler.next(nowMs = 5_000L)?.id)
        val afterFirstDisplay = scheduler.recentTaskIds()
        assertEquals(reminded.id, scheduler.next(nowMs = 5_001L)?.id)

        assertEquals(afterFirstDisplay, scheduler.recentTaskIds())
        assertEquals(scheduler.recentTaskIds().size, scheduler.recentTaskIds().distinct().size)
    }

    @Test
    fun taskPersistenceKeysArePackScopedAndCollisionFree() {
        assertTrue(
            TaskPersistenceKeys.nextEligible("pack_a", "task") !=
                TaskPersistenceKeys.nextEligible("pack", "a_task"),
        )
        assertTrue(
            TaskPersistenceKeys.reminderPending("one", "shared") !=
                TaskPersistenceKeys.reminderPending("two", "shared"),
        )
        assertTrue(TaskPersistenceKeys.recent("one") != TaskPersistenceKeys.recent("two"))
    }

    @Test
    fun automaticInteractionPauseKeepsLongestRestAndCanBeExplicitlyCleared() {
        val pause = AutomaticInteractionPause()
        pause.blockFor(nowUptimeMs = 1_000L, durationMs = 300_000L)
        pause.blockUntil(deadlineUptimeMs = 120_000L)

        assertTrue(pause.isBlocked(300_999L))
        assertTrue(!pause.isBlocked(301_000L))

        pause.blockUntil(500_000L)
        pause.clear()
        assertTrue(!pause.isBlocked(400_000L))
    }

    @Test
    fun automaticPauseSurvivesStateMachineRefreshAndExpiresAtOriginalDeadline() {
        val pause = AutomaticInteractionPause()
        val behavior = BehaviorSpec(
            profiles = InteractionStyle.entries.associateWith {
                BehaviorWeights(idle = 0, walk = 0, run = 100, social = 0)
            },
        )
        pause.blockFor(nowUptimeMs = 1_000L, durationMs = 60_000L)

        val beforeRefresh = PetStateMachine(
            setOf("idle", "run"),
            seed = 1L,
            behavior = behavior,
        )
        assertEquals(AutomaticFrameDirective.FORCE_REST_IDLE, pause.frameDirective(2_000L))
        assertEquals("run", beforeRefresh.tick(2_000L, 12, InteractionStyle.DAILY))

        // Overlay settings refresh/content-pack replacement creates a new machine, while the
        // service-owned pause policy (and therefore the remaining deadline) stays unchanged.
        val afterRefresh = PetStateMachine(
            setOf("idle", "run"),
            seed = 2L,
            behavior = behavior,
        )
        assertEquals(59_000L, pause.remainingMs(2_000L))
        assertEquals(AutomaticFrameDirective.FORCE_REST_IDLE, pause.frameDirective(60_999L))
        assertEquals(AutomaticFrameDirective.ALLOW_AUTOMATIC, pause.frameDirective(61_000L))
        assertEquals("run", afterRefresh.tick(61_000L, 12, InteractionStyle.DAILY))
    }

    @Test
    fun manualPoseCanRenderDuringRestWithoutReenablingAutomaticFrames() {
        val pause = AutomaticInteractionPause()
        pause.blockFor(nowUptimeMs = 1_000L, durationMs = 60_000L)

        assertEquals(AutomaticFrameDirective.FORCE_REST_IDLE, pause.frameDirective(2_000L))
        pause.allowManualActionFor(nowUptimeMs = 2_000L, durationMs = 4_000L)
        assertEquals(AutomaticFrameDirective.KEEP_MANUAL_ACTION, pause.frameDirective(5_999L))
        assertTrue(pause.isBlocked(5_999L))
        assertEquals(AutomaticFrameDirective.FORCE_REST_IDLE, pause.frameDirective(6_000L))
        assertTrue(pause.isBlocked(6_000L))
    }

    private fun task(
        id: String,
        hourStart: Int = 0,
        hourEnd: Int = 23,
        weather: Set<String> = emptySet(),
        options: List<TaskOption> = listOf(TaskOption("done", "完成", "真棒")),
    ) = PetTask(
        id = id,
        title = id,
        prompt = id,
        action = "idle",
        cooldownMinutes = 1,
        options = options,
        hourStart = hourStart,
        hourEnd = hourEnd,
        weatherKinds = weather,
    )
}
