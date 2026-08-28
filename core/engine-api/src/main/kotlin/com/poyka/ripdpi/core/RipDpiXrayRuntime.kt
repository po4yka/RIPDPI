package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Session facade over the single process-owned native lane. Lifecycle callers serialize commands. */
class RipDpiXrayRuntime(
    private val owner: XrayRuntimeOwner,
    private val config: XrayProviderConfig = XrayProviderConfig(),
    private val readinessPollIntervalMs: Long = DefaultReadinessPollIntervalMs,
) {
    @Volatile
    private var lease: XrayRuntimeLease? = null

    val providerState: VpnProviderState
        get() = lease?.status?.state ?: VpnProviderState.Stopped

    val generation: Long?
        get() = lease?.generation

    fun observe(): XrayRuntimeStatus {
        val current = lease ?: return XrayRuntimeStatus()
        owner.observe(current)
        return current.status
    }

    suspend fun start(
        renderedConfig: String,
        protectController: XrayProtectController,
    ): Int {
        validateStart(renderedConfig)
        val acquired = owner.acquire(protectController).also { lease = it }
        try {
            val code = startNative(acquired, renderedConfig)
            return code
        } catch (cancelled: CancellationException) {
            owner.stop(acquired)
            throw cancelled
        } catch (failure: XrayRuntimeException) {
            stop()
            throw failure
        }
    }

    suspend fun awaitReady(timeoutMillis: Long = DefaultXrayReadyTimeoutMs) {
        val current = requireReadinessLease()
        try {
            awaitListener(current, timeoutMillis)
        } catch (cancelled: CancellationException) {
            owner.stop(current)
            throw cancelled
        } catch (failure: XrayRuntimeException) {
            stop()
            throw failure
        }
    }

    private fun validateStart(renderedConfig: String) {
        if (renderedConfig.isBlank()) throw XrayRuntimeException.InvalidConfig("rendered Xray config is blank")
        if (providerState != VpnProviderState.Stopped) {
            throw XrayRuntimeException.IllegalLifecycle("Xray session has not stopped")
        }
    }

    private suspend fun startNative(
        current: XrayRuntimeLease,
        renderedConfig: String,
    ): Int {
        val code =
            withTimeoutOrNull(DefaultXrayReadyTimeoutMs) {
                owner.start(current, renderedConfig).await()
            } ?: throw XrayRuntimeException.StartupFailed("Xray native start timed out", -1)
        if (code != 0) throw XrayRuntimeException.StartupFailed("Xray native start rejected config", code)
        return code
    }

    private fun requireReadinessLease(): XrayRuntimeLease {
        val current = lease
        if (current == null || providerState !in setOf(VpnProviderState.Starting, VpnProviderState.Running)) {
            throw XrayRuntimeException.IllegalLifecycle("Xray readiness requires an active start")
        }
        return current
    }

    private suspend fun awaitListener(
        current: XrayRuntimeLease,
        timeoutMillis: Long,
    ) {
        val ready =
            withTimeoutOrNull(timeoutMillis) {
                while (!isReady(owner.observe(current).await())) delay(readinessPollIntervalMs)
                true
            }
        if (ready != true) throw XrayRuntimeException.ReadinessTimeout("Xray listener readiness timed out")
    }

    private fun isReady(snapshot: XrayRuntimeStatus): Boolean {
        if (!snapshot.alive) throw XrayRuntimeException.Crashed("Xray exited during readiness")
        if (snapshot.state == VpnProviderState.Stopping) {
            throw XrayRuntimeException.IllegalLifecycle("Xray is stopping")
        }
        return snapshot.listenerReady
    }

    /** Revoke this service's callback without waiting for a blocked native worker. */
    fun revokeProtection() {
        lease?.revokeProtection()
    }

    /** A deadline bounds this caller only. Pending/Failed retains ownership until native cleanup succeeds. */
    suspend fun stop(timeoutMillis: Long = DefaultXrayStopTimeoutMs): StopCause {
        val current = lease ?: return StopCause.AlreadyStopped
        val operation = owner.stop(current)
        return withTimeoutOrNull(timeoutMillis) { operation.await() } ?: StopCause.Pending
    }

    /** Refresh is coalesced by the native owner; telemetry never blocks on JNI. */
    fun pollTelemetry(): NativeRuntimeSnapshot {
        val snapshot = observe()
        val state = snapshot.state
        return NativeRuntimeSnapshot.idle(source = "xray").copy(
            state = state.name.lowercase(),
            health =
                if (snapshot.failed) {
                    "failed"
                } else {
                    when (state) {
                        VpnProviderState.Running -> "healthy"
                        VpnProviderState.Stopped -> "idle"
                        else -> "transitioning"
                    }
                },
            ptRuntimeKind = "xray",
            ptRuntimeState = state.name.lowercase(),
            ptRuntimeVersion = snapshot.version,
            listenerAddress = if (snapshot.listenerReady) "127.0.0.1:${config.localInboundPort}" else null,
        )
    }
}

sealed interface StopCause {
    data object Clean : StopCause

    data object AlreadyStopped : StopCause

    data object Pending : StopCause

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
