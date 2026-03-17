package io.proxyt.hotspot

import java.net.URI
import java.util.Locale

data class ProxyConfigValidationResult(
    val config: ProxyConfig?,
    val effectiveUrl: String,
    val portError: String? = null,
    val baseUrlError: String? = null,
    val baseUrlWarning: String? = null,
    val localAddressError: String? = null,
    val localAddressWarning: String? = null,
)

object ProxyConfigValidator {
    private const val DEFAULT_HOTSPOT_IP = "192.168.43.1"

    fun validate(
        portInput: String,
        advertisedBaseUrlInput: String,
        selectedLocalAddressInput: String,
        debug: Boolean,
        localCandidates: List<HotspotAddressCandidate>,
    ): ProxyConfigValidationResult {
        val port = portInput.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            return ProxyConfigValidationResult(
                config = null,
                effectiveUrl = buildLocalUrl(selectedLocalAddressInput.trim(), fallbackPort = 8080),
                portError = "Enter a valid port between 1 and 65535",
            )
        }

        val normalizedBaseUrl = advertisedBaseUrlInput.trim()
        val normalizedLocalAddress = selectedLocalAddressInput.trim()
        val availableAddresses = localCandidates.map { it.address }.toSet()

        if (normalizedBaseUrl.isNotBlank()) {
            val normalized = normalizeAdvertisedBaseUrl(normalizedBaseUrl)
                ?: return ProxyConfigValidationResult(
                    config = null,
                    effectiveUrl = buildLocalUrl(normalizedLocalAddress, port),
                    baseUrlError = "Use a full http:// or https:// URL with a host",
                )

            val host = URI(normalized).host.orEmpty()
            return ProxyConfigValidationResult(
                config = ProxyConfig(
                    port = port,
                    advertisedBaseUrl = normalized,
                    selectedLocalAddress = normalizedLocalAddress,
                    debug = debug,
                ),
                effectiveUrl = normalized,
                baseUrlWarning = buildBaseUrlWarning(host, availableAddresses),
            )
        }

        if (normalizedLocalAddress.isNotBlank() && !HotspotAddressDetector.isPrivateIpv4(normalizedLocalAddress)) {
            return ProxyConfigValidationResult(
                config = null,
                effectiveUrl = buildLocalUrl(normalizedLocalAddress, port),
                localAddressError = "Select a private IPv4 address or enter the advertised URL manually",
            )
        }

        return ProxyConfigValidationResult(
            config = ProxyConfig(
                port = port,
                advertisedBaseUrl = "",
                selectedLocalAddress = normalizedLocalAddress,
                debug = debug,
            ),
            effectiveUrl = resolveEffectiveUrl(
                config = ProxyConfig(
                    port = port,
                    advertisedBaseUrl = "",
                    selectedLocalAddress = normalizedLocalAddress,
                    debug = debug,
                ),
                localCandidates = localCandidates,
            ),
            localAddressWarning = buildLocalAddressWarning(normalizedLocalAddress, availableAddresses),
        )
    }

    fun resolveEffectiveUrl(config: ProxyConfig, localCandidates: List<HotspotAddressCandidate>): String {
        val selectedLocalAddress = config.selectedLocalAddress.trim()
        if (config.advertisedBaseUrl.isNotBlank()) {
            return normalizeAdvertisedBaseUrl(config.advertisedBaseUrl)
                ?: throw IllegalArgumentException("Advertised base URL must be a valid http:// or https:// URL with a host")
        }

        val candidateAddress = when {
            selectedLocalAddress.isNotBlank() -> selectedLocalAddress
            localCandidates.isNotEmpty() -> localCandidates.first().address
            else -> DEFAULT_HOTSPOT_IP
        }
        return buildLocalUrl(candidateAddress, config.port)
    }

    fun normalizeAdvertisedBaseUrl(raw: String): String? {
        val candidate = raw.trim().removeSuffix("/")
        if (candidate.isBlank()) {
            return ""
        }

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) {
            return null
        }

        val host = uri.host ?: return null
        val authority = uri.rawAuthority ?: return null
        val path = uri.rawPath
            ?.trim()
            .orEmpty()
            .removeSuffix("/")
            .takeIf { it.isNotEmpty() && it != "/" }
            .orEmpty()

        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authority$path$query"
    }

    private fun buildLocalUrl(address: String, fallbackPort: Int): String {
        val host = address.ifBlank { DEFAULT_HOTSPOT_IP }
        return "http://$host:$fallbackPort"
    }

    private fun buildBaseUrlWarning(host: String, availableAddresses: Set<String>): String? {
        if (host.isBlank() || host == "localhost") {
            return null
        }
        if (HotspotAddressDetector.isPrivateIpv4(host) && host !in availableAddresses) {
            return "This private IP is not on a detected local interface right now."
        }
        if (!HotspotAddressDetector.isPrivateIpv4(host)) {
            return "Hotspot mode usually needs a private LAN IP or another hostname the client can reach locally."
        }
        return null
    }

    private fun buildLocalAddressWarning(address: String, availableAddresses: Set<String>): String? {
        if (address.isBlank() || address in availableAddresses) {
            return null
        }
        return "Saved local address is not currently detected. Verify the hotspot/LAN interface before starting."
    }
}
