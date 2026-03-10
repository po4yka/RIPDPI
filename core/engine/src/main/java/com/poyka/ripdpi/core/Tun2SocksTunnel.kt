package com.poyka.ripdpi.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Tun2SocksTunnel {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    private val mutex = Mutex()
    private var handle = 0L

    suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        mutex.withLock {
            if (handle != 0L) {
                throw IllegalStateException("Tunnel is already running")
            }

            val createdHandle = jniCreate(Json.encodeToString(config))
            if (createdHandle == 0L) {
                throw IllegalStateException("Native tunnel session was not created")
            }

            try {
                jniStart(createdHandle, tunFd)
                handle = createdHandle
            } catch (e: Exception) {
                jniDestroy(createdHandle)
                throw e
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (handle == 0L) {
                throw IllegalStateException("Tunnel is not running")
            }

            try {
                jniStop(handle)
            } finally {
                try {
                    jniDestroy(handle)
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
                TunnelStats.fromNative(jniGetStats(handle))
            }
        }

    private external fun jniCreate(configJson: String): Long

    private external fun jniStart(
        handle: Long,
        tunFd: Int,
    )

    private external fun jniStop(handle: Long)

    private external fun jniGetStats(handle: Long): LongArray

    private external fun jniDestroy(handle: Long)
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

data class TunnelStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
) {
    companion object {
        fun fromNative(stats: LongArray): TunnelStats =
            TunnelStats(
                txPackets = stats.getOrElse(0) { 0L },
                txBytes = stats.getOrElse(1) { 0L },
                rxPackets = stats.getOrElse(2) { 0L },
                rxBytes = stats.getOrElse(3) { 0L },
            )
    }
}
