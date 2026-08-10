package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal class DiagnosticsArchiveResolverTraceEntryBuilder(
    private val json: Json,
) {
    fun buildRoot(
        selection: DiagnosticsArchiveSelection,
        report: EngineScanReportWire?,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): DiagnosticsArchiveEntry =
        build(
            name = "resolver-trace.jsonl",
            sessionId = selection.primarySession?.id,
            profileId = selection.primarySession?.profileId,
            report = report,
            rawReport = selection.primaryReport,
            telemetry = selection.payload.telemetry,
            targetAliases = targetAliases,
            referencePrefix = "",
        )

    fun buildStage(
        prefix: String,
        stage: DiagnosticsArchiveCompositeStageSelection,
        report: EngineScanReportWire?,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): DiagnosticsArchiveEntry =
        build(
            name = "$prefix/resolver-trace.jsonl",
            sessionId = stage.session?.id,
            profileId = stage.session?.profileId,
            report = report,
            rawReport = stage.report,
            telemetry = stage.telemetry,
            targetAliases = targetAliases,
            referencePrefix = "$prefix/",
        )

    private fun build(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
        rawReport: EngineScanReportWire?,
        telemetry: List<TelemetrySampleEntity>,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
        referencePrefix: String,
    ): DiagnosticsArchiveEntry {
        val records =
            buildRecords(
                sessionId = sessionId,
                profileId = profileId,
                report = report,
                rawReport = rawReport,
                telemetry = telemetry,
                targetAliases = targetAliases,
                referencePrefix = referencePrefix,
            ).mapIndexed { index, record -> record.copy(sequence = index + 1) }
        val content = records.joinToString(separator = "", postfix = "", transform = ::encodeRecord)
        return DiagnosticsArchiveEntry(
            name = name,
            bytes = content.toByteArray(),
        )
    }

    private fun encodeRecord(record: DiagnosticsArchiveResolverTraceRecord): String =
        json.encodeToJsonElement(DiagnosticsArchiveResolverTraceRecord.serializer(), record).toString() + "\n"

    private fun buildRecords(
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
        rawReport: EngineScanReportWire?,
        telemetry: List<TelemetrySampleEntity>,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
        referencePrefix: String,
    ): List<DiagnosticsArchiveResolverTraceRecord> =
        buildList {
            addAll(dnsObservationRecords(sessionId, profileId, rawReport, targetAliases, referencePrefix))
            addAll(resolverAttemptRecords(sessionId, profileId, report, rawReport, targetAliases, referencePrefix))
            resolverRecommendationRecord(sessionId, profileId, report, rawReport, referencePrefix)?.let(::add)
            addAll(runtimeSampleRecords(sessionId, profileId, telemetry, referencePrefix))
        }
}

private fun dnsObservationRecords(
    sessionId: String?,
    profileId: String?,
    rawReport: EngineScanReportWire?,
    targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    referencePrefix: String,
): List<DiagnosticsArchiveResolverTraceRecord> =
    rawReport?.observations.orEmpty().mapIndexedNotNull { index, observation ->
        val dns = observation.dns ?: return@mapIndexedNotNull null
        DiagnosticsArchiveResolverTraceRecord(
            sessionId = sessionId,
            profileId = profileId,
            sequence = 0,
            recordType = DiagnosticsArchiveResolverTraceRecordType.DNS_OBSERVATION,
            targetAlias = targetAliases.aliasFor(observation.target) ?: UnknownResolverTraceTargetAlias,
            dnsStatus = dns.status.name,
            systemAddressCount = dns.udpAddresses.size,
            encryptedAddressCount = dns.encryptedAddresses.size,
            systemLatencyMs = dns.udpLatencyMs,
            encryptedLatencyMs = dns.encryptedLatencyMs,
            tamperingScore = dns.tamperingScore,
            evidenceRefs =
                listOf(
                    primaryReportEvidenceRef(pointer = "/observations/$index/dns", referencePrefix = referencePrefix),
                ),
        )
    }

private fun resolverAttemptRecords(
    sessionId: String?,
    profileId: String?,
    report: EngineScanReportWire?,
    rawReport: EngineScanReportWire?,
    targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    referencePrefix: String,
): List<DiagnosticsArchiveResolverTraceRecord> =
    report?.results.orEmpty().flatMapIndexed { resultIndex, result ->
        val detailIndex = result.details.indexOfFirst { it.key == OracleAttemptDiagnosticsKey }
        if (detailIndex < 0) return@flatMapIndexed emptyList()
        val targetAlias =
            targetAliases.aliasFor(rawReport?.results?.getOrNull(resultIndex)?.target)
                ?: UnknownResolverTraceTargetAlias
        parseResolverAttempts(result.details[detailIndex].value).mapIndexed { attemptIndex, attempt ->
            resolverAttemptRecord(
                sessionId,
                profileId,
                targetAlias,
                attemptIndex,
                attempt,
                primaryReportEvidenceRef(
                    pointer = "/results/$resultIndex/details/$detailIndex",
                    referencePrefix = referencePrefix,
                ),
            )
        }
    }

private fun resolverAttemptRecord(
    sessionId: String?,
    profileId: String?,
    targetAlias: String,
    attemptIndex: Int,
    attempt: ResolverAttemptProjection,
    evidenceRef: String,
) = DiagnosticsArchiveResolverTraceRecord(
    sessionId = sessionId,
    profileId = profileId,
    sequence = 0,
    recordType = DiagnosticsArchiveResolverTraceRecordType.RESOLVER_ATTEMPT,
    targetAlias = targetAlias,
    resolverId = attempt.resolverId,
    resolverRole =
        if (attemptIndex == 0) DiagnosticsArchiveResolverRole.PRIMARY else DiagnosticsArchiveResolverRole.FALLBACK,
    attemptStatus =
        if (attempt.outcome == SuccessfulResolverAttemptOutcome) {
            DiagnosticsArchiveResolverAttemptStatus.SUCCEEDED
        } else {
            DiagnosticsArchiveResolverAttemptStatus.FAILED
        },
    attemptOutcome = attempt.outcome,
    answerCount = attempt.answerCount,
    resolverLatencyMs = attempt.latencyMs,
    evidenceRefs = listOf(evidenceRef),
)

private fun resolverRecommendationRecord(
    sessionId: String?,
    profileId: String?,
    report: EngineScanReportWire?,
    rawReport: EngineScanReportWire?,
    referencePrefix: String,
): DiagnosticsArchiveResolverTraceRecord? {
    val recommendation = report?.resolverRecommendation ?: return null
    return DiagnosticsArchiveResolverTraceRecord(
        sessionId = sessionId,
        profileId = profileId,
        sequence = 0,
        recordType = DiagnosticsArchiveResolverTraceRecordType.RESOLVER_RECOMMENDATION,
        resolverId = recommendation.selectedResolverId,
        protocol = recommendation.selectedProtocol,
        endpointStatus = rawReport?.resolverRecommendation?.selectedEndpoint.toEndpointStatus(),
        triggerOutcome = recommendation.triggerOutcome,
        appliedTemporarily = recommendation.appliedTemporarily,
        persistable = recommendation.persistable,
        evidenceRefs =
            listOf(
                primaryReportEvidenceRef(pointer = "/resolverRecommendation", referencePrefix = referencePrefix),
            ),
    )
}

private fun runtimeSampleRecords(
    sessionId: String?,
    profileId: String?,
    telemetry: List<TelemetrySampleEntity>,
    referencePrefix: String,
): List<DiagnosticsArchiveResolverTraceRecord> =
    telemetry
        .withIndex()
        .sortedWith(compareBy({ it.value.createdAt }, { it.value.id }))
        .map { indexedSample ->
            val rawSample = indexedSample.value
            val sample = rawSample.redactForArchive()
            DiagnosticsArchiveResolverTraceRecord(
                sessionId = sample.sessionId,
                profileId = profileId.takeIf { sample.sessionId != null && sample.sessionId == sessionId },
                sequence = 0,
                recordType = DiagnosticsArchiveResolverTraceRecordType.RUNTIME_SAMPLE,
                recordedAt = sample.createdAt,
                resolverId = sample.resolverId,
                protocol = sample.resolverProtocol,
                endpointStatus = rawSample.resolverEndpoint.toEndpointStatus(),
                resolverLatencyMs = sample.resolverLatencyMs,
                resolverRttBand = sample.resolverRttBand,
                dnsFailuresTotal = sample.dnsFailuresTotal,
                fallbackActive = sample.resolverFallbackActive,
                fallbackReason = sample.resolverFallbackReason,
                evidenceRefs = listOf("${referencePrefix}telemetry.csv#L${indexedSample.index + 2}"),
            )
        }

private data class ResolverAttemptProjection(
    val resolverId: String,
    val outcome: String,
    val latencyMs: Long,
    val answerCount: Int,
)

private fun parseResolverAttempts(value: String): List<ResolverAttemptProjection> =
    value.split('|').mapNotNull { encodedAttempt ->
        val match = ResolverAttemptPattern.matchEntire(encodedAttempt) ?: return@mapNotNull null
        val groups = match.groupValues
        ResolverAttemptProjection(
            resolverId = groups[1],
            outcome = groups[2],
            latencyMs = groups[3].toLongOrNull() ?: return@mapNotNull null,
            answerCount = groups[4].toIntOrNull() ?: return@mapNotNull null,
        )
    }

private fun String?.toEndpointStatus(): DiagnosticsArchiveResolverEndpointStatus =
    if (isNullOrBlank()) {
        DiagnosticsArchiveResolverEndpointStatus.UNAVAILABLE
    } else {
        DiagnosticsArchiveResolverEndpointStatus.REDACTED
    }

private const val UnknownResolverTraceTargetAlias = "target-unknown"
private const val OracleAttemptDiagnosticsKey = "oracleAttemptDiagnostics"
private const val SuccessfulResolverAttemptOutcome = "ok"
private val ResolverAttemptPattern = Regex("^(.+):([^:]+):(\\d+):(\\d+)$")
