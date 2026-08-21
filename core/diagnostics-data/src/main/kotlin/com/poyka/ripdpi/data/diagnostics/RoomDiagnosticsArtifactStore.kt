package com.poyka.ripdpi.data.diagnostics

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDiagnosticsArtifactStore
    private constructor(
        readStore: ArtifactReadStore,
        archiveEventReadStore: ArtifactArchiveEventReadStore,
        writeStore: ArtifactWriteStore,
        rawPathSettlementStore: RoomRawPathSettlementStore,
    ) : DiagnosticsArtifactReadStore by readStore,
        DiagnosticsArtifactQueryStore by readStore,
        DiagnosticsArchiveNativeEventQueryStore by archiveEventReadStore,
        DiagnosticsArtifactWriteStore by writeStore,
        RawPathSettlementStore by rawPathSettlementStore,
        DiagnosticsDurableStateStore by writeStore {
        @Inject
        constructor(
            db: DiagnosticsDatabase,
            dao: DiagnosticsDao,
        ) : this(
            readStore = ArtifactReadStore(dao),
            archiveEventReadStore = ArtifactArchiveEventReadStore(db, dao),
            writeStore = ArtifactWriteStore(db, dao),
            rawPathSettlementStore = RoomRawPathSettlementStore(db, dao),
        )

        private class ArtifactWriteStore(
            private val db: DiagnosticsDatabase,
            private val dao: DiagnosticsDao,
        ) : DiagnosticsArtifactWriteStore,
            DiagnosticsDurableStateStore {
            override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = dao.upsertNetworkSnapshot(snapshot)

            override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) =
                dao.upsertContextSnapshot(snapshot)

            override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) =
                dao.insertTelemetrySample(sample)

            override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) =
                dao.insertNativeSessionEvent(event)

            override suspend fun insertExportRecord(record: ExportRecordEntity) = dao.insertExportRecord(record)

            override suspend fun getDurableState(key: String): DiagnosticsDurableStateEntity? =
                dao.getDiagnosticsDurableState(key)

            override fun observeDurableStateByPrefix(keyPrefix: String): Flow<List<DiagnosticsDurableStateEntity>> =
                dao.observeDiagnosticsDurableStateByPrefix(keyPrefix)

            override suspend fun upsertDurableState(state: DiagnosticsDurableStateEntity) =
                dao.upsertDiagnosticsDurableState(state)

            override suspend fun upsertBoundedDurableState(
                state: DiagnosticsDurableStateEntity,
                keyPrefix: String,
                minimumUpdatedAt: Long,
                retainCount: Int,
            ) {
                db.withTransaction {
                    dao.upsertDiagnosticsDurableState(state)
                    dao.clearDiagnosticsDurableStateByPrefixOlderThan(keyPrefix, minimumUpdatedAt)
                    dao.trimDiagnosticsDurableStateByPrefixToCount(keyPrefix, retainCount)
                }
            }

            override suspend fun clearDurableStateIfCurrent(
                key: String,
                expectedValue: String,
            ): Boolean =
                db.withTransaction {
                    if (dao.getDiagnosticsDurableState(key)?.value != expectedValue) return@withTransaction false
                    dao.clearDiagnosticsDurableState(key, expectedValue)
                    true
                }

            override suspend fun replaceDurableStateIfCurrent(
                state: DiagnosticsDurableStateEntity,
                expectedValue: String,
            ): Boolean =
                dao.replaceDiagnosticsDurableStateIfCurrent(
                    key = state.key,
                    expectedValue = expectedValue,
                    replacementValue = state.value,
                    updatedAt = state.updatedAt,
                ) == 1

            override suspend fun clearDurableStateAndDependencyIfCurrent(
                key: String,
                expectedValue: String,
                dependencyKey: String,
                expectedDependencyValue: String,
            ): Boolean =
                db.withTransaction {
                    if (dao.getDiagnosticsDurableState(key)?.value != expectedValue) return@withTransaction false
                    if (dao.getDiagnosticsDurableState(dependencyKey)?.value != expectedDependencyValue) {
                        return@withTransaction false
                    }
                    dao.clearDiagnosticsDurableState(key, expectedValue)
                    dao.clearDiagnosticsDurableState(dependencyKey, expectedDependencyValue)
                    true
                }

            override suspend fun insertNativeSessionEventAndUpsertDurableState(
                event: NativeSessionEventEntity,
                state: DiagnosticsDurableStateEntity,
            ) {
                db.withTransaction {
                    dao.insertNativeSessionEvent(event)
                    dao.upsertDiagnosticsDurableState(state)
                }
            }

            override suspend fun insertNativeSessionEventAndUpsertDurableStateIfCurrent(
                event: NativeSessionEventEntity,
                state: DiagnosticsDurableStateEntity,
                expectedValue: String,
            ): Boolean =
                db.withTransaction {
                    if (dao.getDiagnosticsDurableState(state.key)?.value != expectedValue) {
                        return@withTransaction false
                    }
                    dao.insertNativeSessionEvent(event)
                    dao.replaceDiagnosticsDurableStateIfCurrent(
                        key = state.key,
                        expectedValue = expectedValue,
                        replacementValue = state.value,
                        updatedAt = state.updatedAt,
                    ) == 1
                }

            override suspend fun insertNativeSessionEventAndClearDurableState(
                event: NativeSessionEventEntity,
                key: String,
                expectedValue: String,
            ) {
                db.withTransaction {
                    dao.insertNativeSessionEvent(event)
                    dao.clearDiagnosticsDurableState(key = key, expectedValue = expectedValue)
                }
            }

            override suspend fun insertNativeSessionEventAndClearDurableStateIfCurrent(
                event: NativeSessionEventEntity,
                key: String,
                expectedValue: String,
            ): Boolean =
                db.withTransaction {
                    if (dao.getDiagnosticsDurableState(key)?.value == expectedValue) {
                        dao.insertNativeSessionEvent(event)
                        dao.clearDiagnosticsDurableState(key = key, expectedValue = expectedValue)
                        true
                    } else {
                        false
                    }
                }

            override suspend fun reconcileDurableStateWithTerminalEvent(
                key: String,
                expectedValue: String,
                replacementState: DiagnosticsDurableStateEntity,
                terminalEventId: String,
                missingTerminalEvent: NativeSessionEventEntity,
            ) {
                db.withTransaction {
                    if (dao.getDiagnosticsDurableState(key)?.value == expectedValue) {
                        if (dao.getNativeEventById(terminalEventId) == null) {
                            dao.insertNativeSessionEvent(missingTerminalEvent)
                        }
                        dao.clearDiagnosticsDurableState(key = key, expectedValue = expectedValue)
                    }
                    dao.upsertDiagnosticsDurableState(replacementState)
                }
            }
        }
    }

private class RoomRawPathSettlementStore(
    private val db: DiagnosticsDatabase,
    private val dao: DiagnosticsDao,
) : RawPathSettlementStore {
    override suspend fun stageRawPathSettlement(marker: DiagnosticsDurableStateEntity): DiagnosticsDurableStateEntity =
        db.withTransaction {
            dao.getDiagnosticsDurableState(marker.key)
                ?: marker.also { dao.upsertDiagnosticsDurableState(it) }
        }

    override suspend fun getPendingRawPathSettlements(limit: Int): List<DiagnosticsDurableStateEntity> =
        dao.getDiagnosticsDurableStateByPrefix(RawPathSettlementDurableStatePrefix, limit)

    override suspend fun commitRawPathSettlement(
        marker: DiagnosticsDurableStateEntity,
        context: DiagnosticContextEntity,
        terminalSession: ScanSessionEntity,
    ): Boolean =
        db.withTransaction {
            if (dao.getDiagnosticsDurableState(marker.key)?.value != marker.value) return@withTransaction false
            dao.upsertContextSnapshot(context)
            dao.upsertScanSession(terminalSession)
            dao.clearDiagnosticsDurableState(marker.key, marker.value)
            true
        }

    override suspend fun quarantineMalformedRawPathSettlement(
        marker: DiagnosticsDurableStateEntity,
        quarantineMarker: DiagnosticsDurableStateEntity,
        sessionId: String,
        terminalSummary: String,
        finishedAt: Long,
    ): Boolean =
        db.withTransaction {
            if (dao.getDiagnosticsDurableState(marker.key)?.value != marker.value) return@withTransaction false
            dao.getScanSession(sessionId)?.takeIf { it.status != "failed" }?.let { session ->
                dao.upsertScanSession(
                    session.copy(status = "failed", summary = terminalSummary, finishedAt = finishedAt),
                )
            }
            dao.upsertDiagnosticsDurableState(quarantineMarker)
            dao.clearDiagnosticsDurableState(marker.key, marker.value)
            true
        }
}
