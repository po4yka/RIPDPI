@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest
import com.poyka.ripdpi.core.WarpEndpointProbeNativeResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.MaxWarpScannerMaxRttMs
import com.poyka.ripdpi.data.MinWarpScannerMaxRttMs
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.CancellationException
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

    suspend fun probe(
        candidate: WarpEndpointCacheEntry,
        timeoutMillis: Int,
        context: WarpEndpointScanContext,
    ): WarpEndpointCacheEntry? = probe(candidate, timeoutMillis)
}

@Singleton
class DefaultWarpEndpointProbe
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WarpCredentialStore,
        private val nativeBindings: RipDpiWarpEndpointProbeBindings,
        private val protection: ActiveProtectSocketPathProvider,
    ) : WarpEndpointProbe {
        override suspend fun probe(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            val credentials = credentialStore.load(candidate.profileId) ?: return null
            return probe(
                candidate,
                timeoutMillis,
                WarpEndpointScanContext.capture(
                    credentials.copy(profileId = candidate.profileId),
                    appSettingsRepository.snapshot(),
                ),
            )
        }

        override suspend fun probe(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
            context: WarpEndpointScanContext,
        ): WarpEndpointCacheEntry? =
            withContext(Dispatchers.IO) {
                protection.captureWarpProbe().use { lease ->
                    if (!lease.isCurrent()) return@withContext null
                    if (context.requiredProtection == RelaySocketProtection.VpnRequired &&
                        lease.socketProtection != RelaySocketProtection.VpnRequired
                    ) {
                        return@withContext null
                    }
                    try {
                        probeNative(
                            candidate,
                            timeoutMillis.coerceIn(MinWarpScannerMaxRttMs, MaxWarpScannerMaxRttMs),
                            lease,
                            context,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }

        // Only a WireGuard handshake answer proves an endpoint works: a silent UDP port also
        // matches a black-holed one, so no answer means no candidate.
        private suspend fun probeNative(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
            lease: ActiveProtectSocketPathProvider.WarpProbeLease,
            context: WarpEndpointScanContext,
        ): WarpEndpointCacheEntry? {
            val credentials = context.credentials
            val privateKey = credentials.privateKey?.takeIf(String::isNotBlank)
            val peerPublicKey = credentials.peerPublicKey?.takeIf(String::isNotBlank)
            return if (credentials.profileId != candidate.profileId || privateKey == null || peerPublicKey == null) {
                null
            } else {
                val request =
                    WarpEndpointProbeNativeRequest(
                        endpoint = candidate.toResolvedEndpoint(),
                        privateKey = privateKey,
                        peerPublicKey = peerPublicKey,
                        clientId = credentials.clientId,
                        amnezia = context.config.amnezia,
                        timeoutMs = timeoutMillis.toLong(),
                        socketProtection = lease.socketProtection,
                    )
                nativeBindings
                    .probeEndpoint(WarpProbeJson.encodeToString(request), lease.controller)
                    ?.let { resultJson ->
                        if (!lease.isCurrent()) return null
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
