package com.poyka.ripdpi.services.network

/**
 * Coarse-grained network state used for policy classification.
 *
 * Privacy note: no SSID, no IP address, no LinkAddress — only opaque transport key and
 * boolean/list primitives that cannot identify a physical location.
 */
data class NetworkSnapshot(
    val dnsServers: List<String>,
    val hasDefaultRoute: Boolean,
    val metered: Boolean,
    val captivePortal: Boolean,
    val suspended: Boolean,
    /** Opaque label: "wifi", "cellular", "ethernet", "other", "unknown". */
    val transportKey: String,
)
