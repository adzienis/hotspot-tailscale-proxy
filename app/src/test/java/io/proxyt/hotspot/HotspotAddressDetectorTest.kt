package io.proxyt.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAddressDetectorTest {
    @Test
    fun `identifies private ipv4 ranges`() {
        assertTrue(HotspotAddressDetector.isPrivateIpv4("10.0.0.1"))
        assertTrue(HotspotAddressDetector.isPrivateIpv4("172.16.5.4"))
        assertTrue(HotspotAddressDetector.isPrivateIpv4("192.168.43.1"))
        assertFalse(HotspotAddressDetector.isPrivateIpv4("8.8.8.8"))
        assertFalse(HotspotAddressDetector.isPrivateIpv4("not-an-ip"))
    }

    @Test
    fun `ranks hotspot candidates ahead of generic lan and removes duplicates`() {
        val ranked = HotspotAddressDetector.rankCandidates(
            listOf(
                HotspotAddressCandidate(interfaceName = "eth0", address = "192.168.0.2", score = 10, kind = "LAN"),
                HotspotAddressCandidate(interfaceName = "ap0", address = "192.168.43.1", score = 0, kind = "Hotspot"),
                HotspotAddressCandidate(interfaceName = "ap0", address = "192.168.43.1", score = 0, kind = "Hotspot"),
            ),
        )

        assertEquals(2, ranked.size)
        assertEquals("192.168.43.1", ranked.first().address)
        assertEquals("192.168.0.2", ranked.last().address)
    }
}
