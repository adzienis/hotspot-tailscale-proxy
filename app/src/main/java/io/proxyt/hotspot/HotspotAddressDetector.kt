package io.proxyt.hotspot

import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotAddressDetector {
    fun detectPrivateIpv4(): String? {
        val candidates = buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceName = networkInterface.name.lowercase()
                val score = when {
                    "wlan" in interfaceName -> 0
                    "ap" in interfaceName -> 1
                    "swlan" in interfaceName -> 2
                    "rndis" in interfaceName -> 3
                    else -> 10
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val ipv4 = address as? Inet4Address ?: continue
                    val hostAddress = ipv4.hostAddress ?: continue
                    if (isPrivateIpv4(hostAddress)) {
                        add(score to hostAddress)
                    }
                }
            }
        }

        return candidates.minByOrNull { it.first }?.second
    }

    private fun isPrivateIpv4(hostAddress: String): Boolean {
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
}
