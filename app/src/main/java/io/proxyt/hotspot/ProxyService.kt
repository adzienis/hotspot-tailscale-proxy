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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ResolvedEndpoint(
    val activeUrl: String,
    val selectedInterface: String = "",
    val selectedIp: String = "",
    val hotspotActive: Boolean? = null,
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
        stopRequested = false
        ProxyPreferences.appendLog(this, "Preflight bind OK: ${bindCheck.message}")
        ProxyPreferences.appendLog(this, "Starting proxy for ${resolvedEndpoint.activeUrl}")
        ProxyPreferences.setStatus(
            this,
            ProxyStatus(
                desiredRunning = true,
                state = ProxyRuntimeState.Starting,
                activeUrl = resolvedEndpoint.activeUrl,
                lastExitCode = null,
                message = "Starting proxy",
                startTimestampMs = startTimestamp,
                lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                error = null,
                diagnostics = diagnosticsSeed.copy(portBindResult = bindCheck.message),
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
            ProxyPreferences.setStatus(
                this,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Running,
                    activeUrl = resolvedEndpoint.activeUrl,
                    lastExitCode = null,
                    message = "Serving on ${resolvedEndpoint.activeUrl}",
                    startTimestampMs = startTimestamp,
                    lastSuccessfulStartTimestampMs = startTimestamp,
                    error = null,
                    diagnostics = diagnosticsSeed.copy(
                        currentPid = processPid,
                        portBindResult = "Proxy process started for port ${config.port}",
                        lastProbeResult = "Probe pending",
                    ),
                ),
            )
            ProxyPreferences.appendLog(
                this,
                "Serving on ${resolvedEndpoint.activeUrl}${processPid?.let { " (pid $it)" }.orEmpty()}",
            )
            refreshNotification("Serving ${resolvedEndpoint.activeUrl}")
            sendStatusBroadcast()
            updateProbeResultAsync(startedProcess, config, resolvedEndpoint)

            executor.execute {
                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                    process = null
                }

                val currentStatus = ProxyPreferences.readStatus(this)
                val nextStatus = if (stopRequested) {
                    ProxyStatus(
                        desiredRunning = false,
                        state = ProxyRuntimeState.Idle,
                        activeUrl = currentStatus.activeUrl,
                        lastExitCode = exitCode,
                        message = "Proxy stopped",
                        startTimestampMs = currentStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                        error = null,
                        diagnostics = currentStatus.diagnostics.copy(currentPid = null),
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
                        activeUrl = currentStatus.activeUrl,
                        lastExitCode = exitCode,
                        message = error.title,
                        lastFailureReason = error.detail,
                        startTimestampMs = currentStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                        error = error,
                        diagnostics = currentStatus.diagnostics.copy(
                            currentPid = null,
                            portBindResult = "Proxy process exited before shutdown",
                        ),
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

    private fun resolveAdvertisedBaseUrl(config: ProxyConfig): String =
        ProxyConfigValidator.resolveEffectiveUrl(config, HotspotAddressDetector.detectCandidates())

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

    private fun updateProbeResultAsync(
        startedProcess: Process,
        config: ProxyConfig,
        endpoint: ResolvedEndpoint,
    ) {
        Thread {
            val probeResult = runLocalProbe(config.port)
            val currentStatus = ProxyPreferences.readStatus(this)
            if (process !== startedProcess || currentStatus.state != ProxyRuntimeState.Running) {
                return@Thread
            }
            ProxyPreferences.setStatus(
                this,
                currentStatus.copy(
                    diagnostics = currentStatus.diagnostics.copy(
                        currentPid = safeProcessPid(startedProcess),
                        selectedInterface = endpoint.selectedInterface,
                        selectedIp = endpoint.selectedIp,
                        portBindResult = if (probeResult.succeeded) {
                            "Bind confirmed on port ${config.port}"
                        } else {
                            currentStatus.diagnostics.portBindResult.ifBlank { "Proxy started for port ${config.port}" }
                        },
                        lastProbeResult = probeResult.message,
                        hotspotActive = endpoint.hotspotActive,
                    ),
                ),
            )
            ProxyPreferences.appendLog(this, probeResult.message)
            sendStatusBroadcast()
        }.start()
    }

    private fun safeProcessPid(process: Process): Long? {
        return try {
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as? Long)
        } catch (_: Exception) {
            null
        }
    }

    private fun runLocalProbe(port: Int): ProbeResult {
        repeat(5) { attempt ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                }
                return ProbeResult(true, "TCP probe to 127.0.0.1:$port succeeded")
            } catch (_: Exception) {
                if (attempt < 4) {
                    Thread.sleep(300)
                }
            }
        }
        return ProbeResult(false, "TCP probe to 127.0.0.1:$port did not connect")
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

data class ProbeResult(
    val succeeded: Boolean,
    val message: String,
)
