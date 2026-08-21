package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsHomePacketCaptureDisposition
import com.poyka.ripdpi.diagnostics.DiagnosticsHomePacketCaptureOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeRunOptions
import com.poyka.ripdpi.pcap.PcapCaptureLeaseAcquisition
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class HomePcapCaptureLeaseCoordinator(
    private val controller: PcapCaptureRuntimeController,
) {
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, Long>()

    fun runOptions(requested: Boolean): DiagnosticsHomeRunOptions =
        DiagnosticsHomeRunOptions(
            packetCaptureRequested = requested,
            admitPacketCapture = { runId -> begin(runId, requested) },
            settlePacketCapture = ::finish,
        )

    suspend fun begin(
        runId: String,
        requested: Boolean,
    ): DiagnosticsHomePacketCaptureDisposition =
        withContext(NonCancellable) {
            if (!requested) return@withContext DiagnosticsHomePacketCaptureDisposition.notRequested()
            mutex.withLock {
                when (val acquired = controller.startIfIdle()) {
                    is PcapCaptureLeaseAcquisition.Acquired -> {
                        leases[runId] = acquired.recording.captureSetId
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.RECORDING,
                            acquired.recording.captureSetId,
                        )
                    }

                    is PcapCaptureLeaseAcquisition.AlreadyActive -> {
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.UNAVAILABLE,
                            failureCode = "capture_already_active",
                        )
                    }

                    is PcapCaptureLeaseAcquisition.Failed -> {
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.FAILED,
                            failureCode = acquired.state.code,
                        )
                    }
                }
            }
        }

    suspend fun finish(runId: String): DiagnosticsHomePacketCaptureDisposition? =
        withContext(NonCancellable) {
            mutex.withLock {
                val id = leases[runId] ?: return@withLock null
                var terminal = controller.stopIfOwned(id)
                if (terminal is PcapCaptureRuntimeState.StopFailed) terminal = controller.stopIfOwned(id)
                if (terminal is PcapCaptureRuntimeState.StopFailed) {
                    return@withLock DiagnosticsHomePacketCaptureDisposition(
                        true,
                        DiagnosticsHomePacketCaptureOutcome.RECORDING,
                        id,
                        failureCode = terminal.code,
                    )
                }
                leases.remove(runId)
                when (terminal) {
                    is PcapCaptureRuntimeState.Completed -> {
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE,
                            totalDrops = terminal.receipt.totalDrops,
                        )
                    }

                    is PcapCaptureRuntimeState.Failed -> {
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.FAILED,
                            totalDrops = terminal.receipt?.totalDrops,
                            failureCode = terminal.code,
                        )
                    }

                    else -> {
                        DiagnosticsHomePacketCaptureDisposition(
                            true,
                            DiagnosticsHomePacketCaptureOutcome.FAILED,
                            failureCode = "capture_lease_superseded",
                        )
                    }
                }
            }
        }
}
