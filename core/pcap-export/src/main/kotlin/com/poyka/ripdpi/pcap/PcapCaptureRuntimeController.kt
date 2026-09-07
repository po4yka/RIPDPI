package com.poyka.ripdpi.pcap

import com.poyka.ripdpi.core.Tun2SocksBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Current user-visible state of a live tunnel PCAP capture. */
sealed interface PcapCaptureRuntimeState {
    data object Idle : PcapCaptureRuntimeState

    data class Recording(
        val captureSetId: Long,
    ) : PcapCaptureRuntimeState

    data class Completed(
        val receipt: PcapCaptureCompletionReceipt,
    ) : PcapCaptureRuntimeState

    data class StopFailed(
        val captureSetId: Long,
        val code: String,
    ) : PcapCaptureRuntimeState

    /** Safe native or lifecycle code; never a filesystem path or exception message. */
    data class Failed(
        val receipt: PcapCaptureCompletionReceipt? = null,
        val code: String,
    ) : PcapCaptureRuntimeState
}

sealed interface PcapCaptureLeaseAcquisition {
    data class Acquired(
        val recording: PcapCaptureRuntimeState.Recording,
    ) : PcapCaptureLeaseAcquisition

    data class AlreadyActive(
        val state: PcapCaptureRuntimeState,
    ) : PcapCaptureLeaseAcquisition

    data class Failed(
        val state: PcapCaptureRuntimeState.Failed,
    ) : PcapCaptureLeaseAcquisition
}

/**
 * Process-wide coordinator between the diagnostics UI and the active VPN
 * tunnel. The VPN runtime binds its live bridge and retires it before native
 * teardown; the UI only ever observes a successful native start as recording.
 */
@Singleton
class PcapCaptureRuntimeController
    internal constructor(
        private val startCapture: suspend (Long) -> Long,
        private val stopCapture: suspend (Long) -> PcapStopResult,
    ) {
        @Inject
        constructor(
            pcapController: PcapController,
        ) : this(
            startCapture = { handle -> pcapController.start(handle, pcapController.captureDirectory) },
            stopCapture = pcapController::stop,
        )

        private val mutex = Mutex()
        private val mutableState = MutableStateFlow<PcapCaptureRuntimeState>(PcapCaptureRuntimeState.Idle)
        private var boundBridge: Tun2SocksBridge? = null
        private var activeBridge: Tun2SocksBridge? = null
        private var activeCaptureSetId: Long? = null
        private var terminalCaptureSetId: Long? = null
        private var terminalState: PcapCaptureRuntimeState? = null

        val state: StateFlow<PcapCaptureRuntimeState> = mutableState

        /**
         * Called by the VPN runtime only after a native tunnel has started.
         *
         * # Cancel safety:
         * Binding is non-cancellable once entered, so a live bridge is either
         * published under the mutex or the call has not started.
         */
        suspend fun bindTunnel(bridge: Tun2SocksBridge) {
            withContext(NonCancellable) {
                mutex.withLock {
                    check(activeBridge == null) { "Cannot replace a tunnel while PCAP capture is active" }
                    boundBridge = bridge
                }
            }
        }

        /**
         * Finalizes capture before [bridge] is stopped or destroyed. This keeps
         * the writer result observable instead of letting native retirement
         * discard it during session teardown.
         *
         * # Cancel safety:
         * Retirement and terminal receipt publication execute in
         * [NonCancellable] while holding the lifecycle mutex.
         */
        suspend fun retireTunnel(bridge: Tun2SocksBridge) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeBridge === bridge) {
                        stopLocked(bridge)
                    }
                    if (boundBridge === bridge) {
                        boundBridge = null
                    }
                }
            }
        }

        suspend fun start(): PcapCaptureRuntimeState =
            mutex.withLock {
                startLocked()
            }

        private suspend fun startLocked(): PcapCaptureRuntimeState =
            activeBridge?.let { mutableState.value }
                ?: boundBridge?.let { bridge -> startOnBoundBridge(bridge) }
                ?: failLocked("tunnel_unavailable")

        /**
         * # Cancel safety:
         * Native start and lease publication run together without cancellation.
         * A cancelled caller can still stop the published capture.
         */
        private suspend fun startOnBoundBridge(bridge: Tun2SocksBridge): PcapCaptureRuntimeState =
            withContext(NonCancellable) {
                val captureSetId =
                    try {
                        bridge.withSessionHandle { handle -> startCapture(handle) } ?: 0L
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@withContext failLocked("start_failed")
                    }
                if (captureSetId <= 0L) {
                    failLocked("start_failed")
                } else {
                    activeBridge = bridge
                    activeCaptureSetId = captureSetId
                    terminalCaptureSetId = null
                    terminalState = null
                    PcapCaptureRuntimeState.Recording(captureSetId).also { mutableState.value = it }
                }
            }

        suspend fun startIfIdle(): PcapCaptureLeaseAcquisition =
            mutex.withLock {
                if (activeBridge != null) return@withLock PcapCaptureLeaseAcquisition.AlreadyActive(mutableState.value)
                when (val state = startLocked()) {
                    is PcapCaptureRuntimeState.Recording -> PcapCaptureLeaseAcquisition.Acquired(state)
                    is PcapCaptureRuntimeState.Failed -> PcapCaptureLeaseAcquisition.Failed(state)
                    else -> error("startLocked returned non-terminal state: $state")
                }
            }

        /**
         * Runs capture-file maintenance against the current native lease.
         * The callback must not call this controller. It holds the lifecycle
         * mutex so cleanup cannot race native start, publication or stop.
         *
         * # Cancel safety:
         * The lock wait can be cancelled. The callback does not suspend.
         */
        suspend fun withCaptureStorageLock(block: (activeCaptureSetId: Long?) -> Unit) {
            mutex.withLock { block(activeCaptureSetId) }
        }

        suspend fun stop(): PcapCaptureRuntimeState =
            withContext(NonCancellable) {
                mutex.withLock {
                    val bridge =
                        activeBridge
                            ?: return@withLock PcapCaptureRuntimeState.Idle.also { mutableState.value = it }
                    stopLocked(bridge)
                }
            }

        suspend fun stopIfOwned(captureSetId: Long): PcapCaptureRuntimeState? =
            withContext(NonCancellable) {
                mutex.withLock {
                    val bridge =
                        activeBridge
                            ?: return@withLock terminalState?.takeIf { terminalCaptureSetId == captureSetId }
                    if (activeCaptureSetId != captureSetId) return@withLock null
                    stopLocked(bridge)
                }
            }

        private suspend fun stopLocked(bridge: Tun2SocksBridge): PcapCaptureRuntimeState {
            val captureSetId = checkNotNull(activeCaptureSetId)
            var failureCode: String? = null
            val result =
                try {
                    bridge.withSessionHandle(stopCapture)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failureCode = "stop_failed"
                    null
                }
            if (failureCode == null) {
                failureCode =
                    when {
                        result == null -> "session_unavailable"
                        !result.wasActive -> "capture_missing"
                        else -> result.failure
                    }
            }
            return if (failureCode == null) {
                activeBridge = null
                activeCaptureSetId = null
                PcapCaptureRuntimeState
                    .Completed(
                        PcapCaptureCompletionReceipt(
                            captureSetId = captureSetId,
                            totalDrops = result?.totalDrops,
                            outcome = PcapCaptureOutcome.CapturedSeparate,
                        ),
                    ).also(::publishTerminalLocked)
            } else if (result != null) {
                activeBridge = null
                activeCaptureSetId = null
                PcapCaptureRuntimeState
                    .Failed(
                        receipt =
                            PcapCaptureCompletionReceipt(
                                captureSetId = captureSetId,
                                totalDrops = result.totalDrops,
                                outcome = PcapCaptureOutcome.Failed,
                            ),
                        code = failureCode,
                    ).also(::publishTerminalLocked)
            } else {
                PcapCaptureRuntimeState.StopFailed(captureSetId, failureCode).also { mutableState.value = it }
            }
        }

        private fun failLocked(code: String): PcapCaptureRuntimeState.Failed =
            PcapCaptureRuntimeState.Failed(code = code).also { mutableState.value = it }

        private fun publishTerminalLocked(state: PcapCaptureRuntimeState): PcapCaptureRuntimeState {
            terminalCaptureSetId =
                when (state) {
                    is PcapCaptureRuntimeState.Completed -> state.receipt.captureSetId
                    is PcapCaptureRuntimeState.Failed -> state.receipt?.captureSetId
                    else -> null
                }
            terminalState = state
            mutableState.value = state
            return state
        }
    }
