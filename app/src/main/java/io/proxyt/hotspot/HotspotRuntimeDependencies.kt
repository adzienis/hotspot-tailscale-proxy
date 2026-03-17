package io.proxyt.hotspot

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

interface ProxyClock {
    fun nowMs(): Long
}

interface RunningProxyProcess {
    val isAlive: Boolean
    fun destroy()
    fun destroyForcibly()
    fun waitFor(): Int
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean
    fun exitValue(): Int
    fun pid(): Long?
}

data class ProxyLaunchRequest(
    val command: List<String>,
    val outputFile: File,
)

interface ProxyProcessLauncher {
    fun launch(request: ProxyLaunchRequest): RunningProxyProcess
}

data class ProxyHealthResult(
    val error: ProxyErrorInfo?,
    val status: String,
    val target: String,
    val detail: String,
)

interface ProxyHealthChecker {
    fun localProbeUrl(port: Int): String

    fun awaitHealthy(
        startedProcess: RunningProxyProcess,
        localProbePort: Int,
        localProbeUrl: String,
        advertisedUrl: String,
        stopRequested: () -> Boolean,
        currentProcessMatches: () -> Boolean,
        logTailProvider: () -> String,
    ): ProxyHealthResult
}

interface LocalAddressProvider {
    fun detectCandidates(): List<HotspotAddressCandidate>
}

interface ProxyStatusStore {
    fun loadConfig(): ProxyConfig
    fun saveConfig(config: ProxyConfig)
    fun readStatus(): ProxyStatus
    fun setStatus(status: ProxyStatus)
    fun reconcileStatus(): ProxyStatus
    fun logFile(): File
    fun readLogTail(maxChars: Int = 12_000): String
    fun clearLogs()
    fun readStartupEventSummary(maxEvents: Int = 5): String
    fun appendLog(message: String)
}

data class HotspotRuntimeDependencies(
    val processLauncher: ProxyProcessLauncher,
    val healthChecker: ProxyHealthChecker,
    val clock: ProxyClock,
    val localAddressProvider: LocalAddressProvider,
    val statusStore: ProxyStatusStore,
) {
    companion object {
        fun create(context: Context): HotspotRuntimeDependencies =
            HotspotRuntimeDependencies(
                processLauncher = DefaultProxyProcessLauncher(),
                healthChecker = DefaultProxyHealthChecker(context),
                clock = object : ProxyClock {
                    override fun nowMs(): Long = System.currentTimeMillis()
                },
                localAddressProvider = object : LocalAddressProvider {
                    override fun detectCandidates(): List<HotspotAddressCandidate> = HotspotAddressDetector.detectCandidates(context)
                },
                statusStore = SharedPreferencesProxyStatusStore(context),
            )
    }
}

class SharedPreferencesProxyStatusStore(private val context: Context) : ProxyStatusStore {
    override fun loadConfig(): ProxyConfig = ProxyPreferences.loadConfig(context)

    override fun saveConfig(config: ProxyConfig) = ProxyPreferences.saveConfig(context, config)

    override fun readStatus(): ProxyStatus = ProxyPreferences.readStatus(context)

    override fun setStatus(status: ProxyStatus) = ProxyPreferences.setStatus(context, status)

    override fun reconcileStatus(): ProxyStatus = ProxyPreferences.reconcileStatus(context)

    override fun logFile(): File = ProxyPreferences.logFile(context)

    override fun readLogTail(maxChars: Int): String = ProxyPreferences.readLogTail(context, maxChars)

    override fun clearLogs() = ProxyPreferences.clearLogs(context)

    override fun readStartupEventSummary(maxEvents: Int): String = ProxyPreferences.readStartupEventSummary(context, maxEvents)

    override fun appendLog(message: String) = ProxyPreferences.appendLog(context, message)
}

class DefaultProxyProcessLauncher : ProxyProcessLauncher {
    override fun launch(request: ProxyLaunchRequest): RunningProxyProcess {
        val process = ProcessBuilder(request.command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(request.outputFile))
            .start()
        return RealRunningProxyProcess(process)
    }
}

class RealRunningProxyProcess(private val delegate: Process) : RunningProxyProcess {
    override val isAlive: Boolean
        get() = delegate.isAlive

    override fun destroy() = delegate.destroy()

    override fun destroyForcibly() {
        delegate.destroyForcibly()
    }

    override fun waitFor(): Int = delegate.waitFor()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)

    override fun exitValue(): Int = delegate.exitValue()

    override fun pid(): Long? =
        try {
            val pidMethod = delegate.javaClass.getMethod("pid")
            (pidMethod.invoke(delegate) as? Long)
        } catch (_: Exception) {
            null
        }
}

object ProxyProcessFailureClassifier {
    fun classify(detail: String, exitCode: Int? = null, logTail: String = ""): ProxyErrorInfo {
        val combined = buildString {
            append(detail)
            if (logTail.isNotBlank()) {
                append('\n')
                append(logTail)
            }
        }.lowercase()

        return when {
            "address already in use" in combined || "bind" in combined || "port already in use" in combined ->
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PORT_IN_USE,
                    title = "Port is already in use",
                    detail = "Another process is already bound to the selected port.",
                    recommendedAction = "Choose a different listen port or stop the app that is already using this port.",
                )

            "permission denied" in combined || "operation not permitted" in combined || "not allowed" in combined ->
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PERMISSION_REQUIRED,
                    title = "Permission issue",
                    detail = "Android or the proxy process denied access needed to start cleanly.",
                    recommendedAction = "Grant the required permission or choose a different port, then try starting the proxy again.",
                )

            exitCode != null ->
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PROXY_EXIT,
                    title = "Proxy process exited",
                    detail = "The bundled proxy exited with code $exitCode.",
                    recommendedAction = "Retry once. If it exits again, copy the last error and inspect the logs for more detail.",
                )

            else ->
                ProxyErrorInfo(
                    category = ProxyErrorCategory.STARTUP_FAILURE,
                    title = "Proxy failed to start",
                    detail = detail,
                    recommendedAction = "Try again. If the problem repeats, copy the last error and inspect the logs.",
                )
        }
    }
}

data class ProbeResult(
    val healthy: Boolean,
    val detail: String,
)

object ProxyHealthCheck {
    fun localProbeUrl(port: Int): String = "http://127.0.0.1:$port/health"
}

object ProxyPortBinder {
    fun check(port: Int): PortBindCheck {
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
}

class DefaultProxyHealthChecker(private val context: Context) : ProxyHealthChecker {
    override fun localProbeUrl(port: Int): String = ProxyHealthCheck.localProbeUrl(port)

    override fun awaitHealthy(
        startedProcess: RunningProxyProcess,
        localProbePort: Int,
        localProbeUrl: String,
        advertisedUrl: String,
        stopRequested: () -> Boolean,
        currentProcessMatches: () -> Boolean,
        logTailProvider: () -> String,
    ): ProxyHealthResult {
        var lastProbeDetail = "Local HTTP probe pending: $localProbeUrl"
        var lastProbeStatus = "Pending"
        var lastProbeTarget = localProbeUrl
        repeat(8) { attempt ->
            if (stopRequested()) {
                return ProxyHealthResult(error = null, status = "Cancelled", target = lastProbeTarget, detail = "Health check cancelled")
            }
            if (!currentProcessMatches()) {
                return ProxyHealthResult(error = null, status = "Superseded", target = lastProbeTarget, detail = "Health check superseded")
            }
            if (!startedProcess.isAlive) {
                val exitCode = runCatching { startedProcess.exitValue() }.getOrNull()
                return ProxyHealthResult(
                    error = ProxyProcessFailureClassifier.classify(
                        detail = "Proxy exited before passing the startup health check.",
                        exitCode = exitCode,
                        logTail = logTailProvider(),
                    ),
                    status = "Failed",
                    target = lastProbeTarget,
                    detail = lastProbeDetail,
                )
            }
            val probeResult = probeLocalProxy(localProbePort, localProbeUrl)
            lastProbeStatus = if (probeResult.healthy) "Healthy" else "Waiting"
            lastProbeTarget = localProbeUrl
            lastProbeDetail = probeResult.detail
            if (probeResult.healthy) {
                return ProxyHealthResult(error = null, status = lastProbeStatus, target = lastProbeTarget, detail = probeResult.detail)
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

        return ProxyHealthResult(
            error = ProxyErrorInfo(
                category = ProxyErrorCategory.STARTUP_FAILURE,
                title = context.getString(R.string.health_check_failed_title),
                detail = "${context.getString(R.string.health_check_failed_detail)} Local probe: $localProbeUrl. Advertised URL: $advertisedUrl. Last probe result: $lastProbeDetail",
                recommendedAction = context.getString(R.string.health_check_failed_action),
            ),
            status = "Failed",
            target = lastProbeTarget,
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
}

interface HotspotDependencyProvider {
    var hotspotRuntimeDependencies: HotspotRuntimeDependencies
}
