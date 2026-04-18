package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryProjector
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.LogcatSnapshot
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.TransportFailureKind
import com.poyka.ripdpi.diagnostics.toRedactedSummary
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named

private const val SuccessRatePercentScale = 100
private const val TlsErrorSampleLimit = 5
private const val AcceptanceMatrixCoverageMin = 75
private const val AcceptanceWinnerCoverageMin = 60
private const val RecommendedLatencyBudgetMs = 250L
private const val InstabilityRetryBudget = 2L
private val KnownRuntimeCapabilityIds =
    setOf(
        "ttl_write",
        "raw_tcp_fake_send",
        "raw_udp_fragmentation",
        "replacement_socket",
        "root_helper_available",
        "vpn_protect_callback",
        "network_binding",
    )

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        private val projector: DiagnosticsSummaryProjector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private val jsonEntryBuilder = DiagnosticsArchiveJsonEntryBuilder(redactor, projector, json)
        private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json)

        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            developerAnalytics: DeveloperAnalyticsPayload = DeveloperAnalyticsPayload(),
        ): List<DiagnosticsArchiveEntry> {
            val snapshotPayload = jsonEntryBuilder.buildSnapshotPayload(selection)
            val contextPayload = jsonEntryBuilder.buildContextPayload(selection)
            val sectionStatuses = buildSectionStatuses(selection)
            val completeness =
                buildCompleteness(
                    selection = selection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                )
            val compositeEntries =
                if (selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE) {
                    jsonEntryBuilder.buildCompositeEntries(selection)
                } else {
                    emptyList()
                }
            val baseEntries =
                buildCoreEntries(
                    target = target,
                    selection = selection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                    completeness = completeness,
                    compositeEntries = compositeEntries,
                    developerAnalytics = developerAnalytics,
                )
            return baseEntries +
                DiagnosticsArchiveEntry(
                    name = "integrity.json",
                    bytes =
                        json
                            .encodeToString(
                                DiagnosticsArchiveIntegrityPayload.serializer(),
                                buildIntegrityPayload(target, baseEntries),
                            ).toByteArray(),
                )
        }

        private fun buildCoreEntries(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
            snapshotPayload: DiagnosticsArchiveSnapshotPayload,
            contextPayload: DiagnosticsArchiveContextPayload,
            completeness: DiagnosticsArchiveCompletenessPayload,
            compositeEntries: List<DiagnosticsArchiveEntry>,
            developerAnalytics: DeveloperAnalyticsPayload,
        ): List<DiagnosticsArchiveEntry> =
            buildList {
                addAll(
                    jsonEntryBuilder.buildJsonEntries(
                        target = target,
                        selection = selection,
                        sectionStatuses = sectionStatuses,
                        snapshotPayload = snapshotPayload,
                        contextPayload = contextPayload,
                        completeness = completeness,
                        compositeEntries = compositeEntries,
                        developerAnalytics = developerAnalytics,
                    ),
                )
                addAll(
                    csvEntryBuilder.buildCsvEntries(
                        selection = selection,
                    ),
                )
            }

        internal fun buildSummary(
            createdAt: Long,
            selection: DiagnosticsArchiveSelection,
        ): String = jsonEntryBuilder.buildSummary(createdAt, selection)

        internal fun buildProbeResultsCsv(results: List<com.poyka.ripdpi.data.diagnostics.ProbeResultEntity>): String =
            csvEntryBuilder.buildProbeResultsCsv(results)
    }

// ---------------------------------------------------------------------------
// File-level private functions (not part of the class — reduces LargeClass / TooManyFunctions)
// ---------------------------------------------------------------------------

internal fun textEntry(
    name: String,
    content: String,
): DiagnosticsArchiveEntry = DiagnosticsArchiveEntry(name = name, bytes = content.toByteArray())

internal fun buildArchiveProvenance(
    target: DiagnosticsArchiveTarget,
    selection: DiagnosticsArchiveSelection,
): DiagnosticsArchiveProvenancePayload {
    val allEvents = selection.primaryEvents + selection.globalEvents
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val runtimeProvenance =
        DiagnosticsArchiveRuntimeProvenance(
            runtimeId = allEvents.latestCorrelation { it.runtimeId },
            mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode },
            policySignature = allEvents.latestCorrelation { it.policySignature },
            fingerprintHash =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            networkScope =
                selection.payload.telemetry
                    .firstOrNull()
                    ?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash },
            androidVersion = context?.device?.androidVersion,
            apiLevel = context?.device?.apiLevel,
            primaryAbi = context?.device?.primaryAbi,
            locale = context?.device?.locale,
            timezone = context?.device?.timezone,
        )
    return DiagnosticsArchiveProvenancePayload(
        runType = selection.runType,
        homeRunId = selection.homeRunId,
        archiveReason = selection.request.reason,
        requestedAt = selection.request.requestedAt,
        createdAt = target.createdAt,
        requestedSessionId =
            if (selection.runType == DiagnosticsArchiveRunType.SINGLE_SESSION) {
                selection.request.requestedSessionId
            } else {
                null
            },
        selectedSessionId = selection.primarySession?.id,
        bundleSessionIds = selection.homeCompositeOutcome?.bundleSessionIds.orEmpty(),
        sessionSelectionStatus = selection.sessionSelectionStatus,
        triggerMetadata =
            selection.primarySession?.let {
                DiagnosticsArchiveTriggerMetadata(
                    launchOrigin = it.launchOrigin,
                    triggerType = it.triggerType,
                    triggerClassification = it.triggerClassification,
                    triggerOccurredAt = it.triggerOccurredAt,
                )
            },
        buildProvenance = selection.buildProvenance,
        runtimeProvenance = runtimeProvenance,
    )
}

internal fun buildRuntimeConfig(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveRuntimeConfigPayload {
    val context = selection.sessionContextModel ?: selection.latestContextModel
    val snapshot = selection.latestSnapshotModel
    val telemetry = selection.payload.telemetry.firstOrNull()
    val serviceConfig = resolveServiceConfig(context?.service, selection.primarySession?.profileId)
    val resolverConfig = resolveResolverConfig(telemetry)
    val networkConfig = resolveNetworkConfig(snapshot)
    val envConfig = resolveEnvironmentConfig(context?.environment, context?.permissions)
    return DiagnosticsArchiveRuntimeConfigPayload(
        configuredMode = serviceConfig.configuredMode,
        activeMode = serviceConfig.activeMode,
        serviceStatus = serviceConfig.serviceStatus,
        selectedProfileId = serviceConfig.selectedProfileId,
        selectedProfileName = serviceConfig.selectedProfileName,
        configSource = serviceConfig.configSource,
        desyncMethod = serviceConfig.desyncMethod,
        chainSummary = serviceConfig.chainSummary,
        routeGroup = serviceConfig.routeGroup,
        restartCount = serviceConfig.restartCount,
        sessionUptimeMs = serviceConfig.sessionUptimeMs,
        hostAutolearnEnabled = serviceConfig.hostAutolearnEnabled,
        learnedHostCount = serviceConfig.learnedHostCount,
        penalizedHostCount = serviceConfig.penalizedHostCount,
        blockedHostCount = serviceConfig.blockedHostCount,
        lastBlockSignal = serviceConfig.lastBlockSignal,
        lastBlockProvider = serviceConfig.lastBlockProvider,
        lastAutolearnHost = serviceConfig.lastAutolearnHost,
        lastAutolearnGroup = serviceConfig.lastAutolearnGroup,
        lastAutolearnAction = serviceConfig.lastAutolearnAction,
        lastNativeErrorHeadline = serviceConfig.lastNativeErrorHeadline,
        resolverId = resolverConfig.resolverId,
        resolverProtocol = resolverConfig.resolverProtocol,
        resolverEndpoint = resolverConfig.resolverEndpoint,
        resolverLatencyMs = resolverConfig.resolverLatencyMs,
        resolverFallbackActive = resolverConfig.resolverFallbackActive,
        resolverFallbackReason = resolverConfig.resolverFallbackReason,
        networkHandoverClass = resolverConfig.networkHandoverClass,
        transport = networkConfig.transport,
        privateDnsMode = networkConfig.privateDnsMode,
        mtu = networkConfig.mtu,
        networkValidated = networkConfig.networkValidated,
        captivePortalDetected = networkConfig.captivePortalDetected,
        batterySaverState = envConfig.batterySaverState,
        powerSaveModeState = envConfig.powerSaveModeState,
        dataSaverState = envConfig.dataSaverState,
        batteryOptimizationState = envConfig.batteryOptimizationState,
        vpnPermissionState = envConfig.vpnPermissionState,
        notificationPermissionState = envConfig.notificationPermissionState,
        networkMeteredState = envConfig.networkMeteredState,
        roamingState = envConfig.roamingState,
        commandLineSettingsEnabled = selection.appSettings.enableCmdSettings,
        commandLineArgsHash =
            selection.appSettings
                .takeIf { it.enableCmdSettings }
                ?.cmdArgs
                ?.takeIf { it.isNotBlank() }
                ?.let(::sha256Hex),
        effectiveStrategySignature = selection.effectiveStrategySignature,
    )
}

private data class ResolvedServiceConfig(
    val configuredMode: String = "unavailable",
    val activeMode: String = "unavailable",
    val serviceStatus: String = "unavailable",
    val selectedProfileId: String = "unavailable",
    val selectedProfileName: String = "unavailable",
    val configSource: String = "unavailable",
    val desyncMethod: String = "unavailable",
    val chainSummary: String = "unavailable",
    val routeGroup: String = "unavailable",
    val restartCount: Int = 0,
    val sessionUptimeMs: Long? = null,
    val hostAutolearnEnabled: String = "unavailable",
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String = "unavailable",
    val lastBlockProvider: String = "unavailable",
    val lastAutolearnHost: String = "unavailable",
    val lastAutolearnGroup: String = "unavailable",
    val lastAutolearnAction: String = "unavailable",
    val lastNativeErrorHeadline: String = "unavailable",
)

private fun resolveServiceConfig(
    service: ServiceContextModel?,
    fallbackProfileId: String?,
): ResolvedServiceConfig =
    if (service == null) {
        ResolvedServiceConfig(selectedProfileId = fallbackProfileId ?: "unavailable")
    } else {
        ResolvedServiceConfig(
            configuredMode = service.configuredMode,
            activeMode = service.activeMode,
            serviceStatus = service.serviceStatus,
            selectedProfileId = service.selectedProfileId,
            selectedProfileName = service.selectedProfileName,
            configSource = service.configSource,
            desyncMethod = service.desyncMethod,
            chainSummary = service.chainSummary,
            routeGroup = service.routeGroup,
            restartCount = service.restartCount,
            sessionUptimeMs = service.sessionUptimeMs,
            hostAutolearnEnabled = service.hostAutolearnEnabled,
            learnedHostCount = service.learnedHostCount,
            penalizedHostCount = service.penalizedHostCount,
            blockedHostCount = service.blockedHostCount,
            lastBlockSignal = service.lastBlockSignal,
            lastBlockProvider = service.lastBlockProvider,
            lastAutolearnHost = service.lastAutolearnHost,
            lastAutolearnGroup = service.lastAutolearnGroup,
            lastAutolearnAction = service.lastAutolearnAction,
            lastNativeErrorHeadline = service.lastNativeErrorHeadline,
        )
    }

private data class ResolvedResolverConfig(
    val resolverId: String = "unavailable",
    val resolverProtocol: String = "unavailable",
    val resolverEndpoint: String = "unavailable",
    val resolverLatencyMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String = "unavailable",
    val networkHandoverClass: String = "unavailable",
)

private fun resolveResolverConfig(telemetry: TelemetrySampleEntity?): ResolvedResolverConfig =
    if (telemetry == null) {
        ResolvedResolverConfig()
    } else {
        ResolvedResolverConfig(
            resolverId = telemetry.resolverId ?: "unavailable",
            resolverProtocol = telemetry.resolverProtocol ?: "unavailable",
            resolverEndpoint = telemetry.resolverEndpoint ?: "unavailable",
            resolverLatencyMs = telemetry.resolverLatencyMs,
            resolverFallbackActive = telemetry.resolverFallbackActive,
            resolverFallbackReason = telemetry.resolverFallbackReason ?: "unavailable",
            networkHandoverClass = telemetry.networkHandoverClass ?: "unavailable",
        )
    }

private data class ResolvedNetworkConfig(
    val transport: String = "unavailable",
    val privateDnsMode: String = "unavailable",
    val mtu: Int? = null,
    val networkValidated: Boolean? = null,
    val captivePortalDetected: Boolean? = null,
)

private fun resolveNetworkConfig(snapshot: NetworkSnapshotModel?): ResolvedNetworkConfig =
    if (snapshot == null) {
        ResolvedNetworkConfig()
    } else {
        ResolvedNetworkConfig(
            transport = snapshot.transport,
            privateDnsMode = snapshot.privateDnsMode,
            mtu = snapshot.mtu,
            networkValidated = snapshot.networkValidated,
            captivePortalDetected = snapshot.captivePortalDetected,
        )
    }

private data class ResolvedEnvironmentConfig(
    val batterySaverState: String = "unavailable",
    val powerSaveModeState: String = "unavailable",
    val dataSaverState: String = "unavailable",
    val batteryOptimizationState: String = "unavailable",
    val vpnPermissionState: String = "unavailable",
    val notificationPermissionState: String = "unavailable",
    val networkMeteredState: String = "unavailable",
    val roamingState: String = "unavailable",
)

private fun resolveEnvironmentConfig(
    environment: EnvironmentContextModel?,
    permissions: PermissionContextModel?,
): ResolvedEnvironmentConfig =
    ResolvedEnvironmentConfig(
        batterySaverState = environment?.batterySaverState ?: "unavailable",
        powerSaveModeState = environment?.powerSaveModeState ?: "unavailable",
        dataSaverState = permissions?.dataSaverState ?: "unavailable",
        batteryOptimizationState = permissions?.batteryOptimizationState ?: "unavailable",
        vpnPermissionState = permissions?.vpnPermissionState ?: "unavailable",
        notificationPermissionState = permissions?.notificationPermissionState ?: "unavailable",
        networkMeteredState = environment?.networkMeteredState ?: "unavailable",
        roamingState = environment?.roamingState ?: "unavailable",
    )

internal fun buildAnalysis(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveAnalysisPayload {
    val telemetry = selection.payload.telemetry.sortedBy { it.createdAt }
    val failureSamples =
        telemetry.filter {
            !it.failureClass.isNullOrBlank() ||
                !it.lastFailureClass.isNullOrBlank() ||
                !it.lastFallbackAction.isNullOrBlank()
        }
    val failureEvents =
        (selection.primaryEvents + selection.globalEvents)
            .filter {
                it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
            }.sortedBy { it.createdAt }
    val latestTelemetry = selection.payload.telemetry.firstOrNull()
    val strategyProbe = selection.primaryReport?.strategyProbeReport
    val observations = selection.primaryReport?.observations.orEmpty()
    val measurementSnapshot = buildMeasurementSnapshot(selection, strategyProbe, latestTelemetry)
    return DiagnosticsArchiveAnalysisPayload(
        failureEnvelope =
            DiagnosticsArchiveFailureEnvelope(
                firstFailureTimestamp =
                    listOfNotNull(
                        failureSamples.firstOrNull()?.createdAt,
                        failureEvents.firstOrNull()?.createdAt,
                    ).minOrNull(),
                lastFailureTimestamp =
                    listOfNotNull(
                        failureSamples.lastOrNull()?.createdAt,
                        failureEvents.lastOrNull()?.createdAt,
                    ).maxOrNull(),
                latestFailureClass =
                    latestTelemetry?.failureClass
                        ?: latestTelemetry?.lastFailureClass
                        ?: failureEvents.lastOrNull()?.message,
                lastFallbackAction = latestTelemetry?.lastFallbackAction,
                retryCounters =
                    DiagnosticsArchiveRetryCounters(
                        proxyRouteRetryCount = latestTelemetry?.proxyRouteRetryCount ?: 0,
                        tunnelRecoveryRetryCount = latestTelemetry?.tunnelRecoveryRetryCount ?: 0,
                        totalRetryCount = latestTelemetry?.retryCount() ?: 0,
                    ),
                failureClassTransitions =
                    (
                        failureSamples.flatMap { sample ->
                            listOfNotNull(sample.failureClass, sample.lastFailureClass)
                        } +
                            failureEvents.map { event -> "native:${event.source}:${event.message}" }
                    ).distinctConsecutive(),
            ),
        strategyExecutionDetail =
            DiagnosticsArchiveStrategyExecutionDetail(
                suiteId = strategyProbe?.suiteId,
                completionKind = strategyProbe?.completionKind?.name,
                tcpCandidates =
                    strategyProbe
                        ?.tcpCandidates
                        ?.map { candidate ->
                            candidate.toExecutionDetail(
                                lane = "tcp",
                                observations = observations,
                            )
                        }.orEmpty(),
                quicCandidates =
                    strategyProbe
                        ?.quicCandidates
                        ?.map { candidate ->
                            candidate.toExecutionDetail(
                                lane = "quic",
                                observations = observations,
                            )
                        }.orEmpty(),
            ),
        recommendationTrace = buildRecommendationTrace(selection),
        measurementSnapshot = measurementSnapshot,
    )
}

private fun buildMeasurementSnapshot(
    selection: DiagnosticsArchiveSelection,
    strategyProbe: StrategyProbeReport?,
    latestTelemetry: TelemetrySampleEntity?,
): DiagnosticsArchiveMeasurementSnapshot {
    val allCandidates = strategyProbe.allCandidates()
    val recommendedTcp =
        strategyProbe
            ?.tcpCandidates
            ?.firstOrNull { it.id == strategyProbe.recommendation.tcpCandidateId }
    val recommendedQuic =
        strategyProbe
            ?.quicCandidates
            ?.firstOrNull { it.id == strategyProbe.recommendation.quicCandidateId }
    val acceptanceMetrics = buildAcceptanceMetrics(strategyProbe)
    val detectabilityMetrics =
        buildDetectabilityMetrics(
            candidates = allCandidates,
            recommendedTcp = recommendedTcp,
            recommendedQuic = recommendedQuic,
        )
    val capabilitySnapshot = buildCapabilitySnapshot(allCandidates)
    return DiagnosticsArchiveMeasurementSnapshot(
        networkIdentityBucket = resolveNetworkIdentityBucket(selection, latestTelemetry),
        targetBucket = resolveTargetBucket(strategyProbe),
        recommendedTcpEmitterTier = recommendedTcp?.emitterTier?.name,
        recommendedQuicEmitterTier = recommendedQuic?.emitterTier?.name,
        capabilitySnapshot = capabilitySnapshot,
        acceptanceMetrics = acceptanceMetrics,
        detectabilityMetrics = detectabilityMetrics,
        rolloutGateAssessment =
            buildRolloutGateAssessment(
                acceptanceMetrics = acceptanceMetrics,
                detectabilityMetrics = detectabilityMetrics,
                capabilitySnapshot = capabilitySnapshot,
                latestTelemetry = latestTelemetry,
                recommendedLatencyMs =
                    listOfNotNull(recommendedTcp?.averageLatencyMs, recommendedQuic?.averageLatencyMs)
                        .maxOrNull(),
            ),
    )
}

private fun StrategyProbeReport?.allCandidates(): List<StrategyProbeCandidateSummary> =
    if (this == null) {
        emptyList()
    } else {
        tcpCandidates + quicCandidates
    }

private fun resolveNetworkIdentityBucket(
    selection: DiagnosticsArchiveSelection,
    latestTelemetry: TelemetrySampleEntity?,
): String {
    val transport =
        selection.latestSnapshotModel?.transport
            ?: latestTelemetry?.networkType
            ?: "unknown"
    val handoverClass =
        latestTelemetry
            ?.networkHandoverClass
            ?.takeIf { it.isNotBlank() }
            ?: "steady"
    val fingerprint =
        latestTelemetry?.telemetryNetworkFingerprintHash
            ?: (selection.primaryEvents + selection.globalEvents).latestCorrelation { it.fingerprintHash }
            ?: "unavailable"
    return "$transport:$handoverClass:$fingerprint"
}

private fun resolveTargetBucket(strategyProbe: StrategyProbeReport?): String =
    when {
        strategyProbe == null -> {
            "unavailable"
        }

        strategyProbe.pilotBucketLabels.isNotEmpty() -> {
            strategyProbe.pilotBucketLabels.distinct().joinToString("|")
        }

        strategyProbe.targetSelection != null -> {
            "${strategyProbe.targetSelection.cohortId}:${strategyProbe.targetSelection.cohortLabel}"
        }

        else -> {
            "unavailable"
        }
    }

private fun buildAcceptanceMetrics(strategyProbe: StrategyProbeReport?): DiagnosticsArchiveAcceptanceMetrics {
    val assessment = strategyProbe?.auditAssessment
    val coverage = assessment?.coverage
    return DiagnosticsArchiveAcceptanceMetrics(
        matrixCoveragePercent = coverage?.matrixCoveragePercent,
        winnerCoveragePercent = coverage?.winnerCoveragePercent,
        tcpCandidatesPlanned = coverage?.tcpCandidatesPlanned ?: 0,
        tcpCandidatesExecuted = coverage?.tcpCandidatesExecuted ?: 0,
        quicCandidatesPlanned = coverage?.quicCandidatesPlanned ?: 0,
        quicCandidatesExecuted = coverage?.quicCandidatesExecuted ?: 0,
        tcpWinnerSucceededTargets = coverage?.tcpWinnerSucceededTargets ?: 0,
        tcpWinnerTotalTargets = coverage?.tcpWinnerTotalTargets ?: 0,
        quicWinnerSucceededTargets = coverage?.quicWinnerSucceededTargets ?: 0,
        quicWinnerTotalTargets = coverage?.quicWinnerTotalTargets ?: 0,
        confidenceLevel = assessment?.confidence?.level?.name,
        confidenceScore = assessment?.confidence?.score,
    )
}

private fun buildDetectabilityMetrics(
    candidates: List<StrategyProbeCandidateSummary>,
    recommendedTcp: StrategyProbeCandidateSummary?,
    recommendedQuic: StrategyProbeCandidateSummary?,
): DiagnosticsArchiveDetectabilityMetrics {
    val rootedProductionCandidates =
        candidates.count { it.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION }
    val labOnlyCandidates =
        candidates.count { it.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY }
    val downgradedCandidates = candidates.count(StrategyProbeCandidateSummary::emitterDowngraded)
    val exactRootRequiredCandidates = candidates.count(StrategyProbeCandidateSummary::exactEmitterRequiresRoot)
    val capabilitySkippedCandidates =
        candidates.count { candidate ->
            candidate.notes.any(::containsUnavailableCapabilityId) ||
                (candidate.skipped && candidate.rationale.contains("capability", ignoreCase = true)) ||
                candidate.rationale.contains("unavailable", ignoreCase = true)
        }
    val notes =
        buildList {
            if (recommendedTcp?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION) {
                add("recommended_tcp_rooted_emitter")
            }
            if (recommendedQuic?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION) {
                add("recommended_quic_rooted_emitter")
            }
            if (recommendedTcp?.emitterDowngraded == true) {
                add("recommended_tcp_downgraded")
            }
            if (recommendedQuic?.emitterDowngraded == true) {
                add("recommended_quic_downgraded")
            }
            if (labOnlyCandidates > 0) {
                add("lab_only_candidates_present")
            }
            if (capabilitySkippedCandidates > 0) {
                add("capability_skips_present")
            }
        }
    return DiagnosticsArchiveDetectabilityMetrics(
        rootedProductionCandidates = rootedProductionCandidates,
        labOnlyCandidates = labOnlyCandidates,
        downgradedCandidates = downgradedCandidates,
        exactRootRequiredCandidates = exactRootRequiredCandidates,
        capabilitySkippedCandidates = capabilitySkippedCandidates,
        skippedCandidates = candidates.count(StrategyProbeCandidateSummary::skipped),
        recommendedUsesRootedEmitter =
            recommendedTcp?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION ||
                recommendedQuic?.emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION,
        recommendedWasDowngraded =
            recommendedTcp?.emitterDowngraded == true || recommendedQuic?.emitterDowngraded == true,
        notes = notes,
    )
}

private fun buildCapabilitySnapshot(
    candidates: List<StrategyProbeCandidateSummary>,
): DiagnosticsArchiveCapabilitySnapshot {
    val evidence =
        candidates
            .flatMap { candidate ->
                candidate.notes.filter(::containsUnavailableCapabilityId)
            }.distinct()
            .sorted()
    val inferredUnavailableCapabilities =
        evidence
            .flatMap(::extractCapabilityIds)
            .distinct()
            .sorted()
    return DiagnosticsArchiveCapabilitySnapshot(
        inferredUnavailableCapabilities = inferredUnavailableCapabilities,
        evidence = evidence,
    )
}

private fun buildRolloutGateAssessment(
    acceptanceMetrics: DiagnosticsArchiveAcceptanceMetrics,
    detectabilityMetrics: DiagnosticsArchiveDetectabilityMetrics,
    capabilitySnapshot: DiagnosticsArchiveCapabilitySnapshot,
    latestTelemetry: TelemetrySampleEntity?,
    recommendedLatencyMs: Long?,
): DiagnosticsArchiveRolloutGateAssessment {
    val results =
        listOf(
            DiagnosticsArchiveRolloutGateResult(
                id = "acceptance",
                passed =
                    (acceptanceMetrics.matrixCoveragePercent ?: 0) >= AcceptanceMatrixCoverageMin &&
                        (acceptanceMetrics.winnerCoveragePercent ?: 0) >= AcceptanceWinnerCoverageMin,
                threshold =
                    "matrixCoveragePercent >= $AcceptanceMatrixCoverageMin and " +
                        "winnerCoveragePercent >= $AcceptanceWinnerCoverageMin",
                actual =
                    "matrix=${acceptanceMetrics.matrixCoveragePercent ?: "unknown"};" +
                        "winner=${acceptanceMetrics.winnerCoveragePercent ?: "unknown"}",
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "latency_budget",
                passed = recommendedLatencyMs != null && recommendedLatencyMs <= RecommendedLatencyBudgetMs,
                threshold = "recommendedLatencyMs <= $RecommendedLatencyBudgetMs",
                actual = recommendedLatencyMs?.toString() ?: "unknown",
                rationale =
                    if (recommendedLatencyMs == null) {
                        "Recommended candidate latency was unavailable."
                    } else {
                        null
                    },
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "instability_budget",
                passed = (latestTelemetry?.retryCount() ?: Long.MAX_VALUE) <= InstabilityRetryBudget,
                threshold = "retryCount <= $InstabilityRetryBudget",
                actual = latestTelemetry?.retryCount()?.toString() ?: "unknown",
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "detectability_budget",
                passed =
                    !detectabilityMetrics.recommendedUsesRootedEmitter &&
                        !detectabilityMetrics.recommendedWasDowngraded,
                threshold = "recommended winner stays non-root and non-downgraded",
                actual =
                    "rooted=${detectabilityMetrics.recommendedUsesRootedEmitter};" +
                        "downgraded=${detectabilityMetrics.recommendedWasDowngraded}",
            ),
            DiagnosticsArchiveRolloutGateResult(
                id = "android_compat_budget",
                passed = capabilitySnapshot.inferredUnavailableCapabilities.isEmpty(),
                threshold = "no inferred missing runtime capabilities",
                actual =
                    capabilitySnapshot.inferredUnavailableCapabilities
                        .takeIf(List<String>::isNotEmpty)
                        ?.joinToString("|")
                        ?: "none",
            ),
        )
    return DiagnosticsArchiveRolloutGateAssessment(
        overallPassed = results.all(DiagnosticsArchiveRolloutGateResult::passed),
        results = results,
    )
}

private fun containsUnavailableCapabilityId(text: String): Boolean = extractCapabilityIds(text).isNotEmpty()

private fun extractCapabilityIds(text: String): List<String> =
    KnownRuntimeCapabilityIds.filter { capability ->
        text.contains("($capability)") || text.contains("$capability unavailable")
    }

private fun buildRecommendationEvidence(
    strategyProbe: StrategyProbeReport?,
    resolver: ResolverRecommendation?,
    assessment: StrategyProbeAuditAssessment?,
    selection: DiagnosticsArchiveSelection,
): List<String> {
    val recommendation = strategyProbe?.recommendation
    return buildList {
        recommendation?.rationale?.takeIf(String::isNotBlank)?.let { add(it) }
        resolver?.rationale?.takeIf(String::isNotBlank)?.let { add("resolver:$it") }
        assessment
            ?.confidence
            ?.rationale
            ?.takeIf(String::isNotBlank)
            ?.let { add(it) }
        addAll(assessment?.confidence?.warnings.orEmpty())
        strategyProbe?.targetSelection?.cohortLabel?.let { add("targetCohort=$it") }
        recommendation?.tcpCandidateLabel?.let { add("tcpWinner=$it") }
        recommendation?.quicCandidateLabel?.let { add("quicWinner=$it") }
        val allTcpCandidatesFailed =
            strategyProbe != null &&
                strategyProbe.tcpCandidates.isNotEmpty() &&
                strategyProbe.tcpCandidates.none { it.succeededTargets > 0 && !it.skipped }
        if (allTcpCandidatesFailed) {
            add("strategy_adequacy:all_tcp_candidates_failed")
        }
        val blockedBootstraps =
            selection.primaryResults
                .filter {
                    it.probeType == "tcp_fat_header" &&
                        it.outcome in setOf("tcp_reset", "tcp_16kb_blocked")
                }.map { it.target }
        if (blockedBootstraps.isNotEmpty()) {
            add("blocked_bootstrap_ips:${blockedBootstraps.joinToString(",")}")
        }
    }
}

private fun buildRecommendationTrace(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveRecommendationTrace? {
    val strategyProbe = selection.primaryReport?.strategyProbeReport
    val resolver = selection.primaryReport?.resolverRecommendation
    if (strategyProbe == null && resolver == null) {
        return null
    }
    val assessment = strategyProbe?.auditAssessment
    val recommendation = strategyProbe?.recommendation
    val evidence = buildRecommendationEvidence(strategyProbe, resolver, assessment, selection)
    return DiagnosticsArchiveRecommendationTrace(
        selectedApproach =
            selection.selectedApproachSummary?.displayName
                ?: recommendation?.tcpCandidateLabel
                ?: resolver?.selectedResolverId
                ?: "unavailable",
        selectedStrategy =
            recommendation
                ?.let { "${it.tcpCandidateLabel} + ${it.quicCandidateLabel}" }
                ?: "unavailable",
        quicLayoutFamily = recommendation?.quicCandidateLayoutFamily,
        selectedResolver =
            recommendation?.dnsStrategyLabel
                ?: resolver?.selectedResolverId,
        confidenceLevel = assessment?.confidence?.level?.name,
        confidenceScore = assessment?.confidence?.score,
        coveragePercent = assessment?.coverage?.matrixCoveragePercent,
        winnerCoveragePercent = assessment?.coverage?.winnerCoveragePercent,
        targetCohort = strategyProbe?.targetSelection?.cohortLabel,
        evidence = evidence.distinct(),
    )
}

private fun buildSectionStatuses(
    selection: DiagnosticsArchiveSelection,
): Map<String, DiagnosticsArchiveSectionStatus> {
    val truncationFlags =
        SectionTruncationFlags(
            telemetry = selection.sourceCounts.telemetrySamples >= DiagnosticsArchiveFormat.telemetryLimit,
            nativeEvents = selection.sourceCounts.nativeEvents >= DiagnosticsArchiveFormat.globalEventLimit,
            snapshots = selection.sourceCounts.snapshots >= DiagnosticsArchiveFormat.snapshotLimit,
            contexts = selection.sourceCounts.contexts >= DiagnosticsArchiveFormat.snapshotLimit,
            logcat = (selection.logcatSnapshot?.byteCount ?: 0) >= LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
        )
    return buildMap {
        selection.includedFiles.forEach { fileName ->
            put(fileName, sectionStatusForFileName(fileName, truncationFlags))
        }
    }
}

private fun buildCompleteness(
    selection: DiagnosticsArchiveSelection,
    sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    snapshotPayload: DiagnosticsArchiveSnapshotPayload,
    contextPayload: DiagnosticsArchiveContextPayload,
): DiagnosticsArchiveCompletenessPayload {
    val snapshotDecodeFailures =
        selection.primarySnapshots.size +
            if (selection.latestPassiveSnapshot != null) {
                1
            } else {
                0 -
                    (
                        snapshotPayload.sessionSnapshots.size +
                            if (snapshotPayload.latestPassiveSnapshot != null) 1 else 0
                    )
            }
    val contextDecodeFailures =
        selection.primaryContexts.size +
            if (selection.latestPassiveContext != null) {
                1
            } else {
                0 -
                    (
                        contextPayload.sessionContexts.size +
                            if (contextPayload.latestPassiveContext != null) 1 else 0
                    )
            }
    val collectionWarnings =
        buildList {
            addAll(selection.collectionWarnings)
            if (snapshotDecodeFailures > 0) {
                add("snapshot_decode_failed_count:$snapshotDecodeFailures")
            }
            if (contextDecodeFailures > 0) {
                add("context_decode_failed_count:$contextDecodeFailures")
            }
            if (selection.buildProvenance.gitCommit == "unavailable") {
                add("git_commit_unavailable")
            }
            selection.buildProvenance.nativeLibraries
                .filter { it.version == "unavailable" }
                .forEach { add("native_library_version_unavailable:${it.name}") }
        }
    return DiagnosticsArchiveCompletenessPayload(
        sectionStatuses = sectionStatuses,
        appliedLimits =
            DiagnosticsArchiveAppliedLimits(
                telemetrySamples = DiagnosticsArchiveFormat.telemetryLimit,
                nativeEvents = DiagnosticsArchiveFormat.globalEventLimit,
                snapshots = DiagnosticsArchiveFormat.snapshotLimit,
                logcatBytes = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
            ),
        sourceCounts = selection.sourceCounts,
        includedCounts =
            DiagnosticsArchiveSourceCounts(
                telemetrySamples = selection.payload.telemetry.size,
                nativeEvents = selection.primaryEvents.size + selection.globalEvents.size,
                snapshots =
                    snapshotPayload.sessionSnapshots.size +
                        if (snapshotPayload.latestPassiveSnapshot != null) 1 else 0,
                contexts =
                    contextPayload.sessionContexts.size +
                        if (contextPayload.latestPassiveContext != null) 1 else 0,
                sessionResults = selection.primaryResults.size,
                sessionSnapshots = snapshotPayload.sessionSnapshots.size,
                sessionContexts = contextPayload.sessionContexts.size,
                sessionEvents = selection.primaryEvents.size,
            ),
        collectionWarnings = collectionWarnings,
        truncation =
            DiagnosticsArchiveTruncation(
                telemetrySamples =
                    selection.sourceCounts.telemetrySamples >= DiagnosticsArchiveFormat.telemetryLimit,
                nativeEvents = selection.sourceCounts.nativeEvents >= DiagnosticsArchiveFormat.globalEventLimit,
                snapshots = selection.sourceCounts.snapshots >= DiagnosticsArchiveFormat.snapshotLimit,
                contexts = selection.sourceCounts.contexts >= DiagnosticsArchiveFormat.snapshotLimit,
                logcat = (selection.logcatSnapshot?.byteCount ?: 0) >= LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
            ),
    )
}

private fun buildIntegrityPayload(
    target: DiagnosticsArchiveTarget,
    entries: List<DiagnosticsArchiveEntry>,
): DiagnosticsArchiveIntegrityPayload =
    DiagnosticsArchiveIntegrityPayload(
        hashAlgorithm = "sha256",
        schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
        generatedAt = target.createdAt,
        files =
            entries.map { entry ->
                DiagnosticsArchiveIntegrityFileEntry(
                    name = entry.name,
                    byteCount = entry.bytes.size,
                    sha256 = sha256Hex(entry.bytes),
                )
            },
    )

internal fun buildStageIndexEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveStageIndexEntry> =
    selection.compositeStages.map { stage ->
        stage.stageSummary.toArchiveStageIndexEntry()
    }

private fun DiagnosticsHomeCompositeStageSummary.toArchiveStageIndexEntry(): DiagnosticsArchiveStageIndexEntry =
    DiagnosticsArchiveStageIndexEntry(
        stageKey = stageKey,
        stageLabel = stageLabel,
        profileId = profileId,
        pathMode = pathMode.name,
        sessionId = sessionId,
        status = status.name.lowercase(),
        headline = headline,
        summary = summary,
        recommendationContributor = recommendationContributor,
    )

internal fun buildTelemetryCsv(selection: DiagnosticsArchiveSelection): String =
    buildTelemetryCsv(
        payload = selection.payload,
        measurementSnapshot =
            buildMeasurementSnapshot(
                selection = selection,
                strategyProbe = selection.primaryReport?.strategyProbeReport,
                latestTelemetry = selection.payload.telemetry.firstOrNull(),
            ),
    )

internal fun buildTelemetryCsv(payload: DiagnosticsArchivePayload): String =
    buildTelemetryCsv(
        payload = payload,
        measurementSnapshot = DiagnosticsArchiveMeasurementSnapshot(),
    )

private fun buildTelemetryCsv(
    payload: DiagnosticsArchivePayload,
    measurementSnapshot: DiagnosticsArchiveMeasurementSnapshot,
): String =
    buildString {
        appendLine(
            "createdAt,activeMode,connectionState,networkType,publicIp,failureClass," +
                "lastFailureClass,lastFallbackAction," +
                "telemetryNetworkFingerprintHash,winningTcpStrategyFamily,winningQuicStrategyFamily," +
                "winningStrategyFamily,networkIdentityBucket,targetBucket,recommendedTcpEmitterTier," +
                "recommendedQuicEmitterTier,acceptanceMatrixCoveragePercent,winnerCoveragePercent," +
                "detectabilityBudgetState,missingRuntimeCapabilities,proxyRttBand,resolverRttBand," +
                "rttBand,proxyRouteRetryCount," +
                "tunnelRecoveryRetryCount,retryCount,resolverId,resolverProtocol," +
                "resolverEndpoint,resolverLatencyMs,dnsFailuresTotal,resolverFallbackActive," +
                "resolverFallbackReason,networkHandoverClass,txPackets,txBytes,rxPackets,rxBytes",
        )
        payload.telemetry.forEach { sample ->
            appendLine(
                listOf(
                    sample.createdAt,
                    sample.activeMode.orEmpty(),
                    sample.connectionState,
                    sample.networkType,
                    if (sample.publicIp.isNullOrEmpty()) "" else "redacted",
                    sample.failureClass.orEmpty(),
                    sample.lastFailureClass.orEmpty(),
                    sample.lastFallbackAction.orEmpty(),
                    sample.telemetryNetworkFingerprintHash.orEmpty(),
                    sample.winningTcpStrategyFamily.orEmpty(),
                    sample.winningQuicStrategyFamily.orEmpty(),
                    sample.winningStrategyFamily().orEmpty(),
                    measurementSnapshot.networkIdentityBucket,
                    measurementSnapshot.targetBucket,
                    measurementSnapshot.recommendedTcpEmitterTier.orEmpty(),
                    measurementSnapshot.recommendedQuicEmitterTier.orEmpty(),
                    measurementSnapshot.acceptanceMetrics.matrixCoveragePercent ?: 0,
                    measurementSnapshot.acceptanceMetrics.winnerCoveragePercent ?: 0,
                    if (measurementSnapshot.rolloutGateAssessment.results.any {
                            it.id == "detectability_budget" &&
                                it.passed
                        }
                    ) {
                        "pass"
                    } else {
                        "fail"
                    },
                    measurementSnapshot.capabilitySnapshot.inferredUnavailableCapabilities.joinToString("|"),
                    sample.proxyRttBand,
                    sample.resolverRttBand,
                    sample.rttBand(),
                    sample.proxyRouteRetryCount,
                    sample.tunnelRecoveryRetryCount,
                    sample.retryCount(),
                    sample.resolverId.orEmpty(),
                    sample.resolverProtocol.orEmpty(),
                    sample.resolverEndpoint.orEmpty(),
                    sample.resolverLatencyMs ?: 0,
                    sample.dnsFailuresTotal,
                    sample.resolverFallbackActive,
                    sample.resolverFallbackReason.orEmpty(),
                    sample.networkHandoverClass.orEmpty(),
                    sample.txPackets,
                    sample.txBytes,
                    sample.rxPackets,
                    sample.rxBytes,
                ).joinToString(","),
            )
        }
    }

internal fun buildNativeEventsCsv(
    primaryEvents: List<NativeSessionEventEntity>,
    globalEvents: List<NativeSessionEventEntity>,
): String =
    buildString {
        appendLine(
            "scope,sessionId,source,level,message," +
                "createdAt,runtimeId,mode,policySignature,fingerprintHash,subsystem",
        )
        primaryEvents.forEach { event ->
            appendLine(
                listOf(
                    csvField("session"),
                    csvField(event.sessionId.orEmpty()),
                    csvField(event.source),
                    csvField(event.level),
                    csvField(event.message),
                    csvField(event.createdAt),
                    csvField(event.runtimeId.orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(event.policySignature.orEmpty()),
                    csvField(event.fingerprintHash.orEmpty()),
                    csvField(event.subsystem.orEmpty()),
                ).joinToString(","),
            )
        }
        globalEvents.forEach { event ->
            appendLine(
                listOf(
                    csvField("global"),
                    csvField(event.sessionId.orEmpty()),
                    csvField(event.source),
                    csvField(event.level),
                    csvField(event.message),
                    csvField(event.createdAt),
                    csvField(event.runtimeId.orEmpty()),
                    csvField(event.mode.orEmpty()),
                    csvField(event.policySignature.orEmpty()),
                    csvField(event.fingerprintHash.orEmpty()),
                    csvField(event.subsystem.orEmpty()),
                ).joinToString(","),
            )
        }
    }

internal fun csvField(value: Any?): String =
    buildString {
        append('"')
        append(value?.toString().orEmpty().replace("\"", "\"\""))
        append('"')
    }

private data class SectionTruncationFlags(
    val telemetry: Boolean,
    val nativeEvents: Boolean,
    val snapshots: Boolean,
    val contexts: Boolean,
    val logcat: Boolean,
)

private fun sectionStatusForFileName(
    fileName: String,
    flags: SectionTruncationFlags,
): DiagnosticsArchiveSectionStatus =
    when (fileName) {
        "summary.txt",
        "manifest.json",
        "report.json",
        "home-analysis.json",
        "stage-index.json",
        "stage-summaries.json",
        -> {
            DiagnosticsArchiveSectionStatus.REDACTED
        }

        "network-snapshots.json" -> {
            if (flags.snapshots) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        "diagnostic-context.json" -> {
            if (flags.contexts) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        "telemetry.csv" -> {
            if (flags.telemetry) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        "native-events.csv" -> {
            if (flags.nativeEvents) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        "logcat.txt" -> {
            if (flags.logcat) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        else -> {
            stageSectionStatusForFileName(fileName, flags)
        }
    }

private fun stageSectionStatusForFileName(
    fileName: String,
    flags: SectionTruncationFlags,
): DiagnosticsArchiveSectionStatus =
    when {
        fileName.endsWith("/report.json") -> {
            DiagnosticsArchiveSectionStatus.REDACTED
        }

        fileName.endsWith("/network-snapshots.json") -> {
            if (flags.snapshots) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        fileName.endsWith("/diagnostic-context.json") -> {
            if (flags.contexts) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.REDACTED
            }
        }

        fileName.endsWith("/telemetry.csv") -> {
            if (flags.telemetry) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        fileName.endsWith("/native-events.csv") -> {
            if (flags.nativeEvents) {
                DiagnosticsArchiveSectionStatus.TRUNCATED
            } else {
                DiagnosticsArchiveSectionStatus.INCLUDED
            }
        }

        else -> {
            DiagnosticsArchiveSectionStatus.INCLUDED
        }
    }

internal fun List<NativeSessionEventEntity>.latestCorrelation(
    selector: (NativeSessionEventEntity) -> String?,
): String? =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .mapNotNull(selector)
        .firstOrNull()

internal fun List<NativeSessionEventEntity>.lifecycleMilestones(limit: Int = 6): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            val subsystem = (event.subsystem ?: event.source).lowercase()
            val message = event.message.lowercase()
            subsystem in setOf("service", "proxy", "tunnel", "diagnostics") &&
                (
                    message.contains("started") ||
                        message.contains("stopped") ||
                        message.contains("stop requested") ||
                        message.contains("listener started") ||
                        message.contains("listener stopped")
                )
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

internal fun List<NativeSessionEventEntity>.recentWarningPreview(limit: Int = 5): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

internal fun BypassApproachSummary.successRateLabel(): String =
    validatedSuccessRate?.let { rate ->
        "${(rate * SuccessRatePercentScale).toInt()}%"
    } ?: "unverified"

internal fun DiagnosticsArchiveBuildProvenance.toSummary(): DiagnosticsArchiveBuildProvenanceSummary =
    DiagnosticsArchiveBuildProvenanceSummary(
        applicationId = applicationId,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        buildType = buildType,
        gitCommit = gitCommit,
        nativeLibraries = nativeLibraries.map { "${it.name}:${it.version}" },
    )

private fun StrategyProbeCandidateSummary.toExecutionDetail(
    lane: String,
    observations: List<ObservationFact>,
): DiagnosticsArchiveCandidateExecutionDetail {
    val strategyFacts = observations.mapNotNull(ObservationFact::strategy).filter { it.candidateId == id }
    val statusCounts =
        strategyFacts
            .groupingBy { it.status.name.lowercase() }
            .eachCount()
            .toSortedMap()
    val transportFailureCounts =
        strategyFacts
            .map { it.transportFailure.name.lowercase() }
            .filterNot { it == TransportFailureKind.NONE.name.lowercase() }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
    val tlsErrorSamples =
        strategyFacts
            .flatMap { fact -> listOfNotNull(fact.tlsError, fact.tlsEchError) }
            .distinct()
            .take(TlsErrorSampleLimit)
    return DiagnosticsArchiveCandidateExecutionDetail(
        lane = lane,
        id = id,
        label = label,
        family = family,
        quicLayoutFamily = quicLayoutFamily,
        outcome = outcome,
        rationale = rationale,
        succeededTargets = succeededTargets,
        totalTargets = totalTargets,
        weightedSuccessScore = weightedSuccessScore,
        totalWeight = totalWeight,
        qualityScore = qualityScore,
        averageLatencyMs = averageLatencyMs,
        skipped = skipped,
        skipReasons =
            buildList {
                if (skipped) {
                    add(rationale)
                }
                addAll(
                    notes.filter {
                        it.contains("skip", ignoreCase = true) ||
                            it.contains("not applicable", ignoreCase = true)
                    },
                )
            }.distinct(),
        notes = notes,
        factBreakdown =
            DiagnosticsArchiveCandidateFactBreakdown(
                observationCount = strategyFacts.size,
                statusCounts = statusCounts,
                transportFailureCounts = transportFailureCounts,
                tlsErrorSamples = tlsErrorSamples,
            ),
    )
}

private fun List<String>.distinctConsecutive(): List<String> {
    val result = mutableListOf<String>()
    for (value in this) {
        if (value.isBlank()) continue
        if (result.lastOrNull() != value) {
            result += value
        }
    }
    return result
}

private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray())

private fun sha256Hex(value: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
