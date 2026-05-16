package com.poyka.ripdpi.services.network

/** Typed events emitted by the Android NetworkCallback bridge — no polling. */
sealed class NetworkEvent {
    /** The network became the active default network. */
    data object Available : NetworkEvent()

    /** Network capabilities changed (validated, captive, metered, suspended, transport). */
    data class CapabilitiesChanged(
        val snapshot: CapabilitiesSnapshot,
    ) : NetworkEvent()

    /** Link properties changed (DNS servers, routes). */
    data class LinkPropertiesChanged(
        val snapshot: LinkPropertiesSnapshot,
    ) : NetworkEvent()

    /** The network was lost. */
    data object Lost : NetworkEvent()
}

/** Coarse capability state extracted from a NetworkCapabilities object. No SSID or IP. */
data class CapabilitiesSnapshot(
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val suspended: Boolean,
    val transports: Set<Any>,
)

/** Coarse link-property state. No LinkAddress, no IP. */
data class LinkPropertiesSnapshot(
    val dnsServers: List<String>,
    val hasDefaultRoute: Boolean,
)
