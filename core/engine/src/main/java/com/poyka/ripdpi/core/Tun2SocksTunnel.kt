package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import javax.inject.Inject

interface Tun2SocksBindings {
    fun create(configJson: String): Long

    fun start(
        handle: Long,
        tunFd: Int,
    )

    fun stop(handle: Long)

    fun getStats(handle: Long): LongArray

    fun getTelemetry(handle: Long): String?

    fun destroy(handle: Long)
}

class Tun2SocksNativeBindings
    @Inject
    constructor() : Tun2SocksBindings {
        companion object {
            init {
                System.loadLibrary("hev-socks5-tunnel")
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
                logcat(LogPriority.ERROR) { "Tunnel native session creation returned null handle" }
                throw NativeError.SessionCreationFailed("tunnel")
            }
            logcat(LogPriority.DEBUG) { "Tunnel native session created: handle=$createdHandle" }

            try {
                withContext(Dispatchers.IO) {
                    nativeBindings.start(createdHandle, tunFd)
                }
                logcat(LogPriority.DEBUG) { "Tunnel native start completed: tunFd=$tunFd" }
                handle = createdHandle
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    nativeBindings.destroy(createdHandle)
                }
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

@Serializable
data class Tun2SocksConfig(
    val tunnelName: String = "tun0",
    val tunnelMtu: Int = 8500,
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
    val dnsQueryTimeoutMs: Int? = null,
    val resolverFallbackActive: Boolean? = null,
    val resolverFallbackReason: String? = null,
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
)
