package com.poyka.ripdpi.services

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.applyStrategyToSettings
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.toRipDpiMorphPolicy
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.core.withoutPacketStrategies
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DirectPolicyEnvironment
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import com.poyka.ripdpi.data.isRuntimeUsableDirectPolicy
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.lua.LuaAssetManager
import com.poyka.ripdpi.services.lua.relativizeLuaScriptPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ConnectionPolicyRuntimeContextAssembler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val antiCorrelationRoutingPolicy: AntiCorrelationRoutingPolicy,
        private val packetBackendRuntime: PacketBackendRuntime,
        private val environmentDetector: EnvironmentDetector,
        private val proxySessionSecretResolver: ProxySessionSecretResolver,
        private val activeProtectSocketPathProvider: ActiveProtectSocketPathProvider,
        private val strategyPackStateStore: StrategyPackStateStore,
    ) {
        suspend fun captureProtectPath(
            mode: Mode,
            settings: AppSettings,
            forPreflight: Boolean,
        ): String? {
            val path = protectPath(mode, forPreflight)
            if (!forPreflight) {
                packetBackendRuntime.prepare(context, mode, settings)
            }
            return path
        }

        fun hostAutolearnStorePath(): String = resolveHostAutolearnStorePath(context)

        private fun protectPath(
            mode: Mode,
            forPreflight: Boolean = false,
        ): String? {
            if (mode != Mode.VPN) return null
            val path = activeProtectSocketPathProvider.current()
            return if (forPreflight) path else checkNotNull(path) { "VPN protect endpoint is not active" }
        }

        suspend fun directPathCapabilities(networkScopeKey: String?): List<RipDpiDirectPathCapability> =
            if (networkScopeKey == null) {
                emptyList()
            } else {
                val now = System.currentTimeMillis()
                val environment = currentDirectPolicyEnvironment()
                serverCapabilityStore
                    .directPathCapabilitiesForFingerprint(networkScopeKey)
                    .filter { record ->
                        record.transportPolicyEnvelope == null ||
                            record.isRuntimeUsableDirectPolicy(now, environment)
                    }.map { record ->
                        val transportPolicyEnvelope = record.effectiveTransportPolicyEnvelope()
                        RipDpiDirectPathCapability(
                            authority = record.authority,
                            quicUsable = record.quicUsable,
                            udpUsable = record.udpUsable,
                            fallbackRequired = record.fallbackRequired,
                            repeatedHandshakeFailureClass = record.repeatedHandshakeFailureClass,
                            transportPolicyVersion = transportPolicyEnvelope.version,
                            ipSetDigest = transportPolicyEnvelope.ipSetDigest,
                            dnsClassification = transportPolicyEnvelope.dnsClassification,
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

        /**
         * Current network environment used to revalidate cached direct-mode
         * policies (ASN / ECH change).  The HTTPS-RR / SVCB TTL trigger is
         * self-contained in the stored record, so it is already live; ASN and
         * per-host ECH have no reliable source on this connection hot path yet,
         * so they stay unknown (a safe no-op that never wrongly drops a policy).
         * TODO(author): populate currentAsn / echCapable once a hot-path
         * network-metadata source lands (epic: wire orchestrator to production).
         */
        private fun currentDirectPolicyEnvironment(): DirectPolicyEnvironment = DirectPolicyEnvironment()

        suspend fun preferredEdges(
            settings: AppSettings,
            networkScopeKey: String?,
        ): Map<String, List<PreferredEdgeCandidate>> =
            antiCorrelationRoutingPolicy.apply(
                settings = settings,
                preferredEdges =
                    networkScopeKey
                        ?.let { fingerprintHash ->
                            networkEdgePreferenceStore.getPreferredEdgesForRuntime(fingerprintHash)
                        }.orEmpty(),
            )

        fun runtimeContext(
            activeDns: ActiveDnsSettings,
            protectPath: String?,
            preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
            directPathCapabilities: List<RipDpiDirectPathCapability>,
        ): RipDpiRuntimeContext? {
            val dnsRuntimeContext = activeDns.toRipDpiRuntimeContext()
            val morphPolicy =
                strategyPackStateStore.state.value.morphPolicy
                    ?.toRipDpiMorphPolicy()
            return when {
                protectPath == null &&
                    dnsRuntimeContext == null &&
                    preferredEdges.isEmpty() &&
                    directPathCapabilities.isEmpty() &&
                    morphPolicy == null -> {
                    null
                }

                dnsRuntimeContext == null -> {
                    RipDpiRuntimeContext(
                        protectPath = protectPath,
                        preferredEdges = preferredEdges,
                        directPathCapabilities = directPathCapabilities,
                        morphPolicy = morphPolicy,
                    )
                }

                else -> {
                    dnsRuntimeContext.copy(
                        protectPath = protectPath,
                        preferredEdges = preferredEdges,
                        directPathCapabilities = directPathCapabilities,
                        morphPolicy = morphPolicy,
                    )
                }
            }
        }

        private suspend fun withLuaSocketContext(
            runtimeContext: RipDpiRuntimeContext?,
            settings: AppSettings,
        ): RipDpiRuntimeContext? {
            val configuredYaml =
                settings.strategyChainYaml.takeIf {
                    it.isNotBlank() &&
                        !packetBackendRuntime.ownsPacketStrategies
                }
            // Both Lua owners need the extracted files before runtime setup.
            val luaScriptBaseDir =
                configuredYaml?.let {
                    withContext(Dispatchers.IO) {
                        LuaAssetManager.ensureExtracted(context).toAbsolutePath().toString()
                    }
                }
            val yaml = configuredYaml.takeIf { packetBackendRuntime.socketPath == null }
            if (runtimeContext == null && yaml == null) return null
            val jailedYaml =
                if (yaml != null && luaScriptBaseDir != null) {
                    withContext(Dispatchers.IO) { relativizeLuaScriptPaths(yaml, luaScriptBaseDir) }
                } else {
                    yaml
                }
            return (runtimeContext ?: RipDpiRuntimeContext()).copy(
                strategyChainYaml = jailedYaml,
                luaScriptBaseDir = luaScriptBaseDir.takeIf { yaml != null },
            )
        }

        suspend fun baselinePreferences(
            settings: AppSettings,
            hostAutolearnStorePath: String,
            networkScopeKey: String?,
            runtimeContext: RipDpiRuntimeContext?,
            destinationRouting: DestinationRoutingPolicy,
            awg: AwgActivationRequest? = null,
        ): RipDpiProxyPreferences {
            val geoPaths = resolveGeoDatabasePaths(context)
            val effectiveRuntimeContext = withLuaSocketContext(runtimeContext, settings)
            return if (settings.enableCmdSettings && !packetBackendRuntime.ownsPacketStrategies) {
                RipDpiProxyCmdPreferences(
                    settings.cmdArgs,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    destinationRouting = destinationRouting,
                    geoipDbPath = geoPaths.geoipDbPath,
                    geositeDbPath = geoPaths.geositeDbPath,
                    // The user's explicit CLI strategy is applied as written, without pack morphing.
                    runtimeContext = effectiveRuntimeContext?.copy(morphPolicy = null),
                )
            } else {
                RipDpiProxyUIPreferences
                    .fromSettings(
                        settings,
                        hostAutolearnStorePath,
                        networkScopeKey,
                        effectiveRuntimeContext,
                        rootMode = packetBackendRuntime.allowsExternalOperations(settings),
                        rootHelperSocketPath = packetBackendRuntime.socketPath,
                        environmentKind = environmentDetector.kind,
                        destinationRouting = destinationRouting,
                        geoipDbPath = geoPaths.geoipDbPath,
                        geositeDbPath = geoPaths.geositeDbPath,
                        awg = awg,
                        workerBearer = proxySessionSecretResolver.currentBearer(settings),
                    ).let { preferences ->
                        if (packetBackendRuntime.ownsPacketStrategies) {
                            preferences.withoutPacketStrategies()
                        } else {
                            preferences
                        }
                    }
            }
        }

        /**
         * The current settings with only the remembered policy's measured strategy lanes laid over them, the same
         * overlay a recommendation uses when it is applied. Every other setting stays as the user has it now. Null
         * when the policy has no lane this overlay can replay; the caller then uses the baseline.
         */
        suspend fun rememberedPreferences(
            policy: RememberedNetworkPolicyEntity,
            hostAutolearnStorePath: String,
            networkScopeKey: String?,
            runtimeContext: RipDpiRuntimeContext?,
            settings: AppSettings,
            destinationRouting: DestinationRoutingPolicy,
            awg: AwgActivationRequest? = null,
        ): RipDpiProxyPreferences? {
            // A lane with a winning family was measured; the other lane keeps the current settings.
            val applyTcp = policy.winningTcpStrategyFamily != null
            val applyQuic = policy.winningQuicStrategyFamily != null
            if (!applyTcp && !applyQuic) return notReplayable(policy, "no_measured_lane")
            val remembered =
                checkNotNull(
                    decodeRipDpiProxyUiPreferences(
                        com.poyka.ripdpi.core
                            .RipDpiProxyJsonPreferences(policy.proxyConfigJson)
                            .toNativeConfigJson(),
                    ),
                ) { "Remembered proxy policy is not a UI config" }
            // applyStrategyToSettings cannot carry these TCP options into the settings.
            val tcpOptionsLost =
                applyTcp && (remembered.chains.tcpRotation != null || remembered.chains.payloadDisable.isNotEmpty())
            return if (tcpOptionsLost) {
                notReplayable(policy, "tcp_rotation_or_payload_disable")
            } else {
                baselinePreferences(
                    settings = remembered.applyStrategyToSettings(settings, applyTcp, applyQuic),
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    networkScopeKey = networkScopeKey,
                    runtimeContext = runtimeContext,
                    destinationRouting = destinationRouting,
                    awg = awg,
                )
            }
        }

        private fun notReplayable(
            policy: RememberedNetworkPolicyEntity,
            reason: String,
        ): Nothing? {
            Logger.i { "Remembered policy ${policy.id} not replayable: $reason; using baseline" }
            return null
        }
    }
