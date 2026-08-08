package com.sweetgirlfriend.pet.runtime

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Pixel geometry shared by the overlay host without depending on Android framework classes. */
data class MotionPoint(
    val x: Float,
    val y: Float,
)

data class MotionInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun maxWith(other: MotionInsets): MotionInsets = MotionInsets(
        left = max(left, other.left),
        top = max(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )
}

data class MotionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left) { "right must not be smaller than left" }
        require(bottom >= top) { "bottom must not be smaller than top" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}

enum class BorderEdge {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
}

data class BorderMotionPose(
    val foot: MotionPoint,
    val edge: BorderEdge,
    /** Rotates an upright humanoid so its feet touch [edge] and its body points inward. */
    val surfaceRotationDegrees: Float,
    /** Clockwise tangent, retained for non-humanoid clips that opt into align-velocity. */
    val velocityRotationDegrees: Float,
    /** Whether a right-facing source clip must be mirrored in local, unrotated coordinates. */
    val faceLocalLeft: Boolean,
)

/**
 * Canonical full-display/safe-area, sensor and border-path calculations.
 *
 * Coordinates use Android window convention: +x is right and +y is down. Display rotation is a
 * clockwise quarter-turn count matching Surface.ROTATION_0/90/180/270 values (0, 1, 2, 3).
 */
object PetMotionGeometry {
    fun safeArea(
        display: MotionRect,
        systemBars: MotionInsets,
        displayCutout: MotionInsets,
    ): MotionRect {
        val insets = systemBars.maxWith(displayCutout)
        val rawLeft = display.left + insets.left.coerceAtLeast(0f)
        val rawTop = display.top + insets.top.coerceAtLeast(0f)
        val rawRight = display.right - insets.right.coerceAtLeast(0f)
        val rawBottom = display.bottom - insets.bottom.coerceAtLeast(0f)
        val collapsedX = (display.left + display.right) / 2f
        val collapsedY = (display.top + display.bottom) / 2f
        return MotionRect(
            left = if (rawLeft <= rawRight) rawLeft else collapsedX,
            top = if (rawTop <= rawBottom) rawTop else collapsedY,
            right = if (rawLeft <= rawRight) rawRight else collapsedX,
            bottom = if (rawTop <= rawBottom) rawBottom else collapsedY,
        )
    }

    /**
     * TYPE_GRAVITY and the accelerometer report the device's support/up vector. Falling motion is
     * its inverse. The y conversion also changes sensor +y-up into window +y-down.
     */
    fun sensorGravityToScreen(
        sensorX: Float,
        sensorY: Float,
        displayRotationQuarterTurns: Int,
    ): MotionPoint {
        val naturalX = -sensorX
        val naturalY = sensorY
        return when (Math.floorMod(displayRotationQuarterTurns, 4)) {
            1 -> MotionPoint(-naturalY, naturalX)
            2 -> MotionPoint(-naturalX, -naturalY)
            3 -> MotionPoint(naturalY, -naturalX)
            else -> MotionPoint(naturalX, naturalY)
        }
    }

    fun borderPerimeter(bounds: MotionRect): Float = (bounds.width + bounds.height) * 2f

    /** Returns a clockwise pose whose foot pivot lies exactly on the requested safe boundary. */
    fun borderPose(progressPx: Float, bounds: MotionRect): BorderMotionPose {
        val perimeter = borderPerimeter(bounds)
        if (perimeter <= 0f) {
            return BorderMotionPose(
                foot = MotionPoint(bounds.left, bounds.top),
                edge = BorderEdge.TOP,
                surfaceRotationDegrees = 180f,
                velocityRotationDegrees = 0f,
                faceLocalLeft = true,
            )
        }
        val progress = positiveModulo(progressPx, perimeter)
        val width = bounds.width
        val height = bounds.height
        return when {
            progress < width -> pose(
                x = bounds.left + progress,
                y = bounds.top,
                edge = BorderEdge.TOP,
                surfaceRotation = 180f,
                tangentX = 1f,
                tangentY = 0f,
            )

            progress < width + height -> pose(
                x = bounds.right,
                y = bounds.top + progress - width,
                edge = BorderEdge.RIGHT,
                surfaceRotation = -90f,
                tangentX = 0f,
                tangentY = 1f,
            )

            progress < width * 2f + height -> pose(
                x = bounds.right - (progress - width - height),
                y = bounds.bottom,
                edge = BorderEdge.BOTTOM,
                surfaceRotation = 0f,
                tangentX = -1f,
                tangentY = 0f,
            )

            else -> pose(
                x = bounds.left,
                y = bounds.bottom - (progress - width * 2f - height),
                edge = BorderEdge.LEFT,
                surfaceRotation = 90f,
                tangentX = 0f,
                tangentY = -1f,
            )
        }
    }

    /** Maps an arbitrary foot point to the nearest point on the clockwise safe-area path. */
    fun borderProgress(point: MotionPoint, bounds: MotionRect): Float {
        val x = point.x.coerceIn(bounds.left, bounds.right)
        val y = point.y.coerceIn(bounds.top, bounds.bottom)
        val distances = listOf(
            y - bounds.top,
            bounds.right - x,
            bounds.bottom - y,
            x - bounds.left,
        )
        return when (distances.indices.minByOrNull { distances[it] } ?: 0) {
            0 -> x - bounds.left
            1 -> bounds.width + y - bounds.top
            2 -> bounds.width + bounds.height + (bounds.right - x)
            else -> bounds.width * 2f + bounds.height + (bounds.bottom - y)
        }
    }

    /** Preserves the current edge and relative along-edge position across rotation or resize. */
    fun remapBorderProgress(
        progressPx: Float,
        oldBounds: MotionRect,
        newBounds: MotionRect,
    ): Float {
        val oldPerimeter = borderPerimeter(oldBounds)
        if (oldPerimeter <= 0f) return 0f
        val progress = positiveModulo(progressPx, oldPerimeter)
        val oldWidth = oldBounds.width
        val oldHeight = oldBounds.height
        return when {
            progress < oldWidth -> newBounds.width * fraction(progress, oldWidth)
            progress < oldWidth + oldHeight -> {
                newBounds.width + newBounds.height * fraction(progress - oldWidth, oldHeight)
            }
            progress < oldWidth * 2f + oldHeight -> {
                newBounds.width + newBounds.height +
                    newBounds.width * fraction(progress - oldWidth - oldHeight, oldWidth)
            }
            else -> {
                newBounds.width * 2f + newBounds.height +
                    newBounds.height * fraction(progress - oldWidth * 2f - oldHeight, oldHeight)
            }
        }
    }

    /** Places the whole drag window just inside the nearest safe side with an explicit gap. */
    fun snappedWindowX(
        currentWindowCenterX: Float,
        windowWidth: Float,
        safeArea: MotionRect,
        edgeGapPx: Float,
    ): Float {
        val gap = edgeGapPx.coerceAtLeast(0f)
        val left = safeArea.left + gap
        val right = safeArea.right - gap - windowWidth
        if (right < left) return safeArea.centerX - windowWidth / 2f
        return if (currentWindowCenterX < safeArea.centerX) left else right
    }

    private fun pose(
        x: Float,
        y: Float,
        edge: BorderEdge,
        surfaceRotation: Float,
        tangentX: Float,
        tangentY: Float,
    ): BorderMotionPose {
        val radians = Math.toRadians(surfaceRotation.toDouble())
        val localRightX = cos(radians).toFloat()
        val localRightY = sin(radians).toFloat()
        val movingAgainstLocalRight = tangentX * localRightX + tangentY * localRightY < 0f
        val velocityRotation = Math.toDegrees(
            kotlin.math.atan2(tangentY.toDouble(), tangentX.toDouble()),
        ).toFloat()
        return BorderMotionPose(
            foot = MotionPoint(x, y),
            edge = edge,
            surfaceRotationDegrees = surfaceRotation,
            velocityRotationDegrees = velocityRotation,
            faceLocalLeft = movingAgainstLocalRight,
        )
    }

    private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus

    private fun fraction(value: Float, length: Float): Float =
        if (length <= 0f) 0f else (value / length).coerceIn(0f, 1f)
}
