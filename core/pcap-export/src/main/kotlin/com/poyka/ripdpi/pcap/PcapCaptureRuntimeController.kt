package com.poyka.ripdpi.pcap

import com.poyka.ripdpi.core.Tun2SocksBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class PcapCaptureRuntimeController
    internal constructor(
        private val startCapture: suspend (Long) -> Long,
        private val stopCapture: suspend (Long) -> PcapStopResult,
        private val pollCompletion: (suspend (Long) -> PcapStopResult?)? = null,
        private val monitorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        @Inject
        constructor(
            pcapController: PcapController,
        ) : this(
            startCapture = { handle -> pcapController.start(handle, pcapController.captureDirectory) },
            stopCapture = pcapController::stop,
            pollCompletion = pcapController::pollCompletion,
        )

        private val mutex = Mutex()
        private val mutableState = MutableStateFlow<PcapCaptureRuntimeState>(PcapCaptureRuntimeState.Idle)
        private var boundTunnel: CaptureTunnel? = null
        private var activeBridge: Tun2SocksBridge? = null
        private var activeCaptureSetId: Long? = null
        private var terminalCaptureSetId: Long? = null
        private var terminalState: PcapCaptureRuntimeState? = null

        private val ownedReceipts = mutableMapOf<Long, PcapCaptureRuntimeState?>()
        private var completionMonitor: Job? = null

        private data class CaptureTunnel(
            val bridge: Tun2SocksBridge,
            val captureAllowed: Flow<Boolean>,
        )

        val state: StateFlow<PcapCaptureRuntimeState> = mutableState

        /**
         * # Cancel safety:
         * Binding is non-cancellable once entered, so a live bridge is either
         * published under the mutex or the call has not started.
         */
        suspend fun bindTunnel(
            bridge: Tun2SocksBridge,
            captureAllowed: Flow<Boolean>,
        ) {
            withContext(NonCancellable) {
                mutex.withLock {
                    check(activeBridge == null) { "Cannot replace a tunnel while PCAP capture is active" }
                    boundTunnel = CaptureTunnel(bridge, captureAllowed)
                }
            }
        }

        /**
         * # Cancel safety:
         * Retirement and terminal receipt publication execute in
         * [NonCancellable] while holding the lifecycle mutex.
         */
        suspend fun retireTunnel(bridge: Tun2SocksBridge) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeBridge === bridge) {
                        val stopped = stopLocked(bridge)
                        if (stopped is PcapCaptureRuntimeState.StopFailed) retireFailedStopLocked(stopped)
                    }
                    if (boundTunnel?.bridge === bridge) {
                        boundTunnel = null
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
                ?: boundTunnel?.let { startOnBoundTunnel(it) }
                ?: failLocked("tunnel_unavailable")

        /**
         * # Cancel safety:
         * Native start and lease publication run together without cancellation.
         * A cancelled caller can still stop the published capture.
         */
        private suspend fun startOnBoundTunnel(tunnel: CaptureTunnel): PcapCaptureRuntimeState {
            val permission =
                try {
                    withTimeoutOrNull(CONSENT_READ_TIMEOUT_MS) { tunnel.captureAllowed.firstOrNull() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (permission != true) {
                return failLocked(if (permission == false) "capture_disabled" else "capture_consent_unavailable")
            }

            return withContext(NonCancellable) {
                val captureSetId =
                    try {
                        tunnel.bridge.withSessionHandle { handle -> startCapture(handle) } ?: 0L
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@withContext failLocked("start_failed")
                    }
                if (captureSetId <= 0L) {
                    failLocked("start_failed")
                } else {
                    activeBridge = tunnel.bridge
                    activeCaptureSetId = captureSetId
                    terminalCaptureSetId = null
                    terminalState = null
                    PcapCaptureRuntimeState.Recording(captureSetId).also {
                        mutableState.value = it
                        monitorCompletion(tunnel, captureSetId)
                    }
                }
            }
        }

        private fun monitorCompletion(
            tunnel: CaptureTunnel,
            captureSetId: Long,
        ) {
            val poll = pollCompletion ?: return
            completionMonitor =
                monitorScope.launch {
                    launch { stopOnConsentWithdrawal(tunnel, captureSetId) }
                    while (true) {
                        delay(COMPLETION_POLL_INTERVAL_MS)
                        if (pollLocked(tunnel.bridge, captureSetId, poll)) break
                    }
                }
        }

        private suspend fun stopOnConsentWithdrawal(
            tunnel: CaptureTunnel,
            captureSetId: Long,
        ) {
            try {
                tunnel.captureAllowed.firstOrNull { !it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Stop recording if the saved permission becomes unavailable.
            }
            while (true) {
                val retry =
                    withContext(NonCancellable) {
                        mutex.withLock {
                            activeBridge === tunnel.bridge && activeCaptureSetId == captureSetId &&
                                stopLocked(tunnel.bridge) is PcapCaptureRuntimeState.StopFailed
                        }
                    }
                if (!retry) return
                delay(COMPLETION_POLL_INTERVAL_MS)
            }
        }

        /**
         * # Cancel safety:
         * Poll can consume a native receipt. Collection and publication run together
         * under the lease mutex and cannot be cancelled once the poll starts.
         */
        private suspend fun pollLocked(
            bridge: Tun2SocksBridge,
            captureSetId: Long,
            poll: suspend (Long) -> PcapStopResult?,
        ): Boolean =
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeBridge !== bridge || activeCaptureSetId != captureSetId) return@withLock true
                    var hadSession = false
                    val result =
                        try {
                            bridge.withSessionHandle { handle ->
                                hadSession = true
                                poll(handle)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            stopFailedLocked(captureSetId, "poll_failed")
                            return@withLock false
                        }
                    if (!hadSession) {
                        retireFailedStopLocked(PcapCaptureRuntimeState.StopFailed(captureSetId, "session_unavailable"))
                        return@withLock true
                    }
                    if (result == null) return@withLock false
                    publishStopLocked(captureSetId, result)
                    activeCaptureSetId != captureSetId
                }
            }

        suspend fun startIfIdle(): PcapCaptureLeaseAcquisition =
            mutex.withLock {
                if (activeBridge != null) return@withLock PcapCaptureLeaseAcquisition.AlreadyActive(mutableState.value)
                when (val state = startLocked()) {
                    is PcapCaptureRuntimeState.Recording -> {
                        ownedReceipts[state.captureSetId] = null
                        PcapCaptureLeaseAcquisition.Acquired(state)
                    }

                    is PcapCaptureRuntimeState.Failed -> {
                        PcapCaptureLeaseAcquisition.Failed(state)
                    }

                    else -> {
                        error("startLocked returned non-terminal state: $state")
                    }
                }
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
                    val bridge = activeBridge
                    if (bridge != null && activeCaptureSetId == captureSetId) {
                        val state = stopLocked(bridge)
                        if (state !is PcapCaptureRuntimeState.StopFailed) ownedReceipts.remove(captureSetId)
                        return@withLock state
                    }
                    ownedReceipts.remove(captureSetId) ?: terminalState?.takeIf { terminalCaptureSetId == captureSetId }
                }
            }

        private suspend fun stopLocked(bridge: Tun2SocksBridge): PcapCaptureRuntimeState {
            val captureSetId = checkNotNull(activeCaptureSetId)
            val result =
                try {
                    bridge.withSessionHandle(stopCapture)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return stopFailedLocked(captureSetId, "stop_failed")
                }
            return if (result == null) {
                stopFailedLocked(captureSetId, "session_unavailable")
            } else {
                publishStopLocked(captureSetId, result)
            }
        }

        private fun publishStopLocked(
            captureSetId: Long,
            result: PcapStopResult,
        ): PcapCaptureRuntimeState {
            val failure = if (result.wasActive) result.failure else "capture_missing"
            if (failure == "timeout") return stopFailedLocked(captureSetId, failure)
            clearActiveLocked()
            val receipt =
                PcapCaptureCompletionReceipt(
                    captureSetId = captureSetId,
                    totalDrops = result.totalDrops,
                    outcome = if (failure == null) PcapCaptureOutcome.CapturedSeparate else PcapCaptureOutcome.Failed,
                    limitReached = result.limitReached,
                )
            return publishTerminalLocked(
                if (failure == null) {
                    PcapCaptureRuntimeState.Completed(receipt)
                } else {
                    PcapCaptureRuntimeState.Failed(receipt, failure)
                },
            )
        }

        private fun stopFailedLocked(
            captureSetId: Long,
            code: String,
        ): PcapCaptureRuntimeState.StopFailed =
            PcapCaptureRuntimeState.StopFailed(captureSetId, code).also {
                mutableState.value =
                    it
            }

        private fun retireFailedStopLocked(stopped: PcapCaptureRuntimeState.StopFailed) {
            clearActiveLocked()
            publishTerminalLocked(
                PcapCaptureRuntimeState.Failed(
                    receipt = PcapCaptureCompletionReceipt(stopped.captureSetId, null, PcapCaptureOutcome.Failed),
                    code = stopped.code,
                ),
            )
        }

        private fun clearActiveLocked() {
            activeBridge = null
            activeCaptureSetId = null
            completionMonitor?.cancel()
            completionMonitor = null
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
            val captureSetId = terminalCaptureSetId
            if (captureSetId != null && captureSetId in ownedReceipts) ownedReceipts[captureSetId] = state
            terminalState = state
            mutableState.value = state
            return state
        }

        private companion object {
            const val COMPLETION_POLL_INTERVAL_MS = 250L
            const val CONSENT_READ_TIMEOUT_MS = 2_000L
        }
    }
