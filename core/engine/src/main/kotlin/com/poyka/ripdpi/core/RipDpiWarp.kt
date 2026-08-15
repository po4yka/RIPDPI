package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.lifetime.HandleReservation
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject

/**
 * Thin JNI binding surface over the WARP (WireGuard) native session in
 * `libripdpi-warp.so` (Rust crate `ripdpi-warp-android`). Each method maps 1:1
 * onto an `external` JNI function; the interface exists so the lifecycle can
 * be faked in unit tests.
 *
 * ## Handle lifecycle and ordering
 * One session runs through [create] -> [start] -> [stop] -> [destroy], in that
 * order. [create] returns an opaque non-zero `Long` handle (a native registry
 * key, not a pointer), or `0` on failure. [start] runs the tunnel; [stop]
 * signals it to unwind; [destroy] removes the session from the registry. After
 * [destroy] returns the handle is dead and must never be reused.
 *
 * ## Idempotency
 * [stop] and [destroy] are both idempotent: each is a silent no-op when the
 * handle is unknown or already retired. [start] on an unknown handle returns
 * the error code `1` rather than blocking.
 *
 * ## fd ownership
 * The WARP runtime adopts no externally supplied file descriptors — it opens
 * and closes its own WireGuard UDP socket and loopback SOCKS listener. That
 * outbound UDP socket is kept off the VPN tunnel via the
 * [RipDpiWarpNativeBindings.jniRegisterVpnProtect] callback — see the
 * `vpnservice-protect-invariant` rule.
 *
 * ## Error mapping
 * The lifecycle bindings report failure purely through return values, **not**
 * Java exceptions: [create] returns `0`, and [start] returns `0` (clean exit),
 * `1` (unknown handle) or `2` (runtime error). A contained Rust panic is
 * absorbed by the FFI boundary and surfaces as the same sentinel.
 *
 * ## Blocking
 * [start] **blocks for the whole tunnel lifetime** — it builds a Tokio runtime
 * and runs the session to completion — so always invoke it on a background
 * dispatcher. [create], [stop], [destroy] and [pollTelemetry] are non-blocking.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
 * containment), §7 (error mapping) and §10 (VpnService.protect callback).
 */
interface RipDpiWarpBindings {
    /** Creates a WARP session from [configJson]; returns its handle, or `0` on failure. */
    fun create(configJson: String): Long

    /**
     * Runs the WARP tunnel for [handle] and **blocks** until it stops. Returns
     * `0` on a clean exit, `1` for an unknown handle, or `2` on a runtime error.
     */
    fun start(handle: Long): Int

    /** Signals the tunnel to shut down; an idempotent no-op when [handle] is unknown. */
    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    /** Retires the session for [handle]; an idempotent no-op when already removed. */
    fun destroy(handle: Long)

    /**
     * Register [listener] to be invoked once when the native runtime becomes
     * ready (ADR 0003); returns a non-zero token when a native readiness push
     * is active, or `0` when unsupported (the wrapper then falls back to
     * telemetry polling). The default returns `0` so test fakes need no
     * override.
     */
    fun registerReadinessListener(
        handle: Long,
        listener: RuntimeReadinessListener,
    ): Long = 0L

    /** Release the readiness listener registered for [handle]; a no-op by default. */
    fun unregisterReadinessListener(handle: Long) {}
}

/**
 * Stateless JNI binding for WARP account provisioning. [executeProvisioning]
 * takes no handle: it runs a one-shot request and returns a JSON result, or
 * `null` on failure / a contained panic. It does not touch the runtime session
 * registry, so it has no lifecycle ordering constraints.
 */
interface RipDpiWarpProvisioningBindings {
    fun executeProvisioning(requestJson: String): String?
}

/**
 * Stateless JNI binding for WARP endpoint probing. [probeEndpoint] takes no
 * handle: it runs a one-shot reachability/RTT probe and returns a JSON result,
 * or `null` on failure / a contained panic. Like [RipDpiWarpProvisioningBindings]
 * it is independent of the runtime session lifecycle.
 */
interface RipDpiWarpEndpointProbeBindings {
    fun probeEndpoint(requestJson: String): String?
}

object RipDpiWarpNativeLoader {
    init {
        System.loadLibrary("ripdpi-warp")
    }

    fun ensureLoaded() = Unit
}

class RipDpiWarpNativeBindings
    @Inject
    constructor() :
    RipDpiWarpBindings,
        RipDpiWarpProvisioningBindings,
        RipDpiWarpEndpointProbeBindings {
        companion object {
            init {
                RipDpiWarpNativeLoader.ensureLoaded()
            }

            /**
             * Register a VPN socket protection callback for the WARP library.
             *
             * Stores a JNI `GlobalRef` to the [vpnService] so the native WARP
             * runtime can call `VpnService.protect(fd)` directly on its WireGuard
             * UDP socket. Must be called **before** [RipDpiWarpBindings.create]/
             * [RipDpiWarpBindings.start] so the socket is protected the moment it
             * is opened; registering later risks a routing loop into the TUN (see
             * the `vpnservice-protect-invariant` rule). The `GlobalRef` is
             * process-global and is released only by [jniUnregisterVpnProtect].
             *
             * Returns a generation token identifying this registration. Keep it
             * and pass it to [jniUnregisterVpnProtect] so a stale unregister from
             * a superseded VPN session cannot clear a newer session's callback.
             * A `0` return means registration failed.
             */
            @JvmStatic
            external fun jniRegisterVpnProtect(vpnService: Any): Long

            /**
             * Unregister the WARP VPN socket protection callback and release the
             * `GlobalRef` held since [jniRegisterVpnProtect], passing back the
             * [token] that call returned. The callback is cleared only if the
             * registry slot still carries that generation; a stale token (a
             * superseded session) or a `0` token is a safe no-op. Call when the
             * VPN service stops, after the WARP session has been stopped/destroyed.
             */
            @JvmStatic
            external fun jniUnregisterVpnProtect(token: Long)
        }

        override fun create(configJson: String): Long = jniCreate(configJson)

        override fun start(handle: Long): Int = jniStart(handle)

        override fun stop(handle: Long) {
            jniStop(handle)
        }

        override fun pollTelemetry(handle: Long): String? = jniPollTelemetry(handle)

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        override fun registerReadinessListener(
            handle: Long,
            listener: RuntimeReadinessListener,
        ): Long = jniRegisterReadinessListener(handle, listener)

        override fun unregisterReadinessListener(handle: Long) {
            jniUnregisterReadinessListener(handle)
        }

        override fun executeProvisioning(requestJson: String): String? = jniExecuteProvisioning(requestJson)

        override fun probeEndpoint(requestJson: String): String? = jniProbeEndpoint(requestJson)

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniPollTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)

        private external fun jniRegisterReadinessListener(
            handle: Long,
            listener: Any,
        ): Long

        private external fun jniUnregisterReadinessListener(handle: Long)

        private external fun jniExecuteProvisioning(requestJson: String): String?

        private external fun jniProbeEndpoint(requestJson: String): String?
    }

private val warpJson = RipDpiJson

@Serializable
private data class WarpRuntimeNativeConfig(
    val enabled: Boolean,
    val profileId: String,
    val accountKind: String,
    val deviceId: String,
    val accessToken: String,
    val clientId: String? = null,
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
    val endpoint: ResolvedRipDpiWarpEndpoint,
    val routeMode: String,
    val routeHosts: String,
    val builtInRulesEnabled: Boolean,
    val endpointSelectionMode: String,
    val manualEndpoint: RipDpiWarpManualEndpointConfig,
    val scannerEnabled: Boolean,
    val scannerParallelism: Int,
    val scannerMaxRttMs: Int,
    val amnezia: RipDpiWarpAmneziaConfig,
    val localSocksHost: String,
    val localSocksPort: Int,
    val mtu: Int,
)

/**
 * Coroutine-friendly owner of a single native WARP handle (see
 * [RipDpiWarpBindings] for the raw JNI contract).
 *
 * Holds at most one live handle and uses [HandleReservation] to let telemetry
 * reservations overlap while lifecycle mutations drain them before clearing or
 * destroying the native session. [start] keeps the blocking
 * [RipDpiWarpBindings.start] on `Dispatchers.IO`; exclusive sections cover only
 * handle publication and final cleanup. A second [start] while a handle is live
 * throws `NativeError.AlreadyRunning`. VPN socket protection must be registered
 * out-of-band via
 * [RipDpiWarpNativeBindings.jniRegisterVpnProtect] before [start].
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle).
 */
class RipDpiWarp(
    private val nativeBindings: RipDpiWarpBindings,
) : RipDpiWarpRuntime {
    private companion object {
        private const val ReadyPollIntervalMs = 50L
    }

    private val reservations = HandleReservation()

    @Volatile private var readinessSignal: CompletableDeferred<Unit>? = null

    @Volatile private var handle = 0L

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun start(config: ResolvedRipDpiWarpConfig): Int {
        val startupSignal = CompletableDeferred<Unit>()
        val nativeConfig =
            WarpRuntimeNativeConfig(
                enabled = config.enabled,
                profileId = config.profileId,
                accountKind = config.accountKind,
                deviceId = config.deviceId,
                accessToken = config.accessToken,
                clientId = config.clientId,
                privateKey = config.privateKey,
                publicKey = config.publicKey,
                peerPublicKey = config.peerPublicKey,
                interfaceAddressV4 = config.interfaceAddressV4,
                interfaceAddressV6 = config.interfaceAddressV6,
                endpoint = config.endpoint,
                routeMode = config.routeMode,
                routeHosts = config.routeHosts,
                builtInRulesEnabled = config.builtInRulesEnabled,
                endpointSelectionMode = config.endpointSelectionMode,
                manualEndpoint = config.manualEndpoint,
                scannerEnabled = config.scannerEnabled,
                scannerParallelism = config.scannerParallelism,
                scannerMaxRttMs = config.scannerMaxRttMs,
                amnezia = config.amnezia,
                localSocksHost = config.localSocksHost,
                localSocksPort = config.localSocksPort,
                mtu = config.mtu,
            )
        val createdHandle =
            reservations.withExclusive {
                if (handle != 0L) {
                    throw NativeError.AlreadyRunning("WARP")
                }
                readinessSignal = startupSignal
                try {
                    val newHandle =
                        withContext(Dispatchers.IO) {
                            nativeBindings.create(warpJson.encodeToString(nativeConfig))
                        }
                    if (newHandle == 0L) {
                        throw NativeError.SessionCreationFailed("warp")
                    }
                    handle = newHandle
                    newHandle
                } catch (error: Exception) {
                    readinessSignal = null
                    startupSignal.completeExceptionally(error)
                    throw error
                }
            }

        try {
            // Install the native readiness push (ADR 0003) before the blocking
            // start() so the listener completes the startup signal the moment the
            // tunnel binds, instead of waiting out a poll interval. Falls back to
            // polling when the native push is unavailable (returns 0).
            withContext(Dispatchers.IO) {
                nativeBindings.registerReadinessListener(createdHandle) { startupSignal.complete(Unit) }
            }

            yield()

            val completionHandle =
                currentCoroutineContext().job.invokeOnCompletion {
                    try {
                        if (handle == createdHandle && createdHandle != 0L) {
                            nativeBindings.stop(createdHandle)
                        }
                    } catch (_: IllegalStateException) {
                    }
                }
            return try {
                withContext(Dispatchers.IO) { nativeBindings.start(createdHandle) }
            } finally {
                completionHandle.dispose()
            }
        } catch (error: Exception) {
            startupSignal.completeExceptionally(error)
            throw error
        } finally {
            reservations.withExclusiveNonCancellable {
                // Release the readiness listener (and its native GlobalRef)
                // before the session is destroyed. No-op when no push was active.
                runCatching { nativeBindings.unregisterReadinessListener(createdHandle) }
                if (handle == createdHandle) {
                    try {
                        nativeBindings.destroy(createdHandle)
                    } finally {
                        handle = 0L
                    }
                }
                if (!startupSignal.isCompleted) {
                    startupSignal.completeExceptionally(IllegalStateException("WARP exited before becoming ready"))
                }
                if (readinessSignal === startupSignal && startupSignal.getCompletionExceptionOrNull() == null) {
                    readinessSignal = null
                }
            }
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        val startupSignal = readinessSignal ?: throw NativeError.NotRunning("WARP")
        var lastState = "idle"
        var lastEventMessage: String? = null
        try {
            withTimeout(timeoutMillis) {
                while (true) {
                    if (startupSignal.isCompleted) {
                        startupSignal.await()
                        return@withTimeout
                    }
                    val telemetry = pollTelemetry()
                    lastState = telemetry.state
                    lastEventMessage = telemetry.nativeEvents.lastOrNull()?.message
                    if (telemetry.hasRuntimeReadyEvent()) {
                        startupSignal.complete(Unit)
                        startupSignal.await()
                        return@withTimeout
                    }
                    // Wait out the poll interval, but wake immediately when a
                    // native readiness push completes the signal (ADR 0003).
                    withTimeoutOrNull(ReadyPollIntervalMs) { startupSignal.await() }
                }
            }
        } catch (_: TimeoutCancellationException) {
            error(
                buildString {
                    append("WARP readiness timed out state=")
                    append(lastState)
                    lastEventMessage?.let {
                        append(" lastEvent=")
                        append(it)
                    }
                },
            )
        }
    }

    override suspend fun stop() {
        reservations.withExclusiveNonCancellable {
            val activeHandle = handle
            handle = 0L
            readinessSignal = null
            if (activeHandle != 0L) {
                withContext(Dispatchers.IO) {
                    runCatching { nativeBindings.stop(activeHandle) }
                    nativeBindings.destroy(activeHandle)
                }
            }
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        val telemetryJson =
            reservations.withReservationOrNull({ handle }) { currentHandle ->
                withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(currentHandle) }
            }
        return telemetryJson
            ?.takeIf { it.isNotBlank() }
            ?.let(warpJson::decodeNativeRuntimeSnapshot)
            ?: NativeRuntimeSnapshot.idle(source = "warp")
    }
}

private fun NativeRuntimeSnapshot.hasRuntimeReadyEvent(): Boolean = nativeEvents.any { it.kind == "runtime_ready" }
