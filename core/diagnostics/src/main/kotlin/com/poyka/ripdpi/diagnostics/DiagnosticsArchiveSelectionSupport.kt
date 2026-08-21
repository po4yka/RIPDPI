package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveSource
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompositeStageSelection
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRelayAttemptKey
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
import com.poyka.ripdpi.diagnostics.export.RelayTraceOutcomes
import com.poyka.ripdpi.diagnostics.export.RelayTraceStages
import com.poyka.ripdpi.diagnostics.export.relayAttemptKey
import com.poyka.ripdpi.diagnostics.export.selectArchiveNativeEvents
import com.poyka.ripdpi.diagnostics.export.selectRelayAttemptTraceEvents
import com.poyka.ripdpi.diagnostics.export.toArchiveNativeEventRetention
import kotlinx.serialization.json.Json

internal data class RelayTraceHydration(
    val events: List<NativeSessionEventEntity>,
    val budgetOmittedAttemptKeys: Set<DiagnosticsArchiveRelayAttemptKey>,
    val sourceWindowOmittedAttemptKeys: Set<DiagnosticsArchiveRelayAttemptKey>,
)

private data class RelayTraceSeed(
    val event: NativeSessionEventEntity,
    val index: Int,
    val sourceTruncated: Boolean,
)

internal suspend fun hydrateArchiveRelayAttemptTraces(
    primaryEvents: List<NativeSessionEventEntity>,
    primarySourceTruncated: Boolean,
    globalEvents: List<NativeSessionEventEntity>,
    globalSourceTruncated: Boolean,
    loadRelayAttemptTraceEvents: suspend (DiagnosticsArchiveRelayAttemptKey) -> List<NativeSessionEventEntity>,
): RelayTraceHydration {
    val retainedEvents = relayTraceSeeds(primaryEvents, primarySourceTruncated, globalEvents, globalSourceTruncated)
    val discoveredAttempts =
        retainedEvents
            .distinctBy { it.event.id }
            .filter { seed -> seed.event.hasRelayAttemptKey() }
            .groupBy { seed -> seed.event.relayAttemptKey() }
            .entries
            .sortedBy { (_, seeds) -> seeds.minOf { it.index } }
    val accumulator = RelayTraceHydrationAccumulator()
    discoveredAttempts.forEach { (attemptKey, seeds) ->
        accumulator.includeAttempt(attemptKey, seeds, loadRelayAttemptTraceEvents)
    }
    return accumulator.result()
}

private fun relayTraceSeeds(
    primaryEvents: List<NativeSessionEventEntity>,
    primarySourceTruncated: Boolean,
    globalEvents: List<NativeSessionEventEntity>,
    globalSourceTruncated: Boolean,
): List<RelayTraceSeed> =
    primaryEvents.mapIndexed { index, event -> RelayTraceSeed(event, index, primarySourceTruncated) } +
        globalEvents.mapIndexed { index, event ->
            RelayTraceSeed(event, primaryEvents.size + index, globalSourceTruncated)
        }

private class RelayTraceHydrationAccumulator {
    private val selectedSourceEvents = mutableListOf<NativeSessionEventEntity>()
    private val omitted = linkedSetOf<DiagnosticsArchiveRelayAttemptKey>()
    private val sourceWindowOmitted = linkedSetOf<DiagnosticsArchiveRelayAttemptKey>()
    private var selectedTraceEventCount = 0

    suspend fun includeAttempt(
        attemptKey: DiagnosticsArchiveRelayAttemptKey,
        seeds: List<RelayTraceSeed>,
        loadRelayAttemptTraceEvents: suspend (DiagnosticsArchiveRelayAttemptKey) -> List<NativeSessionEventEntity>,
    ) {
        val retainedAttemptEvents = seeds.map { it.event }
        if (retainedAttemptEvents.size > DiagnosticsArchiveFormat.sessionEventLimit) {
            omitted += attemptKey
        } else if (seeds.all { it.sourceTruncated } && !retainedAttemptEvents.hasPartialRelayAttemptEvidence()) {
            sourceWindowOmitted += attemptKey
        } else {
            val loadedAttemptEvents =
                loadRelayAttemptTraceEvents(attemptKey)
                    .ifEmpty { retainedAttemptEvents }
                    .distinctBy { it.id }
            val traceEvents = selectRelayAttemptTraceEvents(loadedAttemptEvents, emptyList())
            if (loadedAttemptEvents.size > DiagnosticsArchiveFormat.sessionEventLimit ||
                selectedTraceEventCount + traceEvents.size > DiagnosticsArchiveFormat.sessionEventLimit
            ) {
                omitted += attemptKey
            } else {
                selectedSourceEvents += loadedAttemptEvents
                selectedTraceEventCount += traceEvents.size
            }
        }
    }

    fun result() =
        RelayTraceHydration(
            events = selectedSourceEvents.distinctBy { it.id },
            budgetOmittedAttemptKeys = omitted,
            sourceWindowOmittedAttemptKeys = sourceWindowOmitted,
        )
}

internal class DiagnosticsArchiveCompositeStageSelector(
    private val json: Json,
) {
    suspend fun build(
        isComposite: Boolean,
        compositeOutcome: DiagnosticsHomeCompositeOutcome?,
        compositeSessions: List<ScanSessionEntity>,
        sourceData: DiagnosticsArchiveSourceData,
        loadProbeResults: suspend (String) -> List<ProbeResultEntity>,
        loadNativeEventSource: suspend (String) -> DiagnosticsNativeEventArchiveSource,
        loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity>,
    ): List<DiagnosticsArchiveCompositeStageSelection> {
        if (!isComposite || compositeOutcome == null) return emptyList()
        return compositeOutcome.stageSummaries.map { stageSummary ->
            val session = compositeSessions.firstOrNull { it.id == stageSummary.sessionId }
            session?.let {
                buildStage(stageSummary, it, sourceData, loadProbeResults, loadNativeEventSource, loadStageTelemetry)
            } ?: emptyStage(stageSummary)
        }
    }

    private suspend fun buildStage(
        stageSummary: DiagnosticsHomeCompositeStageSummary,
        session: ScanSessionEntity,
        sourceData: DiagnosticsArchiveSourceData,
        loadProbeResults: suspend (String) -> List<ProbeResultEntity>,
        loadNativeEventSource: suspend (String) -> DiagnosticsNativeEventArchiveSource,
        loadStageTelemetry: suspend (ScanSessionEntity, Set<String>) -> List<TelemetrySampleEntity>,
    ): DiagnosticsArchiveCompositeStageSelection {
        val eventSource = loadNativeEventSource(session.id)
        val events = eventSource.events
        val eventSelection = selectArchiveNativeEvents(eventSource, DiagnosticsArchiveFormat.sessionEventLimit)
        val snapshots = sourceData.snapshots.filter { it.sessionId == session.id }
        val contexts = sourceData.contexts.filter { it.sessionId == session.id }
        val connectionSessionIds =
            buildSet {
                snapshots.mapNotNullTo(this) { it.connectionSessionId }
                contexts.mapNotNullTo(this) { it.connectionSessionId }
                events.mapNotNullTo(this) { it.connectionSessionId }
            }
        val telemetry = loadStageTelemetry(session, connectionSessionIds)
        return DiagnosticsArchiveCompositeStageSelection(
            stageSummary = stageSummary,
            session = session,
            report = session.reportJson?.takeIf(String::isNotBlank)?.let(json::decodeStoredEngineScanReportWire),
            results = loadProbeResults(session.id),
            snapshots = snapshots.take(DiagnosticsArchiveFormat.snapshotLimit),
            contexts = contexts.selectArchiveContexts(),
            events = eventSelection.events,
            telemetry = telemetry.take(DiagnosticsArchiveFormat.telemetryLimit),
            sourceSnapshotCount = snapshots.size,
            sourceContextCount = contexts.size,
            sourceEventCount = eventSource.sourceCounts.total,
            sourceTelemetryCount = telemetry.size,
            sourceEventIds = events.mapTo(linkedSetOf()) { it.id },
            sourceTelemetryIds = telemetry.mapTo(linkedSetOf()) { it.id },
            nativeEventRetention = eventSelection.toArchiveNativeEventRetention(),
        )
    }

    private fun emptyStage(stageSummary: DiagnosticsHomeCompositeStageSummary) =
        DiagnosticsArchiveCompositeStageSelection(
            stageSummary = stageSummary,
            session = null,
            report = null,
            results = emptyList(),
            snapshots = emptyList(),
            contexts = emptyList(),
            events = emptyList(),
        )
}

private fun NativeSessionEventEntity.hasRelayAttemptKey(): Boolean =
    subsystem == "relay" &&
        !connectionSessionId.isNullOrBlank() &&
        !runtimeId.isNullOrBlank() &&
        attemptId != null &&
        attemptSequence != null

private fun List<NativeSessionEventEntity>.hasPartialRelayAttemptEvidence(): Boolean {
    val sequences = mapNotNull { it.attemptSequence }.distinct().sorted()
    if (sequences.firstOrNull()?.let { first -> first > 1 } == true) return true
    return sequences.zipWithNext().any { (left, right) -> right > left + 1 } ||
        any { event -> event.stage !in RelayTraceStages || event.outcome !in RelayTraceOutcomes }
}
