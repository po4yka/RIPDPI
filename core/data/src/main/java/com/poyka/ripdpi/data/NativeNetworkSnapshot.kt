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
)

/** Wi-Fi network details, populated when transport is "wifi". */
@Serializable
data class NativeWifiSnapshot(
    /** Frequency band: "2.4ghz", "5ghz", "6ghz", "unknown" */
    val frequencyBand: String = "unknown",
    /** SHA-256 hex of the sanitized SSID (privacy-preserving; never raw SSID) */
    val ssidHash: String = "",
)
