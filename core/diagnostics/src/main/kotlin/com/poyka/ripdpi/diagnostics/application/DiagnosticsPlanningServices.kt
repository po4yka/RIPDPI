package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PreferredEdgeTransportQuic
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.PreferredEdgeTransportThroughput
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ProbeFamily
import com.poyka.ripdpi.diagnostics.domain.ProbeTask
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val DefaultDiagnosticsProxyPort = 1080

internal interface DiagnosticsIntentResolver {
    suspend fun resolve(
        profileId: String,
        pathMode: ScanPathMode,
    ): DiagnosticsIntent
}

internal interface ScanContextCollector {
    suspend fun collect(intent: DiagnosticsIntent): ScanContext
}

internal interface DiagnosticsPlanner {
    fun plan(
        intent: DiagnosticsIntent,
        context: ScanContext,
    ): ScanPlan
}

internal interface EngineRequestEncoder {
    fun encode(plan: ScanPlan): EngineScanRequestWire
}

@Singleton
internal class DefaultDiagnosticsIntentResolver
    @Inject
    constructor(
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val appSettingsRepository: AppSettingsRepository,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsIntentResolver {
        override suspend fun resolve(
            profileId: String,
            pathMode: ScanPathMode,
        ): DiagnosticsIntent {
            val profile =
                requireNotNull(profileCatalog.getProfile(profileId)) {
                    "Unknown diagnostics profile: $profileId"
                }
            val settings = appSettingsRepository.snapshot()
            val spec = json.decodeProfileSpecWire(profile.requestJson)
            val executionPolicy = spec.normalizedExecutionPolicy()
            return DiagnosticsIntent(
                profileId = spec.profileId,
                displayName = spec.displayName,
                settings = settings,
                kind = spec.kind,
                family = spec.family,
                regionTag = spec.regionTag,
                executionPolicy =
                    ExecutionPolicy(
                        manualOnly = executionPolicy.manualOnly,
                        allowBackground = executionPolicy.allowBackground,
                        requiresRawPath = executionPolicy.requiresRawPath,
                        probePersistencePolicy = executionPolicy.normalizedProbePersistencePolicy().toDomainPolicy(),
                    ),
                packRefs = spec.packRefs,
                domainTargets = spec.domainTargets,
                dnsTargets = spec.dnsTargets,
                tcpTargets = spec.tcpTargets,
                quicTargets = spec.quicTargets,
                serviceTargets = spec.serviceTargets,
                circumventionTargets = spec.circumventionTargets,
                throughputTargets = spec.throughputTargets,
                whitelistSni = spec.whitelistSni,
                telegramTarget = spec.telegramTarget,
                strategyProbe = spec.strategyProbe,
                strategyProbeTargetCohorts =
                    spec.strategyProbeTargetCohorts.map { cohort ->
                        StrategyProbeTargetCohortSpec(
                            id = cohort.id,
                            label = cohort.label,
                            domainTargets = cohort.domainTargets,
                            quicTargets = cohort.quicTargets,
                        )
                    },
                requestedPathMode = pathMode,
            )
        }
    }

@Singleton
internal class DefaultScanContextCollector
    @Inject
    constructor(
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val nativeNetworkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val networkEdgePreferenceStore: NetworkEdgePreferenceStore,
        private val serviceStateStore: ServiceStateStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : ScanContextCollector {
        override suspend fun collect(intent: DiagnosticsIntent): ScanContext {
            val networkFingerprint = networkFingerprintProvider.capture()
            val preferredDnsPath =
                networkFingerprint
                    ?.scopeKey()
                    ?.let { fingerprintHash -> networkDnsPathPreferenceStore.getPreferredPath(fingerprintHash) }
            val preferredEdges =
                networkFingerprint
                    ?.scopeKey()
                    ?.let { fingerprintHash -> networkEdgePreferenceStore.getPreferredEdgesForRuntime(fingerprintHash) }
                    .orEmpty()
            val contextSnapshot = diagnosticsContextProvider.captureContext()
            val profile = profileCatalog.getProfile(intent.profileId)
            val pathMode =
                if (intent.executionPolicy.requiresRawPath) {
                    ScanPathMode.RAW_PATH
                } else {
                    intent.requestedPathMode
                }
            val serviceStatus = serviceStateStore.status.value
            val rawSnapshot = nativeNetworkSnapshotProvider.capture()
            // When transport is "none" and the VPN service is configured but halted, the
            // physical network may still be present — the tunnel went down, not the radio.
            // Annotate the snapshot so the native probe can emit "vpn_tunnel_down" instead of
            // "network_unavailable", which would otherwise abort the diagnostic scan prematurely.
            val vpnServiceWasActive =
                rawSnapshot.transport == "none" &&
                    serviceStatus.second == Mode.VPN &&
                    serviceStatus.first == AppStatus.Halted
            val networkSnapshot =
                if (vpnServiceWasActive) rawSnapshot.copy(vpnServiceWasActive = true) else rawSnapshot
            return ScanContext(
                settings = intent.settings,
                pathMode = pathMode,
                networkFingerprint = networkFingerprint,
                preferredDnsPath = preferredDnsPath,
                preferredEdges = preferredEdges,
                networkSnapshot = networkSnapshot,
                serviceMode = serviceStatus.second.name,
                contextSnapshot = contextSnapshot,
                approachSnapshot = createStoredApproachSnapshot(json, intent.settings, profile, contextSnapshot),
            )
        }
    }

@Singleton
internal class DefaultDiagnosticsPlanner
    @Inject
    constructor() : DiagnosticsPlanner {
        override fun plan(
            intent: DiagnosticsIntent,
            context: ScanContext,
        ): ScanPlan {
            val dnsTargets =
                ConnectivityDnsTargetPlanner.expandTargets(
                    targets = intent.dnsTargets,
                    activeDns = intent.settings.activeDnsSettings(),
                    preferredPath = context.preferredDnsPath,
                )
            val domainTargets =
                intent.domainTargets.map { target ->
                    target.withPreferredEdges(
                        context.preferredEdges[target.host.lowercase()].orEmpty(),
                        PreferredEdgeTransportTcp,
                    )
                }
            val quicTargets =
                intent.quicTargets.map { target ->
                    target.withPreferredEdges(
                        context.preferredEdges[target.host.lowercase()].orEmpty(),
                        PreferredEdgeTransportQuic,
                    )
                }
            val throughputTargets =
                intent.throughputTargets.map { target ->
                    target.hostFromUrl()?.let { host ->
                        target.withPreferredEdges(
                            context.preferredEdges[host.lowercase()].orEmpty(),
                            PreferredEdgeTransportThroughput,
                        )
                    } ?: target
                }
            val probeTasks =
                buildList {
                    dnsTargets.forEach { add(ProbeTask(ProbeFamily.DNS, it.domain, it.domain)) }
                    domainTargets.forEach { add(ProbeTask(ProbeFamily.WEB, it.host, it.host)) }
                    quicTargets.forEach { add(ProbeTask(ProbeFamily.QUIC, it.host, it.host)) }
                    intent.tcpTargets.forEach { add(ProbeTask(ProbeFamily.TCP, it.id, it.provider)) }
                    intent.serviceTargets.forEach { add(ProbeTask(ProbeFamily.SERVICE, it.id, it.service)) }
                    intent.circumventionTargets.forEach { add(ProbeTask(ProbeFamily.CIRCUMVENTION, it.id, it.tool)) }
                    intent.telegramTarget?.let { add(ProbeTask(ProbeFamily.TELEGRAM, "telegram", "Telegram")) }
                    throughputTargets.forEach { add(ProbeTask(ProbeFamily.THROUGHPUT, it.id, it.label)) }
                }
            return ScanPlan(
                intent = intent,
                context = context,
                proxyHost = intent.settings.proxyHostFor(context.pathMode),
                proxyPort = intent.settings.proxyPortFor(context.pathMode),
                dnsTargets = dnsTargets,
                domainTargets = domainTargets,
                quicTargets = quicTargets,
                throughputTargets = throughputTargets,
                probeTasks = probeTasks,
            )
        }
    }

@Singleton
internal class DefaultEngineRequestEncoder
    @Inject
    constructor() : EngineRequestEncoder {
        override fun encode(plan: ScanPlan): EngineScanRequestWire = plan.toEngineScanRequestWire()
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiagnosticsPlanningModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsIntentResolver(resolver: DefaultDiagnosticsIntentResolver): DiagnosticsIntentResolver

    @Binds
    @Singleton
    abstract fun bindScanContextCollector(collector: DefaultScanContextCollector): ScanContextCollector

    @Binds
    @Singleton
    abstract fun bindDiagnosticsPlanner(planner: DefaultDiagnosticsPlanner): DiagnosticsPlanner

    @Binds
    @Singleton
    abstract fun bindEngineRequestEncoder(encoder: DefaultEngineRequestEncoder): EngineRequestEncoder
}

internal fun com.poyka.ripdpi.proto.AppSettings.proxyHostFor(pathMode: ScanPathMode): String? =
    if (pathMode == ScanPathMode.IN_PATH) {
        proxyIp.ifEmpty { "127.0.0.1" }
    } else {
        null
    }

internal fun com.poyka.ripdpi.proto.AppSettings.proxyPortFor(pathMode: ScanPathMode): Int? =
    if (pathMode == ScanPathMode.IN_PATH) {
        proxyPort.takeIf { it > 0 } ?: DefaultDiagnosticsProxyPort
    } else {
        null
    }

private fun DomainTarget.withPreferredEdges(
    edges: List<com.poyka.ripdpi.data.PreferredEdgeCandidate>,
    transportKind: String,
): DomainTarget {
    val ordered = edges.filter { it.transportKind == transportKind }.map { it.ip }
    return copy(connectIps = ordered, connectIp = ordered.firstOrNull() ?: connectIp)
}

private fun QuicTarget.withPreferredEdges(
    edges: List<com.poyka.ripdpi.data.PreferredEdgeCandidate>,
    transportKind: String,
): QuicTarget {
    val ordered = edges.filter { it.transportKind == transportKind }.map { it.ip }
    return copy(connectIps = ordered, connectIp = ordered.firstOrNull() ?: connectIp)
}

private fun ThroughputTarget.withPreferredEdges(
    edges: List<com.poyka.ripdpi.data.PreferredEdgeCandidate>,
    transportKind: String,
): ThroughputTarget {
    val ordered = edges.filter { it.transportKind == transportKind }.map { it.ip }
    return copy(connectIps = ordered, connectIp = ordered.firstOrNull() ?: connectIp)
}

private fun ThroughputTarget.hostFromUrl(): String? =
    runCatching {
        java.net
            .URI(url)
            .host
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
