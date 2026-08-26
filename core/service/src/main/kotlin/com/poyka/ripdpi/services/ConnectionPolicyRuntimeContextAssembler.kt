package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiDirectPathCapability
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRuntimeContext
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.core.withDestinationRoutingPolicy
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DirectPolicyEnvironment
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import com.poyka.ripdpi.data.isRuntimeUsableDirectPolicy
import com.poyka.ripdpi.proto.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        private val rootHelperManager: RootHelperManager,
        private val environmentDetector: EnvironmentDetector,
    ) {
        fun hostAutolearnStorePath(): String = resolveHostAutolearnStorePath(context)

        fun protectPath(mode: Mode): String? {
            if (mode != Mode.VPN) return null
            return File(context.filesDir, "protect_path").absolutePath
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

        fun baselinePreferences(
            settings: AppSettings,
            hostAutolearnStorePath: String,
            networkScopeKey: String?,
            runtimeContext: RipDpiRuntimeContext?,
            destinationRouting: DestinationRoutingPolicy,
            awg: AwgActivationRequest? = null,
        ): RipDpiProxyPreferences {
            val geoPaths = resolveGeoDatabasePaths(context)
            return if (settings.enableCmdSettings) {
                val commandLine =
                    RipDpiProxyCmdPreferences(
                        settings.cmdArgs,
                        hostAutolearnStorePath = hostAutolearnStorePath,
                        destinationRouting = destinationRouting,
                        geoipDbPath = geoPaths.geoipDbPath,
                        geositeDbPath = geoPaths.geositeDbPath,
                        runtimeContext = runtimeContext,
                    )
                commandLine
            } else {
                RipDpiProxyUIPreferences.fromSettings(
                    settings,
                    hostAutolearnStorePath,
                    networkScopeKey,
                    runtimeContext,
                    rootMode = settings.rootModeEnabled,
                    rootHelperSocketPath = rootHelperManager.socketPath,
                    environmentKind = environmentDetector.kind,
                    destinationRouting = destinationRouting,
                    geoipDbPath = geoPaths.geoipDbPath,
                    geositeDbPath = geoPaths.geositeDbPath,
                    awg = awg,
                )
            }
        }

        fun rememberedPreferences(
            configJson: String,
            hostAutolearnStorePath: String,
            networkScopeKey: String?,
            runtimeContext: RipDpiRuntimeContext?,
            settings: AppSettings,
            destinationRouting: DestinationRoutingPolicy,
            awg: AwgActivationRequest? = null,
        ): RipDpiProxyPreferences {
            val geoPaths = resolveGeoDatabasePaths(context)
            val remembered =
                com.poyka.ripdpi.core.RipDpiProxyJsonPreferences(
                    configJson = configJson,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    networkScopeKey = networkScopeKey,
                    runtimeContext = runtimeContext,
                    rootMode = settings.rootModeEnabled,
                    rootHelperSocketPath = rootHelperManager.socketPath,
                    environmentKind = environmentDetector.kind,
                    geoipDbPath = geoPaths.geoipDbPath,
                    geositeDbPath = geoPaths.geositeDbPath,
                    awg = awg,
                )
            val rememberedPreferences =
                checkNotNull(decodeRipDpiProxyUiPreferences(remembered.toNativeConfigJson())) {
                    "Remembered proxy policy cannot be overlaid with canonical destination routing"
                }
            val currentHostAutolearn =
                RipDpiProxyUIPreferences
                    .fromSettings(
                        settings = settings,
                        hostAutolearnStorePath = hostAutolearnStorePath,
                        networkScopeKey = networkScopeKey,
                    ).hostAutolearn
            return rememberedPreferences
                .withSessionOverrides(hostAutolearn = currentHostAutolearn)
                .withDestinationRoutingPolicy(destinationRouting, awgOverride = awg)
        }
    }
