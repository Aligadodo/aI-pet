package com.sweetgirlfriend.pet.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.sweetgirlfriend.pet.content.ContentPackRepository
import com.sweetgirlfriend.pet.runtime.AnimationClip

class FrameSequencePlayer(
    private val loader: ContentPackRepository,
    private var packId: String,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val renderTarget = RectF()
    private val avatarSource = Rect()
    private val avatarTarget = RectF()
    private val avatarClipPath = Path()
    private var clip: AnimationClip? = null
    private var frames: List<Bitmap> = emptyList()
    private var avatarBitmap: Bitmap? = null
    private var gameKit = loader.loadGameKit(packId)
    private var frameIndex = 0
    private var lastFrameAtMs = 0L
    private var finished = false

    var speedMultiplier: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }

    val currentAction: String get() = clip?.action ?: ""
    val currentMotion get() = clip?.motion

    fun setPack(packId: String, action: String = "idle") {
        // A resource pack can be upgraded in-place while keeping the same protocol id. Always
        // reload here so ACTION_REFRESH cannot keep decoded frames or avatar metadata from the
        // previous version merely because its id did not change.
        this.packId = packId
        avatarBitmap?.takeIf { !it.isRecycled }?.recycle()
        gameKit = loader.loadGameKit(packId)
        avatarBitmap = null
        setAction(action, force = true)
    }

    fun setAction(action: String, force: Boolean = false) {
        if (!force && clip?.action == action && !finished) return
        val nextClip = loader.loadClip(packId, action)
        val nextFrames = nextClip.framePaths.map { path ->
            loader.openAsset(path).use { stream ->
                requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode $path" }
            }
        }
        releaseFrames()
        clip = nextClip
        frames = nextFrames
        frameIndex = 0
        lastFrameAtMs = 0L
        finished = false
    }

    fun restart() {
        if (frames.isEmpty()) return
        frameIndex = 0
        lastFrameAtMs = 0L
        finished = false
    }

    fun update(nowMs: Long): Boolean {
        val active = clip ?: return false
        if (frames.size <= 1 || finished) return false
        val frameDuration = (1_000f / (active.fps * speedMultiplier)).toLong().coerceAtLeast(16L)
        if (lastFrameAtMs == 0L) {
            lastFrameAtMs = nowMs
            return true
        }
        val elapsed = nowMs - lastFrameAtMs
        if (elapsed < frameDuration) return false
        val steps = (elapsed / frameDuration).toInt().coerceAtLeast(1)
        lastFrameAtMs += steps * frameDuration
        val candidate = frameIndex + steps
        if (active.loop) {
            frameIndex = candidate % frames.size
        } else {
            frameIndex = candidate.coerceAtMost(frames.lastIndex)
            finished = frameIndex == frames.lastIndex
        }
        return true
    }

    fun draw(canvas: Canvas, bounds: RectF, avatarMode: Boolean = false) {
        if (avatarMode && drawAvatar(canvas, bounds)) return
        val bitmap = frames.getOrNull(frameIndex) ?: return
        canvas.drawBitmap(bitmap, null, targetRect(bitmap, bounds), paint)
    }

    /** Returns the decoded character's ground pivot in view-local coordinates. */
    fun groundAnchorInBounds(bounds: RectF, out: PointF): PointF {
        val bitmap = frames.getOrNull(frameIndex) ?: return out.apply {
            set(bounds.centerX(), bounds.bottom)
        }
        val target = targetRect(bitmap, bounds)
        val motion = clip?.motion
        return out.apply { set(
            target.left + target.width() * (motion?.groundAnchorX ?: 0.5f).coerceIn(0f, 1f),
            target.top + target.height() * (motion?.groundAnchorY ?: 0.94f).coerceIn(0f, 1f),
        ) }
    }

    fun release() {
        releaseFrames()
        avatarBitmap?.takeIf { !it.isRecycled }?.recycle()
        avatarBitmap = null
        clip = null
    }

    private fun drawAvatar(canvas: Canvas, bounds: RectF): Boolean {
        val avatar = gameKit.avatar ?: return false
        val bitmap = avatarBitmap ?: loader.openAsset(avatar.source).use { stream ->
            BitmapFactory.decodeStream(stream)
        }?.also { avatarBitmap = it } ?: return false
        avatarSource.set(
            (bitmap.width * avatar.cropLeft).toInt(),
            (bitmap.height * avatar.cropTop).toInt(),
            (bitmap.width * avatar.cropRight).toInt(),
            (bitmap.height * avatar.cropBottom).toInt(),
        )
        val diameter = minOf(bounds.width(), bounds.height()) * 0.86f
        avatarTarget.set(
            bounds.centerX() - diameter / 2f,
            bounds.centerY() - diameter / 2f,
            bounds.centerX() + diameter / 2f,
            bounds.centerY() + diameter / 2f,
        )
        canvas.save()
        if (avatar.shape == "circle") {
            avatarClipPath.reset()
            avatarClipPath.addOval(avatarTarget, Path.Direction.CW)
            canvas.clipPath(avatarClipPath)
            canvas.drawColor((gameKit.accentColor and 0x00FFFFFF) or 0x22000000)
        }
        canvas.drawBitmap(bitmap, avatarSource, avatarTarget, paint)
        canvas.restore()
        return true
    }

    private fun releaseFrames() {
        frames.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        frames = emptyList()
    }

    private fun targetRect(bitmap: Bitmap, bounds: RectF): RectF {
        val scale = minOf(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = bounds.centerX() - width / 2f
        val top = bounds.bottom - height
        renderTarget.set(left, top, left + width, top + height)
        return renderTarget
    }
}
