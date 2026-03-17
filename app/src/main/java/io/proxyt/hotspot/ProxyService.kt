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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ResolvedEndpoint(
    val activeUrl: String,
    val selectedInterface: String = "",
    val selectedInterfaceKind: String = "",
    val selectedIp: String = "",
    val hotspotActive: Boolean? = null,
)

class ProxyService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val runtimeDependencies by lazy { applicationContext.hotspotRuntimeDependencies() }
    private val statusStore by lazy { runtimeDependencies.statusStore }
    private val localAddressProvider by lazy { runtimeDependencies.localAddressProvider }
    private val clock by lazy { runtimeDependencies.clock }
    private val processLauncher by lazy { runtimeDependencies.processLauncher }
    private val healthChecker by lazy { runtimeDependencies.healthChecker }

    @Volatile
    private var process: RunningProxyProcess? = null

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        statusStore.reconcileStatus()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        statusStore.reconcileStatus()
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
            val currentStatus = statusStore.readStatus()
            statusStore.setStatus(
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

        val previousStatus = statusStore.readStatus()
        val config = parseConfig(intent)
        val localCandidates = localAddressProvider.detectCandidates()
        val resolvedEndpoint = runCatching { resolveEndpoint(config, localCandidates) }
            .getOrDefault(ResolvedEndpoint(activeUrl = ""))
        val diagnosticsSeed = buildDiagnosticsSeed(resolvedEndpoint, localCandidates)

        if (!hasNotificationPermission()) {
            val error = notificationPermissionError()
            statusStore.setStatus(
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
            statusStore.appendLog("Start blocked: ${error.detail}")
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val configError = validateConfig(config)
        if (configError != null) {
            statusStore.setStatus(
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
            statusStore.appendLog(configError.detail)
            refreshNotification(configError.title)
            sendStatusBroadcast()
            stopSelf()
            return
        }

        val bindCheck = ProxyPortBinder.check(config.port)
        if (!bindCheck.canBind) {
            val error = ProxyErrorInfo(
                category = ProxyErrorCategory.PORT_IN_USE,
                title = "Port is already in use",
                detail = bindCheck.message,
                recommendedAction = "Choose a different listen port or stop the app that is already using this port.",
            )
            statusStore.setStatus(
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
            statusStore.appendLog("Preflight bind failed: ${bindCheck.message}")
            refreshNotification(error.title)
            sendStatusBroadcast()
            stopSelf()
            return
        }

        ProxyPreferences.startNewLogSession(this)
        val logFile = statusStore.logFile()
        val binary = File(applicationInfo.nativeLibraryDir, NATIVE_BINARY)
        binary.setExecutable(true, true)
        if (!binary.exists()) {
            val error = ProxyErrorInfo(
                category = ProxyErrorCategory.MISSING_BINARY,
                title = "Bundled binary missing",
                detail = "The app could not find ${binary.absolutePath}.",
                recommendedAction = "Reinstall the app or rebuild it with the bundled native library included.",
            )
            statusStore.setStatus(
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
            statusStore.appendLog(error.detail)
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

        val startTimestamp = clock.nowMs()
        val localProbeUrl = healthChecker.localProbeUrl(config.port)
        stopRequested = false
        statusStore.appendLog("Preflight bind OK: ${bindCheck.message}")
        statusStore.appendLog("Starting proxy for ${resolvedEndpoint.activeUrl}")
        statusStore.appendLog("Using local startup probe $localProbeUrl")
        statusStore.setStatus(
            ProxyStatus(
                desiredRunning = true,
                state = ProxyRuntimeState.Starting,
                activeUrl = resolvedEndpoint.activeUrl,
                lastExitCode = null,
                message = "Starting proxy",
                startTimestampMs = startTimestamp,
                lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                error = null,
                diagnostics = diagnosticsSeed.copy(
                    portBindResult = bindCheck.message,
                    lastProbeStatus = "Pending",
                    lastProbeTarget = localProbeUrl,
                    lastProbeDetail = "Waiting for the local HTTP health check to succeed.",
                ),
            ),
        )
        sendStatusBroadcast()
        startForeground(NOTIFICATION_ID, notification("Starting ${resolvedEndpoint.activeUrl}"))

        try {
            val startedProcess = processLauncher.launch(
                ProxyLaunchRequest(
                    command = command,
                    outputFile = logFile,
                ),
            )
            val processPid = startedProcess.pid()

            process = startedProcess
            statusStore.saveConfig(config)

            executor.execute {
                val probeResult = healthChecker.awaitHealthy(
                    startedProcess = startedProcess,
                    localProbePort = config.port,
                    localProbeUrl = localProbeUrl,
                    advertisedUrl = resolvedEndpoint.activeUrl,
                    stopRequested = { stopRequested },
                    currentProcessMatches = { process === startedProcess },
                    logTailProvider = { statusStore.readLogTail(4_000) },
                )
                val healthError = probeResult.error
                if (stopRequested) {
                    val exitCode = startedProcess.waitFor()
                    val currentStatus = statusStore.readStatus()
                    statusStore.setStatus(
                        ProxyStatus(
                            desiredRunning = false,
                            state = ProxyRuntimeState.Idle,
                            activeUrl = currentStatus.activeUrl.ifBlank { resolvedEndpoint.activeUrl },
                            lastExitCode = exitCode,
                            message = "Proxy stopped",
                            startTimestampMs = currentStatus.startTimestampMs,
                            lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                            error = null,
                            diagnostics = currentStatus.diagnostics.copy(currentPid = null),
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
                    statusStore.setStatus(
                        ProxyStatus(
                            desiredRunning = true,
                            state = ProxyRuntimeState.Failed,
                            activeUrl = resolvedEndpoint.activeUrl,
                            lastExitCode = runCatching { startedProcess.exitValue() }.getOrNull(),
                            message = healthError.title,
                            lastFailureReason = healthError.detail,
                            startTimestampMs = startTimestamp,
                            lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
                            error = healthError,
                            diagnostics = diagnosticsSeed.copy(
                                currentPid = processPid,
                                portBindResult = "Process started but health check did not complete",
                                lastProbeStatus = probeResult.status,
                                lastProbeTarget = probeResult.target,
                                lastProbeDetail = probeResult.detail,
                            ),
                        ),
                    )
                    statusStore.appendLog(healthError.detail)
                    refreshNotification(healthError.title)
                    sendStatusBroadcast()
                    stopSelf()
                    return@execute
                }

                statusStore.setStatus(
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
                            portBindResult = "Bind confirmed on port ${config.port}",
                            lastProbeStatus = probeResult.status,
                            lastProbeTarget = probeResult.target,
                            lastProbeDetail = probeResult.detail,
                        ),
                    ),
                )
                statusStore.appendLog(
                    "Serving on ${resolvedEndpoint.activeUrl}${processPid?.let { " (pid $it)" }.orEmpty()}",
                )
                refreshNotification("Serving ${resolvedEndpoint.activeUrl}")
                sendStatusBroadcast()

                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                    process = null
                }

                val currentStatus = statusStore.readStatus()
                val nextStatus = if (stopRequested) {
                    ProxyStatus(
                        desiredRunning = false,
                        state = ProxyRuntimeState.Idle,
                        activeUrl = currentStatus.activeUrl.ifBlank { resolvedEndpoint.activeUrl },
                        lastExitCode = exitCode,
                        message = "Proxy stopped",
                        startTimestampMs = currentStatus.startTimestampMs,
                        lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
                        error = null,
                        diagnostics = currentStatus.diagnostics.copy(currentPid = null),
                    )
                } else {
                    val error = ProxyProcessFailureClassifier.classify(
                        detail = "Proxy exited with code $exitCode.",
                        exitCode = exitCode,
                        logTail = statusStore.readLogTail(4_000),
                    )
                    ProxyStatus(
                        desiredRunning = true,
                        state = ProxyRuntimeState.Failed,
                        activeUrl = currentStatus.activeUrl.ifBlank { resolvedEndpoint.activeUrl },
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
                statusStore.setStatus(nextStatus)
                statusStore.appendLog(nextStatus.lastFailureReason.ifBlank { nextStatus.message })
                refreshNotification(nextStatus.message)
                sendStatusBroadcast()
                stopSelf()
            }
        } catch (exception: Exception) {
            process = null
            val error = ProxyProcessFailureClassifier.classify(
                detail = exception.message ?: "Unknown startup error.",
                logTail = statusStore.readLogTail(4_000),
            )
            statusStore.setStatus(
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
            statusStore.appendLog("Failed to start proxy: ${error.detail}")
            refreshNotification("Failed to start proxy")
            sendStatusBroadcast()
            stopSelf()
        }
    }

    private fun stopProxy() {
        val currentStatus = statusStore.readStatus()
        val runningProcess = process ?: run {
            statusStore.setStatus(
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
        statusStore.setStatus(
            currentStatus.copy(
                desiredRunning = false,
                state = ProxyRuntimeState.Stopping,
                message = "Stopping proxy",
                error = null,
                diagnostics = currentStatus.diagnostics.copy(
                    currentPid = runningProcess.pid(),
                    lastProbeDetail = currentStatus.diagnostics.lastProbeDetail.ifBlank { "No probe recorded" },
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
        val fallback = statusStore.loadConfig()
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
        val selectedIp = ProxyConfigValidator.resolveSelectedLocalAddress(config, localCandidates)
        val selectedInterface = localCandidates.firstOrNull { it.address == selectedIp }?.interfaceName.orEmpty()
        val selectedInterfaceKind = localCandidates.firstOrNull { it.address == selectedIp }?.kind.orEmpty()
        val hotspotActive = localCandidates.any { it.kind == "Hotspot" || it.kind == "USB tethering" }
        return ResolvedEndpoint(
            activeUrl = activeUrl,
            selectedInterface = selectedInterface,
            selectedInterfaceKind = selectedInterfaceKind,
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
            localCandidates = localAddressProvider.detectCandidates(),
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
            selectedInterfaceKind = endpoint.selectedInterfaceKind,
            selectedIp = endpoint.selectedIp,
            hotspotActive = endpoint.hotspotActive,
        )
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

    private fun copyActiveUrlToClipboard() {
        val status = statusStore.readStatus()
        val activeUrl = status.activeUrl.ifBlank { return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy control URL", activeUrl))
        statusStore.appendLog("Copied active URL from notification")
        refreshNotification(status.message)
    }

    private fun clearErrorState() {
        val status = statusStore.readStatus()
        val nextStatus = status.copy(
            desiredRunning = if (status.state == ProxyRuntimeState.Failed) false else status.desiredRunning,
            state = if (status.state == ProxyRuntimeState.Failed) ProxyRuntimeState.Idle else status.state,
            message = if (status.state == ProxyRuntimeState.Failed) "Proxy idle" else status.message,
            lastFailureReason = "",
            error = null,
        )
        statusStore.setStatus(nextStatus)
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
        val currentStatus = statusStore.readStatus()

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
