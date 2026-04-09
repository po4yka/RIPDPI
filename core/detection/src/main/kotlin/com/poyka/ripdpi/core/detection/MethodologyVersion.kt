package com.poyka.ripdpi.core.detection

object MethodologyVersion {
    const val CURRENT = "2026-04-mincifry-v1"
    const val DESCRIPTION =
        "Based on Russian Ministry of Digital Development methodology " +
            "for detecting VPN/proxy circumvention tools. " +
            "Extended with DNS leak, WebRTC leak, and TLS fingerprint checks."
    const val CHECKER_COUNT = 8

    fun summary(): String =
        buildString {
            appendLine("Methodology: $CURRENT")
            appendLine("Checkers: $CHECKER_COUNT")
            appendLine("  - GeoIP (ip-api.com)")
            appendLine("  - Direct Signs (TRANSPORT_VPN, system proxy, installed apps)")
            appendLine("  - Indirect Signs (interfaces, MTU, routing, DNS, dumpsys)")
            appendLine("  - Location Signals (PLMN MCC, SIM MCC, BSSID)")
            appendLine("  - Bypass (proxy scan, Xray API, split-tunnel IP comparison)")
            appendLine("  - DNS Leak (resolver analysis, encrypted DNS check)")
            appendLine("  - WebRTC Leak (STUN reachability, protection status)")
            appendLine("  - TLS Fingerprint (cipher suites, protocol analysis)")
        }
}
