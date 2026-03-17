package io.proxyt.hotspot

data class LocalAddressSelection(
    val options: List<String>,
    val selectedAddress: String,
)

object LocalAddressSelectionResolver {
    fun resolve(
        candidates: List<HotspotAddressCandidate>,
        currentSelection: String,
    ): LocalAddressSelection {
        val options = candidates.map { it.address }.distinct()
        val normalizedSelection = currentSelection.trim()
        val selectedAddress = when {
            normalizedSelection.isNotBlank() && normalizedSelection in options -> normalizedSelection
            options.isNotEmpty() -> options.first()
            else -> ""
        }
        return LocalAddressSelection(
            options = options,
            selectedAddress = selectedAddress,
        )
    }
}
