package com.sweetgirlfriend.pet.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.sweetgirlfriend.pet.content.ContentPackLoader
import com.sweetgirlfriend.pet.renderer.FrameSequencePlayer
import com.sweetgirlfriend.pet.runtime.EnergyPolicy
import com.sweetgirlfriend.pet.runtime.BackgroundPresentation
import com.sweetgirlfriend.pet.runtime.EnergyProfile
import com.sweetgirlfriend.pet.runtime.InteractionStyle
import com.sweetgirlfriend.pet.runtime.PetActivityLevel
import com.sweetgirlfriend.pet.runtime.PetStateMachine
import java.time.LocalDateTime

class PetWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = PetEngine()

    private inner class PetEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt() }
        private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5A4350.toInt()
            textSize = 36f
        }
        private val loader = ContentPackLoader(applicationContext)
        private val preferences = getSharedPreferences("pet_settings", MODE_PRIVATE)
        private val powerManager = getSystemService(PowerManager::class.java)
        private var player: FrameSequencePlayer? = null
        private var stateMachine: PetStateMachine? = null
        private var visible = false
        private var destroyed = false
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var petCenterFraction = 0.72f
        private var lastTapAtMs = 0L
        private var bubbleText: String? = null
        private var bubbleUntilMs = 0L

        private val drawLoop = object : Runnable {
            override fun run() {
                if (!visible || destroyed) return
                drawFrame()
                val fps = currentFrameRate()
                if (fps > 0) handler.postDelayed(this, 1_000L / fps)
            }
        }

        init {
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            val packId = preferences.getString("pack_id", "girlfriend-classic")
                ?: "girlfriend-classic"
            val actions = loader.availableActions(packId)
            stateMachine = PetStateMachine(actions)
            player = FrameSequencePlayer(loader, packId).also { sequence ->
                sequence.speedMultiplier = preferences.getInt("speed_percent", 100) / 100f
                sequence.setAction("idle", force = true)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            backgroundPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                surfaceHeight.toFloat(),
                intArrayOf(0xFFFFF7FB.toInt(), 0xFFFFE8EF.toInt(), 0xFFF1DCE8.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(drawLoop)
            if (visible && !destroyed) handler.post(drawLoop)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            destroyed = true
            visible = false
            handler.removeCallbacks(drawLoop)
            player?.release()
            player = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            if (!xOffset.isNaN()) petCenterFraction = (0.72f - (xOffset - 0.5f) * 0.14f).coerceIn(0.18f, 0.82f)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - lastTapAtMs <= 320L
                val action = if (doubleTap) {
                    bubbleText = loader.randomDialogue(currentPackId(), "double_tap")
                    stateMachine?.onDoubleTap(now)
                } else {
                    bubbleText = loader.randomDialogue(currentPackId(), "tap")
                    stateMachine?.onTap(now)
                }
                lastTapAtMs = now
                bubbleUntilMs = now + 3_500L
                action?.let { player?.setAction(it) }
                drawFrame()
            }
            super.onTouchEvent(event)
        }

        private fun drawFrame() {
            val now = SystemClock.uptimeMillis()
            val date = LocalDateTime.now()
            val style = runCatching {
                InteractionStyle.valueOf(
                    preferences.getString("interaction_style", "DAILY") ?: "DAILY",
                )
            }.getOrDefault(InteractionStyle.DAILY)
            val hour = if (preferences.getBoolean("quiet_hours_enabled", true)) date.hour else 12
            val action = stateMachine?.tick(now, hour, style)
            if (action != null && action != player?.currentAction) player?.setAction(action)
            player?.update(now)

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas() ?: return
                val presentation = runCatching {
                    BackgroundPresentation.valueOf(
                        preferences.getString("background_presentation", "REPLACE_BACKGROUND")
                            ?: "REPLACE_BACKGROUND",
                    )
                }.getOrDefault(BackgroundPresentation.REPLACE_BACKGROUND)
                if (presentation == BackgroundPresentation.REPLACE_BACKGROUND) {
                    canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), backgroundPaint)
                    drawGround(canvas)
                } else {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                }
                val size = preferences.getInt("size_percent", 78).coerceIn(45, 100) / 100f
                val targetWidth = surfaceWidth * 0.68f * size
                val targetHeight = surfaceHeight * 0.57f * size
                val center = surfaceWidth * petCenterFraction
                val bounds = RectF(
                    center - targetWidth / 2f,
                    surfaceHeight - targetHeight - surfaceHeight * 0.055f,
                    center + targetWidth / 2f,
                    surfaceHeight - surfaceHeight * 0.055f,
                )
                player?.draw(canvas, bounds)
                if (now < bubbleUntilMs) drawBubble(canvas, bubbleText.orEmpty(), bounds)
            } finally {
                canvas?.let(surfaceHolder::unlockCanvasAndPost)
            }
        }

        private fun drawGround(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
            canvas.drawOval(
                surfaceWidth * 0.08f,
                surfaceHeight * 0.82f,
                surfaceWidth * 0.92f,
                surfaceHeight * 1.02f,
                paint,
            )
        }

        private fun drawBubble(canvas: Canvas, text: String, petBounds: RectF) {
            if (text.isBlank()) return
            val maxWidth = surfaceWidth * 0.76f
            val shown = if (text.length > 22) text.take(21) + "…" else text
            val measured = bubbleTextPaint.measureText(shown)
            val width = minOf(maxWidth, measured + 52f)
            val height = 82f
            val left = (petBounds.centerX() - width / 2f).coerceIn(20f, surfaceWidth - width - 20f)
            val top = (petBounds.top - height - 18f).coerceAtLeast(80f)
            canvas.drawRoundRect(RectF(left, top, left + width, top + height), 28f, 28f, bubblePaint)
            canvas.drawText(shown, left + 26f, top + 53f, bubbleTextPaint)
        }

        private fun currentPackId(): String =
            preferences.getString("pack_id", "girlfriend-classic") ?: "girlfriend-classic"

        private fun currentFrameRate(): Int {
            val profile = runCatching {
                EnergyProfile.valueOf(preferences.getString("energy_profile", "ADAPTIVE") ?: "ADAPTIVE")
            }.getOrDefault(EnergyProfile.ADAPTIVE)
            val hour = LocalDateTime.now().hour
            val quiet = preferences.getBoolean("quiet_hours_enabled", true) && (hour >= 23 || hour < 7)
            val action = player?.currentAction.orEmpty()
            val level = when {
                quiet -> PetActivityLevel.SLEEP
                action == "run" || action == "walk" -> PetActivityLevel.ACTIVE
                action == "wave" || action == "photo_pose" -> PetActivityLevel.INTERACTING
                else -> PetActivityLevel.IDLE
            }
            return EnergyPolicy.frameRate(profile, level, powerManager.isInteractive && visible)
        }
    }
}
