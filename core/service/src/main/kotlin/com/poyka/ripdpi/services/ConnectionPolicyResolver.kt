package com.poyka.ripdpi.services

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiConnectionConcurrencyPolicy
import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.toPolicyJson
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
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

/**
 * The connection-policy decision authority. `resolve` merges app settings, DNS
 * configuration, VPN options, and the network-specific remembered policy into
 * a single `ConnectionPolicyResolution`. Production uses
 * `RecoveringConnectionPolicyResolver` around `DefaultConnectionPolicyResolver`. See this module's `README.md`,
 * "Policy memory".
 */
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
        private val rootHelperManager: RootHelperManager,
        private val environmentDetector: EnvironmentDetector,
        private val awgEgressSelectionProvider: AwgEgressSelectionProvider,
    ) : ConnectionPolicyResolver {
        private val dnsSelector =
            ConnectionPolicyDnsSelector(
                networkDnsPathPreferenceStore = networkDnsPathPreferenceStore,
                resolverMappingPolicy = ResolverMappingPolicy(),
                resolverMappingCache = ResolverMappingCache(),
            )
        private val runtimeContextAssembler =
            ConnectionPolicyRuntimeContextAssembler(
                context = context,
                networkEdgePreferenceStore = networkEdgePreferenceStore,
                serverCapabilityStore = serverCapabilityStore,
                antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy,
                rootHelperManager = rootHelperManager,
                environmentDetector = environmentDetector,
            )
        private val rememberedPolicyMatcher = RememberedConnectionPolicyMatcher(rememberedNetworkPolicyStore)
        private val signatureBuilder = ConnectionPolicySignatureBuilder()

        override suspend fun resolve(
            mode: Mode,
            resolverOverride: TemporaryResolverOverride?,
            fingerprint: NetworkFingerprint?,
            handoverClassification: String?,
        ): ConnectionPolicyResolution {
            val baseline = buildBaselineCandidate(mode, resolverOverride, fingerprint)
            val rememberedResolution =
                if (baseline.settings.enableCmdSettings ||
                    !baseline.settings.networkStrategyMemoryEnabled ||
                    baseline.networkScopeKey == null
                ) {
                    null
                } else {
                    buildRememberedResolution(mode, baseline, handoverClassification)
                }
            return rememberedResolution
                ?: buildBaselineResolution(baseline, handoverClassification)
        }

        private suspend fun buildBaselineCandidate(
            mode: Mode,
            resolverOverride: TemporaryResolverOverride?,
            fingerprint: NetworkFingerprint?,
        ): BaselineConnectionPolicy {
            val settings = appSettingsRepository.snapshot()
            rootHelperManager.syncRootMode(context, settings.toSettingsSections().root)
            val dnsResolution = resolveEffectiveDns(settings, resolverOverride)
            val fingerprintSnapshot = fingerprint ?: networkFingerprintProvider.capture()
            val networkScopeKey = fingerprintSnapshot?.scopeKey()
            val directPathCapabilities = runtimeContextAssembler.directPathCapabilities(networkScopeKey)
            val baselineVpnDnsSelection =
                baselineVpnDnsSelection(
                    mode = mode,
                    dnsResolution = dnsResolution,
                    networkScopeKey = networkScopeKey,
                    directPathCapabilities = directPathCapabilities,
                )
            val protectPath = runtimeContextAssembler.protectPath(mode)
            val preferredEdges = runtimeContextAssembler.preferredEdges(settings, networkScopeKey)
            val hostAutolearnStorePath = runtimeContextAssembler.hostAutolearnStorePath()
            val selectedAwgEgress =
                if (mode == Mode.VPN && !settings.enableCmdSettings) {
                    selectedAwgEgress()
                } else {
                    null
                }
            val baselinePreferences =
                baselinePreferences(
                    settings = settings,
                    networkScopeKey = networkScopeKey,
                    directPathCapabilities = directPathCapabilities,
                    baselineVpnDnsSelection = baselineVpnDnsSelection,
                    protectPath = protectPath,
                    preferredEdges = preferredEdges,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    awg = selectedAwgEgress,
                )
            val baselineLaneFamilies =
                rememberedPolicyMatcher.deriveLaneFamilies(
                    proxyPreferences = baselinePreferences,
                    activeDns = baselineVpnDnsSelection.activeDns,
                )
            val baselinePolicy =
                rememberedPolicyMatcher.baselinePolicy(
                    settings = settings,
                    mode = mode,
                    fingerprintSnapshot = fingerprintSnapshot,
                    networkScopeKey = networkScopeKey,
                    baselinePreferences = baselinePreferences,
                    baselineVpnDnsSelection = baselineVpnDnsSelection,
                    baselineLaneFamilies = baselineLaneFamilies,
                )
            return BaselineConnectionPolicy(
                settings = settings,
                dnsResolution = dnsResolution,
                networkScopeKey = networkScopeKey,
                directPathCapabilities = directPathCapabilities,
                baselineVpnDnsSelection = baselineVpnDnsSelection,
                protectPath = protectPath,
                preferredEdges = preferredEdges,
                hostAutolearnStorePath = hostAutolearnStorePath,
                baselinePreferences = baselinePreferences,
                baselinePolicy = baselinePolicy,
                baselinePolicySignature =
                    signatureBuilder.build(
                        mode = mode,
                        proxyPreferences = baselinePreferences,
                        activeDns = baselineVpnDnsSelection.activeDns,
                        resolverFallbackReason = dnsResolution.override?.reason,
                        matchedPolicy = null,
                    ),
            )
        }

        private suspend fun selectedAwgEgress(): AwgActivationRequest? = awgEgressSelectionProvider.selectedAwgEgress()

        private suspend fun baselineVpnDnsSelection(
            mode: Mode,
            dnsResolution: EffectiveDnsResolution,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): VpnDnsSelection =
            dnsSelector.baselineSelection(
                mode = mode,
                dnsResolution = dnsResolution,
                networkScopeKey = networkScopeKey,
                directPathCapabilities = directPathCapabilities,
            )

        private fun baselinePreferences(
            settings: AppSettings,
            networkScopeKey: String?,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
            baselineVpnDnsSelection: VpnDnsSelection,
            protectPath: String?,
            preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
            hostAutolearnStorePath: String,
            awg: AwgActivationRequest?,
        ): RipDpiProxyPreferences =
            runtimeContextAssembler.baselinePreferences(
                settings = settings,
                hostAutolearnStorePath = hostAutolearnStorePath,
                networkScopeKey = networkScopeKey,
                awg = awg,
                runtimeContext =
                    runtimeContextAssembler.runtimeContext(
                        activeDns = baselineVpnDnsSelection.activeDns,
                        protectPath = protectPath,
                        preferredEdges = preferredEdges,
                        directPathCapabilities = directPathCapabilities,
                    ),
            )

        private suspend fun buildRememberedResolution(
            mode: Mode,
            baseline: BaselineConnectionPolicy,
            handoverClassification: String?,
        ): ConnectionPolicyResolution? {
            val matchedPolicy =
                rememberedPolicyMatcher.findValidatedMatch(
                    networkScopeKey = baseline.networkScopeKey.orEmpty(),
                    mode = mode,
                ) ?: return null

            return try {
                val rememberedPolicy = matchedPolicy.toPolicyJson()
                val rememberedLaneFamilies =
                    rememberedPolicyMatcher.deriveLaneFamilies(
                        proxyConfigJson = matchedPolicy.proxyConfigJson,
                        activeDns = baseline.baselineVpnDnsSelection.activeDns,
                    )
                val vpnDnsSelection =
                    dnsSelector.rememberedSelection(
                        mode = mode,
                        baselineSelection = baseline.baselineVpnDnsSelection,
                        rememberedVpnDnsPolicy = rememberedPolicy?.vpnDnsPolicy,
                        resolverOverride = baseline.dnsResolution.override,
                    )
                val effectiveDns = vpnDnsSelection.activeDns
                val baseRuntimeContext =
                    runtimeContextAssembler.runtimeContext(
                        activeDns = effectiveDns,
                        protectPath = baseline.protectPath,
                        preferredEdges = baseline.preferredEdges,
                        directPathCapabilities = baseline.directPathCapabilities,
                    )
                val effectiveRuntimeContext =
                    applyRememberedConnectionConcurrencyPolicy(baseRuntimeContext, rememberedPolicy)
                val proxyPreferences =
                    runtimeContextAssembler.rememberedPreferences(
                        configJson = matchedPolicy.proxyConfigJson,
                        hostAutolearnStorePath = baseline.hostAutolearnStorePath,
                        networkScopeKey = baseline.networkScopeKey,
                        runtimeContext = effectiveRuntimeContext,
                        settings = baseline.settings,
                        awg = baseline.baselinePreferences.awgConfigOrNull(),
                    )
                val appliedPolicy =
                    rememberedPolicyMatcher.appliedPolicy(
                        rememberedPolicy = rememberedPolicy,
                        rememberedLaneFamilies = rememberedLaneFamilies,
                        effectiveDns = effectiveDns,
                    )
                val policySignature =
                    signatureBuilder.build(
                        mode = mode,
                        proxyPreferences = proxyPreferences,
                        activeDns = effectiveDns,
                        resolverFallbackReason = baseline.dnsResolution.override?.reason,
                        matchedPolicy = matchedPolicy,
                    )
                ConnectionPolicyResolution(
                    settings = baseline.settings,
                    proxyPreferences = proxyPreferences,
                    activeDns = effectiveDns,
                    vpnDnsOverride = vpnDnsSelection.rememberedVpnDnsPolicy,
                    matchedNetworkPolicy = matchedPolicy,
                    rememberedPolicyAppliedByExactMatch = true,
                    appliedPolicy = appliedPolicy,
                    networkScopeKey = baseline.networkScopeKey,
                    fingerprintHash = baseline.networkScopeKey,
                    policySignature = policySignature,
                    resolverFallbackReason = baseline.dnsResolution.override?.reason,
                    handoverClassification = handoverClassification,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Logger.w(error) { "Rejected invalid remembered connection policy; using baseline" }
                rememberedNetworkPolicyStore.recordFailure(matchedPolicy)
                null
            }
        }

        private fun applyRememberedConnectionConcurrencyPolicy(
            baseRuntimeContext: RipDpiRuntimeContext?,
            rememberedPolicy: RememberedNetworkPolicyJson?,
        ): RipDpiRuntimeContext? {
            val policy = rememberedPolicy?.connectionConcurrencyPolicy ?: return baseRuntimeContext
            return (baseRuntimeContext ?: RipDpiRuntimeContext()).copy(
                connectionConcurrency =
                    RipDpiConnectionConcurrencyPolicy(
                        selectedProfileId = policy.selectedProfileId,
                        perProfileCaps = policy.perProfileCaps,
                    ),
            )
        }

        private fun buildBaselineResolution(
            baseline: BaselineConnectionPolicy,
            handoverClassification: String?,
        ): ConnectionPolicyResolution =
            ConnectionPolicyResolution(
                settings = baseline.settings,
                proxyPreferences = baseline.baselinePreferences,
                activeDns = baseline.baselineVpnDnsSelection.activeDns,
                vpnDnsOverride = null,
                matchedNetworkPolicy = null,
                rememberedPolicyAppliedByExactMatch = null,
                appliedPolicy = baseline.baselinePolicy,
                networkScopeKey = baseline.networkScopeKey,
                fingerprintHash = baseline.networkScopeKey,
                policySignature = baseline.baselinePolicySignature,
                resolverFallbackReason = baseline.dnsResolution.override?.reason,
                handoverClassification = handoverClassification,
            )

        private data class BaselineConnectionPolicy(
            val settings: AppSettings,
            val dnsResolution: EffectiveDnsResolution,
            val networkScopeKey: String?,
            val directPathCapabilities: List<RipDpiDirectPathCapability>,
            val baselineVpnDnsSelection: VpnDnsSelection,
            val protectPath: String?,
            val preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
            val hostAutolearnStorePath: String,
            val baselinePreferences: RipDpiProxyPreferences,
            val baselinePolicy: RememberedNetworkPolicyJson?,
            val baselinePolicySignature: String,
        )
    }

/** Waits for durable profile mutations before any settings-backed policy snapshot. */
@Singleton
class RecoveringConnectionPolicyResolver
    private constructor(
        private val resolvePolicy:
            suspend (Mode, TemporaryResolverOverride?, NetworkFingerprint?, String?) -> ConnectionPolicyResolution,
        private val profileMutations: ProfileMutationCoordinator,
    ) : ConnectionPolicyResolver {
        @Inject
        constructor(
            delegate: DefaultConnectionPolicyResolver,
            profileMutations: ProfileMutationCoordinator,
        ) : this(delegate::resolve, profileMutations)

        internal constructor(
            delegate: ConnectionPolicyResolver,
            profileMutations: ProfileMutationCoordinator,
        ) : this(delegate::resolve, profileMutations)

        override suspend fun resolve(
            mode: Mode,
            resolverOverride: TemporaryResolverOverride?,
            fingerprint: NetworkFingerprint?,
            handoverClassification: String?,
        ): ConnectionPolicyResolution {
            profileMutations.recover()
            return resolvePolicy(mode, resolverOverride, fingerprint, handoverClassification)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionPolicyResolverModule {
    @Binds
    @Singleton
    abstract fun bindConnectionPolicyResolver(resolver: RecoveringConnectionPolicyResolver): ConnectionPolicyResolver
}
