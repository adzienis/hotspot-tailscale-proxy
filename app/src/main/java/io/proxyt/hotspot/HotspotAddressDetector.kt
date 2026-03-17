package io.proxyt.hotspot

import java.net.Inet4Address
import java.net.NetworkInterface

data class HotspotAddressCandidate(
    val interfaceName: String,
    val address: String,
    val score: Int,
    val kind: String,
)

object HotspotAddressDetector {
    fun detectCandidates(): List<HotspotAddressCandidate> {
        val candidates = buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceName = networkInterface.name
                val lowercaseName = interfaceName.lowercase()
                val kind = classify(lowercaseName)
                val score = score(lowercaseName)

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val ipv4 = address as? Inet4Address ?: continue
                    val hostAddress = ipv4.hostAddress ?: continue
                    if (isPrivateIpv4(hostAddress)) {
                        add(
                            HotspotAddressCandidate(
                                interfaceName = interfaceName,
                                address = hostAddress,
                                score = score,
                                kind = kind,
                            ),
                        )
                    }
                }
            }
        }

        return candidates
            .distinctBy { "${it.interfaceName}:${it.address}" }
            .sortedWith(compareBy<HotspotAddressCandidate> { it.score }.thenBy { it.interfaceName }.thenBy { it.address })
    }

    fun detectPrivateIpv4(): String? {
        return detectCandidates().firstOrNull()?.address
    }

    fun isPrivateIpv4(hostAddress: String): Boolean {
        val octets = hostAddress.split(".")
        if (octets.size != 4) {
            return false
        }

        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false

        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            else -> false
        }
    }

    private fun classify(interfaceName: String): String =
        when {
            "rndis" in interfaceName || "usb" in interfaceName -> "USB tethering"
            "ap" in interfaceName || "swlan" in interfaceName || "hotspot" in interfaceName -> "Hotspot"
            "wlan" in interfaceName || "wifi" in interfaceName -> "Wi-Fi"
            else -> "LAN"
        }

    private fun score(interfaceName: String): Int =
        when {
            "ap" in interfaceName || "hotspot" in interfaceName -> 0
            "swlan" in interfaceName -> 1
            "rndis" in interfaceName || "usb" in interfaceName -> 2
            "wlan" in interfaceName || "wifi" in interfaceName -> 3
            else -> 10
        }
}
