package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderDnsSb
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.builtInEncryptedDnsPathCandidates
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsPathCandidate
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.isAutomaticEncryptedDnsProvider
import com.poyka.ripdpi.data.toActiveDnsSettings
import javax.inject.Inject
import javax.inject.Singleton

internal data class VpnDnsSelection(
    val activeDns: ActiveDnsSettings,
    val preferredPath: EncryptedDnsPathCandidate? = null,
    val rememberedVpnDnsPolicy: VpnDnsPolicyJson? = null,
)

@Singleton
internal class ConnectionPolicyDnsSelector
    @Inject
    constructor(
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val resolverMappingPolicy: ResolverMappingPolicy,
        private val resolverMappingCache: ResolverMappingCache,
    ) {
        suspend fun baselineSelection(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): VpnDnsSelection {
            val preferredPath =
                resolvePreferredVpnDnsPath(
                    mode = mode,
                    dnsResolution = dnsResolution,
                    networkScopeKey = networkScopeKey,
                    directPathCapabilities = directPathCapabilities,
                )
            val baseDns =
                if (shouldUseEncryptedFallback(mode, dnsResolution, preferredPath)) {
                    canonicalDefaultEncryptedDnsPathCandidate().toActiveDnsSettings()
                } else {
                    dnsResolution.activeDns
                }
            return resolveVpnDnsSelection(
                mode = mode,
                baseDns = baseDns,
                preferredPath = preferredPath,
            )
        }

        private fun shouldUseEncryptedFallback(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            preferredPath: EncryptedDnsPathCandidate?,
        ): Boolean {
            if (mode != Mode.VPN || dnsResolution.override != null) return false
            return preferredPath == null && dnsResolution.activeDns.isPlainUdp
        }

        fun rememberedSelection(
            mode: Mode,
            baselineSelection: VpnDnsSelection,
            rememberedVpnDnsPolicy: VpnDnsPolicyJson?,
            resolverOverride: TemporaryResolverOverride?,
        ): VpnDnsSelection =
            resolveVpnDnsSelection(
                mode = mode,
                baseDns = baselineSelection.activeDns,
                preferredPath = baselineSelection.preferredPath,
                rememberedVpnDnsPolicy = rememberedVpnDnsPolicy,
                resolverOverride = resolverOverride,
            )

        private suspend fun resolvePreferredVpnDnsPath(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): EncryptedDnsPathCandidate? {
            if (mode != Mode.VPN || dnsResolution.override != null) return null

            val learnedPath =
                networkScopeKey?.let { scopeKey ->
                    networkDnsPathPreferenceStore
                        .getPreferredPath(scopeKey)
                        ?.takeIf { isAutomaticEncryptedDnsProvider(it.resolverId) }
                        ?: derivePreferredVpnDnsPathFromDirectPathCapabilities(scopeKey, directPathCapabilities)
                }
            return learnedPath
        }

        private fun derivePreferredVpnDnsPathFromDirectPathCapabilities(
            networkScopeKey: String,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): EncryptedDnsPathCandidate? {
            val dnsModes =
                directPathCapabilities
                    .asSequence()
                    .filter { capability -> isResolvableHostnameAuthority(capability.authority) }
                    .mapNotNull { capability ->
                        resolveMappingForCapability(networkScopeKey, capability)?.source
                    }.filter { dnsMode -> dnsMode != DnsMode.SYSTEM }
                    .distinct()
                    .toList()
            val selectedMode = dnsModes.singleOrNull() ?: return null
            return when (selectedMode) {
                DnsMode.DOH_PRIMARY -> builtInDohCandidate(DnsProviderAdGuard)
                DnsMode.DOH_SECONDARY -> builtInDohCandidate(DnsProviderDnsSb)
                DnsMode.SYSTEM -> null
            }
        }

        private fun resolveMappingForCapability(
            networkScopeKey: String,
            capability: RipDpiDirectPathCapability,
        ): ResolvedMapping? {
            val host = capability.authority.normalizedHost()
            return if (host == null) {
                null
            } else {
                resolverMappingCache.get(host, networkScopeKey)
                    ?: selectAndCacheMapping(host, networkScopeKey, capability)
            }
        }

        private fun selectAndCacheMapping(
            host: String,
            networkScopeKey: String,
            capability: RipDpiDirectPathCapability,
        ): ResolvedMapping? {
            val encryptedCandidates = encryptedCandidateForMode(capability.dnsMode)
            val systemCandidates = systemCandidates(capability)
            val mapping =
                resolverMappingPolicy.select(
                    ResolverMappingRequest(
                        host = host,
                        networkScopeKey = networkScopeKey,
                        dnsClassification = capability.dnsClassification,
                        systemCandidates = systemCandidates,
                        encryptedCandidates = encryptedCandidates,
                        transportEnvelope = capability.toTransportPolicyEnvelope(),
                    ),
                )
            if (mapping != null) {
                resolverMappingCache.put(
                    host = host,
                    networkScopeKey = networkScopeKey,
                    mapping = mapping,
                    ttlMs =
                        (systemCandidates + encryptedCandidates).minOfOrNull(ResolverMappingCandidate::ttlMs)
                            ?: DefaultResolverMappingTtlMs,
                )
            }
            return mapping
        }
    }

@Suppress("ReturnCount")
internal fun resolveVpnDnsSelection(
    mode: Mode,
    baseDns: ActiveDnsSettings,
    preferredPath: EncryptedDnsPathCandidate? = null,
    rememberedVpnDnsPolicy: VpnDnsPolicyJson? = null,
    resolverOverride: TemporaryResolverOverride? = null,
): VpnDnsSelection {
    if (mode != Mode.VPN) {
        return VpnDnsSelection(activeDns = baseDns)
    }
    if (resolverOverride != null) {
        return VpnDnsSelection(activeDns = resolverOverride.toActiveDnsSettings())
    }
    val automaticPreferredPath = preferredPath?.takeIf { isAutomaticEncryptedDnsProvider(it.resolverId) }
    if (automaticPreferredPath != null) {
        return VpnDnsSelection(
            activeDns = automaticPreferredPath.toActiveDnsSettings(),
            preferredPath = automaticPreferredPath,
        )
    }
    val rememberedDns =
        rememberedVpnDnsPolicy?.toActiveDnsSettings()?.takeIf { isAutomaticEncryptedDnsProvider(it.providerId) }
    return VpnDnsSelection(
        activeDns = rememberedDns ?: baseDns,
        rememberedVpnDnsPolicy = rememberedVpnDnsPolicy.takeIf { rememberedDns != null },
    )
}

private fun builtInDohCandidate(resolverId: String): EncryptedDnsPathCandidate? =
    builtInEncryptedDnsPathCandidates().firstOrNull { candidate ->
        candidate.resolverId == resolverId && candidate.protocol.equals("doh", ignoreCase = true)
    }

private fun isResolvableHostnameAuthority(authority: String): Boolean {
    val host = authority.normalizedHost()
    return host != null && host.any(Char::isLetter)
}

private fun String.normalizedHost(): String? =
    substringBefore(':')
        .trim()
        .trimEnd('.')
        .lowercase()
        .takeIf { it.isNotEmpty() }

private fun encryptedCandidateForMode(dnsMode: DnsMode): List<ResolverMappingCandidate> {
    val resolverId =
        when (dnsMode) {
            DnsMode.DOH_PRIMARY -> DnsProviderAdGuard
            DnsMode.DOH_SECONDARY -> DnsProviderDnsSb
            DnsMode.SYSTEM -> null
        }
    val ip =
        resolverId
            ?.let(::builtInDohCandidate)
            ?.bootstrapIps
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    return ip
        ?.let { bootstrapIp ->
            listOf(
                ResolverMappingCandidate(
                    ip = bootstrapIp,
                    source = dnsMode,
                    latencyMs =
                        when (dnsMode) {
                            DnsMode.DOH_PRIMARY -> DohPrimarySyntheticLatencyMs
                            DnsMode.DOH_SECONDARY -> DohSecondarySyntheticLatencyMs
                            DnsMode.SYSTEM -> SystemSyntheticLatencyMs
                        },
                    ttlMs = DefaultResolverMappingTtlMs,
                ),
            )
        }.orEmpty()
}

private fun systemCandidates(capability: RipDpiDirectPathCapability): List<ResolverMappingCandidate> =
    capability.ipSetDigest
        .split(',')
        .mapNotNull { ip ->
            ip.trim().takeIf { it.isNotEmpty() }?.let {
                ResolverMappingCandidate(
                    ip = it,
                    source = DnsMode.SYSTEM,
                    latencyMs = SystemSyntheticLatencyMs,
                    ttlMs = DefaultResolverMappingTtlMs,
                    ipSetDigest = capability.ipSetDigest,
                )
            }
        }

private fun RipDpiDirectPathCapability.toTransportPolicyEnvelope(): TransportPolicyEnvelope =
    TransportPolicyEnvelope(
        policy =
            com.poyka.ripdpi.data.TransportPolicy(
                quicMode = quicMode,
                preferredStack = preferredStack,
                dnsMode = dnsMode,
                tcpFamily = tcpFamily,
                outcome = outcome,
            ),
        ipSetDigest = ipSetDigest,
        dnsClassification = dnsClassification,
        transportClass = transportClass,
        reasonCode = reasonCode,
        cooldownUntil = cooldownUntil,
    )

private const val SystemSyntheticLatencyMs = 10L
private const val DohPrimarySyntheticLatencyMs = 20L
private const val DohSecondarySyntheticLatencyMs = 30L
private const val DefaultResolverMappingTtlMs = 5L * 60L * 1_000L
