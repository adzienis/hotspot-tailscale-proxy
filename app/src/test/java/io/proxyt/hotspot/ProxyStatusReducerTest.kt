package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyStatusReducerTest {
    @Test
    fun `marks desired active status as failed when service disappeared`() {
        val status = ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Running,
            message = "Serving on http://192.168.43.1:8080",
        )

        val reconciled = ProxyStatusReducer.reconcilePersistedStatus(
            status = status,
            isServiceRunning = false,
        )

        assertEquals(ProxyRuntimeState.Failed, reconciled.state)
        assertEquals("Proxy stopped unexpectedly", reconciled.message)
        assertEquals(ProxyErrorCategory.STARTUP_FAILURE, reconciled.error?.category)
    }

    @Test
    fun `returns idle when service is gone and app no longer wants it running`() {
        val status = ProxyStatus(
            desiredRunning = false,
            state = ProxyRuntimeState.Stopping,
            message = "Stopping proxy",
            error = ProxyErrorInfo(
                category = ProxyErrorCategory.STARTUP_FAILURE,
                title = "Old failure",
                detail = "Old detail",
                recommendedAction = "Ignore",
            ),
        )

        val reconciled = ProxyStatusReducer.reconcilePersistedStatus(
            status = status,
            isServiceRunning = false,
        )

        assertEquals(ProxyRuntimeState.Idle, reconciled.state)
        assertEquals("Proxy idle", reconciled.message)
        assertNull(reconciled.error)
    }

    @Test
    fun `keeps status unchanged while service is still active`() {
        val status = ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Running,
            message = "Serving on http://192.168.43.1:8080",
        )

        assertEquals(
            status,
            ProxyStatusReducer.reconcilePersistedStatus(
                status = status,
                isServiceRunning = true,
            ),
        )
    }
}
