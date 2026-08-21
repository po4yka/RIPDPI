package com.poyka.ripdpi.data.diagnostics

import androidx.room.withTransaction

internal class ArtifactArchiveEventReadStore(
    private val db: DiagnosticsDatabase,
    private val dao: DiagnosticsDao,
) : DiagnosticsArchiveNativeEventQueryStore {
    override suspend fun getNativeEventArchiveSourceForSession(
        sessionId: String,
        newestLimit: Int,
        criticalClassLimit: Int,
    ): DiagnosticsNativeEventArchiveSource =
        db.withTransaction {
            requireArchiveEventLimits(newestLimit, criticalClassLimit)
            val newestEvents = dao.getNativeEventsForSession(sessionId = sessionId, limit = newestLimit)
            DiagnosticsNativeEventArchiveSource(
                events =
                    buildArchiveEvents(newestEvents) { eventClass ->
                        dao.getNativeEventsForSessionByArchiveClass(
                            sessionId = sessionId,
                            archiveClass = eventClass.storageValue,
                            limit = criticalClassLimit,
                        )
                    },
                sourceCounts = dao.countNativeEventArchiveClassesForSession(sessionId).toArchiveClassCounts(),
            )
        }

    override suspend fun getGlobalNativeEventArchiveSource(
        newestLimit: Int,
        criticalClassLimit: Int,
    ): DiagnosticsNativeEventArchiveSource =
        db.withTransaction {
            requireArchiveEventLimits(newestLimit, criticalClassLimit)
            DiagnosticsNativeEventArchiveSource(
                events =
                    buildArchiveEvents(dao.getGlobalNativeEvents(limit = newestLimit)) { eventClass ->
                        dao.getGlobalNativeEventsByArchiveClass(
                            archiveClass = eventClass.storageValue,
                            limit = criticalClassLimit,
                        )
                    },
                sourceCounts = dao.countGlobalNativeEventArchiveClasses().toArchiveClassCounts(),
            )
        }

    private suspend fun buildArchiveEvents(
        newestEvents: List<NativeSessionEventEntity>,
        loadCriticalEvents: suspend (DiagnosticsNativeEventArchiveClass) -> List<NativeSessionEventEntity>,
    ): List<NativeSessionEventEntity> =
        buildList {
            addAll(newestEvents)
            DiagnosticsNativeEventArchiveClass.entries
                .filterNot { eventClass -> eventClass == DiagnosticsNativeEventArchiveClass.OTHER }
                .forEach { eventClass -> addAll(loadCriticalEvents(eventClass)) }
        }.distinctBy(NativeSessionEventEntity::id)
}

private fun requireArchiveEventLimits(
    newestLimit: Int,
    criticalClassLimit: Int,
) {
    require(newestLimit >= 0) { "Archive native event newest limit must be non-negative" }
    require(criticalClassLimit >= 0) { "Archive native event critical class limit must be non-negative" }
}

private fun List<DiagnosticsNativeEventArchiveClassCountRow>.toArchiveClassCounts():
    DiagnosticsNativeEventArchiveClassCounts {
    val counts = associate { row -> row.archiveClass to row.eventCount }
    return DiagnosticsNativeEventArchiveClassCounts(
        terminal = counts[DiagnosticsNativeEventArchiveClass.TERMINAL.storageValue] ?: 0,
        candidateTrial = counts[DiagnosticsNativeEventArchiveClass.CANDIDATE_TRIAL.storageValue] ?: 0,
        cleanup = counts[DiagnosticsNativeEventArchiveClass.CLEANUP.storageValue] ?: 0,
        runtimeRestoration = counts[DiagnosticsNativeEventArchiveClass.RUNTIME_RESTORATION.storageValue] ?: 0,
        other = counts[DiagnosticsNativeEventArchiveClass.OTHER.storageValue] ?: 0,
    )
}
