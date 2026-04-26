@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

internal data class PathComparisonSelection(
    val domainTargets: List<DomainTarget>,
    val serviceTargets: List<ServiceTarget>,
    val circumventionTargets: List<CircumventionTarget>,
    val failedTargetLabels: List<String>,
)

internal suspend fun buildPathComparisonSelection(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    diagnosticsProfileCatalog: DiagnosticsProfileCatalog,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
    comparisonScanCoordinator: ComparisonScanCoordinator,
): PathComparisonSelection? {
    val progress = progressState.value[runId] ?: return null
    val rawStageSpecs =
        HomeCompositeStageSpecs.filter { spec ->
            spec.key in setOf("default_connectivity", "ru_circumvention", "dpi_full")
        }
    val profileSpecs =
        rawStageSpecs
            .mapNotNull { spec ->
                diagnosticsProfileCatalog.getProfile(spec.profileId)?.let { profile ->
                    spec.profileId to json.decodeProfileSpecWire(profile.requestJson)
                }
            }.toMap()
    val domainCatalog =
        profileSpecs.values
            .flatMap { it.domainTargets }
            .associateBy { it.host.lowercase() }
    val serviceCatalog =
        profileSpecs.values
            .flatMap { it.serviceTargets }
            .associateBy { it.id.lowercase() }
    val circumventionCatalog =
        profileSpecs.values
            .flatMap { it.circumventionTargets }
            .associateBy { it.id.lowercase() }
    val reports =
        rawStageSpecs.mapNotNull { spec ->
            val sessionId =
                progress.stages.firstOrNull { it.stageKey == spec.key }?.sessionId ?: return@mapNotNull null
            decodeReport(scanRecordStore, sessionId, json)?.let { spec to it }
        }
    val controls =
        reports
            .flatMap { (_, report) -> report.observations }
            .mapNotNull { observation ->
                observation.domain
                    ?.takeIf {
                        it.isControl &&
                            domainObservationSuccessful(it)
                    }?.host
            }.distinct()
            .map { host -> domainCatalog[host.lowercase()] ?: DomainTarget(host = host, isControl = true) }
            .take(2)
    if (controls.isEmpty()) return null
    val failedDomains =
        reports
            .flatMap { (_, report) -> report.observations }
            .mapNotNull { observation ->
                observation.domain
                    ?.takeIf { !it.isControl && domainObservationFailed(it) }
                    ?.host
            }.distinct()
            .map { host ->
                domainCatalog[host.lowercase()]?.copy(isControl = false) ?: DomainTarget(host = host)
            }.take(3)
    val failedServices =
        reports
            .flatMap { (_, report) -> report.observations }
            .mapNotNull { observation ->
                when {
                    observation.service?.let(::serviceObservationFailed) == true -> {
                        serviceCatalog[observation.target.lowercase()]
                    }

                    observation.circumvention?.let(::circumventionObservationFailed) == true -> {
                        circumventionCatalog[observation.target.lowercase()]
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
            }.take(2)
    val serviceTargets = failedServices.filterIsInstance<ServiceTarget>()
    val circumventionTargets = failedServices.filterIsInstance<CircumventionTarget>()
    val serviceRuntime =
        buildServiceRuntimeAssessment(
            serviceStatus = serviceStateStore.status.value.first,
            telemetry = serviceStateStore.telemetry.value,
        )
    if (failedDomains.isEmpty() && serviceTargets.isEmpty() && circumventionTargets.isEmpty() &&
        !serviceRuntime.actionable
    ) {
        return null
    }
    val domainTargets =
        (
            controls.map {
                it.copy(
                    isControl = true,
                )
            } + failedDomains
        ).distinctBy { it.host.lowercase() }
    return PathComparisonSelection(
        domainTargets = domainTargets,
        serviceTargets = serviceTargets,
        circumventionTargets = circumventionTargets,
        failedTargetLabels =
            failedDomains.map(DomainTarget::host) + serviceTargets.map(ServiceTarget::id) +
                circumventionTargets.map(CircumventionTarget::id),
    )
}

internal suspend fun buildConnectivityAssessment(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    serviceStateStore: ServiceStateStore,
    comparisonScanCoordinator: ComparisonScanCoordinator,
): ConnectivityAssessment? {
    val progress = progressState.value[runId] ?: return null
    val rawReports =
        progress.stages
            .filter { it.pathMode == ScanPathMode.RAW_PATH }
            .mapNotNull { decodeReport(scanRecordStore, it.sessionId, json) }
    if (rawReports.isEmpty()) return null
    val inPathStage = progress.stages.firstOrNull { it.stageKey == "path_comparison" }
    val inPathReport = decodeReport(scanRecordStore, inPathStage?.sessionId, json)
    val serviceRuntime =
        buildServiceRuntimeAssessment(
            serviceStatus = serviceStateStore.status.value.first,
            telemetry = serviceStateStore.telemetry.value,
        )
    return comparisonScanCoordinator.assessConnectivity(
        rawReports = rawReports,
        inPathReport = inPathReport,
        rawPathSessionIds =
            progress.stages
                .filter {
                    it.pathMode == ScanPathMode.RAW_PATH
                }.mapNotNull { it.sessionId },
        inPathSessionId = inPathStage?.sessionId,
        serviceRuntimeAssessment = serviceRuntime,
    )
}

private fun domainObservationSuccessful(fact: DomainObservationFact): Boolean =
    fact.httpStatus == HttpProbeStatus.OK ||
        fact.tls13Status == TlsProbeStatus.OK ||
        fact.tls12Status == TlsProbeStatus.OK ||
        fact.tls13Status == TlsProbeStatus.VERSION_SPLIT ||
        fact.tls12Status == TlsProbeStatus.VERSION_SPLIT

private fun domainObservationFailed(fact: DomainObservationFact): Boolean =
    fact.transportFailure != TransportFailureKind.NONE ||
        fact.httpStatus == HttpProbeStatus.UNREACHABLE ||
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
