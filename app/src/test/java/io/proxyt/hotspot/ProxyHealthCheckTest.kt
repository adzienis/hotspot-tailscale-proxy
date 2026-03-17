package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyHealthCheckTest {
    @Test
    fun `builds loopback probe url from listen port`() {
        assertEquals("http://127.0.0.1:8080", ProxyHealthCheck.localProbeUrl(8080))
        assertEquals("http://127.0.0.1:443", ProxyHealthCheck.localProbeUrl(443))
    }
}
