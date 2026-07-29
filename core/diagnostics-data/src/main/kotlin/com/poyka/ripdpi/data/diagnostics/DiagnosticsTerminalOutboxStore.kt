package com.poyka.ripdpi.data.diagnostics

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

interface DiagnosticsTerminalOutboxStore {
    suspend fun beginTerminalOutbox(
        finishedSession: BypassUsageSessionEntity,
        marker: DiagnosticsDurableStateEntity,
        policyDependency: DiagnosticsDurableStateEntity?,
    ): DiagnosticsDurableStateEntity

    suspend fun getPendingTerminalOutboxes(limit: Int = 64): List<DiagnosticsDurableStateEntity>

    suspend fun getTerminalOutbox(key: String): DiagnosticsDurableStateEntity?

    suspend fun checkpointTerminalOutbox(
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean

    suspend fun checkpointTerminalArtifacts(
        events: List<NativeSessionEventEntity>,
        telemetrySample: TelemetrySampleEntity?,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean

    suspend fun checkpointTerminalPolicy(
        policy: RememberedNetworkPolicyEntity?,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean

    suspend fun checkpointTerminalSession(
        finishedSession: BypassUsageSessionEntity,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean

    suspend fun completeTerminalOutbox(
        marker: DiagnosticsDurableStateEntity,
        retainPolicyDependency: Boolean = false,
    ): Boolean

    suspend fun completeTerminalOutboxWithAssessment(
        assessment: NativeSessionEventEntity,
        marker: DiagnosticsDurableStateEntity,
        retainPolicyDependency: Boolean = false,
    ): Boolean
}

@Singleton
class RoomDiagnosticsTerminalOutboxStore
    @Inject
    constructor(
        private val db: DiagnosticsDatabase,
        private val dao: DiagnosticsDao,
    ) : DiagnosticsTerminalOutboxStore {
        override suspend fun beginTerminalOutbox(
            finishedSession: BypassUsageSessionEntity,
            marker: DiagnosticsDurableStateEntity,
            policyDependency: DiagnosticsDurableStateEntity?,
        ): DiagnosticsDurableStateEntity =
            db.withTransaction {
                dao.upsertBypassUsageSession(finishedSession)
                policyDependency?.let { dependency -> dao.upsertDiagnosticsDurableState(dependency) }
                val current = dao.getDiagnosticsDurableState(marker.key)
                if (current == null) {
                    dao.upsertDiagnosticsDurableState(marker)
                    marker
                } else {
                    current
                }
            }

        override suspend fun getPendingTerminalOutboxes(limit: Int): List<DiagnosticsDurableStateEntity> =
            db.withTransaction {
                migrateLegacyTerminalOutboxes(limit)
                dao.getDiagnosticsDurableStateByPrefix(TerminalOutboxDurableStatePrefix, limit)
            }

        override suspend fun getTerminalOutbox(key: String): DiagnosticsDurableStateEntity? =
            dao.getDiagnosticsDurableState(key)

        override suspend fun checkpointTerminalOutbox(
            expectedMarker: DiagnosticsDurableStateEntity,
            replacementMarker: DiagnosticsDurableStateEntity,
        ): Boolean = replaceMarkerIfCurrent(expectedMarker, replacementMarker)

        override suspend fun checkpointTerminalArtifacts(
            events: List<NativeSessionEventEntity>,
            telemetrySample: TelemetrySampleEntity?,
            expectedMarker: DiagnosticsDurableStateEntity,
            replacementMarker: DiagnosticsDurableStateEntity,
        ): Boolean =
            db.withTransaction {
                if (!isCurrent(expectedMarker)) return@withTransaction false
                events.forEach { event -> dao.insertNativeSessionEvent(event) }
                telemetrySample?.let { sample -> dao.insertTelemetrySample(sample) }
                replaceMarkerIfCurrent(expectedMarker, replacementMarker)
            }

        override suspend fun checkpointTerminalPolicy(
            policy: RememberedNetworkPolicyEntity?,
            expectedMarker: DiagnosticsDurableStateEntity,
            replacementMarker: DiagnosticsDurableStateEntity,
        ): Boolean =
            db.withTransaction {
                if (!isCurrent(expectedMarker)) return@withTransaction false
                policy?.let { dao.upsertRememberedNetworkPolicy(it) }
                replaceMarkerIfCurrent(expectedMarker, replacementMarker)
            }

        override suspend fun checkpointTerminalSession(
            finishedSession: BypassUsageSessionEntity,
            expectedMarker: DiagnosticsDurableStateEntity,
            replacementMarker: DiagnosticsDurableStateEntity,
        ): Boolean =
            db.withTransaction {
                if (!isCurrent(expectedMarker)) return@withTransaction false
                dao.upsertBypassUsageSession(finishedSession)
                replaceMarkerIfCurrent(expectedMarker, replacementMarker)
            }

        override suspend fun completeTerminalOutbox(
            marker: DiagnosticsDurableStateEntity,
            retainPolicyDependency: Boolean,
        ): Boolean =
            db.withTransaction {
                if (!isCurrent(marker)) return@withTransaction false
                dao.clearDiagnosticsDurableState(marker.key, marker.value)
                if (!retainPolicyDependency) {
                    dao.clearDiagnosticsDurableState(terminalPolicyDependencyKey(marker.key))
                }
                true
            }

        override suspend fun completeTerminalOutboxWithAssessment(
            assessment: NativeSessionEventEntity,
            marker: DiagnosticsDurableStateEntity,
            retainPolicyDependency: Boolean,
        ): Boolean =
            db.withTransaction {
                if (!isCurrent(marker)) return@withTransaction false
                dao.insertNativeSessionEvent(assessment)
                dao.clearDiagnosticsDurableState(marker.key, marker.value)
                if (!retainPolicyDependency) {
                    dao.clearDiagnosticsDurableState(terminalPolicyDependencyKey(marker.key))
                }
                true
            }

        private suspend fun replaceMarkerIfCurrent(
            expectedMarker: DiagnosticsDurableStateEntity,
            replacementMarker: DiagnosticsDurableStateEntity,
        ): Boolean =
            dao.replaceDiagnosticsDurableStateIfCurrent(
                key = expectedMarker.key,
                expectedValue = expectedMarker.value,
                replacementValue = replacementMarker.value,
                updatedAt = replacementMarker.updatedAt,
            ) == 1

        private suspend fun isCurrent(marker: DiagnosticsDurableStateEntity): Boolean =
            dao.getDiagnosticsDurableState(marker.key)?.value == marker.value

        private suspend fun migrateLegacyTerminalOutboxes(limit: Int) {
            dao.getPendingTerminalOutboxes(limit).forEach { legacyMarker ->
                val durableKey = legacyMarker.id.takeIf { it.startsWith(TerminalOutboxDurableStatePrefix) }
                if (durableKey != null && dao.getDiagnosticsDurableState(durableKey) == null) {
                    dao.upsertDiagnosticsDurableState(
                        DiagnosticsDurableStateEntity(
                            key = durableKey,
                            value = legacyMarker.message,
                            updatedAt = legacyMarker.createdAt,
                        ),
                    )
                }
                dao.deleteNativeSessionEvent(legacyMarker.id)
            }
        }
    }

const val TerminalOutboxDurableStatePrefix = "runtime_terminal_outbox:"
const val TerminalPolicyDependencyDurableStatePrefix = "runtime_terminal_policy:"
const val PolicyHandoverDeliveryDurableStatePrefix = "policy_handover_delivery:"

private fun terminalPolicyDependencyKey(markerKey: String): String =
    "$TerminalPolicyDependencyDurableStatePrefix${markerKey.removePrefix(TerminalOutboxDurableStatePrefix)}"
