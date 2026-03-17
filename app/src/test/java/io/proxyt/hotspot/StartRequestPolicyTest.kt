package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Test

class StartRequestPolicyTest {
    @Test
    fun `starts immediately when notification permission is already granted`() {
        assertEquals(
            StartRequestDecision.StartImmediately,
            StartRequestPolicy.decide(hasNotificationPermission = true),
        )
    }

    @Test
    fun `requests notification permission when missing`() {
        assertEquals(
            StartRequestDecision.RequestNotificationPermission,
            StartRequestPolicy.decide(hasNotificationPermission = false),
        )
    }
}
