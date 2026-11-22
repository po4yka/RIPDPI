@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

private const val MaxControlsForComparison = 2
private const val MaxDomainsForComparison = 3
private const val MaxFailedServicesForComparison = 2

internal data class PathComparisonSelection(
    val domainTargets: List<DomainTarget>,
    val serviceTargets: List<ServiceTarget>,
    val circumventionTargets: List<CircumventionTarget>,
    val failedTargetLabels: List<String>,
    val rawProfilesByTarget: Map<ConnectivityTargetKey, Set<String>>,
)

private data class ComparisonTargetCatalogs(
    val domainCatalog: Map<Pair<String, String>, DomainTarget>,
    val serviceCatalog: Map<Pair<String, String>, ServiceTarget>,
    val circumventionCatalog: Map<Pair<String, String>, CircumventionTarget>,
)

private suspend fun buildTargetCatalogs(
    rawStageSpecs: List<HomeCompositeStageSpec>,
    diagnosticsProfileCatalog: DiagnosticsProfileCatalog,
    json: Json,
): ComparisonTargetCatalogs {
    val profileSpecs =
        rawStageSpecs
            .mapNotNull { spec ->
                diagnosticsProfileCatalog.getProfile(spec.profileId)?.let { profile ->
                    spec.profileId to json.decodeProfileSpecWire(profile.requestJson)
                }
            }.toMap()
    return ComparisonTargetCatalogs(
        domainCatalog =
            profileSpecs
                .flatMap { (profileId, spec) ->
                    spec.domainTargets.map { target -> (profileId to target.host.lowercase()) to target }
                }.toMap(),
        serviceCatalog =
            profileSpecs
                .flatMap { (profileId, spec) ->
                    spec.serviceTargets.map { target -> (profileId to target.id.lowercase()) to target }
                }.toMap(),
        circumventionCatalog =
            profileSpecs
                .flatMap { (profileId, spec) ->
                    spec.circumventionTargets.map { target -> (profileId to target.id.lowercase()) to target }
                }.toMap(),
    )
}

private fun matchingDomainTargets(
    reports: List<ScanReport>,
    domainCatalog: Map<Pair<String, String>, DomainTarget>,
    matches: (DomainObservationFact, DiagnosticsOutcomeBucket) -> Boolean,
): Sequence<DomainTarget> =
    reports.asSequence().flatMap { report ->
        report.observations.asSequence().mapNotNull { observation ->
            val fact = observation.domain ?: return@mapNotNull null
            val http = fact.httpComparisonBucket(report.domainResult(fact.host))
            domainCatalog[report.profileId to fact.host.lowercase()]
                ?.takeIf { it.isControl == fact.isControl && matches(fact, http) }
        }
    }

private fun collectFailedServices(
    reports: List<ScanReport>,
    serviceCatalog: Map<Pair<String, String>, ServiceTarget>,
    circumventionCatalog: Map<Pair<String, String>, CircumventionTarget>,
): List<Any> =
    reports
        .flatMap { report -> report.observations.map { report.profileId to it } }
        .mapNotNull { (profileId, observation) ->
            when {
                observation.service?.let(::serviceObservationFailed) == true -> {
                    serviceCatalog[profileId to observation.target.lowercase()]
                }

                observation.circumvention?.let(::circumventionObservationFailed) == true -> {
                    circumventionCatalog[profileId to observation.target.lowercase()]
                }

                else -> {
                    null
                }
            }
        }.distinctBy { target ->
            when (target) {
                is ServiceTarget -> "service:${target.id}"
                is CircumventionTarget -> "circumvention:${target.id}"
                else -> target.toString()
            }
        }.take(MaxFailedServicesForComparison)

private fun buildPathSelection(
    reports: List<ScanReport>,
    catalogs: ComparisonTargetCatalogs,
    serviceStateStore: ServiceStateStore,
): PathComparisonSelection? {
    val workingDomains =
        matchingDomainTargets(reports, catalogs.domainCatalog) { _, http -> http == DiagnosticsOutcomeBucket.Healthy }
    val controls =
        workingDomains
            .filter { it.isControl && it.controlTier != ControlTier.DOMESTIC }
            .distinctBy { it.host.lowercase() }
            .take(MaxControlsForComparison)
            .toList()
    val workingDomain = workingDomains.firstOrNull { !it.isControl }
    if (controls.isEmpty() && workingDomain == null) return null

    val failedDomains =
        matchingDomainTargets(reports, catalogs.domainCatalog) { fact, http ->
            !fact.isControl && domainObservationFailed(fact, http)
        }
    val affectedDomainTargets =
        (listOfNotNull(workingDomain).asSequence() + failedDomains)
            .distinctBy { it.host.lowercase() }
            .take(MaxDomainsForComparison)
            .toList()
    val failedServices = collectFailedServices(reports, catalogs.serviceCatalog, catalogs.circumventionCatalog)
    val serviceTargets = failedServices.filterIsInstance<ServiceTarget>()
    val circumventionTargets = failedServices.filterIsInstance<CircumventionTarget>()
    val serviceRuntime =
        buildServiceRuntimeAssessment(
            serviceStatus = serviceStateStore.status.value.first,
            telemetry = serviceStateStore.telemetry.value,
        )
    val hasActionableTargets =
        affectedDomainTargets.isNotEmpty() || failedServices.isNotEmpty() || serviceRuntime.actionable
    val failedDomainLabels = affectedDomainTargets.filterNot { it == workingDomain }.map(DomainTarget::host)
    val domainTargets = controls + affectedDomainTargets
    return PathComparisonSelection(
        domainTargets = domainTargets,
        serviceTargets = serviceTargets,
        circumventionTargets = circumventionTargets,
        failedTargetLabels =
            failedDomainLabels + serviceTargets.map(ServiceTarget::id) +
                circumventionTargets.map(CircumventionTarget::id),
        rawProfilesByTarget =
            buildMap {
                domainTargets.forEach { target ->
                    put(
                        ConnectivityTargetKey(ObservationKind.DOMAIN, target.host),
                        catalogs.domainCatalog.profilesFor(target),
                    )
                }
                serviceTargets.forEach { target ->
                    put(
                        ConnectivityTargetKey(ObservationKind.SERVICE, target.id),
                        catalogs.serviceCatalog.profilesFor(target),
                    )
                }
                circumventionTargets.forEach { target ->
                    put(
                        ConnectivityTargetKey(ObservationKind.CIRCUMVENTION, target.id),
                        catalogs.circumventionCatalog.profilesFor(target),
                    )
                }
            },
    ).takeIf { hasActionableTargets }
}

private fun <T> Map<Pair<String, String>, T>.profilesFor(target: T): Set<String> =
    entries.filter { it.value == target }.mapTo(mutableSetOf()) { it.key.first }

private suspend fun resolvePathComparisonSelection(
    progress: DiagnosticsHomeCompositeProgress,
    diagnosticsProfileCatalog: DiagnosticsProfileCatalog,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
): PathComparisonSelection? {
    val rawStageSpecs =
        HomeCompositeStageSpecs.filter { spec ->
            spec.key in setOf("default_connectivity", "ru_circumvention", "dpi_full")
        }
    val catalogs = buildTargetCatalogs(rawStageSpecs, diagnosticsProfileCatalog, json)
    val reports =
        rawStageSpecs.mapNotNull { spec ->
            val stage = progress.stages.firstOrNull { it.stageKey == spec.key } ?: return@mapNotNull null
            if (!stage.isCompletedStage()) return@mapNotNull null
            decodeReport(scanRecordStore, stage.sessionId, json)?.takeIf {
                it.pathMode == ScanPathMode.RAW_PATH && it.completionKind == ScanCompletionKind.NORMAL
            }
        }
    return buildPathSelection(reports, catalogs, serviceStateStore)
}

internal suspend fun buildPathComparisonSelection(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    diagnosticsProfileCatalog: DiagnosticsProfileCatalog,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
): PathComparisonSelection? {
    val progress = progressState.value[runId] ?: return null
    return resolvePathComparisonSelection(progress, diagnosticsProfileCatalog, scanRecordStore, json, serviceStateStore)
}

private suspend fun resolveConnectivityAssessment(
    progress: DiagnosticsHomeCompositeProgress,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
    comparisonScanCoordinator: ComparisonScanCoordinator,
    selection: PathComparisonSelection?,
): ConnectivityAssessment? {
    val rawStages = progress.stages.filter { it.pathMode == ScanPathMode.RAW_PATH }
    val rawSessionIds = rawStages.mapNotNull { it.sessionId }
    if (rawSessionIds.isEmpty()) return null
    val rawReports =
        rawStages.filter { it.isCompletedStage() }.mapNotNull { stage ->
            decodeReport(scanRecordStore, stage.sessionId, json)?.takeIf { it.pathMode == ScanPathMode.RAW_PATH }
        }
    val inPathStage = progress.stages.firstOrNull { it.stageKey == "path_comparison" }
    val inPathSession = inPathStage?.sessionId?.let { scanRecordStore.getScanSession(it) }
    val inPathReport =
        inPathSession
            ?.reportJson
            ?.takeIf {
                inPathStage?.isCompletedStage() == true &&
                    inPathSession.status == "completed"
            }?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
            ?.takeIf { it.pathMode == ScanPathMode.IN_PATH }
    val serviceRuntime =
        buildServiceRuntimeAssessment(
            serviceStatus = serviceStateStore.status.value.first,
            telemetry = serviceStateStore.telemetry.value,
        )
    return comparisonScanCoordinator.assessConnectivity(
        rawReports = rawReports,
        inPathReport = inPathReport,
        rawPathSessionIds = rawSessionIds,
        inPathSessionId = inPathStage?.sessionId,
        serviceRuntimeAssessment = serviceRuntime,
        rawProfilesByTarget = selection?.rawProfilesByTarget,
        activePathIsVpn = inPathSession?.serviceMode.equals("VPN", ignoreCase = true),
    )
}

internal suspend fun buildConnectivityAssessment(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
    comparisonScanCoordinator: ComparisonScanCoordinator,
    selection: PathComparisonSelection?,
): ConnectivityAssessment? {
    val progress = progressState.value[runId] ?: return null
    return resolveConnectivityAssessment(
        progress,
        scanRecordStore,
        json,
        serviceStateStore,
        comparisonScanCoordinator,
        selection,
    )
}

private fun domainObservationFailed(
    fact: DomainObservationFact,
    http: DiagnosticsOutcomeBucket,
): Boolean =
    fact.transportFailure != TransportFailureKind.NONE ||
        http == DiagnosticsOutcomeBucket.Failed ||
        fact.tls13Status == TlsProbeStatus.HANDSHAKE_FAILED ||
        fact.tls12Status == TlsProbeStatus.HANDSHAKE_FAILED ||
        fact.tls13Status == TlsProbeStatus.CERT_INVALID ||
        fact.tls12Status == TlsProbeStatus.CERT_INVALID

private fun serviceObservationFailed(fact: ServiceObservationFact): Boolean =
    fact.endpointStatus == EndpointProbeStatus.BLOCKED ||
        fact.endpointStatus == EndpointProbeStatus.FAILED ||
        fact.bootstrapStatus == HttpProbeStatus.UNREACHABLE ||
        fact.mediaStatus == HttpProbeStatus.UNREACHABLE ||
        fact.quicStatus == QuicProbeStatus.ERROR

private fun circumventionObservationFailed(fact: CircumventionObservationFact): Boolean =
    fact.handshakeStatus == EndpointProbeStatus.BLOCKED ||
        fact.handshakeStatus == EndpointProbeStatus.FAILED ||
        fact.bootstrapStatus == HttpProbeStatus.UNREACHABLE
