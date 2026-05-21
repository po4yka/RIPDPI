package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.lifetime.HandleReservation
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

interface RipDpiRelayRuntime {
    suspend fun start(config: ResolvedRipDpiRelayConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultRelayReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultRelayReadyTimeoutMs = 5_000L

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
 * The relay adopts no externally supplied file descriptors and exposes no JNI
 * callback channel — it opens a loopback SOCKS listener and its own outbound
 * transport sockets, and closes them within the native session.
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

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniPollTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)
    }

private val relayJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ResolvedRelayFinalmaskConfig(
    val type: String = com.poyka.ripdpi.data.RelayFinalmaskTypeOff,
    val headerHex: String = "",
    val trailerHex: String = "",
    val randRange: String = "",
    val sudokuSeed: String = "",
    val fragmentPackets: Int = 0,
    val fragmentMinBytes: Int = 0,
    val fragmentMaxBytes: Int = 0,
)

@Serializable
data class ResolvedShadowTlsInnerRelayConfig(
    val kind: String,
    val profileId: String,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val vlessUuid: String? = null,
)

@Serializable
data class ResolvedRipDpiRelayConfig(
    val enabled: Boolean,
    val kind: String,
    val profileId: String,
    val outboundBindIp: String = "",
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntryServer: String,
    val chainEntryPort: Int,
    val chainEntryServerName: String,
    val chainEntryPublicKey: String,
    val chainEntryShortId: String,
    val chainEntryProfileId: String = "",
    val chainExitServer: String,
    val chainExitPort: Int,
    val chainExitServerName: String,
    val chainExitPublicKey: String,
    val chainExitShortId: String,
    val chainExitProfileId: String = "",
    val masqueUrl: String,
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val shadowTlsInner: ResolvedShadowTlsInnerRelayConfig? = null,
    val naivePath: String = "",
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = "",
    val ptSnowflakeFrontDomain: String = "",
    val localSocksHost: String,
    val localSocksPort: Int,
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
    val quicBindLowPort: Boolean = false,
    val quicMigrateAfterHandshake: Boolean = false,
    val vlessUuid: String? = null,
    val chainEntryUuid: String? = null,
    val chainExitUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val tuicUuid: String? = null,
    val tuicPassword: String? = null,
    val shadowTlsPassword: String? = null,
    val naiveUsername: String? = null,
    val naivePassword: String? = null,
    val tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
    val masqueAuthMode: String? = null,
    val masqueAuthToken: String? = null,
    val masqueClientCertificateChainPem: String? = null,
    val masqueClientPrivateKeyPem: String? = null,
    val masqueCloudflareGeohashHeader: String? = null,
    val masquePrivacyPassProviderUrl: String? = null,
    val masquePrivacyPassProviderAuthToken: String? = null,
    val cloudflareTunnelToken: String? = null,
    val cloudflareTunnelCredentialsJson: String? = null,
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val appsScriptAuthKey: String? = null,
    val finalmask: ResolvedRelayFinalmaskConfig = ResolvedRelayFinalmaskConfig(),
)

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
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle) and
 * `docs/architecture/jni-handle-lifetime-telemetry-lock.md` (lock model).
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

        yield()

        try {
            val completionHandle =
                coroutineContext[Job]!!.invokeOnCompletion {
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
                if (readinessSignal === startupSignal && startupSignal.getCompletionExceptionOrNull() == null) {
                    readinessSignal = null
                }
            }
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        val startupSignal = readinessSignal ?: throw NativeError.NotRunning("relay")
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
                    delay(ReadyPollIntervalMs)
                }
            }
        } catch (_: TimeoutCancellationException) {
            error(
                buildString {
                    append("Relay readiness timed out state=")
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
            ?.let { relayJson.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "relay")
    }
}

private fun NativeRuntimeSnapshot.hasRuntimeReadyEvent(): Boolean = nativeEvents.any { it.kind == "runtime_ready" }
