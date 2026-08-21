package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveClass
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveClassCounts
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveSource
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.toNativeEventArchiveSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.poyka.ripdpi.data.diagnostics.archiveEventClass as toDataArchiveEventClass

@Serializable
internal enum class DiagnosticsArchiveNativeEventClass {
    @SerialName("terminal")
    TERMINAL,

    @SerialName("candidate_trial")
    CANDIDATE_TRIAL,

    @SerialName("cleanup")
    CLEANUP,

    @SerialName("runtime_restoration")
    RUNTIME_RESTORATION,

    @SerialName("other")
    OTHER,
}

internal data class DiagnosticsArchiveNativeEventSelection(
    val events: List<NativeSessionEventEntity>,
    val sourceCounts: Map<DiagnosticsArchiveNativeEventClass, Int>,
    val includedCounts: Map<DiagnosticsArchiveNativeEventClass, Int>,
    val omittedCounts: Map<DiagnosticsArchiveNativeEventClass, Int>,
)

@Serializable
internal data class DiagnosticsArchiveNativeEventClassCounts(
    val terminal: Int = 0,
    val candidateTrial: Int = 0,
    val cleanup: Int = 0,
    val runtimeRestoration: Int = 0,
    val other: Int = 0,
) {
    val total: Int
        get() = terminal + candidateTrial + cleanup + runtimeRestoration + other

    fun count(eventClass: DiagnosticsArchiveNativeEventClass): Int =
        when (eventClass) {
            DiagnosticsArchiveNativeEventClass.TERMINAL -> terminal
            DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL -> candidateTrial
            DiagnosticsArchiveNativeEventClass.CLEANUP -> cleanup
            DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION -> runtimeRestoration
            DiagnosticsArchiveNativeEventClass.OTHER -> other
        }
}

@Serializable
internal data class DiagnosticsArchiveNativeEventRetention(
    val sourceCounts: DiagnosticsArchiveNativeEventClassCounts = DiagnosticsArchiveNativeEventClassCounts(),
    val includedCounts: DiagnosticsArchiveNativeEventClassCounts = DiagnosticsArchiveNativeEventClassCounts(),
    val omittedCounts: DiagnosticsArchiveNativeEventClassCounts = DiagnosticsArchiveNativeEventClassCounts(),
)

@Serializable
internal data class DiagnosticsArchiveNativeEventCompleteness(
    val global: DiagnosticsArchiveNativeEventRetention = DiagnosticsArchiveNativeEventRetention(),
    val primarySession: DiagnosticsArchiveNativeEventRetention = DiagnosticsArchiveNativeEventRetention(),
    val compositeStages: Map<String, DiagnosticsArchiveNativeEventRetention> = emptyMap(),
)

internal fun DiagnosticsArchiveNativeEventSelection.toArchiveNativeEventRetention() =
    DiagnosticsArchiveNativeEventRetention(
        sourceCounts = sourceCounts.toArchiveNativeEventClassCounts(),
        includedCounts = includedCounts.toArchiveNativeEventClassCounts(),
        omittedCounts = omittedCounts.toArchiveNativeEventClassCounts(),
    )

internal fun selectArchiveNativeEvents(
    events: List<NativeSessionEventEntity>,
    limit: Int,
): DiagnosticsArchiveNativeEventSelection =
    selectArchiveNativeEvents(
        source = events.toNativeEventArchiveSource(),
        limit = limit,
    )

internal fun selectArchiveNativeEvents(
    source: DiagnosticsNativeEventArchiveSource,
    limit: Int,
): DiagnosticsArchiveNativeEventSelection {
    require(limit >= 0) { "Archive native event limit must be non-negative" }
    val sourceEvents = source.events.distinctBy { it.id }
    val sourceCounts = source.sourceCounts.toArchiveEventClassMap()
    if (limit == 0) {
        return DiagnosticsArchiveNativeEventSelection(
            events = emptyList(),
            sourceCounts = sourceCounts,
            includedCounts = emptyArchiveEventClassCounts(),
            omittedCounts = sourceCounts,
        )
    }

    val selected = LinkedHashMap<String, NativeSessionEventEntity>(limit)
    val criticalClasses =
        DiagnosticsArchiveNativeEventClass.entries.filterNot { eventClass ->
            eventClass == DiagnosticsArchiveNativeEventClass.OTHER
        }
    val criticalEvents = sourceEvents.groupBy(NativeSessionEventEntity::archiveEventClass)
    repeat(DiagnosticsArchiveFormat.criticalEventClassLimit) { quotaIndex ->
        criticalClasses.forEach { eventClass ->
            criticalEvents[eventClass]?.getOrNull(quotaIndex)?.let { event ->
                if (selected.size < limit) {
                    selected[event.id] = event
                }
            }
        }
    }
    sourceEvents.forEach { event ->
        if (selected.size < limit) selected.putIfAbsent(event.id, event)
    }

    val selectedIds = selected.keys
    val selectedEvents = sourceEvents.filter { it.id in selectedIds }
    val includedCounts = selectedEvents.countByArchiveClass()
    DiagnosticsArchiveNativeEventClass.entries.forEach { eventClass ->
        require(includedCounts.getValue(eventClass) <= sourceCounts.getValue(eventClass)) {
            "Archive native event class count is inconsistent for ${eventClass.name}"
        }
    }
    return DiagnosticsArchiveNativeEventSelection(
        events = selectedEvents,
        sourceCounts = sourceCounts,
        includedCounts = includedCounts,
        omittedCounts =
            DiagnosticsArchiveNativeEventClass.entries.associateWith { eventClass ->
                sourceCounts.getValue(eventClass) - includedCounts.getValue(eventClass)
            },
    )
}

private fun List<NativeSessionEventEntity>.countByArchiveClass(): Map<DiagnosticsArchiveNativeEventClass, Int> {
    val counts = emptyArchiveEventClassCounts().toMutableMap()
    forEach { event ->
        val eventClass = event.archiveEventClass()
        counts[eventClass] = counts.getValue(eventClass) + 1
    }
    return counts
}

private fun DiagnosticsNativeEventArchiveClassCounts.toArchiveEventClassMap():
    Map<DiagnosticsArchiveNativeEventClass, Int> =
    mapOf(
        DiagnosticsArchiveNativeEventClass.TERMINAL to terminal,
        DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL to candidateTrial,
        DiagnosticsArchiveNativeEventClass.CLEANUP to cleanup,
        DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION to runtimeRestoration,
        DiagnosticsArchiveNativeEventClass.OTHER to other,
    )

private fun emptyArchiveEventClassCounts(): Map<DiagnosticsArchiveNativeEventClass, Int> =
    DiagnosticsArchiveNativeEventClass.entries.associateWith { 0 }

private fun Map<DiagnosticsArchiveNativeEventClass, Int>.toArchiveNativeEventClassCounts() =
    DiagnosticsArchiveNativeEventClassCounts(
        terminal = getValue(DiagnosticsArchiveNativeEventClass.TERMINAL),
        candidateTrial = getValue(DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL),
        cleanup = getValue(DiagnosticsArchiveNativeEventClass.CLEANUP),
        runtimeRestoration = getValue(DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION),
        other = getValue(DiagnosticsArchiveNativeEventClass.OTHER),
    )

private fun NativeSessionEventEntity.archiveEventClass(): DiagnosticsArchiveNativeEventClass =
    when (toDataArchiveEventClass()) {
        DiagnosticsNativeEventArchiveClass.TERMINAL -> {
            DiagnosticsArchiveNativeEventClass.TERMINAL
        }

        DiagnosticsNativeEventArchiveClass.CANDIDATE_TRIAL -> {
            DiagnosticsArchiveNativeEventClass.CANDIDATE_TRIAL
        }

        DiagnosticsNativeEventArchiveClass.CLEANUP -> {
            DiagnosticsArchiveNativeEventClass.CLEANUP
        }

        DiagnosticsNativeEventArchiveClass.RUNTIME_RESTORATION -> {
            DiagnosticsArchiveNativeEventClass.RUNTIME_RESTORATION
        }

        DiagnosticsNativeEventArchiveClass.OTHER -> {
            DiagnosticsArchiveNativeEventClass.OTHER
        }
    }
