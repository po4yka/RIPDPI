package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import javax.inject.Inject
import javax.inject.Singleton

interface WarpEndpointScanner {
    suspend fun resolveEndpoint(
        profileId: String,
        networkScopeKey: String,
        provisioned: WarpEndpointCacheEntry?,
    ): WarpEndpointCacheEntry?
}

@Singleton
class DefaultWarpEndpointScanner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val endpointStore: WarpEndpointStore,
        private val endpointProbe: WarpEndpointProbe,
    ) : WarpEndpointScanner {
        override suspend fun resolveEndpoint(
            profileId: String,
            networkScopeKey: String,
            provisioned: WarpEndpointCacheEntry?,
        ): WarpEndpointCacheEntry? {
            val normalizedScope = networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val now = System.currentTimeMillis()
            val settings = appSettingsRepository.snapshot()
            val scannerEnabled = settings.warpScannerEnabled
            val parallelism =
                settings.warpScannerParallelism
                    .takeIf { it > 0 }
                    ?: DefaultWarpScannerParallelism
            val timeoutMillis =
                settings.warpScannerMaxRttMs
                    .takeIf { it > 0 }
                    ?: DefaultWarpScannerMaxRttMs

            val scopedCandidate =
                endpointStore.load(profileId, normalizedScope)?.let { cached ->
                    if (cached.isFreshWarpEndpoint(now)) {
                        cached.copy(profileId = profileId, networkScopeKey = normalizedScope)
                    } else {
                        probeCachedWarpEntry(
                            endpointProbe = endpointProbe,
                            endpointStore = endpointStore,
                            profileId = profileId,
                            networkScopeKey = normalizedScope,
                            entry = cached,
                            timeoutMillis = timeoutMillis,
                        )
                    }
                }
            val globalCandidate =
                scopedCandidate ?: endpointStore.load(profileId, GlobalWarpEndpointScopeKey)?.let { cached ->
                    probeCachedWarpEntry(
                        endpointProbe = endpointProbe,
                        endpointStore = endpointStore,
                        profileId = profileId,
                        networkScopeKey = GlobalWarpEndpointScopeKey,
                        entry = cached,
                        timeoutMillis = timeoutMillis,
                    )?.let { global ->
                        persistWarpBestCandidate(
                            endpointStore = endpointStore,
                            profileId = profileId,
                            networkScopeKey = normalizedScope,
                            candidate = global,
                        )
                    }
                }
            val scannedCandidate =
                globalCandidate ?: if (scannerEnabled) {
                    scanWarpCandidatePool(
                        endpointProbe = endpointProbe,
                        candidates = buildWarpCandidatePool(endpointStore, profileId, provisioned),
                        timeoutMillis = timeoutMillis,
                        parallelism = parallelism,
                    )?.let { bestCandidate ->
                        persistWarpBestCandidate(
                            endpointStore = endpointStore,
                            profileId = profileId,
                            networkScopeKey = normalizedScope,
                            candidate = bestCandidate,
                        )
                    }
                } else {
                    null
                }

            return scannedCandidate ?: provisioned?.let { fallback ->
                persistWarpBestCandidate(
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    candidate = fallback,
                )
            }
        }
    }
