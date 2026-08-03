package com.poyka.ripdpi.core

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.lifetime.HandleReservation
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
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
 * [start] adopts the TUN fd and moves `Ready -> Starting -> Running`; [stop]
 * cancels the worker and moves it out of `Running`; [destroy] retires the
 * handle. A readiness timeout moves through native cleanup ownership so the
 * JNI deadline remains bounded. After [destroy] returns the handle is dead and
 * must never be reused.
 *
 * ## Idempotency
 * None of the lifecycle calls are idempotent on the native side. [start] throws
 * `IllegalStateException` unless the session is `Ready`; [stop] throws
 * `IllegalStateException` unless it is `Running`; [destroy] throws while a
 * normally running worker is still owned by the session, but accepts a failed
 * startup whose worker is already owned by the native reaper.
 * [Tun2SocksTunnel] provides the idempotent wrapper by gating on its own handle
 * field.
 *
 * ## fd ownership
 * [start] **dups** the TUN fd it is handed (`adopt_tun_fd`). The caller keeps
 * ownership of the original descriptor — the `VpnService` `ParcelFileDescriptor`
 * — and must close it itself; the native side closes only its own dup, when the
 * tunnel worker exits. Ordinary outbound sockets are protected through the
 * `protectPath` Unix-domain socket; direct-DNS sockets use the separately
 * generation-guarded JNI underlay binder.
 *
 * ## Error mapping
 * Native failures throw Java exceptions from the JNI frame:
 * `IllegalArgumentException` (bad handle, bad config, invalid TUN fd),
 * `IllegalStateException` (wrong lifecycle state), `IOException` (fd adoption
 * or worker-spawn failure). A contained Rust panic in the session lifecycle
 * surfaces as the sentinel return (`0` handle / no-op) rather than crossing the
 * boundary; direct-DNS binder registration instead throws a sanitized
 * `RuntimeException` so the service rolls back the registration group.
 *
 * ## Blocking
 * [start] returns after the worker has completed packet-loop initialization —
 * it does **not** run packet IO on the caller thread. Native startup waits at
 * most five seconds for that readiness barrier. A readiness timeout requests
 * cancellation and transfers the join to a native runtime reaper instead of
 * blocking past the deadline. [stop] cancels a running worker and joins it, so
 * both lifecycle calls belong on the IO dispatcher. [create],
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
     * and spawns the tunnel worker. Returns only after the worker has adopted
     * the fd and initialized the smoltcp packet loop; startup timeout/failure is
     * thrown synchronously. The caller retains ownership of the original
     * [tunFd] and must close it; the native dup is closed by the worker on exit.
     */
    fun start(
        handle: Long,
        tunFd: Int,
    )

    /** Cancels the tunnel worker and **blocks** until it joins; throws `IllegalStateException` if not `Running`. */
    fun stop(handle: Long)

    fun getStats(handle: Long): LongArray

    fun getForwardingEvidence(handle: Long): String?

    fun getIcmpIngressPackets(handle: Long): Long

    fun getTelemetry(handle: Long): String?

    /** Retires [handle]; a failed-start reaper may still own the cancelled worker and duplicated fd. */
    fun destroy(handle: Long)

    /**
     * Register a per-flow app-attribution bridge and start the native worker that
     * pushes `noteFlow` notifications up to it (see `ripdpi-tunnel-android`
     * `flow_attribution`). [bridge] must expose
     * `noteFlow(int protocol, String localIp, int localPort, String remoteIp, int remotePort, int requestKind)`.
     * Returns a generation token (`0` on failure) threaded back to
     * [unregisterFlowAttribution]. Process-global on the native side, so register
     * once per tunnel session after [start] and release on [stop].
     */
    fun registerFlowAttribution(bridge: Any): Long

    /** Release the flow-attribution bridge registered under [token]; a stale or `0` token is a safe no-op. */
    fun unregisterFlowAttribution(token: Long)
}

object Tun2SocksNativeLoader {
    init {
        System.loadLibrary("ripdpi-tunnel")
    }

    fun ensureLoaded() = Unit
}

/** Control-plane JNI probe for the kernel's unprivileged `SO_BINDTODEVICE` behavior. */
class TunDeviceQualificationNativeBindings
    @Inject
    constructor() {
        fun probeUnprivilegedBindToDevice(): Int {
            Tun2SocksNativeLoader.ensureLoaded()
            return jniProbeUnprivilegedBindToDevice()
        }

        private external fun jniProbeUnprivilegedBindToDevice(): Int
    }

class Tun2SocksNativeBindings
    @Inject
    constructor() : Tun2SocksBindings {
        companion object {
            @JvmStatic
            fun registerDirectDnsSocketBinder(bridge: Any): Long {
                Tun2SocksNativeLoader.ensureLoaded()
                return jniRegisterDirectDnsSocketBinderNative(bridge)
            }

            @JvmStatic
            fun unregisterDirectDnsSocketBinder(token: Long) {
                Tun2SocksNativeLoader.ensureLoaded()
                jniUnregisterDirectDnsSocketBinderNative(token)
            }

            @JvmStatic
            private external fun jniRegisterDirectDnsSocketBinderNative(bridge: Any): Long

            @JvmStatic
            private external fun jniUnregisterDirectDnsSocketBinderNative(token: Long)
        }

        init {
            Tun2SocksNativeLoader.ensureLoaded()
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

        // A contained native panic marshals to a null jlongArray; coalesce so callers
        // (TunnelStats.fromNative) never see null and NPE. fromNative treats an empty
        // array as all-zero stats.
        override fun getStats(handle: Long): LongArray = jniGetStats(handle) ?: LongArray(0)

        override fun getForwardingEvidence(handle: Long): String? =
            TunForwardingEvidenceNativeBindings.getForwardingEvidence(handle)

        override fun getIcmpIngressPackets(handle: Long): Long = jniGetIcmpIngressPackets(handle)

        override fun getTelemetry(handle: Long): String? = jniGetTelemetry(handle)

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        override fun registerFlowAttribution(bridge: Any): Long = jniRegisterFlowAttribution(bridge)

        override fun unregisterFlowAttribution(token: Long) {
            jniUnregisterFlowAttribution(token)
        }

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(
            handle: Long,
            tunFd: Int,
        )

        private external fun jniStop(handle: Long)

        private external fun jniGetStats(handle: Long): LongArray?

        private external fun jniGetIcmpIngressPackets(handle: Long): Long

        private external fun jniGetTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)

        private external fun jniRegisterFlowAttribution(bridge: Any): Long

        private external fun jniUnregisterFlowAttribution(token: Long)
    }

private object TunForwardingEvidenceNativeBindings {
    init {
        Tun2SocksNativeLoader.ensureLoaded()
    }

    fun getForwardingEvidence(handle: Long): String? = jniGetForwardingEvidence(handle)

    private external fun jniGetForwardingEvidence(handle: Long): String?
}

/**
 * Coroutine-friendly owner of a single native tunnel handle (see
 * [Tun2SocksBindings] for the raw JNI contract).
 *
 * Holds at most one live handle and uses [HandleReservation] to let read-style
 * stats/telemetry calls run concurrently while lifecycle mutations drain them
 * before destroying the native session.
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
    private val configJson =
        RipDpiEncodeDefaultsJson
    private val telemetryJson = RipDpiJson
    private val reservations = HandleReservation()
    private var handle = 0L

    /** Generation token from [Tun2SocksBindings.registerFlowAttribution]; `0` when not registered. */
    private var flowAttributionToken = 0L

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
        flowAttributionBridge: Any? = null,
    ) {
        reservations.withExclusive {
            if (handle != 0L) {
                throw NativeError.AlreadyRunning("Tunnel")
            }
            require(config.schemaVersion == Tun2SocksConfigSchemaVersion) {
                "Unsupported tunnel native config schema version: ${config.schemaVersion}; " +
                    "expected $Tun2SocksConfigSchemaVersion"
            }

            val createdHandle =
                withContext(Dispatchers.IO) {
                    nativeBindings.create(configJson.encodeToString(config))
                }
            if (createdHandle == 0L) {
                Logger.e { "Tunnel native session creation returned null handle" }
                throw NativeError.SessionCreationFailed("tunnel")
            }
            Logger.d { "Tunnel native session created: handle=$createdHandle" }

            handle = createdHandle
            var startAttempted = false
            try {
                if (config.uidPolicyMode != "disarmed" && flowAttributionBridge == null) {
                    throw NativeError.SessionCreationFailed("tunnel flow attribution")
                }
                startAttempted = true
                withContext(Dispatchers.IO) {
                    nativeBindings.start(createdHandle, tunFd)
                }
                Logger.d { "Tunnel native start completed: tunFd=$tunFd" }
                if (flowAttributionBridge != null) {
                    val registrationToken =
                        withContext(Dispatchers.IO) {
                            nativeBindings.registerFlowAttribution(flowAttributionBridge)
                        }
                    if (registrationToken == 0L && config.uidPolicyMode != "disarmed") {
                        throw NativeError.SessionCreationFailed("tunnel flow attribution")
                    }
                    flowAttributionToken = registrationToken
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    if (flowAttributionToken != 0L) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                nativeBindings.unregisterFlowAttribution(flowAttributionToken)
                            }
                        }
                        flowAttributionToken = 0L
                    }
                    if (startAttempted) {
                        runCatching { withContext(Dispatchers.IO) { nativeBindings.stop(createdHandle) } }
                    }
                    runCatching { withContext(Dispatchers.IO) { nativeBindings.destroy(createdHandle) } }
                }
                handle = 0L
                throw error
            }
        }
    }

    suspend fun stop() {
        reservations.withExclusiveNonCancellable {
            if (handle == 0L) {
                throw NativeError.NotRunning("Tunnel")
            }

            val currentHandle = handle
            val attributionToken = flowAttributionToken
            try {
                if (attributionToken != 0L) {
                    withContext(Dispatchers.IO) {
                        nativeBindings.unregisterFlowAttribution(attributionToken)
                    }
                    flowAttributionToken = 0L
                }
                withContext(Dispatchers.IO) {
                    nativeBindings.stop(currentHandle)
                }
            } finally {
                try {
                    withContext(Dispatchers.IO) {
                        nativeBindings.destroy(currentHandle)
                    }
                } finally {
                    handle = 0L
                }
            }
        }
    }

    suspend fun stats(): TunnelStats =
        reservations.withReservationOrNull({ handle }) { currentHandle ->
            val nativeStats =
                withContext(Dispatchers.IO) {
                    nativeBindings.getStats(currentHandle)
                }
            TunnelStats.fromNative(nativeStats)
        } ?: TunnelStats()

    /**
     * Runs [block] while the native session is reserved from lifecycle teardown.
     *
     * This is intentionally the only way a collaborator may use the opaque
     * native handle. In particular, a PCAP start/stop operation must not race
     * `stop()`/`destroy()` and attach to a reused or already-destroyed session.
     */
    suspend fun <T> withSessionHandle(block: suspend (Long) -> T): T? =
        reservations.withReservationOrNull({ handle }, block)

    suspend fun telemetry(): NativeRuntimeSnapshot =
        reservations
            .withReservationOrNull({ handle }) { currentHandle ->
                withContext(Dispatchers.IO) {
                    nativeBindings.getTelemetry(currentHandle) to nativeBindings.getIcmpIngressPackets(currentHandle)
                }
            }?.let { (json, icmpIngressPackets) ->
                json
                    ?.takeIf { it.isNotBlank() }
                    ?.let(telemetryJson::decodeNativeRuntimeSnapshot)
                    ?.let { snapshot ->
                        snapshot.copy(
                            tunnelStats = snapshot.tunnelStats.copy(icmpIngressPackets = icmpIngressPackets),
                        )
                    }
            }
            ?: NativeRuntimeSnapshot.idle(source = "tunnel")

    suspend fun forwardingEvidence(): TunForwardingEvidence =
        reservations
            .withReservationOrNull({ handle }) { currentHandle ->
                withContext(Dispatchers.IO) {
                    nativeBindings.getForwardingEvidence(currentHandle)
                }
            }?.takeIf { it.isNotBlank() }
            ?.let { telemetryJson.decodeFromString(TunForwardingEvidence.serializer(), it) }
            ?: TunForwardingEvidence()
}

const val defaultTun2SocksTunnelMtu: Int = 1500

/**
 * Current tunnel native-config wire schema version. [Tun2SocksConfig] must
 * carry `schemaVersion`; missing and non-current versions are rejected. Version
 * 3 establishes fail-closed validation at both the Kotlin and live Android Rust
 * adapter boundaries; it is independent of the standalone YAML schema. See
 * `docs/architecture/CONFIG_CONTRACTS.md` §8.
 */
const val Tun2SocksConfigSchemaVersion: Int = 3

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
    val encryptedDnsOdohProxyUrl: String? = null,
    val encryptedDnsOdohProxyOperatorId: String? = null,
    val encryptedDnsOdohTargetHost: String? = null,
    val encryptedDnsOdohTargetPath: String? = null,
    val encryptedDnsOdohTargetOperatorId: String? = null,
    val encryptedDnsOdohConfigSource: String? = null,
    val encryptedDnsOdohConfigsHex: String? = null,
    val encryptedDnsOdohConfigsRetrievedAtSecs: Long? = null,
    val encryptedDnsOdohConfigsTtlSecs: Long? = null,
    val encryptedDnsTlsRootsPem: String? = null,
    val dnsQueryTimeoutMs: Int? = null,
    val resolverFallbackActive: Boolean? = null,
    val resolverFallbackReason: String? = null,
    val routeDnsThroughSocks5: Boolean? = null,
    val splitDnsPolicy: Tun2SocksSplitDnsPolicy? = null,
    val strategyChainYaml: String? = null,
    val protectPath: String? = null,
    val rootHelperSocketPath: String? = null,
    val luaScriptBaseDir: String? = null,
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
    val webrtcProtectionEnabled: Boolean = false,
    val uidPolicyMode: String = "disarmed",
    val uidPolicyUids: List<Int> = emptyList(),
    val uidPolicyAllowIcmp: Boolean = false,
    val logContext: RipDpiLogContext? = null,
    @Required
    val schemaVersion: Int = Tun2SocksConfigSchemaVersion,
)

@Serializable
data class TunForwardingEvidence(
    val tunReadPackets: Long = 0,
    val tunReadBytes: Long = 0,
    val tunWritePackets: Long = 0,
    val tunWriteBytes: Long = 0,
    val tunReadErrors: Long = 0,
    val tunWriteErrors: Long = 0,
    val tunParseFailures: Long = 0,
    val tunPolicyDrops: Long = 0,
    val tunInterceptorDrops: Long = 0,
    val tunQueueDrops: Long = 0,
    val firstTunWriteAtEpochMs: Long? = null,
    val lastTunWriteAtEpochMs: Long? = null,
)

@Serializable
data class Tun2SocksSplitDnsPolicy(
    val canonicalDigest: String,
    val destinationRoutingDigest: String,
    val defaultAction: String,
    val rules: List<Tun2SocksSplitDnsRule>,
    val directResolverCandidates: List<String>,
    val bootstrapPins: List<String>,
    val geositeDbPath: String? = null,
    val coverageReason: String? = null,
)

@Serializable
data class Tun2SocksSplitDnsRule(
    val action: String,
    val network: String,
    val domains: List<Tun2SocksSplitDnsDomainMatcher>,
    val hasIpRanges: Boolean,
    val hasPorts: Boolean,
)

@Serializable
data class Tun2SocksSplitDnsDomainMatcher(
    val kind: String,
    val value: String,
)
