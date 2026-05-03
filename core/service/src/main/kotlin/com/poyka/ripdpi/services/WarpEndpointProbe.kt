@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings
import com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest
import com.poyka.ripdpi.core.WarpEndpointProbeNativeResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

interface WarpEndpointProbe {
    suspend fun probe(
        candidate: WarpEndpointCacheEntry,
        timeoutMillis: Int,
    ): WarpEndpointCacheEntry?
}

@Singleton
class DefaultWarpEndpointProbe
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WarpCredentialStore,
        private val nativeBindings: RipDpiWarpNativeBindings,
    ) : WarpEndpointProbe {
        override suspend fun probe(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? =
            withContext(Dispatchers.IO) {
                runCatching { probeBlocking(candidate, timeoutMillis.coerceAtLeast(250)) }.getOrNull()
            }

        private suspend fun probeBlocking(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            probeNative(candidate, timeoutMillis)?.let { return it }
            return probeFallbackUdp(candidate, timeoutMillis)
        }

        private suspend fun probeNative(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            val credentials = credentialStore.load(candidate.profileId)
            val privateKey = credentials?.privateKey?.takeIf(String::isNotBlank)
            val peerPublicKey = credentials?.peerPublicKey?.takeIf(String::isNotBlank)
            return if (credentials == null || privateKey == null || peerPublicKey == null) {
                null
            } else {
                val settings = appSettingsRepository.snapshot()
                val request =
                    WarpEndpointProbeNativeRequest(
                        endpoint = candidate.toResolvedEndpoint(),
                        privateKey = privateKey,
                        peerPublicKey = peerPublicKey,
                        clientId = credentials.clientId,
                        amnezia =
                            RipDpiWarpAmneziaConfig(
                                enabled = settings.warpAmneziaEnabled,
                                jc = settings.warpAmneziaJc,
                                jmin = settings.warpAmneziaJmin,
                                jmax = settings.warpAmneziaJmax,
                                h1 = settings.warpAmneziaH1,
                                h2 = settings.warpAmneziaH2,
                                h3 = settings.warpAmneziaH3,
                                h4 = settings.warpAmneziaH4,
                                s1 = settings.warpAmneziaS1,
                                s2 = settings.warpAmneziaS2,
                                s3 = settings.warpAmneziaS3,
                                s4 = settings.warpAmneziaS4,
                            ),
                        timeoutMs = timeoutMillis.toLong(),
                    )
                nativeBindings.probeEndpoint(WarpProbeJson.encodeToString(request))?.let { resultJson ->
                    val result = WarpProbeJson.decodeFromString<WarpEndpointProbeNativeResult>(resultJson)
                    candidate.copy(
                        host =
                            result.host
                                .ifBlank {
                                    candidate.host ?: ""
                                }.ifBlank { candidate.ipv4 ?: candidate.ipv6.orEmpty() },
                        ipv4 = result.ipv4 ?: candidate.ipv4,
                        ipv6 = result.ipv6 ?: candidate.ipv6,
                        port = result.port,
                        source = "scanner_native",
                        rttMs = result.rttMs,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    )
                }
            }
        }

        private fun probeFallbackUdp(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            val address = resolveSocketAddress(candidate) ?: return null
            val startedAtNanos = System.nanoTime()
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis
                socket.connect(address)
                val payload = byteArrayOf(0x01, 0x03, 0x03, 0x07)
                socket.send(DatagramPacket(payload, payload.size))
                try {
                    val response = DatagramPacket(ByteArray(64), 64)
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    // WARP UDP endpoints typically stay silent; a clean send is still a usable signal.
                }
            }
            val resolvedAddress = address.address
            val elapsedMillis = max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)
            return candidate.copy(
                host = candidate.host?.ifBlank { address.hostString } ?: address.hostString,
                ipv4 = candidate.ipv4 ?: (resolvedAddress as? Inet4Address)?.hostAddress,
                ipv6 = candidate.ipv6 ?: (resolvedAddress as? Inet6Address)?.hostAddress,
                source = "scanner",
                rttMs = elapsedMillis,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        private fun WarpEndpointCacheEntry.toResolvedEndpoint() =
            ResolvedRipDpiWarpEndpoint(
                host = host?.ifBlank { ipv4 ?: ipv6.orEmpty() } ?: ipv4 ?: ipv6.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private fun resolveSocketAddress(candidate: WarpEndpointCacheEntry): InetSocketAddress? {
            val port = candidate.port.takeIf { it > 0 }
            return port?.let { resolvedPort ->
                val literalAddress =
                    candidate.ipv4?.takeIf(String::isNotBlank)
                        ?: candidate.ipv6?.takeIf(String::isNotBlank)
                literalAddress?.let { InetSocketAddress(it, resolvedPort) }
                    ?: candidate.host?.takeIf(String::isNotBlank)?.let { host ->
                        runCatching { InetAddress.getAllByName(host).firstOrNull() }
                            .getOrNull()
                            ?.let { resolved -> InetSocketAddress(resolved, resolvedPort) }
                    }
            }
        }

        private companion object {
            val WarpProbeJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
        }
    }
