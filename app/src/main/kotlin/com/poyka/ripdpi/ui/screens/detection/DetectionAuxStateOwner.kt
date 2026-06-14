package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionHistoryRepository
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.community.CommunityStatsRepository
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the non-run auxiliary state for the detection screen: observed detection settings, history,
 * community stats, onboarding, and settings-write callbacks.
 *
 * Mutates the screen state exclusively through the shared [DetectionCheckStateReducer], on [scope]
 * (the host VM's `viewModelScope`). The settings-write callbacks are exposed as the same
 * `(T) -> Unit` properties the screen consumes via property access.
 */
internal class DetectionAuxStateOwner(
    private val scope: CoroutineScope,
    private val reducer: DetectionCheckStateReducer,
    private val appSettingsRepository: AppSettingsRepository,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val historyStore: DetectionHistoryRepository,
    private val communityStatsRepository: CommunityStatsRepository,
    private val detectionCheckPreferences: DetectionCheckPreferenceStore,
) {
    fun observeDetectionSettings() {
        scope.launch {
            appSettingsRepository.settings.collect { settings ->
                val privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled
                val debugModeEnabled = settings.detectionCheckDebugModeEnabled
                val colorVisionMode =
                    DetectionColorVisionMode.fromWire(settings.detectionCheckColorVisionMode)
                reducer.mutate { state ->
                    state.copy(
                        privacyModeEnabled = privacyModeEnabled,
                        cdnPullingEnabled = settings.detectionCheckCdnPullingEnabled,
                        debugModeEnabled = debugModeEnabled,
                        tlsKeylogPath = settings.detectionDiagnosticTlsKeylogPath,
                        colorVisionMode = colorVisionMode,
                        redGreenAltEnabled = settings.redGreenStatusAltEnabled,
                        reportText =
                            state.result?.let { result ->
                                DetectionReportFormatter.format(
                                    result = result,
                                    privacyModeEnabled = privacyModeEnabled,
                                )
                            } ?: state.reportText,
                        debugReportText =
                            state.result
                                ?.takeIf { debugModeEnabled }
                                ?.let { result ->
                                    DetectionDebugFormatter.format(
                                        result = result,
                                        settings = DetectionResultPresenter.debugSettings(settings),
                                        privacyModeEnabled = privacyModeEnabled,
                                    )
                                },
                    )
                }
            }
        }
    }

    fun loadHistory() {
        scope.launch {
            val history = historyStore.loadLatest()
            reducer.mutate { it.copy(history = history) }
        }
    }

    fun loadCommunityState() {
        scope.launch {
            val stats = communityStatsRepository.loadCachedOrLocal()
            if (stats != null) {
                reducer.mutate { it.copy(communityStats = stats) }
            }
        }
    }

    fun refreshCommunityStats() {
        scope.launch {
            reducer.mutate { it.copy(communityStatsLoading = true, communityStatsError = null) }
            try {
                val refreshResult = communityStatsRepository.refresh()
                val localStats = refreshResult.localStats
                reducer.mutate { it.copy(communityStats = localStats) }

                val remoteStats = refreshResult.remoteStats
                if (remoteStats != null) {
                    reducer.mutate { it.copy(communityStats = remoteStats) }
                } else if (reducer.value.communityStats == null) {
                    reducer.mutate {
                        it.copy(communityStatsError = refreshResult.remoteError?.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Defensive UI fallback: refresh chains may throw IOException (cache I/O,
                // settings flow), JSON parsing errors, or runtime exceptions; surface any
                // failure as an error state instead of crashing the VM.
                if (reducer.value.communityStats == null) {
                    reducer.mutate { it.copy(communityStatsError = e.message) }
                }
            } finally {
                reducer.mutate { it.copy(communityStatsLoading = false) }
            }
        }
    }

    fun reloadCommunityStats() {
        refreshCommunityStats()
    }

    fun dismissOnboarding() {
        detectionCheckPreferences.markOnboardingShown()
        reducer.mutate { it.copy(showOnboarding = false) }
    }

    fun applyAllFixes() {
        scope.launch {
            appSettingsRepository.update {
                applyDetectionFixes(reducer.value.suggestedFixes)
            }
            reducer.mutate { it.copy(suggestedFixes = emptyList()) }
        }
    }

    suspend fun saveToHistory(
        result: DetectionCheckResult,
        score: Int,
    ) {
        val entry =
            DetectionHistoryEntry(
                networkFingerprint = networkFingerprintProvider.capture()?.scopeKey() ?: "unknown",
                networkSummary =
                    networkFingerprintProvider.capture()?.summary()?.let {
                        "${it.transport}/${it.identityKind}"
                    } ?: result.geoIp.findings
                        .firstOrNull { it.description.startsWith("ISP:") }
                        ?.description
                        ?.removePrefix("ISP: ") ?: "Unknown",
                timestamp = System.currentTimeMillis(),
                verdict = result.verdict.name,
                stealthScore = score,
                evidenceCount =
                    result.geoIp.evidence.size +
                        result.directSigns.evidence.size +
                        result.indirectSigns.evidence.size +
                        result.locationSignals.evidence.size +
                        result.bypassResult.evidence.size,
            )
        historyStore.save(entry)
        loadHistory()
    }

    val setPrivacyModeEnabled: (Boolean) -> Unit = { enabled ->
        scope.launch {
            appSettingsRepository.update {
                detectionCheckPrivacyModeEnabled = enabled
            }
        }
    }

    val setCdnPullingEnabled: (Boolean) -> Unit = { enabled ->
        scope.launch {
            appSettingsRepository.update {
                detectionCheckCdnPullingEnabled = enabled
            }
        }
    }

    val setDebugModeEnabled: (Boolean) -> Unit = { enabled ->
        scope.launch {
            appSettingsRepository.update {
                detectionCheckDebugModeEnabled = enabled
            }
        }
    }

    val setColorVisionMode: (DetectionColorVisionMode) -> Unit = { mode ->
        scope.launch {
            appSettingsRepository.update {
                detectionCheckColorVisionMode = mode.wireValue
            }
        }
    }

    val unlockProtanopiaVariant: () -> Unit = {
        scope.launch {
            appSettingsRepository.update {
                enableRedGreenStatusAlt()
            }
        }
    }
}
