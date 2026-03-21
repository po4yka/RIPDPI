package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

/** Kotlin mirror of the Rust `NetworkSnapshot` type in ripdpi-proxy-config.
 *
 * All fields use default values so that the Rust side can deserialize older JSON
 * (from Kotlin builds that predate individual fields) without failures.
 */
@Serializable
data class NativeNetworkSnapshot(
    /** Physical transport: "wifi", "cellular", "ethernet", "vpn", "none", "unknown" */
    val transport: String = "",
    /** NET_CAPABILITY_VALIDATED */
    val validated: Boolean = false,
    /** NET_CAPABILITY_CAPTIVE_PORTAL */
    val captivePortal: Boolean = false,
    /** !NET_CAPABILITY_NOT_METERED */
    val metered: Boolean = false,
    /** "system" (default/opportunistic) or strict hostname from Private DNS settings */
    val privateDnsMode: String = "",
    /** DNS servers from LinkProperties.getDnsServers() */
    val dnsServers: List<String> = emptyList(),
    /** Present when transport is "cellular" */
    val cellular: NativeCellularSnapshot? = null,
    /** Present when transport is "wifi" */
    val wifi: NativeWifiSnapshot? = null,
    /** LinkProperties.getMtu() when API >= 29 and the platform reports a positive value */
    val mtu: Int? = null,
    /** TrafficStats.getUidTxBytes(uid) at capture time */
    val trafficTxBytes: Long = 0L,
    /** TrafficStats.getUidRxBytes(uid) at capture time */
    val trafficRxBytes: Long = 0L,
    /** System.currentTimeMillis() at capture time */
    val capturedAtMs: Long = 0L,
)

/** Cellular network details, populated when transport is "cellular". */
@Serializable
data class NativeCellularSnapshot(
    /** Radio generation: "2g", "3g", "4g", "5g", "unknown" */
    val generation: String = "unknown",
    val roaming: Boolean = false,
    /** MCC+MNC of the serving network operator */
    val operatorCode: String = "",
    /** Diagnostics-style mobile network type: "LTE", "NR", "IWLAN", "unknown", etc. */
    val dataNetworkType: String = "unknown",
    /** ServiceState.state normalized to "in_service", "out_of_service", etc. */
    val serviceState: String = "unknown",
    /** Carrier ID when the platform reports a non-negative value */
    val carrierId: Int? = null,
    /** SignalStrength.level */
    val signalLevel: Int? = null,
    /** First reported cell signal strength dBm */
    val signalDbm: Int? = null,
)

/** Wi-Fi network details, populated when transport is "wifi". */
@Serializable
data class NativeWifiSnapshot(
    /** Frequency band: "2.4ghz", "5ghz", "6ghz", "unknown" */
    val frequencyBand: String = "unknown",
    /** SHA-256 hex of the sanitized SSID (privacy-preserving; never raw SSID) */
    val ssidHash: String = "",
    /** Wi-Fi frequency in MHz when the platform reports a positive value */
    val frequencyMhz: Int? = null,
    /** RSSI in dBm when the platform reports a sane value */
    val rssiDbm: Int? = null,
    /** Wi-Fi link speed in Mbps when the platform reports a positive value */
    val linkSpeedMbps: Int? = null,
    /** Wi-Fi RX link speed in Mbps when available */
    val rxLinkSpeedMbps: Int? = null,
    /** Wi-Fi TX link speed in Mbps when available */
    val txLinkSpeedMbps: Int? = null,
    /** Diagnostics-style channel width label: "20 MHz", "80 MHz", "unknown", etc. */
    val channelWidth: String = "unknown",
    /** Diagnostics-style standard label: "802.11ax", "legacy", "unknown", etc. */
    val wifiStandard: String = "unknown",
)
