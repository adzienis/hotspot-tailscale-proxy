package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigValidatorTest {
    private val candidates = listOf(
        HotspotAddressCandidate(interfaceName = "ap0", address = "192.168.43.1", score = 0, kind = "Hotspot"),
        HotspotAddressCandidate(interfaceName = "wlan0", address = "192.168.1.20", score = 3, kind = "Wi-Fi"),
    )

    @Test
    fun `rejects invalid port`() {
        val result = ProxyConfigValidator.validate(
            portInput = "70000",
            advertisedBaseUrlInput = "",
            selectedLocalAddressInput = "192.168.43.1",
            debug = false,
            localCandidates = candidates,
        )

        assertEquals("Enter a valid port between 1 and 65535", result.portError)
        assertNull(result.config)
    }

    @Test
    fun `normalizes manual advertised url`() {
        val result = ProxyConfigValidator.validate(
            portInput = "8080",
            advertisedBaseUrlInput = " https://example.com/path/ ",
            selectedLocalAddressInput = "",
            debug = true,
            localCandidates = candidates,
        )

        assertNull(result.baseUrlError)
        assertNotNull(result.config)
        assertEquals("https://example.com/path", result.config?.advertisedBaseUrl)
        assertEquals("https://example.com/path", result.effectiveUrl)
    }

    @Test
    fun `uses selected local address when manual url is blank`() {
        val result = ProxyConfigValidator.validate(
            portInput = "8080",
            advertisedBaseUrlInput = "",
            selectedLocalAddressInput = "192.168.43.1",
            debug = false,
            localCandidates = candidates,
        )

        assertNull(result.portError)
        assertNull(result.localAddressError)
        assertEquals("http://192.168.43.1:8080", result.effectiveUrl)
        assertEquals("192.168.43.1", result.config?.selectedLocalAddress)
    }

    @Test
    fun `warns when manual private ip is not currently detected`() {
        val result = ProxyConfigValidator.validate(
            portInput = "8080",
            advertisedBaseUrlInput = "http://192.168.99.1:8080",
            selectedLocalAddressInput = "",
            debug = false,
            localCandidates = candidates,
        )

        assertEquals("This private IP is not on a detected local interface right now.", result.baseUrlWarning)
    }
}
