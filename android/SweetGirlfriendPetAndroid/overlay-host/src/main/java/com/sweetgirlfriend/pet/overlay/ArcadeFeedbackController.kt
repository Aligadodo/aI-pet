package com.sweetgirlfriend.pet.overlay

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal enum class ArcadeFeedbackEvent { MOVE, COLLECT, BOMB, COMBO, WIN, LOSE, RESTORE }

/** Lazily allocates audio and always exposes an explicit lifecycle release point. */
internal class ArcadeFeedbackController(context: Context) {
    private val appContext = context.applicationContext
    private var toneGenerator: ToneGenerator? = null
    private var soundEnabled = false
    private var hapticsEnabled = true
    private var reducedEffects = false

    fun configure(soundEnabled: Boolean, hapticsEnabled: Boolean, reducedEffects: Boolean) {
        this.soundEnabled = soundEnabled
        this.hapticsEnabled = hapticsEnabled
        this.reducedEffects = reducedEffects
        if (!soundEnabled) releaseTone()
    }

    fun emit(event: ArcadeFeedbackEvent) {
        if (event == ArcadeFeedbackEvent.MOVE && reducedEffects) return
        if (soundEnabled) {
            val (tone, duration) = when (event) {
                ArcadeFeedbackEvent.COLLECT -> ToneGenerator.TONE_PROP_ACK to 55
                ArcadeFeedbackEvent.BOMB -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 95
                ArcadeFeedbackEvent.COMBO -> ToneGenerator.TONE_PROP_BEEP2 to 70
                ArcadeFeedbackEvent.WIN -> ToneGenerator.TONE_CDMA_ABBR_ALERT to 140
                ArcadeFeedbackEvent.LOSE -> ToneGenerator.TONE_PROP_NACK to 100
                ArcadeFeedbackEvent.RESTORE -> ToneGenerator.TONE_PROP_PROMPT to 70
                ArcadeFeedbackEvent.MOVE -> ToneGenerator.TONE_PROP_BEEP to 25
            }
            runCatching {
                val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 22).also {
                    toneGenerator = it
                }
                generator.startTone(tone, duration)
            }
        }
        if (hapticsEnabled) vibrate(event)
    }

    fun release() {
        releaseTone()
    }

    private fun vibrate(event: ArcadeFeedbackEvent) {
        val duration = when (event) {
            ArcadeFeedbackEvent.MOVE -> 8L
            ArcadeFeedbackEvent.COLLECT -> 22L
            ArcadeFeedbackEvent.BOMB -> 42L
            ArcadeFeedbackEvent.COMBO -> 30L
            ArcadeFeedbackEvent.WIN -> 55L
            ArcadeFeedbackEvent.LOSE -> 38L
            ArcadeFeedbackEvent.RESTORE -> 18L
        }
        val amplitude = when (event) {
            ArcadeFeedbackEvent.BOMB, ArcadeFeedbackEvent.WIN -> 105
            ArcadeFeedbackEvent.LOSE -> 80
            else -> 48
        }
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        }
    }

    private fun releaseTone() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
