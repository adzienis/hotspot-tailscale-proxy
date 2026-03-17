package io.proxyt.hotspot

import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPortBinderTest {
    @Test
    fun `reports free ephemeral port as bindable`() {
        val port = ServerSocket(0).use { socket -> socket.localPort }

        val result = ProxyPortBinder.check(port)

        assertTrue(result.canBind)
    }

    @Test
    fun `reports already bound port as unavailable`() {
        ServerSocket(0).use { socket ->
            val result = ProxyPortBinder.check(socket.localPort)

            assertFalse(result.canBind)
        }
    }
}
