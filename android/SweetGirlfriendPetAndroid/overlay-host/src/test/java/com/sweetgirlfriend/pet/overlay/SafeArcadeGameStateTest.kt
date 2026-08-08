package com.sweetgirlfriend.pet.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeArcadeGameStateTest {
    @Test
    fun snakeGrowsScoresWinsAndRejectsReverseDirection() {
        val state = SnakeGameState(
            columns = 5,
            rows = 3,
            start = ArcadeCell(1, 1),
            walls = emptySet(),
            foods = listOf(ArcadeCell(2, 1), ArcadeCell(3, 1)),
        )

        val first = state.step(ArcadeDirection.RIGHT)
        assertTrue(first.ateFood)
        assertEquals(ArcadeDirection.RIGHT, state.direction)
        assertFalse(state.accepts(ArcadeDirection.LEFT))

        val second = state.step(ArcadeDirection.LEFT)
        assertTrue(second.ateFood)
        assertEquals(ArcadeOutcome.WON, second.outcome)
        assertEquals("snake-cleared", second.event)
        assertEquals(2, state.eatenCount)
        assertTrue(state.body.size >= 3)
        assertTrue(state.score > first.score)
    }

    @Test
    fun snakeReportsWallCollision() {
        val state = SnakeGameState(
            columns = 3,
            rows = 2,
            start = ArcadeCell(0, 0),
            walls = setOf(ArcadeCell(1, 0)),
            foods = listOf(ArcadeCell(2, 1)),
        )

        val update = state.step(ArcadeDirection.RIGHT)

        assertEquals(ArcadeOutcome.LOST, update.outcome)
        assertEquals("snake-collision", update.event)
    }

    @Test
    fun bomberPathAvoidsHardObstacleAndExplosionStopsAtBlockers() {
        val hard = setOf(ArcadeCell(3, 2))
        val targets = setOf(ArcadeCell(2, 2), ArcadeCell(4, 2))
        val blast = ArcadeGridPathfinder.blastCells(
            origin = ArcadeCell(1, 2),
            range = 5,
            columns = 7,
            rows = 5,
            hardObstacles = hard,
            destructible = targets,
        )

        assertTrue(ArcadeCell(2, 2) in blast)
        assertFalse(ArcadeCell(3, 2) in blast)
        assertFalse(ArcadeCell(4, 2) in blast)

        val state = BomberGameState(7, 5, ArcadeCell(0, 2), hard, targets)
        assertEquals(listOf(ArcadeCell(0, 2), ArcadeCell(1, 2)), state.pathToTarget())
        assertTrue(state.moveTo(ArcadeCell(1, 2)))
        val update = state.detonate()
        assertEquals(setOf(ArcadeCell(2, 2)), update.destroyed)
        assertEquals(0.5f, update.progress, 0.001f)
        assertTrue(update.score > 0)
    }

    @Test
    fun bomberClearsAllTargetsAndReportsWin() {
        val state = BomberGameState(
            columns = 5,
            rows = 3,
            start = ArcadeCell(1, 1),
            hardObstacles = emptySet(),
            destructibleTargets = setOf(ArcadeCell(2, 1), ArcadeCell(1, 2)),
            blastRange = 2,
        )

        val update = state.detonate()

        assertEquals(2, update.destroyed.size)
        assertEquals(2, update.combo)
        assertEquals(1f, update.progress, 0.001f)
        assertEquals(ArcadeOutcome.WON, update.outcome)
        assertEquals("bomber-cleared", update.event)
    }
}
