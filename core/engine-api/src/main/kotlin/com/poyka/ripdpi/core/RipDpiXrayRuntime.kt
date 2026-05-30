package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Managed Xray relay runtime that maps libXray onto the same lifecycle language
 * as [RipDpiRelayRuntime] / [RipDpiWarpRuntime]: `start` / `awaitReady` / `stop`
 * / `pollTelemetry`.
 *
 * The runtime owns the [VpnProviderState] machine and the protect-first
 * ordering guarantee. It delegates every native concern to [XrayNativeBridge],
 * which is the only seam over libXray — making this whole class unit-testable
 * with [FakeXrayNativeBridge] and free of gomobile linkage.
 *
 * ### Protect-first lifecycle
 * [start] registers the protect controller with the bridge *before* asking the
 * bridge to start Xray. Xray-core opens outbound sockets immediately on start;
 * registering protect afterwards would race the first outbound socket into the
 * TUN loop. See `.claude/rules/vpnservice-protect-invariant.md`.
 *
 * ### Secret-safe telemetry
 * [pollTelemetry] emits the Xray version and provider state but NEVER the
 * profile secrets carried in the rendered JSON config (UUIDs, REALITY private
 * keys, short-ids). The rendered config is held only for the duration of
 * [start] and is not retained on the instance.
 *
 * Not thread-safe: the caller serializes lifecycle calls on a single
 * coroutine, matching the existing relay/warp runtimes.
 */
class RipDpiXrayRuntime(
    private val bridge: XrayNativeBridge,
    private val config: XrayProviderConfig = XrayProviderConfig(),
    private val readinessPollIntervalMs: Long = DefaultReadinessPollIntervalMs,
    /**
     * Dispatcher the blocking native [XrayNativeBridge.stop] runs on. Defaulting
     * to [Dispatchers.IO] lets the bounded [stop] timeout fire even when the
     * native call blocks the worker thread — a hung libXray stop returns
     * [StopCause.Failed] without wedging the caller's dispatcher.
     */
    private val nativeStopDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var state: VpnProviderState = VpnProviderState.Stopped

    /** Last reason an attempted transition was rejected, for diagnostics. */
    var lastTransitionError: String? = null
        private set

    /** Current provider state. Read-only to callers. */
    val providerState: VpnProviderState
        get() = state

    /**
     * Register protect, start Xray, and move the provider into [VpnProviderState.Starting].
     *
     * Ordering is load-bearing: the protect controller is registered with the
     * bridge BEFORE [XrayNativeBridge.start] runs.
     *
     * @param renderedConfig fully-rendered Xray JSON config (may contain secrets).
     *   Not retained beyond this call.
     * @param protectController protect seam wired into the VPN service.
     * @return the bridge status code from [XrayNativeBridge.start]. `0` means the
     *   start was accepted; readiness is confirmed via [awaitReady].
     * @throws XrayRuntimeException.InvalidConfig if [renderedConfig] is blank.
     * @throws XrayRuntimeException.IllegalLifecycle if not currently [VpnProviderState.Stopped].
     * @throws XrayRuntimeException.StartupFailed if the bridge rejects the start (non-zero code).
     */
    suspend fun start(
        renderedConfig: String,
        protectController: XrayProtectController,
    ): Int {
        requireTransition(VpnProviderState.Starting)
        if (renderedConfig.isBlank()) {
            throw XrayRuntimeException.InvalidConfig("rendered Xray config is blank")
        }

        // PROTECT-FIRST: register protect before Xray opens any outbound socket.
        bridge.registerProtect(protectController)

        val code = bridge.start(renderedConfig)
        if (code != 0) {
            // Reset to Stopped so the runtime can be retried; surface a typed failure.
            forceState(VpnProviderState.Stopped)
            throw XrayRuntimeException.StartupFailed("libXray start rejected config (code=$code)", code)
        }

        transition(VpnProviderState.Starting)
        return code
    }

    /**
     * Wait until the local inbound listener is ready, then move to
     * [VpnProviderState.Running]. Bounded by [timeoutMillis].
     *
     * @throws XrayRuntimeException.IllegalLifecycle if not currently [VpnProviderState.Starting].
     * @throws XrayRuntimeException.ReadinessTimeout if the listener does not become ready in time.
     * @throws XrayRuntimeException.Crashed if the bridge reports the process exited while waiting.
     */
    suspend fun awaitReady(timeoutMillis: Long = DefaultXrayReadyTimeoutMs) {
        if (state != VpnProviderState.Starting) {
            throw XrayRuntimeException.IllegalLifecycle(
                "awaitReady requires Starting state, was $state",
            )
        }

        val became =
            withTimeoutOrNull(timeoutMillis) {
                while (!bridge.listenerReady()) {
                    if (!bridge.isAlive()) {
                        return@withTimeoutOrNull READINESS_PROCESS_DEAD
                    }
                    delay(readinessPollIntervalMs)
                }
                READINESS_OK
            }

        if (became == READINESS_OK) {
            transition(VpnProviderState.Running)
            return
        }
        // Both remaining outcomes are failures: tear down once, then surface the
        // matching typed cause from a single throw site (keeps the linear
        // lifecycle consistent on every abort path).
        throw readinessFailure(became, timeoutMillis)
    }

    private fun readinessFailure(
        outcome: Int?,
        timeoutMillis: Long,
    ): XrayRuntimeException {
        if (outcome == READINESS_PROCESS_DEAD) {
            forceState(VpnProviderState.Stopped)
            return XrayRuntimeException.Crashed("Xray process exited during readiness wait")
        }
        // Timeout: tear down so we don't leak a half-started Xray.
        runCatching { bridge.stop() }
        forceState(VpnProviderState.Stopped)
        return XrayRuntimeException.ReadinessTimeout(
            "Xray listener not ready within ${timeoutMillis}ms",
        )
    }

    /**
     * Stop Xray. Bounded and idempotent: stopping an already-[VpnProviderState.Stopped]
     * runtime returns [StopCause.AlreadyStopped] cleanly without touching the bridge.
     *
     * @return a typed [StopCause]: [StopCause.Clean] on a normal stop,
     *   [StopCause.AlreadyStopped] when no-op, or [StopCause.Failed] when the
     *   bridge threw while stopping (the runtime is still forced to Stopped).
     */
    suspend fun stop(timeoutMillis: Long = DefaultXrayStopTimeoutMs): StopCause {
        if (state == VpnProviderState.Stopped) {
            return StopCause.AlreadyStopped
        }

        forceState(VpnProviderState.Stopping)

        val result =
            withTimeoutOrNull(timeoutMillis) {
                withContext(nativeStopDispatcher) {
                    runCatching { bridge.stop() }
                }
            }

        forceState(VpnProviderState.Stopped)

        return when {
            result == null -> {
                StopCause.Failed("stop exceeded ${timeoutMillis}ms")
            }

            result.isFailure -> {
                StopCause.Failed(
                    "libXray stop threw: ${result.exceptionOrNull()?.message ?: "unknown"}",
                )
            }

            else -> {
                StopCause.Clean
            }
        }
    }

    /**
     * Snapshot of runtime state for telemetry. Carries the Xray version and the
     * provider state but NO profile secrets.
     *
     * The Xray version is read live from the bridge so a version mismatch
     * between the AAR and the rendered config surfaces in telemetry. The bridge
     * call is wrapped so a native failure degrades to an `"unknown"` version
     * rather than breaking the poll.
     */
    fun pollTelemetry(): NativeRuntimeSnapshot {
        val version = runCatching { bridge.version() }.getOrDefault(UnknownVersion)
        return NativeRuntimeSnapshot.idle(source = TelemetrySource).copy(
            state = state.telemetryName(),
            health = state.telemetryHealth(),
            ptRuntimeKind = "xray",
            ptRuntimeState = state.telemetryName(),
            ptRuntimeVersion = version,
            listenerAddress =
                if (state == VpnProviderState.Running) {
                    "127.0.0.1:${config.localInboundPort}"
                } else {
                    null
                },
        )
    }

    private fun requireTransition(next: VpnProviderState) {
        if (!state.canTransitionTo(next)) {
            val message = "illegal transition $state -> $next"
            lastTransitionError = message
            throw XrayRuntimeException.IllegalLifecycle(message)
        }
    }

    private fun transition(next: VpnProviderState) {
        if (!state.canTransitionTo(next)) {
            val message = "illegal transition $state -> $next"
            lastTransitionError = message
            throw XrayRuntimeException.IllegalLifecycle(message)
        }
        state = next
        lastTransitionError = null
    }

    /**
     * Forces [state] without consulting the transition table. Used only on
     * teardown / abort paths where the linear lifecycle does not apply (e.g.
     * collapsing `Running -> Stopped` after a crash). Internal callers ensure
     * the resulting state is a terminal-safe one.
     */
    private fun forceState(next: VpnProviderState) {
        state = next
    }

    private fun VpnProviderState.telemetryName(): String =
        when (this) {
            VpnProviderState.Stopped -> "stopped"
            VpnProviderState.Starting -> "starting"
            VpnProviderState.Running -> "running"
            VpnProviderState.Stopping -> "stopping"
        }

    private fun VpnProviderState.telemetryHealth(): String =
        when (this) {
            VpnProviderState.Running -> "healthy"
            VpnProviderState.Stopped -> "idle"
            else -> "transitioning"
        }

    private companion object {
        const val TelemetrySource = "xray"
        const val UnknownVersion = "unknown"
        const val READINESS_OK = 1
        const val READINESS_PROCESS_DEAD = 2
    }
}

/**
 * Typed result of [RipDpiXrayRuntime.stop].
 *
 * Distinguishes a clean stop from a failed/timed-out one so the service layer
 * can decide whether teardown was orderly. [Failed] still leaves the runtime in
 * [VpnProviderState.Stopped] — the cause is advisory.
 */
sealed interface StopCause {
    /** Xray stopped cleanly. */
    data object Clean : StopCause

    /** Stop was a no-op because the runtime was already stopped. */
    data object AlreadyStopped : StopCause

    /** Stop did not complete cleanly (bridge threw or timed out). */
    data class Failed(
        val detail: String,
    ) : StopCause
}

/**
 * Typed lifecycle errors for [RipDpiXrayRuntime].
 *
 * Messages NEVER include rendered config contents, so they are safe to log.
 */
sealed class XrayRuntimeException(
    message: String,
) : Exception(message) {
    /** Rendered config was structurally unusable (blank / empty). */
    class InvalidConfig(
        message: String,
    ) : XrayRuntimeException(message)

    /** A lifecycle call was made from a state that does not permit it. */
    class IllegalLifecycle(
        message: String,
    ) : XrayRuntimeException(message)

    /** libXray rejected the start request with a non-zero code. */
    class StartupFailed(
        message: String,
        val code: Int,
    ) : XrayRuntimeException(message)

    /** The listener did not become ready within the bounded timeout. */
    class ReadinessTimeout(
        message: String,
    ) : XrayRuntimeException(message)

    /** The Xray process exited unexpectedly (crash / non-clean exit). */
    class Crashed(
        message: String,
    ) : XrayRuntimeException(message)
}

internal const val DefaultXrayReadyTimeoutMs = 5_000L
internal const val DefaultXrayStopTimeoutMs = 3_000L
internal const val DefaultReadinessPollIntervalMs = 50L
