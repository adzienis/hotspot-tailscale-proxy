package io.proxyt.hotspot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
                    lastFailureReason = "",
                    error = null,
                ),
            )
            refreshNotification("Proxy already running")
            sendStatusBroadcast()
            return
        }

        val currentStatus = ProxyPreferences.readStatus(this)
        val config = parseConfig(intent)
        val resolvedBaseUrl = resolveAdvertisedBaseUrl(config)
        if (!hasNotificationPermission()) {
            val error = notificationPermissionError()
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = getString(R.string.notification_permission_required_short),
                    lastFailureReason = error.detail,
                    lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                ),
            )
            ProxyPreferences.appendLog(this, "Start blocked: ${error.detail}")
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val logFile = ProxyPreferences.logFile(this)
        val binary = File(applicationInfo.nativeLibraryDir, NATIVE_BINARY)
        binary.setExecutable(true, true)
        if (!binary.exists()) {
            val error = ProxyErrorInfo(
                category = ProxyErrorCategory.MISSING_BINARY,
                title = "Bundled binary missing",
                detail = "The app could not find ${binary.absolutePath}.",
                recommendedAction = "Reinstall the app or rebuild it with the bundled native library included.",
            )
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = error.title,
                    lastFailureReason = error.detail,
                    lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                ),
            )
            ProxyPreferences.appendLog(this, error.detail)
            refreshNotification(error.title)
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val configError = validateConfig(config)
        if (configError != null) {
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = configError.title,
                    lastFailureReason = configError.detail,
                    lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                    error = configError,
                ),
            )
            ProxyPreferences.appendLog(this, configError.detail)
            refreshNotification(configError.title)
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
                lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                error = null,
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
                    error = null,
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
                        error = null,
                    )
                } else {
                    val error = classifyProcessFailure(
                        detail = "Proxy exited with code $exitCode.",
                        exitCode = exitCode,
                        logTail = ProxyPreferences.readLogTail(this, 4_000),
                    )
                    ProxyStatus(
                        desiredRunning = true,
                        state = ProxyRuntimeState.Failed,
                        activeUrl = resolvedBaseUrl,
                        lastExitCode = exitCode,
                        message = error.title,
                        lastFailureReason = error.detail,
                        startTimestampMs = previousStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                        error = error,
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
            val error = classifyProcessFailure(
                detail = exception.message ?: "Unknown startup error.",
                logTail = ProxyPreferences.readLogTail(this, 4_000),
            )
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = error.title,
                    lastFailureReason = error.detail,
                    startTimestampMs = startTimestamp,
                    lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                ),
            )
            ProxyPreferences.appendLog(this, "Failed to start proxy: ${error.detail}")
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
                    lastFailureReason = "",
                    error = null,
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
                error = null,
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

    private fun validateConfig(config: ProxyConfig): ProxyErrorInfo? {
        if (config.port !in 1..65535) {
            return ProxyErrorInfo(
                category = ProxyErrorCategory.INVALID_CONFIG,
                title = "Invalid configuration",
                detail = "Port ${config.port} is outside the valid range of 1 to 65535.",
                recommendedAction = "Enter a listen port between 1 and 65535, then try again.",
            )
        }

        val advertisedBaseUrl = config.advertisedBaseUrl
        if (advertisedBaseUrl.isBlank()) {
            return null
        }

        val uri = Uri.parse(advertisedBaseUrl)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            return ProxyErrorInfo(
                category = ProxyErrorCategory.INVALID_CONFIG,
                title = "Invalid configuration",
                detail = "Advertised URL must be a full http:// or https:// URL with a host.",
                recommendedAction = "Fix the advertised base URL or clear it to use the detected hotspot address.",
            )
        }

        return null
    }

    private fun classifyProcessFailure(
        detail: String,
        exitCode: Int? = null,
        logTail: String = "",
    ): ProxyErrorInfo {
        val combined = buildString {
            append(detail)
            if (logTail.isNotBlank()) {
                append('\n')
                append(logTail)
            }
        }.lowercase()

        return when {
            "address already in use" in combined || "bind" in combined || "port already in use" in combined -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PORT_IN_USE,
                    title = "Port is already in use",
                    detail = "Another process is already bound to the selected port.",
                    recommendedAction = "Choose a different listen port or stop the app that is already using this port.",
                )
            }

            "permission denied" in combined || "operation not permitted" in combined || "not allowed" in combined -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PERMISSION_REQUIRED,
                    title = "Permission issue",
                    detail = "Android or the proxy process denied access needed to start cleanly.",
                    recommendedAction = "Grant the required permission or choose a different port, then try starting the proxy again.",
                )
            }

            exitCode != null -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PROXY_EXIT,
                    title = "Proxy process exited",
                    detail = "The bundled proxy exited with code $exitCode.",
                    recommendedAction = "Retry once. If it exits again, copy the last error and inspect the logs for more detail.",
                )
            }

            else -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.STARTUP_FAILURE,
                    title = "Proxy failed to start",
                    detail = detail,
                    recommendedAction = "Try again. If the problem repeats, copy the last error and inspect the logs.",
                )
            }
        }
    }

    private fun notificationPermissionError(): ProxyErrorInfo =
        ProxyErrorInfo(
            category = ProxyErrorCategory.PERMISSION_REQUIRED,
            title = getString(R.string.notification_permission_required_short),
            detail = getString(R.string.notification_permission_required_message),
            recommendedAction = getString(R.string.notification_permission_settings_message),
        )

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
