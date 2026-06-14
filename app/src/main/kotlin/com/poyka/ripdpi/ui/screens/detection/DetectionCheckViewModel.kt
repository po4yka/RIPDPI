package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionHistoryRepository
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.MethodologyVersion
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.core.detection.community.CommunityStatsRepository
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import com.poyka.ripdpi.core.detection.debug.DetectionDebugSettings
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.detection.RoutingProtectionRecommendationStrings
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetectionCheckUiState(
    val isRunning: Boolean = false,
    val progress: DetectionProgress? = null,
    val result: DetectionCheckResult? = null,
    val narrative: VerdictNarrative? = null,
    val stealthScore: Int? = null,
    val stealthLabel: String? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val suggestedFixes: List<DetectionSuggestedFix> = emptyList(),
    val reportText: String? = null,
    val debugReportText: String? = null,
    val error: String? = null,
    val showOnboarding: Boolean = false,
    val permissionAction: DetectionPermissionPlanner.Action = DetectionPermissionPlanner.Action.NONE,
    val missingPermissions: List<String> = emptyList(),
    val history: List<DetectionHistoryEntry> = emptyList(),
    val communityStats: CommunityStats? = null,
    val communityStatsLoading: Boolean = false,
    val communityStatsError: String? = null,
    val privacyModeEnabled: Boolean = false,
    val cdnPullingEnabled: Boolean = false,
    val debugModeEnabled: Boolean = false,
    val tlsKeylogPath: String = "",
    val colorVisionMode: DetectionColorVisionMode = DetectionColorVisionMode.OFF,
    val redGreenAltEnabled: Boolean = false,
) {
    val tlsKeylogWarningPath: String?
        get() = tlsKeylogPath.takeUnless { !debugModeEnabled || privacyModeEnabled || it.isBlank() }
}

@HiltViewModel
class DetectionCheckViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider,
        private val routingProtectionCatalogService: RoutingProtectionCatalogService,
        private val detectionCheckRunner: DetectionCheckRunner,
        private val historyStore: DetectionHistoryRepository,
        private val communityStatsRepository: CommunityStatsRepository,
        private val detectionCheckPreferences: DetectionCheckPreferences,
        private val detectionCheckPlatform: DetectionCheckPlatform,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DetectionCheckUiState())
        val uiState: StateFlow<DetectionCheckUiState> = _uiState.asStateFlow()

        private var runJob: Job? = null

        init {
            checkInitialState()
        }

        private fun checkInitialState() {
            if (!detectionCheckPreferences.wasOnboardingShown()) {
                _uiState.value = _uiState.value.copy(showOnboarding = true)
            }
            refreshPermissionState()
            observeDetectionSettings()
            loadHistory()
            loadCommunityState()
        }

        private fun observeDetectionSettings() {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    val privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled
                    val debugModeEnabled = settings.detectionCheckDebugModeEnabled
                    val colorVisionMode =
                        DetectionColorVisionMode.fromWire(settings.detectionCheckColorVisionMode)
                    _uiState.value =
                        _uiState.value.copy(
                            privacyModeEnabled = privacyModeEnabled,
                            cdnPullingEnabled = settings.detectionCheckCdnPullingEnabled,
                            debugModeEnabled = debugModeEnabled,
                            tlsKeylogPath = settings.detectionDiagnosticTlsKeylogPath,
                            colorVisionMode = colorVisionMode,
                            redGreenAltEnabled = settings.redGreenStatusAltEnabled,
                            reportText =
                                _uiState.value.result?.let { result ->
                                    DetectionReportFormatter.format(
                                        result = result,
                                        privacyModeEnabled = privacyModeEnabled,
                                    )
                                } ?: _uiState.value.reportText,
                            debugReportText =
                                _uiState.value.result
                                    ?.takeIf { debugModeEnabled }
                                    ?.let { result ->
                                        DetectionDebugFormatter.format(
                                            result = result,
                                            settings = settings.toDetectionDebugSettings(),
                                            privacyModeEnabled = privacyModeEnabled,
                                        )
                                    },
                        )
                }
            }
        }

        private fun loadHistory() {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        history = historyStore.loadLatest(),
                    )
            }
        }

        private fun loadCommunityState() {
            viewModelScope.launch {
                val stats = communityStatsRepository.loadCachedOrLocal()
                if (stats != null) {
                    _uiState.value = _uiState.value.copy(communityStats = stats)
                }
            }
        }

        private fun refreshCommunityStats() {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(communityStatsLoading = true, communityStatsError = null)
                try {
                    val refreshResult = communityStatsRepository.refresh()
                    val localStats = refreshResult.localStats
                    _uiState.value = _uiState.value.copy(communityStats = localStats)

                    val remoteStats = refreshResult.remoteStats
                    if (remoteStats != null) {
                        _uiState.value = _uiState.value.copy(communityStats = remoteStats)
                    } else if (_uiState.value.communityStats == null) {
                        _uiState.value =
                            _uiState.value.copy(
                                communityStatsError = refreshResult.remoteError?.message,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Defensive UI fallback: refresh chains may throw IOException (cache I/O,
                    // settings flow), JSON parsing errors, or runtime exceptions; surface any
                    // failure as an error state instead of crashing the VM.
                    if (_uiState.value.communityStats == null) {
                        _uiState.value =
                            _uiState.value.copy(communityStatsError = e.message)
                    }
                } finally {
                    _uiState.value = _uiState.value.copy(communityStatsLoading = false)
                }
            }
        }

        fun reloadCommunityStats() {
            refreshCommunityStats()
        }

        fun dismissOnboarding() {
            detectionCheckPreferences.markOnboardingShown()
            _uiState.value = _uiState.value.copy(showOnboarding = false)
        }

        fun onPermissionsResult() {
            markPermissionsRequested()
            refreshPermissionState()
        }

        fun refreshPermissionState() {
            val states =
                requiredPermissions().map { permission ->
                    DetectionPermissionPlanner.PermissionState(
                        permission = permission,
                        granted = detectionCheckPlatform.isPermissionGranted(permission),
                        shouldShowRationale = false,
                        wasRequestedBefore = detectionCheckPreferences.wasPermissionRequested(permission),
                    )
                }
            val action = DetectionPermissionPlanner.decideAction(states)
            val missing = DetectionPermissionPlanner.missingPermissions(states)
            _uiState.value =
                _uiState.value.copy(
                    permissionAction = action,
                    missingPermissions = missing,
                )
        }

        fun startCheck() {
            if (_uiState.value.isRunning) return
            runJob =
                viewModelScope.launch {
                    _uiState.value = _uiState.value.resetForCheckRun()
                    val outcome =
                        runCatching {
                            val settings = appSettingsRepository.settings.first()
                            val config = settings.toDetectionRunnerConfig(detectionCheckPlatform.packageName)
                            val result =
                                detectionCheckPlatform.runDetectionCheck(
                                    runner = detectionCheckRunner,
                                    config = config,
                                    onProgress = { progress ->
                                        _uiState.value = _uiState.value.copy(progress = progress)
                                    },
                                )

                            val score = StealthScore.compute(result)
                            val label = StealthScore.label(score)
                            val recommendations =
                                buildAllRecommendations(
                                    result = result,
                                    settings = settings,
                                    snapshot = routingProtectionCatalogService.snapshot(),
                                    stringResolver = stringResolver,
                                )
                            val privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled
                            val reportText =
                                DetectionReportFormatter.format(
                                    result = result,
                                    privacyModeEnabled = privacyModeEnabled,
                                )
                            val debugReportText =
                                result
                                    .takeIf { settings.detectionCheckDebugModeEnabled }
                                    ?.let {
                                        DetectionDebugFormatter.format(
                                            result = it,
                                            settings = settings.toDetectionDebugSettings(),
                                            privacyModeEnabled = privacyModeEnabled,
                                        )
                                    }
                            val fixes = settings.suggestDetectionFixes(result)

                            saveToHistory(result, score)
                            refreshCommunityStats()

                            _uiState.value =
                                _uiState.value.withCheckResult(
                                    result = result,
                                    score = score,
                                    label = label,
                                    recommendations = recommendations,
                                    fixes = fixes,
                                    reportText = reportText,
                                    debugReportText = debugReportText,
                                )
                        }

                    outcome.onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                progress = null,
                                error = error.message ?: stringResolver.getString(R.string.detection_error_unknown),
                            )
                    }
                }
        }

        private fun DetectionCheckUiState.resetForCheckRun(): DetectionCheckUiState =
            copy(
                isRunning = true,
                progress = null,
                result = null,
                narrative = null,
                stealthScore = null,
                stealthLabel = null,
                recommendations = emptyList(),
                suggestedFixes = emptyList(),
                reportText = null,
                debugReportText = null,
                error = null,
            )

        private fun DetectionCheckUiState.withCheckResult(
            result: DetectionCheckResult,
            score: Int,
            label: String,
            recommendations: List<Recommendation>,
            fixes: List<DetectionSuggestedFix>,
            reportText: String,
            debugReportText: String?,
        ): DetectionCheckUiState =
            copy(
                isRunning = false,
                progress = null,
                result = result,
                narrative = result.verdictNarrative,
                stealthScore = score,
                stealthLabel = label,
                recommendations = recommendations,
                suggestedFixes = fixes,
                reportText = reportText,
                debugReportText = debugReportText,
            )

        private fun AppSettings.toDetectionRunnerConfig(packageName: String): DetectionRunnerConfig =
            DetectionRunnerConfig(
                ownProxyPort = proxyPort.takeIf { it > 0 },
                ownPackageName = packageName,
                includeBypassCheck = detectionCheckIncludeBypass,
                includeLocationCheck = detectionCheckIncludeLocation,
                includeRttTriangulationCheck =
                    detectionCheckNetworkRequestsEnabled && detectionCheckRttTriangulationEnabled,
                includeCdnPullingCheck =
                    detectionCheckNetworkRequestsEnabled && detectionCheckCdnPullingEnabled,
                includeCallTransportCheck =
                    detectionCheckNetworkRequestsEnabled && detectionCheckCallTransportProbeEnabled,
                bypassScanOptions =
                    BypassScanOptions(
                        proxyScanEnabled = detectionCheckIncludeBypass,
                        xrayApiScanEnabled = detectionCheckXrayApiScanEnabled,
                        callTransportProbeEnabled = false,
                        portRange = toBypassPortRange(),
                    ),
                resolverConfig = toDetectionResolverConfig(),
                encryptedDnsEnabled = dnsMode == "encrypted" || detectionCheckDnsResolverMode == "doh",
                webRtcProtectionEnabled = webrtcProtectionEnabled,
                tlsFingerprintProfile = tlsFingerprintProfile.ifEmpty { "chrome_stable" },
            )

        private fun AppSettings.toDetectionDebugSettings(): DetectionDebugSettings =
            DetectionDebugSettings(
                cdnPullingEnabled = detectionCheckCdnPullingEnabled,
                dnsResolverMode = detectionCheckDnsResolverMode.ifEmpty { dnsMode },
                portRange = detectionCheckPortRangeMode.ifEmpty { "popular" },
                debugModeEnabled = detectionCheckDebugModeEnabled,
            )

        private fun AppSettings.toBypassPortRange(): BypassPortRange =
            when (DetectionPortRangeMode.fromWire(detectionCheckPortRangeMode)) {
                DetectionPortRangeMode.POPULAR -> {
                    BypassPortRange.Popular
                }

                DetectionPortRangeMode.EXTENDED -> {
                    BypassPortRange.Extended
                }

                DetectionPortRangeMode.FULL -> {
                    BypassPortRange.Full
                }

                DetectionPortRangeMode.CUSTOM -> {
                    BypassPortRange.Custom(
                        start = detectionCheckCustomPortStart.takeIf { it > 0 } ?: 1080,
                        end = detectionCheckCustomPortEnd.takeIf { it > 0 } ?: 1090,
                    )
                }
            }

        fun applyAllFixes() {
            viewModelScope.launch {
                appSettingsRepository.update {
                    applyDetectionFixes(_uiState.value.suggestedFixes)
                }
                _uiState.value = _uiState.value.copy(suggestedFixes = emptyList())
            }
        }

        val setPrivacyModeEnabled: (Boolean) -> Unit = { enabled ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckPrivacyModeEnabled = enabled
                }
            }
        }

        val setCdnPullingEnabled: (Boolean) -> Unit = { enabled ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckCdnPullingEnabled = enabled
                }
            }
        }

        val setDebugModeEnabled: (Boolean) -> Unit = { enabled ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckDebugModeEnabled = enabled
                }
            }
        }

        val setColorVisionMode: (DetectionColorVisionMode) -> Unit = { mode ->
            viewModelScope.launch {
                appSettingsRepository.update {
                    detectionCheckColorVisionMode = mode.wireValue
                }
            }
        }

        val unlockProtanopiaVariant: () -> Unit = {
            viewModelScope.launch {
                appSettingsRepository.update {
                    enableRedGreenStatusAlt()
                }
            }
        }

        fun stopCheck() {
            runJob?.cancel()
            runJob = null
            _uiState.value = _uiState.value.copy(isRunning = false, progress = null)
        }

        private suspend fun saveToHistory(
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

        private fun markPermissionsRequested() {
            detectionCheckPreferences.markPermissionsRequested(requiredPermissions())
        }

        companion object {
            fun requiredPermissions(): Array<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    )
                } else {
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
                }
        }
    }

private fun buildAllRecommendations(
    result: DetectionCheckResult,
    settings: AppSettings,
    snapshot: RoutingProtectionCatalogSnapshot,
    stringResolver: StringResolver,
): List<Recommendation> =
    DetectionRecommendations.generate(result) +
        buildRoutingProtectionRecommendations(
            result = result,
            settings = settings,
            snapshot = snapshot,
            strings = RoutingProtectionRecommendationStrings.resolve(stringResolver),
        )
