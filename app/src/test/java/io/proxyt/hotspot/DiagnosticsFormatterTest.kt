package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFormatterTest {
    @Test
    fun `builds clipboard payload from error status`() {
        val status = ProxyStatus(
            state = ProxyRuntimeState.Failed,
            message = "Proxy failed to start",
            lastExitCode = 17,
            error = ProxyErrorInfo(
                category = ProxyErrorCategory.PROXY_EXIT,
                title = "Proxy process exited",
                detail = "The bundled proxy exited with code 17.",
                recommendedAction = "Retry once.",
            ),
        )

        assertEquals(
            """
            Proxy process exited
            The bundled proxy exited with code 17.
            Exit code: 17
            Recommended action: Retry once.
            """.trimIndent(),
            DiagnosticsFormatter.buildClipboardError(status),
        )
    }

    @Test
    fun `returns null clipboard payload when no failure details exist`() {
        val status = ProxyStatus(
            state = ProxyRuntimeState.Running,
            message = "Serving on http://192.168.43.1:8080",
        )

        assertEquals(null, DiagnosticsFormatter.buildClipboardError(status))
    }

    @Test
    fun `builds share diagnostics payload with logs and startup events`() {
        val payload = DiagnosticsFormatter.buildShareDiagnostics(
            status = ProxyStatus(
                state = ProxyRuntimeState.Running,
                message = "Serving on http://192.168.43.1:8080",
                activeUrl = "http://192.168.43.1:8080",
            ),
            startupEvents = "Starting proxy\nServing on http://192.168.43.1:8080",
            logTail = "[2026-03-17 10:00:00] Serving on http://192.168.43.1:8080",
        )

        assertTrue(payload.contains("State: Running"))
        assertTrue(payload.contains("Startup events:"))
        assertTrue(payload.contains("Recent logs:"))
    }
}
