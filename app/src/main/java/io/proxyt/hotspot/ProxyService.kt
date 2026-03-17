package io.proxyt.hotspot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ProxyPreferences.reconcileStatus(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ProxyPreferences.reconcileStatus(this)
        when (intent?.action) {
            ACTION_STOP -> stopProxy()
            else -> startProxy(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        process?.destroy()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startProxy(intent: Intent?) {
        if (process != null) {
            val currentStatus = ProxyPreferences.readStatus(this)
            ProxyPreferences.setStatus(
                this,
                currentStatus.copy(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Running,
                    message = "Proxy already running",
                ),
            )
            refreshNotification("Proxy already running")
            sendStatusBroadcast()
            return
        }

        val config = parseConfig(intent)
        val resolvedBaseUrl = resolveAdvertisedBaseUrl(config)
        if (!hasNotificationPermission()) {
            val failure = getString(R.string.notification_permission_required_message)
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = getString(R.string.notification_permission_required_short),
                    lastFailureReason = failure,
                ),
            )
            ProxyPreferences.appendLog(this, "Start blocked: $failure")
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val logFile = ProxyPreferences.logFile(this)
        val binary = File(applicationInfo.nativeLibraryDir, NATIVE_BINARY)
        binary.setExecutable(true, true)
        if (!binary.exists()) {
            val failure = "Missing bundled binary: ${binary.absolutePath}"
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = "Bundled proxy binary missing",
                    lastFailureReason = failure,
                ),
            )
            ProxyPreferences.appendLog(this, failure)
            refreshNotification("Bundled proxy binary missing")
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val command = buildList {
            add(binary.absolutePath)
            add("serve")
            add("--http-only")
            add("--bind")
            add("0.0.0.0")
            add("--port")
            add(config.port.toString())
            add("--base-url")
            add(resolvedBaseUrl)
            if (config.debug) {
                add("--debug")
            }
        }

        val startTimestamp = System.currentTimeMillis()
        stopRequested = false
        ProxyPreferences.appendLog(this, "Starting proxy for $resolvedBaseUrl")
        ProxyPreferences.setStatus(
            this,
            ProxyStatus(
                desiredRunning = true,
                state = ProxyRuntimeState.Starting,
                activeUrl = resolvedBaseUrl,
                lastExitCode = null,
                message = "Starting proxy",
                startTimestampMs = startTimestamp,
                lastSuccessfulStartTimestampMs = ProxyPreferences.readStatus(this).lastSuccessfulStartTimestampMs,
            ),
        )
        sendStatusBroadcast()
        startForeground(NOTIFICATION_ID, notification("Starting $resolvedBaseUrl"))

        try {
            val startedProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()

            process = startedProcess
            ProxyPreferences.saveConfig(this, config)
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Running,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = "Serving on $resolvedBaseUrl",
                    startTimestampMs = startTimestamp,
                    lastSuccessfulStartTimestampMs = startTimestamp,
                ),
            )
            refreshNotification("Serving $resolvedBaseUrl")
            sendStatusBroadcast()

            executor.execute {
                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                    process = null
                }

                val previousStatus = ProxyPreferences.readStatus(this)
                val nextStatus = if (stopRequested) {
                    ProxyStatus(
                        desiredRunning = false,
                        state = ProxyRuntimeState.Idle,
                        activeUrl = resolvedBaseUrl,
                        lastExitCode = exitCode,
                        message = "Proxy stopped",
                        startTimestampMs = previousStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    )
                } else {
                    ProxyStatus(
                        desiredRunning = true,
                        state = ProxyRuntimeState.Failed,
                        activeUrl = resolvedBaseUrl,
                        lastExitCode = exitCode,
                        message = "Proxy exited unexpectedly",
                        lastFailureReason = "Proxy exited with code $exitCode",
                        startTimestampMs = previousStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    )
                }
                ProxyPreferences.setStatus(this, nextStatus)
                ProxyPreferences.appendLog(this, nextStatus.lastFailureReason.ifBlank { nextStatus.message })
                refreshNotification(nextStatus.message)
                sendStatusBroadcast()
                stopSelf()
            }
        } catch (exception: Exception) {
            process = null
            val failure = exception.message ?: "unknown error"
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = "Failed to start proxy",
                    lastFailureReason = failure,
                    startTimestampMs = startTimestamp,
                    lastSuccessfulStartTimestampMs = ProxyPreferences.readStatus(this).lastSuccessfulStartTimestampMs,
                ),
            )
            ProxyPreferences.appendLog(this, "Failed to start proxy: $failure")
            refreshNotification("Failed to start proxy")
            sendStatusBroadcast()
            stopSelf()
        }
    }

    private fun stopProxy() {
        val currentStatus = ProxyPreferences.readStatus(this)
        val runningProcess = process ?: run {
            ProxyPreferences.setStatus(
                this,
                currentStatus.copy(
                    desiredRunning = false,
                    state = ProxyRuntimeState.Idle,
                    message = "Proxy stopped",
                ),
            )
            sendStatusBroadcast()
            stopSelf()
            return
        }

        stopRequested = true
        ProxyPreferences.setStatus(
            this,
            currentStatus.copy(
                desiredRunning = false,
                state = ProxyRuntimeState.Stopping,
                message = "Stopping proxy",
            ),
        )
        refreshNotification("Stopping proxy")
        sendStatusBroadcast()
        runningProcess.destroy()
        executor.execute {
            if (!runningProcess.waitFor(2, TimeUnit.SECONDS)) {
                runningProcess.destroyForcibly()
            }
        }
    }

    private fun parseConfig(intent: Intent?): ProxyConfig {
        val fallback = ProxyPreferences.loadConfig(this)
        return ProxyConfig(
            port = intent?.getIntExtra(EXTRA_PORT, fallback.port) ?: fallback.port,
            advertisedBaseUrl = intent?.getStringExtra(EXTRA_ADVERTISED_BASE_URL)
                ?.trim()
                ?.removeSuffix("/")
                .orEmpty()
                .ifBlank { fallback.advertisedBaseUrl.trim().removeSuffix("/") },
            debug = intent?.getBooleanExtra(EXTRA_DEBUG, fallback.debug) ?: fallback.debug,
        )
    }

    private fun resolveAdvertisedBaseUrl(config: ProxyConfig): String {
        if (config.advertisedBaseUrl.isNotBlank()) {
            return config.advertisedBaseUrl.trim().removeSuffix("/")
        }

        val address = HotspotAddressDetector.detectPrivateIpv4() ?: DEFAULT_HOTSPOT_IP
        return "http://$address:${config.port}"
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendStatusBroadcast() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun refreshNotification(status: String) {
        if (!hasNotificationPermission()) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(process != null)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proxy status",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "io.proxyt.hotspot.action.START"
        const val ACTION_STATUS = "io.proxyt.hotspot.action.STATUS"
        const val ACTION_STOP = "io.proxyt.hotspot.action.STOP"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_ADVERTISED_BASE_URL = "extra_advertised_base_url"
        const val EXTRA_DEBUG = "extra_debug"
        const val DEFAULT_HOTSPOT_IP = "192.168.43.1"

        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 4001
        private const val NATIVE_BINARY = "libproxyt.so"
    }
}
