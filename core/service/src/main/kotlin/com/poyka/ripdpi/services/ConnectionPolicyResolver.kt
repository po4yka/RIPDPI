package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.toPolicyJson
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.toActiveDnsSettings
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectionPolicyResolution(
    val settings: AppSettings,
    val proxyPreferences: RipDpiProxyPreferences,
    val activeDns: ActiveDnsSettings,
    val vpnDnsOverride: VpnDnsPolicyJson? = null,
    val matchedNetworkPolicy: RememberedNetworkPolicyEntity? = null,
    val rememberedPolicyAppliedByExactMatch: Boolean? = null,
    val appliedPolicy: RememberedNetworkPolicyJson? = null,
    val networkScopeKey: String? = null,
    val fingerprintHash: String? = null,
    val policySignature: String,
    val resolverFallbackReason: String? = null,
    val handoverClassification: String? = null,
)

internal data class VpnDnsSelection(
    val activeDns: ActiveDnsSettings,
    val preferredPath: EncryptedDnsPathCandidate? = null,
    val rememberedVpnDnsPolicy: VpnDnsPolicyJson? = null,
)

interface ConnectionPolicyResolver {
    suspend fun resolve(
        mode: Mode,
        resolverOverride: TemporaryResolverOverride? = null,
        fingerprint: NetworkFingerprint? = null,
        handoverClassification: String? = null,
    ): ConnectionPolicyResolution
}

@Singleton
class DefaultConnectionPolicyResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val antiCorrelationRoutingPolicy: AntiCorrelationRoutingPolicy,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val startupDnsProbe: VpnStartupDnsProbe,
        private val rootHelperManager: RootHelperManager,
    ) : ConnectionPolicyResolver {
        @Suppress("LongMethod", "ReturnCount")
        override suspend fun resolve(
            mode: Mode,
            resolverOverride: TemporaryResolverOverride?,
            fingerprint: NetworkFingerprint?,
            handoverClassification: String?,
        ): ConnectionPolicyResolution {
            val settings = appSettingsRepository.snapshot()
            val dnsResolution = resolveEffectiveDns(settings, resolverOverride)
            val fingerprintSnapshot = fingerprint ?: networkFingerprintProvider.capture()
            val networkScopeKey = fingerprintSnapshot?.scopeKey()
            val preferredVpnDnsPath =
                resolvePreferredVpnDnsPath(
                    mode = mode,
                    dnsResolution = dnsResolution,
                    networkScopeKey = networkScopeKey,
                )
            val directPathCapabilities = resolveDirectPathCapabilities(networkScopeKey)
            val baselineVpnDnsSelection =
                resolveVpnDnsSelection(
                    mode = mode,
                    baseDns = dnsResolution.activeDns,
                    preferredPath = preferredVpnDnsPath,
                )
            val protectPath = resolveVpnProtectPath(context, mode)
            val preferredEdges = resolvePreferredEdges(settings, networkScopeKey)
            val runtimeContext =
                mergeRuntimeContext(
                    baselineVpnDnsSelection.activeDns,
                    protectPath,
                    preferredEdges,
                    directPathCapabilities,
                )
            val hostAutolearnStorePath = resolveHostAutolearnStorePath(context)

            val baselinePreferences =
                buildBaselinePreferences(settings, hostAutolearnStorePath, networkScopeKey, runtimeContext)
            val baselineLaneFamilies =
                (baselinePreferences as? RipDpiProxyUIPreferences)?.deriveStrategyLaneFamilies(
                    activeDns = baselineVpnDnsSelection.activeDns,
                )

            val baselinePolicy =
                buildBaselinePolicy(
                    settings,
                    mode,
                    fingerprintSnapshot,
                    networkScopeKey,
                    baselinePreferences,
                    baselineVpnDnsSelection,
                    baselineLaneFamilies,
                )
            val baselinePolicySignature =
                buildConnectionPolicySignature(
                    mode = mode,
                    proxyPreferences = baselinePreferences,
                    activeDns = baselineVpnDnsSelection.activeDns,
                    resolverFallbackReason = dnsResolution.override?.reason,
                    matchedPolicy = null,
                )

            if (settings.enableCmdSettings || !settings.networkStrategyMemoryEnabled || networkScopeKey == null) {
                return buildBaselineResolution(
                    settings,
                    baselinePreferences,
                    baselineVpnDnsSelection,
                    baselinePolicy,
                    networkScopeKey,
                    baselinePolicySignature,
                    dnsResolution,
                    handoverClassification,
                )
            }

            val matchedPolicy =
                rememberedNetworkPolicyStore.findValidatedMatch(
                    fingerprintHash = networkScopeKey,
                    mode = mode,
                ) ?: return buildBaselineResolution(
                    settings,
                    baselinePreferences,
                    baselineVpnDnsSelection,
                    baselinePolicy,
                    networkScopeKey,
                    baselinePolicySignature,
                    dnsResolution,
                    handoverClassification,
                )

            val rememberedPolicy = matchedPolicy.toPolicyJson()
            val rememberedLaneFamilies =
                decodeRipDpiProxyUiPreferences(matchedPolicy.proxyConfigJson)?.deriveStrategyLaneFamilies(
                    activeDns = baselineVpnDnsSelection.activeDns,
                )
            val vpnDnsSelection =
                resolveVpnDnsSelection(
                    mode = mode,
                    baseDns = baselineVpnDnsSelection.activeDns,
                    preferredPath = baselineVpnDnsSelection.preferredPath,
                    rememberedVpnDnsPolicy = rememberedPolicy?.vpnDnsPolicy,
                    resolverOverride = dnsResolution.override,
                )
            val effectiveDns = vpnDnsSelection.activeDns
            val effectiveRuntimeContext =
                mergeRuntimeContext(
                    effectiveDns,
                    protectPath,
                    preferredEdges,
                    directPathCapabilities,
                )
            val proxyPreferences =
                RipDpiProxyJsonPreferences(
                    configJson = matchedPolicy.proxyConfigJson,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    networkScopeKey = networkScopeKey,
                    runtimeContext = effectiveRuntimeContext,
                    rootMode = settings.rootModeEnabled,
                    rootHelperSocketPath = rootHelperManager.socketPath,
                )
            val appliedPolicy =
                rememberedPolicy?.copy(
                    winningTcpStrategyFamily =
                        rememberedPolicy.winningTcpStrategyFamily ?: rememberedLaneFamilies?.tcpStrategyFamily,
                    winningQuicStrategyFamily =
                        rememberedPolicy.winningQuicStrategyFamily ?: rememberedLaneFamilies?.quicStrategyFamily,
                    winningDnsStrategyFamily =
                        rememberedPolicy.winningDnsStrategyFamily ?: effectiveDns.strategyFamily(),
                )
            val policySignature =
                buildConnectionPolicySignature(
                    mode = mode,
                    proxyPreferences = proxyPreferences,
                    activeDns = effectiveDns,
                    resolverFallbackReason = dnsResolution.override?.reason,
                    matchedPolicy = matchedPolicy,
                )
            return ConnectionPolicyResolution(
                settings = settings,
                proxyPreferences = proxyPreferences,
                activeDns = effectiveDns,
                vpnDnsOverride = vpnDnsSelection.rememberedVpnDnsPolicy,
                matchedNetworkPolicy = matchedPolicy,
                rememberedPolicyAppliedByExactMatch = true,
                appliedPolicy = appliedPolicy,
                networkScopeKey = networkScopeKey,
                fingerprintHash = networkScopeKey,
                policySignature = policySignature,
                resolverFallbackReason = dnsResolution.override?.reason,
                handoverClassification = handoverClassification,
            )
        }

        private fun mergeRuntimeContext(
            activeDns: ActiveDnsSettings,
            protectPath: String?,
            preferredEdges: Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>>,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): RipDpiRuntimeContext? {
            val dnsRuntimeContext = activeDns.toRipDpiRuntimeContext()
            return when {
                protectPath == null &&
                    dnsRuntimeContext == null &&
                    preferredEdges.isEmpty() &&
                    directPathCapabilities.isEmpty() -> {
                    null
                }

                dnsRuntimeContext == null -> {
                    RipDpiRuntimeContext(
                        protectPath = protectPath,
                        preferredEdges = preferredEdges,
                        directPathCapabilities = directPathCapabilities,
                    )
                }

                else -> {
                    dnsRuntimeContext.copy(
                        protectPath = protectPath,
                        preferredEdges = preferredEdges,
                        directPathCapabilities = directPathCapabilities,
                    )
                }
            }
        }

        private suspend fun resolveDirectPathCapabilities(networkScopeKey: String?): List<RipDpiDirectPathCapability> =
            if (networkScopeKey == null) {
                emptyList()
            } else {
                serverCapabilityStore
                    .directPathCapabilitiesForFingerprint(networkScopeKey)
                    .map { record ->
                        val transportPolicyEnvelope = record.effectiveTransportPolicyEnvelope()
                        RipDpiDirectPathCapability(
                            authority = record.authority,
                            quicUsable = record.quicUsable,
                            udpUsable = record.udpUsable,
                            fallbackRequired = record.fallbackRequired,
                            repeatedHandshakeFailureClass = record.repeatedHandshakeFailureClass,
                            transportPolicyVersion = transportPolicyEnvelope.version,
                            ipSetDigest = transportPolicyEnvelope.ipSetDigest,
                            quicMode = transportPolicyEnvelope.policy.quicMode,
                            preferredStack = transportPolicyEnvelope.policy.preferredStack,
                            dnsMode = transportPolicyEnvelope.policy.dnsMode,
                            tcpFamily = transportPolicyEnvelope.policy.tcpFamily,
                            outcome = transportPolicyEnvelope.policy.outcome,
                            transportClass = transportPolicyEnvelope.transportClass,
                            reasonCode = transportPolicyEnvelope.reasonCode,
                            cooldownUntil = transportPolicyEnvelope.cooldownUntil,
                            updatedAt = record.updatedAt,
                        )
                    }
            }

        private suspend fun resolvePreferredEdges(
            settings: AppSettings,
            networkScopeKey: String?,
        ): Map<String, List<com.poyka.ripdpi.data.PreferredEdgeCandidate>> =
            antiCorrelationRoutingPolicy.apply(
                settings = settings,
                preferredEdges =
                    networkScopeKey
                        ?.let { fingerprintHash ->
                            networkEdgePreferenceStore.getPreferredEdgesForRuntime(fingerprintHash)
                        }.orEmpty(),
            )

        private fun buildBaselinePreferences(
            settings: AppSettings,
            hostAutolearnStorePath: String,
            networkScopeKey: String?,
            runtimeContext: RipDpiRuntimeContext?,
        ): RipDpiProxyPreferences =
            if (settings.enableCmdSettings) {
                RipDpiProxyCmdPreferences(
                    settings.cmdArgs,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    runtimeContext = runtimeContext,
                )
            } else {
                RipDpiProxyUIPreferences.fromSettings(
                    settings,
                    hostAutolearnStorePath,
                    networkScopeKey,
                    runtimeContext,
                    rootMode = settings.rootModeEnabled,
                    rootHelperSocketPath = rootHelperManager.socketPath,
                )
            }

        private fun buildBaselinePolicy(
            settings: AppSettings,
            mode: Mode,
            fingerprintSnapshot: NetworkFingerprint?,
            networkScopeKey: String?,
            baselinePreferences: RipDpiProxyPreferences,
            baselineVpnDnsSelection: VpnDnsSelection,
            baselineLaneFamilies: StrategyLaneFamilies?,
        ): RememberedNetworkPolicyJson? {
            if (settings.enableCmdSettings || fingerprintSnapshot == null || networkScopeKey == null) return null
            return RememberedNetworkPolicyJson(
                fingerprintHash = networkScopeKey,
                mode = mode.preferenceValue,
                summary = fingerprintSnapshot.summary(),
                proxyConfigJson = stripRipDpiRuntimeContext(baselinePreferences.toNativeConfigJson()),
                vpnDnsPolicy =
                    if (mode == Mode.VPN) {
                        baselineVpnDnsSelection.activeDns.toVpnDnsPolicyJson()
                    } else {
                        null
                    },
                winningTcpStrategyFamily = baselineLaneFamilies?.tcpStrategyFamily,
                winningQuicStrategyFamily = baselineLaneFamilies?.quicStrategyFamily,
                winningDnsStrategyFamily = baselineLaneFamilies?.dnsStrategyFamily,
            )
        }

        private fun buildBaselineResolution(
            settings: AppSettings,
            baselinePreferences: RipDpiProxyPreferences,
            baselineVpnDnsSelection: VpnDnsSelection,
            baselinePolicy: RememberedNetworkPolicyJson?,
            networkScopeKey: String?,
            baselinePolicySignature: String,
            dnsResolution: EffectiveDnsResolution,
            handoverClassification: String?,
        ): ConnectionPolicyResolution =
            ConnectionPolicyResolution(
                settings = settings,
                proxyPreferences = baselinePreferences,
                activeDns = baselineVpnDnsSelection.activeDns,
                vpnDnsOverride = null,
                matchedNetworkPolicy = null,
                rememberedPolicyAppliedByExactMatch = null,
                appliedPolicy = baselinePolicy,
                networkScopeKey = networkScopeKey,
                fingerprintHash = networkScopeKey,
                policySignature = baselinePolicySignature,
                resolverFallbackReason = dnsResolution.override?.reason,
                handoverClassification = handoverClassification,
            )

        @Suppress("ReturnCount")
        private suspend fun resolvePreferredVpnDnsPath(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
        ): EncryptedDnsPathCandidate? {
            if (mode != Mode.VPN || dnsResolution.override != null) {
                return null
            }
            val scopeKey = networkScopeKey ?: return null
            val preferred = networkDnsPathPreferenceStore.getPreferredPath(scopeKey)
            if (preferred != null) {
                // Apply diagnostics-recommended path for both encrypted and plain UDP DNS.
                // When DNS is plain UDP (system default) and diagnostics detected tampering,
                // this proactively switches to the recommended encrypted resolver on VPN start
                // instead of waiting for 2 consecutive DNS failures via the failover controller.
                return preferred
            }
            // Cold start: no diagnostic has ever run on this network.
            // Do a quick DNS integrity check and switch to encrypted DNS if tampering detected.
            return startupDnsProbe.probeIfTampered(dnsResolution.activeDns.mode)
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
    if (preferredPath != null) {
        return VpnDnsSelection(
            activeDns = preferredPath.toActiveDnsSettings(),
            preferredPath = preferredPath,
        )
    }
    val rememberedDns = rememberedVpnDnsPolicy?.toActiveDnsSettings()
    return VpnDnsSelection(
        activeDns = rememberedDns ?: baseDns,
        rememberedVpnDnsPolicy = rememberedVpnDnsPolicy,
    )
}

internal fun buildConnectionPolicySignature(
    mode: Mode,
    proxyPreferences: RipDpiProxyPreferences,
    activeDns: ActiveDnsSettings,
    resolverFallbackReason: String?,
    matchedPolicy: RememberedNetworkPolicyEntity?,
): String =
    listOf(
        mode.preferenceValue,
        stripRipDpiRuntimeContext(proxyPreferences.toNativeConfigJson()),
        activeDns.mode,
        activeDns.providerId,
        activeDns.dnsIp,
        activeDns.encryptedDnsProtocol,
        activeDns.encryptedDnsHost,
        activeDns.encryptedDnsPort.toString(),
        activeDns.encryptedDnsTlsServerName,
        activeDns.encryptedDnsBootstrapIps.joinToString(","),
        activeDns.encryptedDnsDohUrl,
        activeDns.encryptedDnsDnscryptProviderName,
        activeDns.encryptedDnsDnscryptPublicKey,
        resolverFallbackReason.orEmpty(),
        matchedPolicy?.id?.toString().orEmpty(),
    ).joinToString("|").encodeSha256()

private const val HexRadix = 16
private const val HexNibbleShift = 4

private fun String.encodeSha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() shr HexNibbleShift) and 0xF).toString(HexRadix))
            append((byte.toInt() and 0xF).toString(HexRadix))
        }
    }
}

/**
 * Returns the absolute path to the protect socket file when in VPN mode, or null otherwise.
 * The file exists only while [VpnProtectSocketServer] is running (i.e. VPN service is active).
 */
private fun resolveVpnProtectPath(
    context: Context,
    mode: Mode,
): String? {
    if (mode != Mode.VPN) return null
    val file = File(context.filesDir, "protect_path")
    return file.absolutePath
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionPolicyResolverModule {
    @Binds
    @Singleton
    abstract fun bindConnectionPolicyResolver(resolver: DefaultConnectionPolicyResolver): ConnectionPolicyResolver
}
