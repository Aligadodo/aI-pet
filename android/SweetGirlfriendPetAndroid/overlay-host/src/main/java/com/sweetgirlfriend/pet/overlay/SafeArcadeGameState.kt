package com.sweetgirlfriend.pet.overlay

import java.util.ArrayDeque
import kotlin.math.abs

internal data class ArcadeCell(val column: Int, val row: Int) {
    fun moved(direction: ArcadeDirection): ArcadeCell =
        ArcadeCell(column + direction.columnDelta, row + direction.rowDelta)
}

internal enum class ArcadeDirection(val columnDelta: Int, val rowDelta: Int) {
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1),
    DOWN(0, 1);

    fun isOpposite(other: ArcadeDirection): Boolean =
        columnDelta == -other.columnDelta && rowDelta == -other.rowDelta

    companion object {
        fun between(from: ArcadeCell, to: ArcadeCell): ArcadeDirection? = entries.firstOrNull {
            from.moved(it) == to
        }
    }
}

internal enum class ArcadeOutcome { RUNNING, WON, LOST }

internal data class SnakeUpdate(
    val moved: Boolean,
    val ateFood: Boolean,
    val score: Int,
    val combo: Int,
    val outcome: ArcadeOutcome,
    val event: String? = null,
)

internal class SnakeGameState(
    private val columns: Int,
    private val rows: Int,
    start: ArcadeCell,
    walls: Set<ArcadeCell>,
    foods: Collection<ArcadeCell>,
    private val initialLength: Int = 3,
) {
    val walls: Set<ArcadeCell> = walls.filterTo(linkedSetOf()) { inside(it) && it != start }
    private val remainingFoods = foods.filterTo(linkedSetOf()) {
        inside(it) && it != start && it !in this.walls
    }
    private val bodyCells = ArrayDeque<ArcadeCell>().apply { add(start) }

    val body: List<ArcadeCell> get() = bodyCells.toList()
    val allFoods: Set<ArcadeCell> = remainingFoods.toSet()
    val remainingFoodCells: Set<ArcadeCell> get() = remainingFoods.toSet()
    var food: ArcadeCell? = chooseNextFood(start)
        private set
    var direction: ArcadeDirection = ArcadeDirection.RIGHT
        private set
    var score: Int = 0
        private set
    var combo: Int = 0
        private set
    var eatenCount: Int = 0
        private set
    var outcome: ArcadeOutcome = if (food == null) ArcadeOutcome.WON else ArcadeOutcome.RUNNING
        private set
    private var desiredLength = initialLength.coerceAtLeast(1)
    private var stepsSinceFood = 0

    val head: ArcadeCell get() = bodyCells.first

    fun accepts(direction: ArcadeDirection): Boolean =
        bodyCells.size <= 1 || !direction.isOpposite(this.direction)

    fun suggestedDirection(): ArcadeDirection? {
        if (outcome != ArcadeOutcome.RUNNING) return null
        val goal = food ?: return null
        val bodyBlockers = bodyCells.toList().dropLast(1).toSet()
        val path = ArcadeGridPathfinder.shortestPath(
            start = head,
            goals = setOf(goal),
            columns = columns,
            rows = rows,
            blocked = walls + bodyBlockers - goal,
        )
        ArcadeDirection.between(head, path.getOrNull(1) ?: head)?.let { candidate ->
            if (accepts(candidate)) return candidate
        }
        return ArcadeDirection.entries.firstOrNull { candidate ->
            accepts(candidate) && canEnter(head.moved(candidate), growing = false)
        }
    }

    fun step(requestedDirection: ArcadeDirection): SnakeUpdate {
        if (outcome != ArcadeOutcome.RUNNING) return snapshot(moved = false, ateFood = false)
        val nextDirection = requestedDirection.takeIf(::accepts) ?: direction
        val next = head.moved(nextDirection)
        val growing = next == food
        if (!canEnter(next, growing)) {
            outcome = ArcadeOutcome.LOST
            combo = 0
            return snapshot(false, false, "snake-collision")
        }
        direction = nextDirection
        bodyCells.addFirst(next)
        var ate = false
        if (growing) {
            ate = true
            remainingFoods.remove(next)
            eatenCount++
            combo = (combo + 1).coerceAtMost(8)
            score += 10 + combo * 5
            desiredLength++
            stepsSinceFood = 0
            food = chooseNextFood(next)
            if (food == null) outcome = ArcadeOutcome.WON
        } else {
            stepsSinceFood++
            if (stepsSinceFood > 9) combo = 0
        }
        while (bodyCells.size > desiredLength) bodyCells.removeLast()
        return snapshot(
            moved = true,
            ateFood = ate,
            event = if (outcome == ArcadeOutcome.WON) "snake-cleared" else if (ate) "snake-food" else null,
        )
    }

    private fun canEnter(cell: ArcadeCell, growing: Boolean): Boolean {
        if (!inside(cell) || cell in walls) return false
        val occupied = if (!growing && bodyCells.isNotEmpty()) bodyCells.toList().dropLast(1) else bodyCells.toList()
        return cell !in occupied
    }

    private fun chooseNextFood(from: ArcadeCell): ArcadeCell? = remainingFoods
        .asSequence()
        .map { candidate ->
            candidate to ArcadeGridPathfinder.shortestPath(
                start = from,
                goals = setOf(candidate),
                columns = columns,
                rows = rows,
                blocked = walls,
            )
        }
        .filter { (_, path) -> path.isNotEmpty() }
        .minWithOrNull(compareBy<Pair<ArcadeCell, List<ArcadeCell>>> { it.second.size }.thenBy { it.first.row }.thenBy { it.first.column })
        ?.first

    private fun inside(cell: ArcadeCell): Boolean =
        cell.column in 0 until columns && cell.row in 0 until rows

    private fun snapshot(moved: Boolean, ateFood: Boolean, event: String? = null) = SnakeUpdate(
        moved = moved,
        ateFood = ateFood,
        score = score,
        combo = combo,
        outcome = outcome,
        event = event,
    )
}

internal data class BomberUpdate(
    val affected: Set<ArcadeCell>,
    val destroyed: Set<ArcadeCell>,
    val score: Int,
    val combo: Int,
    val progress: Float,
    val outcome: ArcadeOutcome,
    val event: String? = null,
)

internal class BomberGameState(
    private val columns: Int,
    private val rows: Int,
    start: ArcadeCell,
    hardObstacles: Set<ArcadeCell>,
    destructibleTargets: Set<ArcadeCell>,
    private val blastRange: Int = 2,
) {
    val hardObstacles: Set<ArcadeCell> = hardObstacles.filterTo(linkedSetOf()) { inside(it) && it != start }
    private val remainingTargets = destructibleTargets.filterTo(linkedSetOf()) {
        inside(it) && it != start && it !in this.hardObstacles
    }
    val originalTargets: Set<ArcadeCell> = remainingTargets.toSet()
    val targets: Set<ArcadeCell> get() = remainingTargets.toSet()
    val totalTargets: Int = remainingTargets.size
    var player: ArcadeCell = start
        private set
    var target: ArcadeCell? = null
        private set
    var score: Int = 0
        private set
    var combo: Int = 0
        private set
    var outcome: ArcadeOutcome = if (remainingTargets.isEmpty()) ArcadeOutcome.WON else ArcadeOutcome.RUNNING
        private set

    init {
        selectNearestReachableTarget()
    }

    fun selectTarget(cell: ArcadeCell): Boolean {
        if (cell !in remainingTargets || reachableApproach(cell).isEmpty()) return false
        target = cell
        return true
    }

    fun pathToTarget(): List<ArcadeCell> {
        if (outcome != ArcadeOutcome.RUNNING) return emptyList()
        val selected = target?.takeIf { it in remainingTargets && reachableApproach(it).isNotEmpty() }
            ?: selectNearestReachableTarget()
            ?: run {
                outcome = ArcadeOutcome.LOST
                return emptyList()
            }
        return reachableApproach(selected)
    }

    fun moveTo(cell: ArcadeCell): Boolean {
        if (outcome != ArcadeOutcome.RUNNING || manhattan(player, cell) != 1 || !isWalkable(cell)) return false
        player = cell
        return true
    }

    fun canDetonateTarget(): Boolean = target?.let { selected ->
        selected in ArcadeGridPathfinder.blastCells(
            origin = player,
            range = blastRange,
            columns = columns,
            rows = rows,
            hardObstacles = hardObstacles,
            destructible = remainingTargets,
        )
    } == true

    fun detonate(): BomberUpdate {
        if (outcome != ArcadeOutcome.RUNNING) return snapshot(emptySet(), emptySet())
        val affected = ArcadeGridPathfinder.blastCells(
            origin = player,
            range = blastRange,
            columns = columns,
            rows = rows,
            hardObstacles = hardObstacles,
            destructible = remainingTargets,
        )
        val destroyed = remainingTargets.intersect(affected)
        remainingTargets.removeAll(destroyed)
        combo = destroyed.size
        if (destroyed.isNotEmpty()) score += destroyed.size * 25 + (destroyed.size - 1).coerceAtLeast(0) * 15
        target = null
        if (remainingTargets.isEmpty()) {
            outcome = ArcadeOutcome.WON
        } else if (selectNearestReachableTarget() == null) {
            outcome = ArcadeOutcome.LOST
        }
        return snapshot(
            affected,
            destroyed,
            event = when {
                outcome == ArcadeOutcome.WON -> "bomber-cleared"
                outcome == ArcadeOutcome.LOST -> "path-blocked"
                destroyed.isNotEmpty() -> "bomber-hit"
                else -> "bomber-miss"
            },
        )
    }

    private fun selectNearestReachableTarget(): ArcadeCell? {
        val selection = remainingTargets
            .map { it to reachableApproach(it) }
            .filter { (_, path) -> path.isNotEmpty() }
            .minWithOrNull(compareBy<Pair<ArcadeCell, List<ArcadeCell>>> { it.second.size }.thenBy { it.first.row }.thenBy { it.first.column })
        target = selection?.first
        return target
    }

    private fun reachableApproach(candidate: ArcadeCell): List<ArcadeCell> {
        val approaches = ArcadeDirection.entries
            .map(candidate::moved)
            .filter(::inside)
            .filter(::isWalkable)
            .toSet()
        return ArcadeGridPathfinder.shortestPath(
            start = player,
            goals = approaches,
            columns = columns,
            rows = rows,
            blocked = hardObstacles + remainingTargets,
        )
    }

    private fun isWalkable(cell: ArcadeCell): Boolean =
        inside(cell) && cell !in hardObstacles && cell !in remainingTargets

    private fun inside(cell: ArcadeCell): Boolean =
        cell.column in 0 until columns && cell.row in 0 until rows

    private fun snapshot(
        affected: Set<ArcadeCell>,
        destroyed: Set<ArcadeCell>,
        event: String? = null,
    ) = BomberUpdate(
        affected = affected,
        destroyed = destroyed,
        score = score,
        combo = combo,
        progress = if (totalTargets == 0) 1f else 1f - remainingTargets.size.toFloat() / totalTargets,
        outcome = outcome,
        event = event,
    )
}

internal object ArcadeGridPathfinder {
    fun shortestPath(
        start: ArcadeCell,
        goals: Set<ArcadeCell>,
        columns: Int,
        rows: Int,
        blocked: Set<ArcadeCell>,
    ): List<ArcadeCell> {
        if (goals.isEmpty()) return emptyList()
        if (start in goals) return listOf(start)
        val queue = ArrayDeque<ArcadeCell>()
        val previous = mutableMapOf<ArcadeCell, ArcadeCell?>()
        queue += start
        previous[start] = null
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            ArcadeDirection.entries.asSequence()
                .map(cell::moved)
                .filter { it.column in 0 until columns && it.row in 0 until rows }
                .filter { it !in blocked && it !in previous }
                .forEach { next ->
                    previous[next] = cell
                    if (next in goals) return reconstruct(next, previous)
                    queue += next
                }
        }
        return emptyList()
    }

    fun blastCells(
        origin: ArcadeCell,
        range: Int,
        columns: Int,
        rows: Int,
        hardObstacles: Set<ArcadeCell>,
        destructible: Set<ArcadeCell>,
    ): Set<ArcadeCell> = buildSet {
        add(origin)
        ArcadeDirection.entries.forEach { direction ->
            var cursor = origin
            for (distance in 1..range.coerceAtLeast(0)) {
                cursor = cursor.moved(direction)
                if (cursor.column !in 0 until columns || cursor.row !in 0 until rows || cursor in hardObstacles) break
                add(cursor)
                if (cursor in destructible) break
            }
        }
    }

    private fun reconstruct(
        end: ArcadeCell,
        previous: Map<ArcadeCell, ArcadeCell?>,
    ): List<ArcadeCell> {
        val path = mutableListOf<ArcadeCell>()
        var cursor: ArcadeCell? = end
        while (cursor != null) {
            path += cursor
            cursor = previous[cursor]
        }
        return path.asReversed()
    }
}

private fun manhattan(left: ArcadeCell, right: ArcadeCell): Int =
    abs(left.column - right.column) + abs(left.row - right.row)
