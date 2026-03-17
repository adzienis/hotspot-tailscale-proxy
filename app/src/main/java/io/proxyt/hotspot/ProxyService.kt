package io.proxyt.hotspot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ResolvedEndpoint(
    val activeUrl: String,
    val selectedInterface: String = "",
    val selectedIp: String = "",
    val hotspotActive: Boolean? = null,
)

object ProxyHealthCheck {
    fun localProbeUrl(port: Int): String = "http://127.0.0.1:$port/health"
}

data class ProbeResult(
    val healthy: Boolean,
    val detail: String,
)

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
            ACTION_COPY_URL -> copyActiveUrlToClipboard()
            ACTION_CLEAR_ERROR -> clearErrorState()
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
                ProxyServiceStatusFactory.alreadyRunning(currentStatus),
            )
            refreshNotification("Proxy already running")
            sendStatusBroadcast()
            return
        }

        val previousStatus = ProxyPreferences.readStatus(this)
        val config = parseConfig(intent)
        val localCandidates = HotspotAddressDetector.detectCandidates()
        val resolvedEndpoint = runCatching { resolveEndpoint(config, localCandidates) }
            .getOrDefault(ResolvedEndpoint(activeUrl = ""))
        val diagnosticsSeed = buildDiagnosticsSeed(resolvedEndpoint, localCandidates)

        if (!hasNotificationPermission()) {
            val error = notificationPermissionError()
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedEndpoint.activeUrl,
                    lastExitCode = null,
                    message = getString(R.string.notification_permission_required_short),
                    lastFailureReason = error.detail,
                    lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                    diagnostics = diagnosticsSeed,
                ),
            )
            ProxyPreferences.appendLog(this, "Start blocked: ${error.detail}")
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
                    activeUrl = resolvedEndpoint.activeUrl,
                    lastExitCode = null,
                    message = configError.title,
                    lastFailureReason = configError.detail,
                    lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    error = configError,
                    diagnostics = diagnosticsSeed.copy(portBindResult = "Validation failed before bind"),
                ),
            )
            ProxyPreferences.appendLog(this, configError.detail)
            refreshNotification(configError.title)
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val bindCheck = checkPortBindable(config.port)
        if (!bindCheck.canBind) {
            val error = ProxyErrorInfo(
                category = ProxyErrorCategory.PORT_IN_USE,
                title = "Port is already in use",
                detail = bindCheck.message,
                recommendedAction = "Choose a different listen port or stop the app that is already using this port.",
            )
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Failed,
                    activeUrl = resolvedEndpoint.activeUrl,
                    message = error.title,
                    lastFailureReason = error.detail,
                    lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                    diagnostics = diagnosticsSeed.copy(portBindResult = bindCheck.message),
                ),
            )
            ProxyPreferences.appendLog(this, "Preflight bind failed: ${bindCheck.message}")
            refreshNotification(error.title)
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
                    activeUrl = resolvedEndpoint.activeUrl,
                    lastExitCode = null,
                    message = error.title,
                    lastFailureReason = error.detail,
                    lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                    diagnostics = diagnosticsSeed.copy(portBindResult = bindCheck.message),
                ),
            )
            ProxyPreferences.appendLog(this, error.detail)
            refreshNotification(error.title)
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
            add(resolvedEndpoint.activeUrl)
            if (config.debug) {
                add("--debug")
            }
        }

        val startTimestamp = System.currentTimeMillis()
        val localProbeUrl = ProxyHealthCheck.localProbeUrl(config.port)
        stopRequested = false
        ProxyPreferences.appendLog(this, "Preflight bind OK: ${bindCheck.message}")
        ProxyPreferences.appendLog(this, "Starting proxy for ${resolvedEndpoint.activeUrl}")
        ProxyPreferences.appendLog(this, "Using local startup probe $localProbeUrl")
        ProxyPreferences.setStatus(
            this,
            ProxyServiceStatusFactory.starting(
                previousStatus = previousStatus,
                activeUrl = resolvedEndpoint.activeUrl,
                diagnostics = diagnosticsSeed.copy(portBindResult = bindCheck.message),
                startTimestampMs = startTimestamp,
                probeDetail = "Local HTTP probe pending: $localProbeUrl",
            ),
        )
        sendStatusBroadcast()
        startForeground(NOTIFICATION_ID, notification("Starting ${resolvedEndpoint.activeUrl}"))

        try {
            val startedProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            val processPid = safeProcessPid(startedProcess)

            process = startedProcess
            ProxyPreferences.saveConfig(this, config)

            executor.execute {
                val probeResult = awaitHealthy(
                    startedProcess = startedProcess,
                    localProbePort = config.port,
                    localProbeUrl = localProbeUrl,
                    advertisedUrl = resolvedEndpoint.activeUrl,
                )
                val healthError = probeResult.error
                if (stopRequested) {
                    val exitCode = startedProcess.waitFor()
                    val currentStatus = ProxyPreferences.readStatus(this)
                    ProxyPreferences.setStatus(
                        this,
                        ProxyServiceStatusFactory.idleAfterStop(
                            currentStatus = currentStatus,
                            activeUrl = resolvedEndpoint.activeUrl,
                            exitCode = exitCode,
                        ),
                    )
                    refreshNotification("Proxy stopped")
                    sendStatusBroadcast()
                    stopSelf()
                    return@execute
                }

                if (healthError != null) {
                    if (process === startedProcess) {
                        process = null
                    }
                    ProxyPreferences.setStatus(
                        this,
                        ProxyServiceStatusFactory.healthCheckFailed(
                            previousStatus = previousStatus,
                            activeUrl = resolvedEndpoint.activeUrl,
                            processPid = processPid,
                            diagnostics = diagnosticsSeed,
                            error = healthError,
                            startTimestampMs = startTimestamp,
                            lastExitCode = runCatching { startedProcess.exitValue() }.getOrNull(),
                            probeDetail = probeResult.detail,
                        ),
                    )
                    ProxyPreferences.appendLog(this, healthError.detail)
                    refreshNotification(healthError.title)
                    sendStatusBroadcast()
                    stopSelf()
                    return@execute
                }

                ProxyPreferences.setStatus(
                    this,
                    ProxyServiceStatusFactory.running(
                        activeUrl = resolvedEndpoint.activeUrl,
                        startTimestampMs = startTimestamp,
                        processPid = processPid,
                        diagnostics = diagnosticsSeed,
                        probeDetail = probeResult.detail,
                        port = config.port,
                    ),
                )
                ProxyPreferences.appendLog(
                    this,
                    "Serving on ${resolvedEndpoint.activeUrl}${processPid?.let { " (pid $it)" }.orEmpty()}",
                )
                refreshNotification("Serving ${resolvedEndpoint.activeUrl}")
                sendStatusBroadcast()

                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                    process = null
                }

                val currentStatus = ProxyPreferences.readStatus(this)
                val nextStatus = if (stopRequested) {
                    ProxyServiceStatusFactory.idleAfterStop(
                        currentStatus = currentStatus,
                        activeUrl = resolvedEndpoint.activeUrl,
                        exitCode = exitCode,
                    )
                } else {
                    val error = classifyProcessFailure(
                        detail = "Proxy exited with code $exitCode.",
                        exitCode = exitCode,
                        logTail = ProxyPreferences.readLogTail(this, 4_000),
                    )
                    ProxyServiceStatusFactory.exitedUnexpectedly(
                        currentStatus = currentStatus,
                        activeUrl = resolvedEndpoint.activeUrl,
                        exitCode = exitCode,
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
                    activeUrl = resolvedEndpoint.activeUrl,
                    lastExitCode = null,
                    message = error.title,
                    lastFailureReason = error.detail,
                    startTimestampMs = startTimestamp,
                    lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                    error = error,
                    diagnostics = diagnosticsSeed.copy(portBindResult = "Proxy failed before bind was confirmed"),
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
                    diagnostics = currentStatus.diagnostics.copy(currentPid = null),
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
                diagnostics = currentStatus.diagnostics.copy(
                    currentPid = safeProcessPid(runningProcess),
                    lastProbeResult = currentStatus.diagnostics.lastProbeResult.ifBlank { "No probe recorded" },
                ),
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
            advertisedBaseUrl = intent?.getStringExtra(EXTRA_ADVERTISED_BASE_URL).orEmpty()
                .ifBlank { fallback.advertisedBaseUrl },
            selectedLocalAddress = intent?.getStringExtra(EXTRA_SELECTED_LOCAL_ADDRESS).orEmpty()
                .ifBlank { fallback.selectedLocalAddress },
            debug = intent?.getBooleanExtra(EXTRA_DEBUG, fallback.debug) ?: fallback.debug,
        )
    }

    private fun resolveEndpoint(
        config: ProxyConfig,
        localCandidates: List<HotspotAddressCandidate>,
    ): ResolvedEndpoint {
        val activeUrl = ProxyConfigValidator.resolveEffectiveUrl(config, localCandidates)
        val selectedIp = when {
            config.advertisedBaseUrl.isNotBlank() -> ""
            config.selectedLocalAddress.isNotBlank() -> config.selectedLocalAddress
            else -> localCandidates.firstOrNull()?.address.orEmpty()
        }
        val selectedInterface = localCandidates.firstOrNull { it.address == selectedIp }?.interfaceName.orEmpty()
        val hotspotActive = localCandidates.any { it.kind == "Hotspot" || it.kind == "USB tethering" }
        return ResolvedEndpoint(
            activeUrl = activeUrl,
            selectedInterface = selectedInterface,
            selectedIp = selectedIp,
            hotspotActive = hotspotActive,
        )
    }

    private fun validateConfig(config: ProxyConfig): ProxyErrorInfo? {
        val validation = ProxyConfigValidator.validate(
            portInput = config.port.toString(),
            advertisedBaseUrlInput = config.advertisedBaseUrl,
            selectedLocalAddressInput = config.selectedLocalAddress,
            debug = config.debug,
            localCandidates = HotspotAddressDetector.detectCandidates(),
        )

        return when {
            validation.portError != null -> ProxyErrorInfo(
                category = ProxyErrorCategory.INVALID_CONFIG,
                title = "Invalid configuration",
                detail = validation.portError,
                recommendedAction = "Enter a listen port between 1 and 65535, then try again.",
            )

            validation.baseUrlError != null -> ProxyErrorInfo(
                category = ProxyErrorCategory.INVALID_CONFIG,
                title = "Invalid configuration",
                detail = validation.baseUrlError,
                recommendedAction = "Fix the advertised base URL or clear it to use the selected local IP.",
            )

            validation.localAddressError != null -> ProxyErrorInfo(
                category = ProxyErrorCategory.INVALID_CONFIG,
                title = "Invalid configuration",
                detail = validation.localAddressError,
                recommendedAction = "Pick one of the detected private IPv4 addresses or enter a full advertised URL.",
            )

            else -> null
        }
    }

    private fun buildDiagnosticsSeed(
        endpoint: ResolvedEndpoint,
        localCandidates: List<HotspotAddressCandidate>,
    ): ProxyDiagnostics {
        val selectedInterface = endpoint.selectedInterface.ifBlank {
            endpoint.selectedIp.takeIf { it.isNotBlank() }
                ?.let { ip -> localCandidates.firstOrNull { it.address == ip }?.interfaceName }
                .orEmpty()
        }
        return ProxyDiagnostics(
            selectedInterface = selectedInterface,
            selectedIp = endpoint.selectedIp,
            hotspotActive = endpoint.hotspotActive,
        )
    }

    private fun checkPortBindable(port: Int): PortBindCheck {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
            PortBindCheck(true, "Preflight bind OK on port $port")
        } catch (exception: Exception) {
            PortBindCheck(false, "Port $port is unavailable: ${exception.message ?: "unknown error"}")
        }
    }

    private fun safeProcessPid(process: Process): Long? {
        return try {
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as? Long)
        } catch (_: Exception) {
            null
        }
    }

    private fun classifyProcessFailure(
        detail: String,
        exitCode: Int? = null,
        logTail: String = "",
    ): ProxyErrorInfo = ProxyServiceFailureClassifier.classify(detail, exitCode, logTail)

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

    private data class HealthCheckResult(
        val error: ProxyErrorInfo?,
        val detail: String,
    )

    private fun awaitHealthy(
        startedProcess: Process,
        localProbePort: Int,
        localProbeUrl: String,
        advertisedUrl: String,
    ): HealthCheckResult {
        var lastProbeDetail = "Local HTTP probe pending: $localProbeUrl"
        repeat(8) { attempt ->
            if (stopRequested) {
                return HealthCheckResult(error = null, detail = "Health check cancelled")
            }
            if (process !== startedProcess) {
                return HealthCheckResult(error = null, detail = "Health check superseded")
            }
            if (!startedProcess.isAlive) {
                val exitCode = runCatching { startedProcess.exitValue() }.getOrNull()
                return HealthCheckResult(
                    error = classifyProcessFailure(
                        detail = "Proxy exited before passing the startup health check.",
                        exitCode = exitCode,
                        logTail = ProxyPreferences.readLogTail(this, 4_000),
                    ),
                    detail = lastProbeDetail,
                )
            }
            val probeResult = probeLocalProxy(localProbePort, localProbeUrl)
            lastProbeDetail = probeResult.detail
            if (probeResult.healthy) {
                return HealthCheckResult(error = null, detail = probeResult.detail)
            }
            if (attempt < 7) {
                Thread.sleep(750)
            }
        }

        runCatching {
            startedProcess.destroy()
            if (!startedProcess.waitFor(2, TimeUnit.SECONDS)) {
                startedProcess.destroyForcibly()
            }
        }

        return HealthCheckResult(
            error = ProxyErrorInfo(
                category = ProxyErrorCategory.STARTUP_FAILURE,
                title = getString(R.string.health_check_failed_title),
                detail = "${getString(R.string.health_check_failed_detail)} Local probe: $localProbeUrl. Advertised URL: $advertisedUrl. Last probe result: $lastProbeDetail",
                recommendedAction = getString(R.string.health_check_failed_action),
            ),
            detail = lastProbeDetail,
        )
    }

    private fun probeLocalProxy(port: Int, url: String): ProbeResult {
        val httpResult = probeUrl(url)
        if (httpResult.healthy) {
            return httpResult
        }

        val tcpResult = probeTcpPort(port)
        if (tcpResult.healthy) {
            return ProbeResult(
                healthy = true,
                detail = "Local TCP probe to 127.0.0.1:$port succeeded after HTTP probe failed: ${httpResult.detail}",
            )
        }

        return ProbeResult(
            healthy = false,
            detail = "${httpResult.detail}; ${tcpResult.detail}",
        )
    }

    private fun probeUrl(url: String): ProbeResult {
        if (url.isBlank()) {
            return ProbeResult(healthy = false, detail = "Local HTTP probe skipped: blank URL")
        }

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1_000
                readTimeout = 1_000
                instanceFollowRedirects = false
                setRequestProperty("Connection", "close")
            }
            try {
                val code = connection.responseCode
                ProbeResult(
                    healthy = code in 100..599,
                    detail = "Local HTTP probe to $url returned $code",
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            ProbeResult(
                healthy = false,
                detail = "Local HTTP probe to $url failed: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}",
            )
        }
    }

    private fun probeTcpPort(port: Int): ProbeResult {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            }
            ProbeResult(
                healthy = true,
                detail = "Local TCP probe to 127.0.0.1:$port succeeded",
            )
        }.getOrElse { error ->
            ProbeResult(
                healthy = false,
                detail = "Local TCP probe to 127.0.0.1:$port failed: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}",
            )
        }
    }

    private fun copyActiveUrlToClipboard() {
        val status = ProxyPreferences.readStatus(this)
        val activeUrl = status.activeUrl.ifBlank { return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy control URL", activeUrl))
        ProxyPreferences.appendLog(this, "Copied active URL from notification")
        refreshNotification(status.message)
    }

    private fun clearErrorState() {
        val status = ProxyPreferences.readStatus(this)
        val nextStatus = status.copy(
            desiredRunning = if (status.state == ProxyRuntimeState.Failed) false else status.desiredRunning,
            state = if (status.state == ProxyRuntimeState.Failed) ProxyRuntimeState.Idle else status.state,
            message = if (status.state == ProxyRuntimeState.Failed) "Proxy idle" else status.message,
            lastFailureReason = "",
            error = null,
        )
        ProxyPreferences.setStatus(this, nextStatus)
        sendStatusBroadcast()
        refreshNotification(nextStatus.message)
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
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val currentStatus = ProxyPreferences.readStatus(this)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(launchPendingIntent)
            .setOngoing(process != null)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                servicePendingIntent(ACTION_STOP, 1),
            )
            .apply {
                if (currentStatus.activeUrl.isNotBlank()) {
                    addAction(
                        android.R.drawable.ic_menu_share,
                        getString(R.string.notification_action_copy_url),
                        servicePendingIntent(ACTION_COPY_URL, 2),
                    )
                }
                if (currentStatus.error != null || currentStatus.lastFailureReason.isNotBlank()) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notification_action_clear_error),
                        servicePendingIntent(ACTION_CLEAR_ERROR, 3),
                    )
                }
            }
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ProxyService::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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
        const val ACTION_COPY_URL = "io.proxyt.hotspot.action.COPY_URL"
        const val ACTION_CLEAR_ERROR = "io.proxyt.hotspot.action.CLEAR_ERROR"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_ADVERTISED_BASE_URL = "extra_advertised_base_url"
        const val EXTRA_SELECTED_LOCAL_ADDRESS = "extra_selected_local_address"
        const val EXTRA_DEBUG = "extra_debug"
        const val DEFAULT_HOTSPOT_IP = "192.168.43.1"

        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 4001
        private const val NATIVE_BINARY = "libproxyt.so"
    }
}

data class PortBindCheck(
    val canBind: Boolean,
    val message: String,
)
