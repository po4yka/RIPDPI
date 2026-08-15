package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.lifetime.HandleReservation
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import javax.inject.Inject

/**
 * Thin JNI binding surface over the relay-transport native session in
 * `libripdpi-relay.so` (Rust crate `ripdpi-relay-android`). Each method maps
 * 1:1 onto an `external` JNI function; the interface exists so the lifecycle
 * can be faked in unit tests.
 *
 * ## Handle lifecycle and ordering
 * One session runs through [create] -> [start] -> [stop] -> [destroy], in that
 * order. [create] returns an opaque non-zero `Long` handle (a native registry
 * key, not a pointer), or `0` on failure. [start] runs the relay; [stop]
 * signals it to unwind; [destroy] removes the session from the registry. After
 * [destroy] returns the handle is dead and must never be reused.
 *
 * ## Idempotency
 * [stop] and [destroy] are both idempotent: each is a silent no-op when the
 * handle is unknown or already retired (unlike the proxy and tunnel bindings,
 * which throw on a stale handle). [start] on an unknown handle returns the
 * error code `1` rather than blocking.
 *
 * ## fd ownership
 * The relay adopts no externally supplied file descriptors — it opens a loopback
 * SOCKS listener and its own outbound transport sockets, and closes them within
 * the native session. Outbound xHTTP transport sockets are kept off the VPN
 * tunnel via [RipDpiRelayNativeBindings.jniRegisterVpnProtect]; register before
 * [create]/[start] so a relay endpoint cannot route back into the TUN.
 *
 * ## Error mapping
 * The relay bindings report failure purely through return values, **not** Java
 * exceptions: [create] returns `0`, and [start] returns `0` (clean exit), `1`
 * (unknown handle) or `2` (runtime/transport error). A contained Rust panic is
 * absorbed by the FFI boundary and surfaces as the same sentinel (`0` handle /
 * non-zero code / `null` telemetry).
 *
 * ## Blocking
 * [start] **blocks for the whole relay lifetime** — it builds a Tokio runtime
 * and runs the session to completion — so always invoke it on a background
 * dispatcher. [create], [stop], [destroy] and [pollTelemetry] are non-blocking.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
 * containment) and §7 (error mapping).
 */
interface RipDpiRelayBindings {
    /** Creates a relay session from [configJson]; returns its handle, or `0` on failure. */
    fun create(configJson: String): Long

    /**
     * Runs the relay for [handle] and **blocks** until it stops. Returns `0`
     * on a clean exit, `1` for an unknown handle, or `2` on a runtime error.
     */
    fun start(handle: Long): Int

    /** Signals the relay to shut down; an idempotent no-op when [handle] is unknown. */
    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    /** Retires the session for [handle]; an idempotent no-op when already removed. */
    fun destroy(handle: Long)

    /**
     * Register [listener] to be invoked once when the native runtime becomes
     * ready (ADR 0003); returns a non-zero token when a native readiness push
     * is active, or `0` when unsupported (e.g. the Apps Script backend, which
     * has no native readiness event) — the wrapper then falls back to telemetry
     * polling. The default returns `0` so test fakes need no override.
     */
    fun registerReadinessListener(
        handle: Long,
        listener: RuntimeReadinessListener,
    ): Long = 0L

    /** Release the readiness listener registered for [handle]; a no-op by default. */
    fun unregisterReadinessListener(handle: Long) {}
}

object RipDpiRelayNativeLoader {
    init {
        System.loadLibrary("ripdpi-relay")
    }

    fun ensureLoaded() = Unit
}

class RipDpiRelayNativeBindings
    @Inject
    constructor() : RipDpiRelayBindings {
        companion object {
            init {
                RipDpiRelayNativeLoader.ensureLoaded()
            }

            /**
             * Register a VPN socket protection callback for the relay library.
             *
             * Stores a JNI `GlobalRef` to [vpnService] so native xHTTP relay
             * transports can call `VpnService.protect(fd)` before connecting
             * their carrier TCP sockets. Must be called before [create]/[start].
             *
             * Returns a generation token. Pass it to [jniUnregisterVpnProtect]
             * so a stale unregister cannot clear a newer session's callback.
             * A `0` return means registration failed.
             */
            @JvmStatic
            external fun jniRegisterVpnProtect(vpnService: Any): Long

            /**
             * Unregister the relay VPN socket protection callback and release
             * the `GlobalRef` held since [jniRegisterVpnProtect]. A stale token
             * or `0` token is a safe no-op.
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
    }

private val relayJson = RipDpiJson

/**
 * Coroutine-friendly owner of a single native relay handle (see
 * [RipDpiRelayBindings] for the raw JNI contract).
 *
 * Holds at most one live handle and uses [HandleReservation] to let telemetry
 * reservations overlap while lifecycle mutations drain them before clearing or
 * destroying the native session. [start] keeps the blocking
 * [RipDpiRelayBindings.start] on `Dispatchers.IO` and `destroy`s the handle in
 * a `finally` block once it returns, so callers never call `destroy` directly.
 * [stop] drains in-flight telemetry, then clears the handle field and runs
 * `stop` + `destroy` — both are idempotent native no-ops, so a redundant [stop]
 * is harmless. A second [start] while a handle is live throws
 * `NativeError.AlreadyRunning`.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle).
 */
class RipDpiRelay(
    private val nativeBindings: RipDpiRelayBindings,
) : RipDpiRelayRuntime {
    private companion object {
        private const val ReadyPollIntervalMs = 50L
    }

    private val reservations = HandleReservation()

    @Volatile private var readinessSignal: CompletableDeferred<Unit>? = null

    @Volatile private var handle = 0L

    @Suppress("TooGenericExceptionCaught")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
        require(config.schemaVersion == RelayNativeConfigSchemaVersion) {
            "Unsupported relay native config schema version: ${config.schemaVersion}"
        }
        val startupSignal = CompletableDeferred<Unit>()
        val createdHandle =
            reservations.withExclusive {
                if (handle != 0L) {
                    throw NativeError.AlreadyRunning("relay")
                }
                readinessSignal = startupSignal
                try {
                    val newHandle =
                        withContext(Dispatchers.IO) {
                            nativeBindings.create(relayJson.encodeToString(config))
                        }
                    if (newHandle == 0L) {
                        throw NativeError.SessionCreationFailed("relay")
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
            // relay binds, instead of waiting out a poll interval. Falls back to
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
                    startupSignal.completeExceptionally(IllegalStateException("Relay exited before becoming ready"))
                }
                // Clear the readiness signal once this session's handle is
                // retired, regardless of how the startup signal completed. An
                // exceptionally completed signal left behind would make a
                // later awaitReady() resurface this dead session's failure
                // instead of reporting NotRunning.
                if (readinessSignal === startupSignal) {
                    readinessSignal = null
                }
            }
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        val startupSignal = readinessSignal ?: throw NativeError.NotRunning("relay")
        awaitRuntimeReady(
            startupSignal = startupSignal,
            timeoutMillis = timeoutMillis,
            pollIntervalMillis = ReadyPollIntervalMs,
            timeoutMessagePrefix = "Relay readiness timed out",
            pollTelemetry = { pollTelemetry() },
        )
    }

    override suspend fun stop() {
        // Exclusive: drain in-flight telemetry reservations before the handle
        // is cleared and the native session is stopped/destroyed.
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
        // Reserve the active handle, then run the JNI call without holding the
        // exclusive lifecycle section, so telemetry polls overlap.
        val telemetryJson =
            reservations.withReservationOrNull({ handle }) { currentHandle ->
                withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(currentHandle) }
            }
        return telemetryJson
            ?.takeIf { it.isNotBlank() }
            ?.let(relayJson::decodeNativeRuntimeSnapshot)
            ?: NativeRuntimeSnapshot.idle(source = "relay")
    }
}
