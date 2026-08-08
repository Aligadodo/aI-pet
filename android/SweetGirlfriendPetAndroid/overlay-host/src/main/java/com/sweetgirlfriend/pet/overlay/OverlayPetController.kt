package com.sweetgirlfriend.pet.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.sweetgirlfriend.pet.runtime.PlayMode

object OverlayPetController {
    @Volatile
    private var running = false

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun start(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val intent = Intent(context, OverlayPetService::class.java).setAction(OverlayPetService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OverlayPetService::class.java))
    }

    fun showTask(context: Context) {
        if (!hasPermission(context)) return
        context.startService(
            Intent(context, OverlayPetService::class.java).setAction(OverlayPetService.ACTION_SHOW_TASK),
        )
    }

    fun refresh(context: Context) {
        if (!isRunning(context)) return
        context.startService(
            Intent(context, OverlayPetService::class.java).setAction(OverlayPetService.ACTION_REFRESH),
        )
    }

    fun setPlayMode(context: Context, mode: PlayMode) {
        if (!hasPermission(context) || !isRunning(context)) return
        context.startService(
            Intent(context, OverlayPetService::class.java)
                .setAction(OverlayPetService.ACTION_SET_PLAY_MODE)
                .putExtra(OverlayPetService.EXTRA_PLAY_MODE, mode.name),
        )
    }

    fun isRunning(context: Context): Boolean = running

    internal fun markRunning(value: Boolean) {
        running = value
    }

    const val PREFERENCES = "pet_settings"
}
