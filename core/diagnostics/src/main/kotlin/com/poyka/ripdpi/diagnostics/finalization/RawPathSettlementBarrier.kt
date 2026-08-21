package com.poyka.ripdpi.diagnostics.finalization

import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeStatus
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementQuarantineStatePrefix
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal const val RawPathSettlementContextKind = "post_runtime_restore"
internal const val RawPathSettlementSupersededSummary = "Raw-path runtime settlement superseded by user stop"
internal const val RawPathSettlementInconclusiveSummaryPrefix = "Raw-path runtime settlement inconclusive"
internal const val MalformedRawPathSettlementSummary = "Raw-path runtime settlement receipt was unreadable"

internal enum class RawPathHomeSettlementDisposition { USABLE, INCONCLUSIVE, SUPERSEDED_BY_USER_STOP }

internal data class RawPathHomeSettlementEvaluation(
    val disposition: RawPathHomeSettlementDisposition,
    val reason: String,
)

internal fun RawPathExecutionResult.evaluateForHomeContinuation(): RawPathHomeSettlementEvaluation =
    when {
        settlement.outcome == RawPathExecutionSettlementOutcome.SupersededByUserStop -> {
            RawPathHomeSettlementEvaluation(
                RawPathHomeSettlementDisposition.SUPERSEDED_BY_USER_STOP,
                "superseded_by_user_stop",
            )
        }

        executionOutcome != RawPathExecutionOutcome.Completed -> {
            RawPathHomeSettlementEvaluation(
                RawPathHomeSettlementDisposition.INCONCLUSIVE,
                "execution_${executionOutcome.name.lowercase()}",
            )
        }

        else -> {
            settlement.inconclusiveReason()?.let { reason ->
                RawPathHomeSettlementEvaluation(RawPathHomeSettlementDisposition.INCONCLUSIVE, reason)
            } ?: RawPathHomeSettlementEvaluation(
                RawPathHomeSettlementDisposition.USABLE,
                if (settlement.resumeRequired) "post_runtime_running" else "restore_not_required",
            )
        }
    }

private fun com.poyka.ripdpi.data.RawPathExecutionSettlement.inconclusiveReason(): String? =
    when {
        outcome == RawPathExecutionSettlementOutcome.RestoreFailed -> {
            "restore_failed"
        }

        outcome == RawPathExecutionSettlementOutcome.RestorePolicyDisabled -> {
            "restore_policy_disabled"
        }

        resumeRequired && outcome != RawPathExecutionSettlementOutcome.Restored -> {
            "required_resume_not_restored"
        }

        resumeRequired &&
            (postRuntimeContext.status != RawPathRuntimeStatus.Running || postRuntimeContext.mode == null) -> {
            "post_runtime_not_usable"
        }

        else -> {
            null
        }
    }

@Serializable
private data class PendingRawPathSettlement(
    val context: DiagnosticContextEntity,
    val terminalStatus: String,
    val terminalSummary: String,
    val terminalFinishedAt: Long,
)

@Singleton
class RawPathSettlementBarrier
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val rawPathSettlementStore: RawPathSettlementStore,
        @param:Named("diagnosticsJson") private val json: Json,
    ) {
        private companion object {
            const val CommitAttempts = 2
            const val RecoveryBatchSize = 64
            const val MalformedQuarantineValue = "malformed_receipt_v1"
        }

        suspend fun persist(
            context: DiagnosticContextEntity,
            terminalSession: ScanSessionEntity,
        ) {
            val pending =
                PendingRawPathSettlement(
                    context,
                    terminalSession.status,
                    terminalSession.summary,
                    checkNotNull(terminalSession.finishedAt),
                )
            val stagedMarker = rawPathSettlementStore.stageRawPathSettlement(pending.toMarker(json))
            commitWithRetry(stagedMarker, stagedMarker.decodePending(json))
        }

        suspend fun recoverPending() {
            while (true) {
                val markers = rawPathSettlementStore.getPendingRawPathSettlements(RecoveryBatchSize)
                if (markers.isEmpty()) return
                markers.forEach { recoverMarker(it) }
            }
        }

        private suspend fun recoverMarker(marker: DiagnosticsDurableStateEntity) {
            val decoded = runCatching { marker.decodePending(json) }
            when (val failure = decoded.exceptionOrNull()) {
                null -> commitWithRetry(marker, decoded.getOrThrow())
                is CancellationException -> throw failure
                is Error -> throw failure
                else -> quarantineMalformedWithRetry(marker)
            }
        }

        private suspend fun quarantineMalformedWithRetry(marker: DiagnosticsDurableStateEntity) {
            val sessionId = marker.key.removePrefix(RawPathSettlementDurableStatePrefix)
            val quarantineMarker =
                DiagnosticsDurableStateEntity(
                    key = "$RawPathSettlementQuarantineStatePrefix${sessionId.ifBlank { "unattributed" }}",
                    value = MalformedQuarantineValue,
                    updatedAt = marker.updatedAt,
                )
            retryCommit {
                check(
                    rawPathSettlementStore.quarantineMalformedRawPathSettlement(
                        marker,
                        quarantineMarker,
                        sessionId,
                        MalformedRawPathSettlementSummary,
                        marker.updatedAt,
                    ),
                ) {
                    "Raw-path settlement marker changed before quarantine: ${marker.key}"
                }
            }
        }

        private suspend fun commitWithRetry(
            marker: DiagnosticsDurableStateEntity,
            pending: PendingRawPathSettlement,
        ) {
            retryCommit {
                val sessionId = checkNotNull(pending.context.sessionId)
                val currentSession =
                    checkNotNull(scanRecordStore.getScanSession(sessionId)) {
                        "Raw-path scan session disappeared before settlement recovery: $sessionId"
                    }
                val terminal =
                    currentSession.copy(
                        status = pending.terminalStatus,
                        summary = pending.terminalSummary,
                        finishedAt = pending.terminalFinishedAt,
                    )
                check(rawPathSettlementStore.commitRawPathSettlement(marker, pending.context, terminal)) {
                    "Raw-path settlement marker changed before commit: ${marker.key}"
                }
            }
        }

        private suspend fun retryCommit(operation: suspend () -> Unit) {
            var lastFailure: Throwable? = null
            repeat(CommitAttempts) {
                val failure = runCatching { operation() }.exceptionOrNull()
                when (failure) {
                    null -> {
                        return
                    }

                    else -> {
                        failure.throwIfNonRetryable()
                        lastFailure = failure
                    }
                }
            }
            throw RawPathSettlementBarrierException(checkNotNull(lastFailure))
        }
    }

private fun Throwable.throwIfNonRetryable() {
    when (this) {
        is CancellationException -> throw this
        is Error -> throw this
        else -> Unit
    }
}

private fun PendingRawPathSettlement.toMarker(json: Json): DiagnosticsDurableStateEntity {
    val sessionId = checkNotNull(context.sessionId)
    return DiagnosticsDurableStateEntity(
        "$RawPathSettlementDurableStatePrefix$sessionId",
        json.encodeToString(PendingRawPathSettlement.serializer(), this),
        context.capturedAt,
    )
}

private fun DiagnosticsDurableStateEntity.decodePending(json: Json): PendingRawPathSettlement =
    json.decodeFromString(PendingRawPathSettlement.serializer(), value)

internal class RawPathSettlementBarrierException(
    cause: Throwable,
) : IllegalStateException("Failed to publish raw-path settlement barrier", cause)
