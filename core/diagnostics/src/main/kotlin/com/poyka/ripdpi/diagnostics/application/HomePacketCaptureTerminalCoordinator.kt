@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class HomePacketCaptureTerminalCoordinator(
    private val scope: CoroutineScope,
) {
    private companion object {
        private const val SettlementRetryDelayMillis = 100L
        private const val RecoveryRetryDelayMillis = 1_000L
        private const val TerminalSettlementAttempts = 2
        private val log = Logger.withTag("HomeAnalysis")
    }

    private val dispositions = ConcurrentHashMap<String, DiagnosticsHomePacketCaptureDisposition>()
    private val settlers =
        ConcurrentHashMap<String, suspend (String) -> DiagnosticsHomePacketCaptureDisposition?>()

    suspend fun admit(
        runId: String,
        options: DiagnosticsHomeRunOptions,
    ): DiagnosticsHomePacketCaptureDisposition =
        withContext(NonCancellable) {
            val disposition =
                try {
                    options.admitPacketCapture(runId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DiagnosticsHomePacketCaptureDisposition(
                        requested = options.packetCaptureRequested,
                        outcome = DiagnosticsHomePacketCaptureOutcome.FAILED,
                        failureCode = "capture_admission_failed",
                    )
                }
            dispositions[runId] = disposition
            settlers[runId] = options.settlePacketCapture
            disposition
        }

    suspend fun settle(runId: String): Boolean =
        withContext(NonCancellable) {
            val settler = settlers[runId] ?: return@withContext true
            var current = dispositions[runId]
            var attempts = 0
            while (
                current?.outcome == DiagnosticsHomePacketCaptureOutcome.RECORDING &&
                attempts < TerminalSettlementAttempts
            ) {
                attempts += 1
                val disposition =
                    runCatching { settler(runId) }
                        .getOrNull()
                        ?: current.copy(failureCode = "capture_settlement_failed")
                dispositions[runId] = disposition
                current = disposition
                if (disposition.outcome == DiagnosticsHomePacketCaptureOutcome.RECORDING) {
                    delay(SettlementRetryDelayMillis)
                }
            }
            if (current?.outcome != DiagnosticsHomePacketCaptureOutcome.RECORDING) {
                settlers.remove(runId, settler)
                return@withContext true
            }
            dispositions[runId] =
                requireNotNull(current).copy(
                    outcome = DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING,
                    failureCode = "capture_cleanup_pending",
                )
            if (settlers.remove(runId, settler)) {
                scheduleRecovery(runId, settler)
            }
            true
        }

    fun disposition(runId: String): DiagnosticsHomePacketCaptureDisposition =
        dispositions[runId] ?: DiagnosticsHomePacketCaptureDisposition.notRequested()

    fun clear(runId: String) {
        settlers.remove(runId)
        dispositions.remove(runId)
    }

    private fun scheduleRecovery(
        runId: String,
        settler: suspend (String) -> DiagnosticsHomePacketCaptureDisposition?,
    ) {
        scope.launch {
            var terminal = false
            while (!terminal) {
                val disposition =
                    withContext(NonCancellable) {
                        runCatching { settler(runId) }.getOrNull()
                    }
                terminal = disposition?.outcome?.let { it != DiagnosticsHomePacketCaptureOutcome.RECORDING } == true
                if (!terminal) delay(RecoveryRetryDelayMillis)
            }
            log.i { "packet capture recovery completed after report finalization: runId=$runId" }
        }
    }
}
