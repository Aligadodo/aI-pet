package com.sweetgirlfriend.pet.app

import android.content.Context
import android.content.Intent
import android.os.Build
import com.sweetgirlfriend.pet.content.PackInstallResult

/** Process state shared by the Activity and the short-lived foreground service. */
internal object LanUploadSessionManager {
    data class Snapshot(
        val starting: Boolean,
        val running: Boolean,
        val endpoint: LanPackUploadServer.Endpoint?,
        val message: String?,
        val error: String?,
    )

    private val lock = Any()
    private var server: LanPackUploadServer? = null
    private var endpoint: LanPackUploadServer.Endpoint? = null
    private var starting = false
    private var message: String? = null
    private var error: String? = null
    private var installListener: ((PackInstallResult) -> Unit)? = null
    private var stateListener: ((Snapshot) -> Unit)? = null
    private val pendingResults = ArrayDeque<PackInstallResult>()

    fun requestStart(context: Context) {
        synchronized(lock) {
            if (starting || server?.isRunning() == true) return
            starting = true
            message = null
            error = null
        }
        publishState()
        try {
            val intent = Intent(context, LanUploadForegroundService::class.java)
                .setAction(LanUploadForegroundService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (failure: Throwable) {
            markStartFailure(failure)
        }
    }

    fun requestStop(context: Context) {
        val intent = Intent(context, LanUploadForegroundService::class.java)
            .setAction(LanUploadForegroundService.ACTION_STOP)
        runCatching { context.startService(intent) }
            .onFailure {
                context.stopService(Intent(context, LanUploadForegroundService::class.java))
                stopServer("局域网上传已关闭")
            }
    }

    internal fun startServer(context: Context): LanPackUploadServer.Endpoint {
        synchronized(lock) {
            val currentEndpoint = endpoint
            if (server?.isRunning() == true && currentEndpoint != null) return currentEndpoint
        }
        stopServer(null, publish = false)
        val created = LanPackUploadServer(context.applicationContext, ::publishInstallResult)
        return try {
            val started = created.start()
            synchronized(lock) {
                server = created
                endpoint = started
                starting = false
                message = "局域网上传正在运行"
                error = null
            }
            publishState()
            started
        } catch (failure: Throwable) {
            created.stop()
            markStartFailure(failure)
            throw failure
        }
    }

    internal fun refreshNetwork(): LanPackUploadServer.Endpoint? {
        val current = synchronized(lock) { server } ?: return null
        if (!current.isRunning()) return null
        return runCatching { current.refreshEndpoint() }
            .onSuccess { refreshed ->
                synchronized(lock) { endpoint = refreshed }
                publishState()
            }
            .getOrNull()
    }

    internal fun stopServer(reason: String?, publish: Boolean = true) {
        val previous = synchronized(lock) {
            val value = server
            server = null
            endpoint = null
            starting = false
            message = reason
            error = null
            value
        }
        previous?.stop()
        if (publish) publishState()
    }

    internal fun markStartFailure(failure: Throwable) {
        synchronized(lock) {
            server = null
            endpoint = null
            starting = false
            message = null
            error = failure.message ?: failure.javaClass.simpleName
        }
        publishState()
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    fun attach(
        onInstallResult: (PackInstallResult) -> Unit,
        onStateChanged: (Snapshot) -> Unit,
    ) {
        val pending: List<PackInstallResult>
        val snapshot: Snapshot
        synchronized(lock) {
            installListener = onInstallResult
            stateListener = onStateChanged
            pending = buildList {
                while (pendingResults.isNotEmpty()) add(pendingResults.removeFirst())
            }
            snapshot = snapshotLocked()
        }
        onStateChanged(snapshot)
        pending.forEach(onInstallResult)
    }

    fun detach(
        onInstallResult: (PackInstallResult) -> Unit,
        onStateChanged: (Snapshot) -> Unit,
    ) {
        synchronized(lock) {
            if (installListener === onInstallResult) installListener = null
            if (stateListener === onStateChanged) stateListener = null
        }
    }

    private fun publishInstallResult(result: PackInstallResult) {
        val callback = synchronized(lock) {
            val active = installListener
            if (active == null) {
                if (pendingResults.size >= MAX_PENDING_RESULTS) pendingResults.removeFirst()
                pendingResults.addLast(result)
            }
            active
        }
        callback?.invoke(result)
    }

    private fun publishState() {
        val callback: ((Snapshot) -> Unit)?
        val value: Snapshot
        synchronized(lock) {
            callback = stateListener
            value = snapshotLocked()
        }
        callback?.invoke(value)
    }

    private fun snapshotLocked(): Snapshot = Snapshot(
        starting = starting,
        running = server?.isRunning() == true,
        endpoint = endpoint,
        message = message,
        error = error,
    )

    private const val MAX_PENDING_RESULTS = 4
}
