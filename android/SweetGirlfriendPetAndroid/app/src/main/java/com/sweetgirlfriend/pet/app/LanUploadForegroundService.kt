package com.sweetgirlfriend.pet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.TimeUnit

/** Keeps the explicitly enabled 15-minute upload session alive in background. */
class LanUploadForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var expiryRunnable: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val refreshNetworkRunnable = Runnable {
        val endpoint = LanUploadSessionManager.refreshNetwork() ?: return@Runnable
        updateForegroundNotification(endpoint)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopSession("局域网上传已关闭")
            ACTION_START -> startSession(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        expiryRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacks(refreshNetworkRunnable)
        unregisterNetworkCallback()
        if (LanUploadSessionManager.snapshot().running || LanUploadSessionManager.snapshot().starting) {
            LanUploadSessionManager.stopServer("上传服务已被系统停止，请重新开启")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(startId: Int) {
        startInForeground(buildNotification(null))
        runCatching { LanUploadSessionManager.startServer(applicationContext) }
            .onSuccess { endpoint ->
                updateForegroundNotification(endpoint)
                scheduleExpiry(endpoint.expiresAtMillis)
                registerNetworkCallback()
            }
            .onFailure {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
    }

    private fun stopSession(reason: String) {
        LanUploadSessionManager.stopServer(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleExpiry(expiresAtMillis: Long) {
        expiryRunnable?.let(handler::removeCallbacks)
        val task = Runnable { stopSession("局域网上传会话已到期") }
        expiryRunnable = task
        handler.postDelayed(task, (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRefresh()

            override fun onLost(network: Network) = scheduleNetworkRefresh()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleNetworkRefresh()
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun scheduleNetworkRefresh() {
        handler.removeCallbacks(refreshNetworkRunnable)
        handler.postDelayed(refreshNetworkRunnable, NETWORK_REFRESH_DEBOUNCE_MS)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "局域网资源包上传", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在短时会话内接收同一局域网电脑上传的桌宠资源包"
                setShowBadge(false)
            },
        )
    }

    private fun updateForegroundNotification(endpoint: LanPackUploadServer.Endpoint) {
        startInForeground(buildNotification(endpoint))
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(endpoint: LanPackUploadServer.Endpoint?): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            31,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            32,
            Intent(this, LanUploadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (endpoint == null) {
            "正在准备安全上传地址…"
        } else {
            val remaining = (endpoint.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining + 59_999L).coerceAtLeast(1L)
            val address = endpoint.browserUrls.firstOrNull()?.substringAfter("http://")?.substringBefore('/')
            buildString {
                append("剩余约 ").append(minutes).append(" 分钟")
                if (address != null) append(" · ").append(address)
                append(" · 配对码 ").append(LanPairingPolicy.displayCode(endpoint.pairingCode))
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("桌宠资源包局域网上传")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n电脑打开裸地址后输入配对码；仅接受校验通过的 PetPack v2。"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "关闭上传", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.sweetgirlfriend.pet.action.START_LAN_UPLOAD"
        const val ACTION_STOP = "com.sweetgirlfriend.pet.action.STOP_LAN_UPLOAD"
        private const val CHANNEL_ID = "sweetpet_lan_upload"
        private const val NOTIFICATION_ID = 7_402
        private const val NETWORK_REFRESH_DEBOUNCE_MS = 800L
    }
}
