package com.sweetgirlfriend.pet.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.sweetgirlfriend.pet.content.ContentPackRepository
import kotlin.math.cos
import kotlin.math.sin

class FrameAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var player: FrameSequencePlayer? = null
    private var running = false
    private var characterScale = 0.88f
    private var targetFrameRate = 12
    private var clearTransparentSurface = false
    private var avatarMode = false
    private var motionRotation = 0f
    private var mirrorHorizontally = false
    private var requestedSurfaceRotation = 0f
    private var requestedVelocityRotation = 0f
    private var requestedSurfaceFacingLeft = false
    private val characterBounds = RectF()
    private val rawGroundAnchor = PointF()
    private val transformedGroundAnchor = PointF()
    private val handler = Handler(Looper.getMainLooper())
    private val frameLoop = object : Runnable {
        override fun run() {
            if (!running || targetFrameRate <= 0) return
            if (visibility == VISIBLE && player?.update(SystemClock.uptimeMillis()) == true) invalidate()
            handler.postDelayed(this, (1_000L / targetFrameRate.coerceIn(1, 30)))
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "桌宠动画预览"
    }

    fun configure(loader: ContentPackRepository, packId: String, action: String = "idle") {
        player?.release()
        player = FrameSequencePlayer(loader, packId).also { it.setAction(action, force = true) }
        applyMotionMetadata()
        invalidate()
    }

    fun setPack(packId: String) {
        player?.setPack(packId)
        applyMotionMetadata()
        invalidate()
    }

    fun setAction(action: String, restart: Boolean = false) {
        val activePlayer = player
        if (restart && activePlayer?.currentAction == action) {
            activePlayer.restart()
        } else {
            activePlayer?.setAction(action, force = restart)
        }
        applyMotionMetadata()
        invalidate()
    }

    fun restartAnimation() {
        player?.restart()
        handler.removeCallbacks(frameLoop)
        if (running && targetFrameRate > 0) handler.post(frameLoop)
        invalidate()
    }

    fun setCharacterScale(scale: Float) {
        characterScale = scale.coerceIn(0.45f, 1f)
        invalidate()
    }

    fun setSpeedMultiplier(speed: Float) {
        player?.speedMultiplier = speed
    }

    fun setTargetFrameRate(fps: Int) {
        targetFrameRate = fps.coerceIn(0, 30)
        handler.removeCallbacks(frameLoop)
        if (running && targetFrameRate > 0) handler.post(frameLoop)
    }

    /**
     * Overlay windows use a persistent translucent surface. Clearing the full view before each
     * bitmap prevents opaque pixels from an earlier, wider pose from surviving behind the next
     * frame. Keep this disabled for ordinary views so their Android background remains intact.
     */
    fun setTransparentSurfaceCompositing(enabled: Boolean) {
        clearTransparentSurface = enabled
        invalidate()
    }

    fun setAvatarMode(enabled: Boolean) {
        avatarMode = enabled
        invalidate()
    }

    /**
     * Supplies both physical orientations. The active clip then selects the one declared by its
     * rotationPolicy instead of treating align-surface and align-velocity as synonyms.
     *
     * surfaceFacingLeft is expressed in the clip's unrotated local coordinate space. This lets a
     * left-facing source pack and a right-facing source pack follow the same movement command.
     */
    fun setMotionTransform(
        surfaceRotationDegrees: Float,
        velocityRotationDegrees: Float = surfaceRotationDegrees,
        surfaceFacingLeft: Boolean = false,
    ) {
        requestedSurfaceRotation = surfaceRotationDegrees
        requestedVelocityRotation = velocityRotationDegrees
        requestedSurfaceFacingLeft = surfaceFacingLeft
        applyMotionMetadata()
        invalidate()
    }

    private fun applyMotionMetadata() {
        val motion = player?.currentMotion
        val policy = motion?.rotationPolicy?.lowercase() ?: "upright"
        motionRotation = when (policy) {
            "align-surface" -> requestedSurfaceRotation
            "align-velocity" -> requestedVelocityRotation
            else -> 0f
        }
        val desiredFacingLeft = if (policy == "align-velocity") false else requestedSurfaceFacingLeft
        mirrorHorizontally = motion?.supportsHorizontalMirror != false && when (motion?.defaultFacing?.lowercase()) {
            "left" -> !desiredFacingLeft
            "right" -> desiredFacingLeft
            else -> policy != "align-velocity" && requestedSurfaceFacingLeft
        }
    }

    fun groundAnchorInView(): PointF {
        val bounds = updateCharacterBounds()
        val anchor = resolveRawGroundAnchor(bounds)
        val pivotX = bounds.centerX()
        val pivotY = bounds.centerY()
        var dx = anchor.x - pivotX
        val dy = anchor.y - pivotY
        if (mirrorHorizontally) dx = -dx
        val radians = Math.toRadians(motionRotation.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        transformedGroundAnchor.set(
            pivotX + dx * cosine - dy * sine,
            pivotY + dx * sine + dy * cosine,
        )
        return transformedGroundAnchor
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        if (targetFrameRate > 0) handler.post(frameLoop)
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(frameLoop)
        player?.release()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (clearTransparentSurface) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        val bounds = updateCharacterBounds()
        val pivotX = bounds.centerX()
        val pivotY = bounds.centerY()
        canvas.save()
        canvas.rotate(motionRotation, pivotX, pivotY)
        if (mirrorHorizontally) canvas.scale(-1f, 1f, pivotX, pivotY)
        player?.draw(canvas, bounds, avatarMode)
        canvas.restore()
    }

    private fun updateCharacterBounds(): RectF {
        val targetWidth = width * characterScale
        val left = (width - targetWidth) / 2f
        characterBounds.set(left, 0f, left + targetWidth, height.toFloat())
        return characterBounds
    }

    private fun resolveRawGroundAnchor(bounds: RectF): PointF {
        if (avatarMode) return rawGroundAnchor.apply { set(bounds.centerX(), bounds.centerY()) }
        return player?.groundAnchorInBounds(bounds, rawGroundAnchor) ?: rawGroundAnchor.apply {
            set(width / 2f, height.toFloat())
        }
    }
}
