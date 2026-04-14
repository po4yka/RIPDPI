package com.poyka.ripdpi.diagnostics

/**
 * Aggregates lazily-computed augmentations for the home Full Analysis outcome:
 *  - Network character summary (transport, ASN, IPv6, captive portal, MTU, etc.)
 *  - Per-app routing sanity (installed VPN-detector apps with no per-app override)
 *  - Live network probes (bufferbloat, DNS resolver characterization)
 *
 * Implementations live in the app layer so we can keep core:diagnostics free of
 * Android Context / network-IO dependencies while still surfacing this data on
 * the Home Dashboard outcome sheet.
 */
interface HomeAnalysisAugmentationSource {
    suspend fun networkCharacter(): HomeNetworkCharacterSummary?

    suspend fun routingSanity(): HomeRoutingSanitySummary?

    suspend fun bufferbloat(): HomeBufferbloatResult?

    suspend fun dnsCharacterization(): HomeDnsCharacterization?
}

object NoopHomeAnalysisAugmentationSource : HomeAnalysisAugmentationSource {
    override suspend fun networkCharacter(): HomeNetworkCharacterSummary? = null

    override suspend fun routingSanity(): HomeRoutingSanitySummary? = null

    override suspend fun bufferbloat(): HomeBufferbloatResult? = null

    override suspend fun dnsCharacterization(): HomeDnsCharacterization? = null
}
