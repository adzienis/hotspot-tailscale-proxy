package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyServiceTestingTest {
    @Test
    fun `classifies bind failures as port in use`() {
        val error = ProxyServiceFailureClassifier.classify(
            detail = "listen tcp 0.0.0.0:8080: bind: address already in use",
        )

        assertEquals(ProxyErrorCategory.PORT_IN_USE, error.category)
        assertEquals("Port is already in use", error.title)
    }

    @Test
    fun `classifies permission failures from logs`() {
        val error = ProxyServiceFailureClassifier.classify(
            detail = "startup failed",
            logTail = "operation not permitted while opening socket",
        )

        assertEquals(ProxyErrorCategory.PERMISSION_REQUIRED, error.category)
        assertEquals("Permission issue", error.title)
    }

    @Test
    fun `builds starting status with pending probe detail`() {
        val previousStatus = ProxyStatus(lastSuccessfulStartTimestampMs = 1234L)
        val diagnostics = ProxyDiagnostics(portBindResult = "Preflight bind OK on port 8080")

        val status = ProxyServiceStatusFactory.starting(
            previousStatus = previousStatus,
            activeUrl = "http://192.168.43.1:8080",
            diagnostics = diagnostics,
            startTimestampMs = 5678L,
            probeDetail = "Local HTTP probe pending: http://127.0.0.1:8080/health",
        )

        assertEquals(ProxyRuntimeState.Starting, status.state)
        assertEquals("http://192.168.43.1:8080", status.activeUrl)
        assertEquals(1234L, status.lastSuccessfulStartTimestampMs)
        assertEquals("Local HTTP probe pending: http://127.0.0.1:8080/health", status.diagnostics.lastProbeResult)
    }

    @Test
    fun `builds running status after healthy probe`() {
        val diagnostics = ProxyDiagnostics(
            selectedInterface = "ap0",
            selectedIp = "192.168.43.1",
            hotspotActive = true,
        )

        val status = ProxyServiceStatusFactory.running(
            activeUrl = "http://192.168.43.1:8080",
            startTimestampMs = 999L,
            processPid = 42L,
            diagnostics = diagnostics,
            probeDetail = "Local HTTP probe to http://127.0.0.1:8080/health returned 200",
            port = 8080,
        )

        assertEquals(ProxyRuntimeState.Running, status.state)
        assertEquals(42L, status.diagnostics.currentPid)
        assertEquals("Bind confirmed on port 8080", status.diagnostics.portBindResult)
        assertEquals("Local HTTP probe to http://127.0.0.1:8080/health returned 200", status.diagnostics.lastProbeResult)
        assertEquals(999L, status.lastSuccessfulStartTimestampMs)
    }

    @Test
    fun `builds health check failure status without advancing last successful start`() {
        val previousStatus = ProxyStatus(lastSuccessfulStartTimestampMs = 222L)
        val diagnostics = ProxyDiagnostics(selectedIp = "192.168.43.1")
        val error = ProxyErrorInfo(
            category = ProxyErrorCategory.STARTUP_FAILURE,
            title = "Proxy failed health check",
            detail = "The control URL did not answer after the proxy process started.",
            recommendedAction = "Retry",
        )

        val status = ProxyServiceStatusFactory.healthCheckFailed(
            previousStatus = previousStatus,
            activeUrl = "http://192.168.43.1:8080",
            processPid = 84L,
            diagnostics = diagnostics,
            error = error,
            startTimestampMs = 333L,
            lastExitCode = null,
            probeDetail = "Local HTTP probe to http://127.0.0.1:8080/health failed: ConnectException",
        )

        assertEquals(ProxyRuntimeState.Failed, status.state)
        assertEquals(222L, status.lastSuccessfulStartTimestampMs)
        assertEquals(84L, status.diagnostics.currentPid)
        assertEquals("Process started but health check did not complete", status.diagnostics.portBindResult)
    }

    @Test
    fun `builds idle status after requested stop`() {
        val currentStatus = ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Stopping,
            activeUrl = "http://192.168.43.1:8080",
            startTimestampMs = 555L,
            lastSuccessfulStartTimestampMs = 444L,
            diagnostics = ProxyDiagnostics(currentPid = 99L, lastProbeResult = "ok"),
        )

        val status = ProxyServiceStatusFactory.idleAfterStop(
            currentStatus = currentStatus,
            activeUrl = "http://192.168.43.1:8080",
            exitCode = 0,
        )

        assertEquals(ProxyRuntimeState.Idle, status.state)
        assertEquals(0, status.lastExitCode)
        assertNull(status.diagnostics.currentPid)
        assertEquals(444L, status.lastSuccessfulStartTimestampMs)
    }
}
