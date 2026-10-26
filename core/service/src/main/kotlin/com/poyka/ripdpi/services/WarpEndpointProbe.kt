@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest
import com.poyka.ripdpi.core.WarpEndpointProbeNativeResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

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
        private val nativeBindings: RipDpiWarpEndpointProbeBindings,
    ) : WarpEndpointProbe {
        override suspend fun probe(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? =
            withContext(Dispatchers.IO) {
                runCatching { probeBlocking(candidate, timeoutMillis.coerceAtLeast(250)) }.getOrNull()
            }

        // Only a WireGuard handshake answer proves an endpoint works: a silent UDP port also
        // matches a black-holed one, so no answer means no candidate.
        private suspend fun probeBlocking(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? = probeNative(candidate, timeoutMillis)

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
                                wireProfile = settings.warpAmneziaWireProfile,
                                i1 = settings.warpAmneziaI1,
                                i2 = settings.warpAmneziaI2,
                                i3 = settings.warpAmneziaI3,
                                i4 = settings.warpAmneziaI4,
                                i5 = settings.warpAmneziaI5,
                                h1 = settings.warpAmneziaH1.ifEmpty { "1" },
                                h2 = settings.warpAmneziaH2.ifEmpty { "2" },
                                h3 = settings.warpAmneziaH3.ifEmpty { "3" },
                                h4 = settings.warpAmneziaH4.ifEmpty { "4" },
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

        private fun WarpEndpointCacheEntry.toResolvedEndpoint() =
            ResolvedRipDpiWarpEndpoint(
                host = host?.ifBlank { ipv4 ?: ipv6.orEmpty() } ?: ipv4 ?: ipv6.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private companion object {
            val WarpProbeJson =
                RipDpiContractJson
        }
    }
