package com.poyka.ripdpi.core

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Thin JNI binding surface over the tun2socks tunnel native session in
 * `libripdpi-tunnel.so` (Rust crate `ripdpi-tunnel-android`). Each method maps
 * 1:1 onto an `external` JNI function; the interface exists so the lifecycle
 * can be faked in unit tests.
 *
 * ## Handle lifecycle and ordering
 * One session runs through [create] -> [start] -> [stop] -> [destroy], in that
 * order. [create] returns an opaque non-zero `Long` handle (a native registry
 * key, not a pointer) for a session in the `Ready` state, or `0` on failure.
 * [start] adopts the TUN fd and moves `Ready -> Running`; [stop] cancels the
 * worker and moves it out of `Running`; [destroy] retires the handle. After
 * [destroy] returns the handle is dead and must never be reused.
 *
 * ## Idempotency
 * None of the lifecycle calls are idempotent on the native side. [start] throws
 * `IllegalStateException` unless the session is `Ready`; [stop] throws
 * `IllegalStateException` unless it is `Running`; [destroy] throws
 * `IllegalStateException` if the worker is still running. [Tun2SocksTunnel]
 * provides the idempotent wrapper by gating on its own handle field.
 *
 * ## fd ownership
 * [start] **dups** the TUN fd it is handed (`adopt_tun_fd`). The caller keeps
 * ownership of the original descriptor — the `VpnService` `ParcelFileDescriptor`
 * — and must close it itself; the native side closes only its own dup, when the
 * tunnel worker exits. There is no JNI callback channel: outbound sockets are
 * protected through the `protectPath` Unix-domain socket named in the config,
 * not through a registered callback.
 *
 * ## Error mapping
 * Native failures throw Java exceptions from the JNI frame:
 * `IllegalArgumentException` (bad handle, bad config, invalid TUN fd),
 * `IllegalStateException` (wrong lifecycle state), `IOException` (fd adoption
 * or worker-spawn failure). A contained Rust panic surfaces as the sentinel
 * return (`0` handle / no-op) rather than crossing the boundary.
 *
 * ## Blocking
 * [start] returns after the worker thread is spawned — it does **not** run
 * packet IO on the caller thread. [stop] is the one blocking lifecycle call: it
 * cancels the worker and joins it, so keep it on the IO dispatcher. [create],
 * [getStats], [getTelemetry] and [destroy] are non-blocking.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §9 (TUN fd
 * ownership) and §6 (panic containment).
 */
interface Tun2SocksBindings {
    /** Creates a `Ready` tunnel session from [configJson]; returns its handle, or `0` on failure. */
    fun create(configJson: String): Long

    /**
     * Starts the native session: validates and **dups** [tunFd], adopts the dup,
     * and spawns the tunnel worker. Returns after worker launch, not after
     * tunnel termination. The caller retains ownership of the original [tunFd]
     * and must close it; the native dup is closed by the worker on exit.
     */
    fun start(
        handle: Long,
        tunFd: Int,
    )

    /** Cancels the tunnel worker and **blocks** until it joins; throws `IllegalStateException` if not `Running`. */
    fun stop(handle: Long)

    fun getStats(handle: Long): LongArray

    fun getTelemetry(handle: Long): String?

    /** Retires the session for [handle]; throws `IllegalStateException` if the worker is still running. */
    fun destroy(handle: Long)
}

class Tun2SocksNativeBindings
    @Inject
    constructor() : Tun2SocksBindings {
        companion object {
            init {
                System.loadLibrary("ripdpi-tunnel")
            }
        }

        override fun create(configJson: String): Long = jniCreate(configJson)

        override fun start(
            handle: Long,
            tunFd: Int,
        ) {
            jniStart(handle, tunFd)
        }

        override fun stop(handle: Long) {
            jniStop(handle)
        }

        override fun getStats(handle: Long): LongArray = jniGetStats(handle)

        override fun getTelemetry(handle: Long): String? = jniGetTelemetry(handle)

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(
            handle: Long,
            tunFd: Int,
        )

        private external fun jniStop(handle: Long)

        private external fun jniGetStats(handle: Long): LongArray

        private external fun jniGetTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)
    }

/**
 * Coroutine-friendly owner of a single native tunnel handle (see
 * [Tun2SocksBindings] for the raw JNI contract).
 *
 * Holds at most one live handle and serializes every JNI call under [mutex].
 * [start] runs [Tun2SocksBindings.create] then [Tun2SocksBindings.start]; if
 * `start` fails (including via cancellation) it `destroy`s the freshly created
 * handle so no orphan session is left in the native registry. [stop] always
 * pairs `stop` with `destroy` in a `finally` chain, so callers never invoke
 * `destroy` directly. A second [start] while a handle is live throws
 * `NativeError.AlreadyRunning`; [stop] with no live handle throws
 * `NativeError.NotRunning`. The TUN fd passed to [start] stays owned by the
 * caller — see [Tun2SocksBindings] § fd ownership.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §9 (TUN fd).
 */
class Tun2SocksTunnel(
    private val nativeBindings: Tun2SocksBindings,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val mutex = Mutex()
    private var handle = 0L

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        mutex.withLock {
            if (handle != 0L) {
                throw NativeError.AlreadyRunning("Tunnel")
            }

            val createdHandle =
                withContext(Dispatchers.IO) {
                    nativeBindings.create(json.encodeToString(config))
                }
            if (createdHandle == 0L) {
                Logger.e { "Tunnel native session creation returned null handle" }
                throw NativeError.SessionCreationFailed("tunnel")
            }
            Logger.d { "Tunnel native session created: handle=$createdHandle" }

            handle = createdHandle
            try {
                withContext(Dispatchers.IO) {
                    nativeBindings.start(createdHandle, tunFd)
                }
                Logger.d { "Tunnel native start completed: tunFd=$tunFd" }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        nativeBindings.destroy(createdHandle)
                    }
                }
                handle = 0L
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    nativeBindings.destroy(createdHandle)
                }
                handle = 0L
                throw e
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (handle == 0L) {
                throw NativeError.NotRunning("Tunnel")
            }

            try {
                withContext(Dispatchers.IO) {
                    nativeBindings.stop(handle)
                }
            } finally {
                try {
                    withContext(Dispatchers.IO) {
                        nativeBindings.destroy(handle)
                    }
                } finally {
                    handle = 0L
                }
            }
        }
    }

    suspend fun stats(): TunnelStats =
        mutex.withLock {
            if (handle == 0L) {
                TunnelStats()
            } else {
                val nativeStats =
                    withContext(Dispatchers.IO) {
                        nativeBindings.getStats(handle)
                    }
                TunnelStats.fromNative(nativeStats)
            }
        }

    suspend fun telemetry(): NativeRuntimeSnapshot =
        mutex.withLock {
            if (handle == 0L) {
                NativeRuntimeSnapshot.idle(source = "tunnel")
            } else {
                withContext(Dispatchers.IO) {
                    nativeBindings.getTelemetry(handle)
                }?.takeIf { it.isNotBlank() }
                    ?.let { json.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
                    ?: NativeRuntimeSnapshot.idle(source = "tunnel")
            }
        }
}

const val defaultTun2SocksTunnelMtu: Int = 1500

@Serializable
data class Tun2SocksConfig(
    val tunnelName: String = "tun0",
    val tunnelMtu: Int = defaultTun2SocksTunnelMtu,
    val multiQueue: Boolean = false,
    val tunnelIpv4: String? = null,
    val tunnelIpv6: String? = null,
    val socks5Address: String = "127.0.0.1",
    val socks5Port: Int,
    val socks5Udp: String? = "udp",
    val socks5UdpAddress: String? = null,
    val socks5Pipeline: Boolean? = null,
    val username: String? = null,
    val password: String? = null,
    val mapdnsAddress: String? = null,
    val mapdnsPort: Int? = null,
    val mapdnsNetwork: String? = null,
    val mapdnsNetmask: String? = null,
    val mapdnsCacheSize: Int? = null,
    val encryptedDnsResolverId: String? = null,
    val encryptedDnsProtocol: String? = null,
    val encryptedDnsHost: String? = null,
    val encryptedDnsPort: Int? = null,
    val encryptedDnsTlsServerName: String? = null,
    val encryptedDnsBootstrapIps: List<String> = emptyList(),
    val encryptedDnsDohUrl: String? = null,
    val encryptedDnsDnscryptProviderName: String? = null,
    val encryptedDnsDnscryptPublicKey: String? = null,
    val encryptedDnsTlsRootsPem: String? = null,
    val dnsQueryTimeoutMs: Int? = null,
    val resolverFallbackActive: Boolean? = null,
    val resolverFallbackReason: String? = null,
    val strategyChainYaml: String? = null,
    val protectPath: String? = null,
    val rootHelperSocketPath: String? = null,
    val taskStackSize: Int = 81_920,
    val tcpBufferSize: Int? = null,
    val udpRecvBufferSize: Int? = null,
    val udpCopyBufferNums: Int? = null,
    val maxSessionCount: Int? = null,
    val connectTimeoutMs: Int? = null,
    val tcpReadWriteTimeoutMs: Int? = null,
    val udpReadWriteTimeoutMs: Int? = null,
    val logLevel: String = "warn",
    val limitNofile: Int? = null,
    val logContext: RipDpiLogContext? = null,
)
