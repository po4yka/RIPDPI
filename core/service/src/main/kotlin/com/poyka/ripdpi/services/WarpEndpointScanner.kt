package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.MaxWarpScannerMaxRttMs
import com.poyka.ripdpi.data.MaxWarpScannerParallelism
import com.poyka.ripdpi.data.MinWarpScannerMaxRttMs
import com.poyka.ripdpi.data.MinWarpScannerParallelism
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.normalizeWarpScannerMode
import javax.inject.Inject
import javax.inject.Singleton

enum class WarpEndpointResolutionOrigin { UserMutation, RuntimeProvisioning }

interface WarpEndpointScanner {
    suspend fun resolveEndpoint(
        profileId: String,
        networkScopeKey: String,
        provisioned: WarpEndpointCacheEntry?,
        origin: WarpEndpointResolutionOrigin,
        context: WarpEndpointScanContext? = null,
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
            origin: WarpEndpointResolutionOrigin,
            context: WarpEndpointScanContext?,
        ): WarpEndpointCacheEntry? {
            val persist = origin == WarpEndpointResolutionOrigin.UserMutation && context == null
            val normalizedScope = networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val now = System.currentTimeMillis()
            val scanSettings = loadScanSettings(profileId, context)
            val boundProbe = context?.bind(endpointProbe) ?: endpointProbe
            if (!scanSettings.enabled) {
                return normalizeWarpEndpoint(
                    profileId,
                    normalizedScope,
                    endpointStore.load(profileId, normalizedScope)
                        ?: endpointStore.load(profileId, GlobalWarpEndpointScopeKey)
                        ?: provisioned,
                )
            }

            val scopedCandidate =
                endpointStore.load(profileId, normalizedScope)?.let { cached ->
                    if (origin == WarpEndpointResolutionOrigin.UserMutation && context == null &&
                        cached.isFreshWarpEndpoint(now)
                    ) {
                        cached.copy(profileId = profileId, networkScopeKey = normalizedScope)
                    } else {
                        probeCachedWarpEntry(
                            endpointProbe = boundProbe,
                            endpointStore = endpointStore,
                            profileId = profileId,
                            networkScopeKey = normalizedScope,
                            entry = cached,
                            timeoutMillis = scanSettings.timeoutMillis,
                            persist = persist,
                        )
                    }
                }
            val globalCandidate =
                scopedCandidate ?: endpointStore.load(profileId, GlobalWarpEndpointScopeKey)?.let { cached ->
                    probeCachedWarpEntry(
                        endpointProbe = boundProbe,
                        endpointStore = endpointStore,
                        profileId = profileId,
                        networkScopeKey = GlobalWarpEndpointScopeKey,
                        entry = cached,
                        timeoutMillis = scanSettings.timeoutMillis,
                        persist = persist,
                    )?.let { global ->
                        persistWarpBestCandidate(
                            endpointStore = endpointStore,
                            profileId = profileId,
                            networkScopeKey = normalizedScope,
                            candidate = global,
                            persist = persist,
                        )
                    }
                }
            val scannedCandidate =
                globalCandidate ?: scanAndPersistCandidate(
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    provisioned = provisioned,
                    scanSettings = scanSettings,
                    persist = persist,
                    boundProbe = boundProbe,
                )

            return if (origin == WarpEndpointResolutionOrigin.RuntimeProvisioning) {
                scannedCandidate
            } else {
                scannedCandidate ?: provisioned?.let { fallback ->
                    persistWarpBestCandidate(
                        endpointStore = endpointStore,
                        profileId = profileId,
                        networkScopeKey = normalizedScope,
                        candidate = fallback,
                        persist = persist,
                    )
                }
            }
        }

        private suspend fun loadScanSettings(
            profileId: String,
            context: WarpEndpointScanContext?,
        ): WarpEndpointScanSettings {
            context?.let {
                return WarpEndpointScanSettings(
                    enabled = it.config.scannerEnabled,
                    mode = it.scannerMode,
                    parallelism =
                        it.config.scannerParallelism.coerceIn(
                            MinWarpScannerParallelism,
                            MaxWarpScannerParallelism,
                        ),
                    timeoutMillis =
                        it.config.scannerMaxRttMs.coerceIn(
                            MinWarpScannerMaxRttMs,
                            MaxWarpScannerMaxRttMs,
                        ),
                )
            }
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
                parallelism =
                    (settings.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism)
                        .coerceIn(MinWarpScannerParallelism, MaxWarpScannerParallelism),
                timeoutMillis =
                    (settings.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs)
                        .coerceIn(MinWarpScannerMaxRttMs, MaxWarpScannerMaxRttMs),
            )
        }

        private suspend fun scanAndPersistCandidate(
            profileId: String,
            networkScopeKey: String,
            provisioned: WarpEndpointCacheEntry?,
            scanSettings: WarpEndpointScanSettings,
            persist: Boolean,
            boundProbe: WarpEndpointProbe,
        ): WarpEndpointCacheEntry? {
            if (!scanSettings.enabled) return null
            val bestCandidate = scanBestCandidate(profileId, provisioned, scanSettings, boundProbe)
            return bestCandidate?.let {
                persistWarpBestCandidate(
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = networkScopeKey,
                    candidate = it,
                    persist = persist,
                )
            }
        }

        private suspend fun scanBestCandidate(
            profileId: String,
            provisioned: WarpEndpointCacheEntry?,
            scanSettings: WarpEndpointScanSettings,
            boundProbe: WarpEndpointProbe,
        ): WarpEndpointCacheEntry? =
            if (scanSettings.mode == WarpScannerModeAutomatic) {
                scanAutomaticWarpCandidatePool(
                    endpointProbe = boundProbe,
                    endpointStore = endpointStore,
                    profileId = profileId,
                    provisioned = provisioned,
                    timeoutMillis = scanSettings.timeoutMillis,
                    parallelism = scanSettings.parallelism,
                )
            } else {
                scanWarpCandidatePool(
                    endpointProbe = boundProbe,
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
