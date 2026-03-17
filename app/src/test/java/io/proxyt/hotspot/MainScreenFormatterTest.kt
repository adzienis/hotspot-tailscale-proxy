package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenFormatterTest {
    @Test
    fun `formats running state label`() {
        assertEquals("Running", MainScreenFormatter.stateLabel(ProxyRuntimeState.Running))
    }

    @Test
    fun `formats missing binary error label`() {
        assertEquals("Missing binary", MainScreenFormatter.errorLabel(ProxyErrorCategory.MISSING_BINARY))
    }

    @Test
    fun `recommends notification permission before start when missing`() {
        val status = ProxyStatus(state = ProxyRuntimeState.Idle)

        assertEquals(
            "This app depends on foreground-service notifications. Grant notification permission on Android 13 or newer before starting the proxy.",
            MainScreenFormatter.defaultRecommendedAction(
                status = status,
                hasNotificationPermission = false,
                ignoringBatteryOptimizations = true,
            ),
        )
    }

    @Test
    fun `prefers concrete error action when status already contains one`() {
        val status = ProxyStatus(
            state = ProxyRuntimeState.Failed,
            error = ProxyErrorInfo(
                category = ProxyErrorCategory.STARTUP_FAILURE,
                title = "Proxy failed to start",
                detail = "Health check timed out",
                recommendedAction = "Retry with a different port.",
            ),
        )

        assertEquals(
            "Retry with a different port.",
            MainScreenFormatter.defaultRecommendedAction(
                status = status,
                hasNotificationPermission = true,
                ignoringBatteryOptimizations = true,
            ),
        )
    }
}
