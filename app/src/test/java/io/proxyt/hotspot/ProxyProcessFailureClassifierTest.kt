package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyProcessFailureClassifierTest {
    @Test
    fun `classifies bind failures as port in use`() {
        val error = ProxyProcessFailureClassifier.classify(
            detail = "listen tcp :8080: bind: address already in use",
        )

        assertEquals(ProxyErrorCategory.PORT_IN_USE, error.category)
    }

    @Test
    fun `classifies permission failures`() {
        val error = ProxyProcessFailureClassifier.classify(
            detail = "operation not permitted while starting proxy process",
        )

        assertEquals(ProxyErrorCategory.PERMISSION_REQUIRED, error.category)
    }

    @Test
    fun `classifies explicit exit codes as process exits`() {
        val error = ProxyProcessFailureClassifier.classify(
            detail = "Proxy exited before passing the startup health check.",
            exitCode = 23,
        )

        assertEquals(ProxyErrorCategory.PROXY_EXIT, error.category)
        assertEquals("The bundled proxy exited with code 23.", error.detail)
    }
}
