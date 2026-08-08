package com.sweetgirlfriend.pet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetStateMachineTest {
    private val actions = setOf("idle", "walk", "run", "wave", "photo_pose")

    @Test
    fun tapAndDoubleTapUsePortableActionNames() {
        val machine = PetStateMachine(actions, seed = 7)
        assertEquals("wave", machine.onTap(1_000L))
        assertEquals("photo_pose", machine.onDoubleTap(2_000L))
    }

    @Test
    fun manualPlacementKeepsPetAtRest() {
        val machine = PetStateMachine(actions, seed = 7)
        machine.onUserPlaced(10_000L, 60_000L)
        assertEquals("idle", machine.tick(20_000L, 12, InteractionStyle.SWEET))
    }

    @Test
    fun missingActionsAlwaysFallBackToAvailableAction() {
        val machine = PetStateMachine(setOf("idle"), seed = 7)
        assertEquals("idle", machine.onDoubleTap(2_000L))
        assertTrue(machine.tick(20_000L, 12, InteractionStyle.DAILY) == "idle")
    }

    @Test
    fun packBehaviorWeightsDriveAutomaticActions() {
        val behavior = BehaviorSpec(
            profiles = InteractionStyle.entries.associateWith { BehaviorWeights(idle = 0, walk = 0, run = 100, social = 0) },
        )
        val machine = PetStateMachine(actions, seed = 7, behavior = behavior)

        assertEquals("run", machine.tick(20_000L, 12, InteractionStyle.DAILY))
    }
}
