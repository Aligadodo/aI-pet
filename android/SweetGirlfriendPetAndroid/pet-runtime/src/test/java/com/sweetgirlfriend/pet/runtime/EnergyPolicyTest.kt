package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EnergyPolicyTest {
    @Test
    fun screenOffAlwaysStopsFrames() {
        EnergyProfile.entries.forEach { profile ->
            PetActivityLevel.entries.forEach { level ->
                assertEquals(0, EnergyPolicy.frameRate(profile, level, screenInteractive = false))
            }
        }
    }

    @Test
    fun adaptiveProfileScalesDownWhenIdle() {
        assertEquals(16, EnergyPolicy.frameRate(EnergyProfile.ADAPTIVE, PetActivityLevel.INTERACTING, true))
        assertEquals(4, EnergyPolicy.frameRate(EnergyProfile.ADAPTIVE, PetActivityLevel.IDLE, true))
        assertEquals(1, EnergyPolicy.frameRate(EnergyProfile.ADAPTIVE, PetActivityLevel.SLEEP, true))
    }

    @Test
    fun schedulerRespectsCooldownAndSnooze() {
        val later = TaskOption("later", "稍后", "好呀", snoozeMinutes = 15)
        val task = PetTask("water", "喝水", "喝口水吧", "wave", 90, listOf(later))
        val scheduler = TaskScheduler(listOf(task))
        assertSame(task, scheduler.next(1_000L))
        scheduler.recordChoice(task, later, 1_000L)
        assertNull(scheduler.next(1_001L))
        assertSame(task, scheduler.next(901_000L))
    }
}
