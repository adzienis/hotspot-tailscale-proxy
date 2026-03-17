package io.proxyt.hotspot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FakeRunningProxyProcess : RunningProxyProcess {
    @Volatile
    override var isAlive: Boolean = true
    private val waitCalls = AtomicInteger(0)
    private val exitCodeRef = AtomicReference(0)
    private val finished = CountDownLatch(1)

    fun finish(exitCode: Int = 0) {
        exitCodeRef.set(exitCode)
        isAlive = false
        finished.countDown()
    }

    override fun destroy() {
        finish(0)
    }

    override fun destroyForcibly() {
        finish(0)
    }

    override fun waitFor(): Int {
        waitCalls.incrementAndGet()
        finished.await(5, TimeUnit.SECONDS)
        return exitCodeRef.get()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitCalls.incrementAndGet()
        return finished.await(timeout, unit)
    }

    override fun exitValue(): Int = exitCodeRef.get()

    override fun pid(): Long? = 4242L
}

class FakeProcessLauncher(
    private val process: FakeRunningProxyProcess = FakeRunningProxyProcess(),
) : ProxyProcessLauncher {
    val launchedRequests = mutableListOf<ProxyLaunchRequest>()

    override fun launch(request: ProxyLaunchRequest): RunningProxyProcess {
        launchedRequests += request
        return process
    }
}

class FakeHealthChecker(
    private val result: ProxyHealthResult = ProxyHealthResult(
        error = null,
        status = "Healthy",
        target = "http://127.0.0.1:8080/health",
        detail = "Local HTTP probe to http://127.0.0.1:8080/health returned 200",
    ),
) : ProxyHealthChecker {
    override fun localProbeUrl(port: Int): String = "http://127.0.0.1:$port/health"

    override fun awaitHealthy(
        startedProcess: RunningProxyProcess,
        localProbePort: Int,
        localProbeUrl: String,
        advertisedUrl: String,
        stopRequested: () -> Boolean,
        currentProcessMatches: () -> Boolean,
        logTailProvider: () -> String,
    ): ProxyHealthResult = result
}

class FakeLocalAddressProvider(
    private val candidates: List<HotspotAddressCandidate> = listOf(
        HotspotAddressCandidate("wlan0", "192.168.43.1", 0, "Hotspot"),
    ),
) : LocalAddressProvider {
    override fun detectCandidates(): List<HotspotAddressCandidate> = candidates
}

class FakeClock(private var now: Long = 1_710_000_000_000L) : ProxyClock {
    override fun nowMs(): Long = now++
}

class InMemoryProxyStatusStore(private val rootDir: File) : ProxyStatusStore {
    private val statusRef = AtomicReference(ProxyStatus())
    private val configRef = AtomicReference(ProxyConfig())
    private val logFileRef = AtomicReference(File(rootDir, "proxyt-test.log"))
    private val waiters = mutableListOf<Pair<(ProxyStatus) -> Boolean, CountDownLatch>>()

    override fun loadConfig(): ProxyConfig = configRef.get()

    override fun saveConfig(config: ProxyConfig) {
        configRef.set(config)
    }

    override fun readStatus(): ProxyStatus = statusRef.get()

    override fun setStatus(status: ProxyStatus) {
        statusRef.set(status)
        synchronized(waiters) {
            waiters.forEach { (predicate, latch) ->
                if (predicate(status)) {
                    latch.countDown()
                }
            }
        }
    }

    override fun reconcileStatus(): ProxyStatus = statusRef.get()

    override fun logFile(): File = logFileRef.get().apply { parentFile?.mkdirs() }

    override fun readLogTail(maxChars: Int): String {
        val file = logFile()
        if (!file.exists()) {
            return "No logs yet."
        }
        val contents = file.readText()
        return contents.takeLast(maxChars)
    }

    override fun clearLogs() {
        logFile().writeText("")
    }

    override fun readStartupEventSummary(maxEvents: Int): String {
        val lines = readLogTail(24_000).lines().filter { it.isNotBlank() }
        return if (lines.isEmpty()) {
            "No recent startup events."
        } else {
            lines.takeLast(maxEvents).joinToString("\n")
        }
    }

    override fun appendLog(message: String) {
        logFile().appendText("$message\n")
    }

    fun awaitStatus(
        timeoutMs: Long = 5_000,
        predicate: (ProxyStatus) -> Boolean,
    ): ProxyStatus {
        val current = readStatus()
        if (predicate(current)) {
            return current
        }
        val latch = CountDownLatch(1)
        synchronized(waiters) {
            waiters += predicate to latch
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for proxy status. Current status=${readStatus()}"
        }
        return readStatus()
    }
}

data class FakeRuntimeBundle(
    val process: FakeRunningProxyProcess,
    val launcher: FakeProcessLauncher,
    val healthChecker: FakeHealthChecker,
    val statusStore: InMemoryProxyStatusStore,
)

fun installFakeRuntimeDependencies(
    context: Context = ApplicationProvider.getApplicationContext(),
    healthResult: ProxyHealthResult = ProxyHealthResult(
        error = null,
        status = "Healthy",
        target = "http://127.0.0.1:8080/health",
        detail = "Local HTTP probe to http://127.0.0.1:8080/health returned 200",
    ),
): FakeRuntimeBundle {
    val app = context.applicationContext as HotspotApplication
    val process = FakeRunningProxyProcess()
    val launcher = FakeProcessLauncher(process)
    val healthChecker = FakeHealthChecker(healthResult)
    val statusStore = InMemoryProxyStatusStore(File(context.cacheDir, "androidTestRuntime"))
    app.hotspotRuntimeDependencies = HotspotRuntimeDependencies(
        processLauncher = launcher,
        healthChecker = healthChecker,
        clock = FakeClock(),
        localAddressProvider = FakeLocalAddressProvider(),
        statusStore = statusStore,
    )
    return FakeRuntimeBundle(
        process = process,
        launcher = launcher,
        healthChecker = healthChecker,
        statusStore = statusStore,
    )
}
