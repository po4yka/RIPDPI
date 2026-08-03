package com.poyka.ripdpi.pcap

import com.poyka.ripdpi.core.Tun2SocksBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Current user-visible state of a live tunnel PCAP capture. */
sealed interface PcapCaptureRuntimeState {
    data object Idle : PcapCaptureRuntimeState

    data class Recording(
        val captureSetId: Long,
    ) : PcapCaptureRuntimeState

    /** Safe native or lifecycle code; never a filesystem path or exception message. */
    data class Failed(
        val code: String,
    ) : PcapCaptureRuntimeState
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

        val state: StateFlow<PcapCaptureRuntimeState> = mutableState

        /** Called by the VPN runtime only after a native tunnel has started. */
        suspend fun bindTunnel(bridge: Tun2SocksBridge) {
            mutex.withLock {
                check(activeBridge == null) { "Cannot replace a tunnel while PCAP capture is active" }
                boundBridge = bridge
            }
        }

        /**
         * Finalizes capture before [bridge] is stopped or destroyed. This keeps
         * the writer result observable instead of letting native retirement
         * discard it during session teardown.
         */
        suspend fun retireTunnel(bridge: Tun2SocksBridge) {
            mutex.withLock {
                if (activeBridge === bridge) {
                    stopLocked(bridge)
                }
                if (boundBridge === bridge) {
                    boundBridge = null
                }
            }
        }

        suspend fun start(): PcapCaptureRuntimeState =
            mutex.withLock {
                val existing = activeBridge
                if (existing != null) return@withLock mutableState.value
                val bridge =
                    boundBridge
                        ?: return@withLock failLocked("tunnel_unavailable")
                val captureSetId =
                    try {
                        bridge.withSessionHandle { handle ->
                            startCapture(handle)
                        } ?: 0L
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@withLock failLocked("start_failed")
                    }
                if (captureSetId <= 0L) {
                    return@withLock failLocked("start_failed")
                }
                activeBridge = bridge
                PcapCaptureRuntimeState.Recording(captureSetId).also { mutableState.value = it }
            }

        suspend fun stop(): PcapCaptureRuntimeState =
            mutex.withLock {
                val bridge =
                    activeBridge
                        ?: return@withLock PcapCaptureRuntimeState.Idle.also { mutableState.value = it }
                stopLocked(bridge)
            }

        private suspend fun stopLocked(bridge: Tun2SocksBridge): PcapCaptureRuntimeState {
            activeBridge = null
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
                PcapCaptureRuntimeState.Idle.also { mutableState.value = it }
            } else {
                failLocked(failureCode)
            }
        }

        private fun failLocked(code: String): PcapCaptureRuntimeState.Failed =
            PcapCaptureRuntimeState.Failed(code).also { mutableState.value = it }
    }
