package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.normalizeWarpScannerMode
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
            val scanSettings = loadScanSettings(profileId)

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
                            timeoutMillis = scanSettings.timeoutMillis,
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
                        timeoutMillis = scanSettings.timeoutMillis,
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
                globalCandidate ?: scanAndPersistCandidate(
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    provisioned = provisioned,
                    scanSettings = scanSettings,
                )

            return scannedCandidate ?: provisioned?.let { fallback ->
                persistWarpBestCandidate(
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    candidate = fallback,
                )
            }
        }

        private suspend fun loadScanSettings(profileId: String): WarpEndpointScanSettings {
            val settings = appSettingsRepository.snapshot()
            val scannerMode =
                if (settings.warpProfileId == profileId) {
                    normalizeWarpScannerMode(settings.warpLastScannerMode)
                } else {
                    WarpScannerModeAutomatic
                }
            return WarpEndpointScanSettings(
                enabled = settings.warpScannerEnabled,
                mode = scannerMode,
                parallelism = settings.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
                timeoutMillis = settings.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs,
            )
        }

        private suspend fun scanAndPersistCandidate(
            profileId: String,
            networkScopeKey: String,
            provisioned: WarpEndpointCacheEntry?,
            scanSettings: WarpEndpointScanSettings,
        ): WarpEndpointCacheEntry? {
            if (!scanSettings.enabled) return null
            val bestCandidate = scanBestCandidate(profileId, provisioned, scanSettings)
            return bestCandidate?.let {
                persistWarpBestCandidate(
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = networkScopeKey,
                    candidate = it,
                )
            }
        }

        private suspend fun scanBestCandidate(
            profileId: String,
            provisioned: WarpEndpointCacheEntry?,
            scanSettings: WarpEndpointScanSettings,
        ): WarpEndpointCacheEntry? =
            if (scanSettings.mode == WarpScannerModeAutomatic) {
                scanAutomaticWarpCandidatePool(
                    endpointProbe = endpointProbe,
                    endpointStore = endpointStore,
                    profileId = profileId,
                    provisioned = provisioned,
                    timeoutMillis = scanSettings.timeoutMillis,
                    parallelism = scanSettings.parallelism,
                )
            } else {
                scanWarpCandidatePool(
                    endpointProbe = endpointProbe,
                    candidates = buildWarpCandidatePool(endpointStore, profileId, provisioned),
                    timeoutMillis = scanSettings.timeoutMillis,
                    parallelism = scanSettings.parallelism,
                )
            }
    }

private data class WarpEndpointScanSettings(
    val enabled: Boolean,
    val mode: String,
    val parallelism: Int,
    val timeoutMillis: Int,
)
