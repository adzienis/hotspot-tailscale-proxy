package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAddressSelectionResolverTest {
    @Test
    fun `drops stale selected address on refresh`() {
        val result = LocalAddressSelectionResolver.resolve(
            candidates = listOf(
                HotspotAddressCandidate(interfaceName = "ap0", address = "192.168.43.1", score = 0, kind = "Hotspot"),
                HotspotAddressCandidate(interfaceName = "wlan0", address = "192.168.1.20", score = 3, kind = "Wi-Fi"),
            ),
            currentSelection = "192.168.99.1",
        )

        assertEquals(listOf("192.168.43.1", "192.168.1.20"), result.options)
        assertEquals("192.168.43.1", result.selectedAddress)
    }

    @Test
    fun `clears selection when no candidates remain`() {
        val result = LocalAddressSelectionResolver.resolve(
            candidates = emptyList(),
            currentSelection = "192.168.43.1",
        )

        assertEquals(emptyList<String>(), result.options)
        assertEquals("", result.selectedAddress)
    }
}
