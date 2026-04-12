package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.DetectionAutoTuner
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionHistoryStore
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.DetectionRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.MethodologyVersion
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.community.CommunityComparisonClient
import com.poyka.ripdpi.core.detection.community.CommunityComparisonStore
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
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
    val stealthScore: Int? = null,
    val stealthLabel: String? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val autoTuneFixes: List<AutoTuneFix> = emptyList(),
    val reportText: String? = null,
    val error: String? = null,
    val showOnboarding: Boolean = false,
    val permissionAction: DetectionPermissionPlanner.Action = DetectionPermissionPlanner.Action.NONE,
    val missingPermissions: List<String> = emptyList(),
    val history: List<DetectionHistoryEntry> = emptyList(),
    val communityStats: CommunityStats? = null,
)

private const val entropyModeBalanced = 3
private const val entropyPaddingTargetPermilValue = 3400
private const val shannonEntropyTargetPermilValue = 7920
private const val strategyEvolutionRecommendedEpsilon = 0.1

@HiltViewModel
class DetectionCheckViewModel
    @Inject
    constructor(
        private val application: Application,
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider,
        private val routingProtectionCatalogService: RoutingProtectionCatalogService,
    ) : AndroidViewModel(application) {
        private val _uiState = MutableStateFlow(DetectionCheckUiState())
        val uiState: StateFlow<DetectionCheckUiState> = _uiState.asStateFlow()

        private var runJob: Job? = null
        private val prefs by lazy {
            application.getSharedPreferences("detection_check_prefs", android.content.Context.MODE_PRIVATE)
        }
        private val historyStore by lazy { DetectionHistoryStore(application) }
        private val communityStore by lazy { CommunityComparisonStore(application) }

        init {
            checkInitialState()
        }

        private fun checkInitialState() {
            val onboardingShown = prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)
            if (!onboardingShown) {
                _uiState.value = _uiState.value.copy(showOnboarding = true)
            }
            refreshPermissionState()
            loadHistory()
            loadCommunityState()
        }

        private fun loadHistory() {
            _uiState.value =
                _uiState.value.copy(
                    history = historyStore.latestEntries(),
                )
        }

        private fun loadCommunityState() {
            val cached = communityStore.getCachedStats()
            if (cached != null) {
                _uiState.value = _uiState.value.copy(communityStats = cached)
            } else {
                val localStats = CommunityComparisonClient.computeLocalStats(historyStore)
                if (localStats.totalReports > 0) {
                    _uiState.value = _uiState.value.copy(communityStats = localStats)
                }
            }
        }

        private fun refreshCommunityStats() {
            viewModelScope.launch {
                val localStats = CommunityComparisonClient.computeLocalStats(historyStore)
                _uiState.value = _uiState.value.copy(communityStats = localStats)

                val settings = appSettingsRepository.settings.first()
                val statsUrl =
                    settings.communityApiUrl.ifBlank {
                        CommunityComparisonClient.DEFAULT_STATS_URL
                    }
                val client = CommunityComparisonClient()
                client.fetchStats(statsUrl).onSuccess { remoteStats ->
                    communityStore.cacheStats(remoteStats)
                    _uiState.value = _uiState.value.copy(communityStats = remoteStats)
                }
            }
        }

        fun dismissOnboarding() {
            prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
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
                        granted =
                            ContextCompat.checkSelfPermission(
                                application,
                                permission,
                            ) == PackageManager.PERMISSION_GRANTED,
                        shouldShowRationale = false,
                        wasRequestedBefore = prefs.getBoolean("perm_requested_$permission", false),
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
                            val config = settings.toDetectionRunnerConfig(application.packageName)
                            val result =
                                DetectionRunner.run(
                                    context = application,
                                    config = config,
                                    onProgress = { progress ->
                                        _uiState.value = _uiState.value.copy(progress = progress)
                                    },
                                )

                            val score = StealthScore.compute(result)
                            val label = StealthScore.label(score)
                            val recommendations =
                                DetectionRecommendations.generate(result) +
                                    buildRoutingProtectionRecommendations(
                                        result = result,
                                        settings = settings,
                                        snapshot = routingProtectionCatalogService.snapshot(),
                                    )
                            val reportText = DetectionReportFormatter.format(result)
                            val fixes =
                                DetectionAutoTuner.suggestFixes(
                                    result = result,
                                    tlsFingerprintEnabled = true,
                                    entropyPaddingEnabled = settings.entropyMode != 0,
                                    encryptedDnsEnabled = settings.dnsMode == "encrypted",
                                    fullTunnelEnabled = settings.fullTunnelMode,
                                    strategyEvolutionEnabled = settings.strategyEvolution,
                                )

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
                                error = error.message ?: "Unknown error",
                            )
                    }
                }
        }

        private fun DetectionCheckUiState.resetForCheckRun(): DetectionCheckUiState =
            copy(
                isRunning = true,
                progress = null,
                result = null,
                stealthScore = null,
                stealthLabel = null,
                recommendations = emptyList(),
                autoTuneFixes = emptyList(),
                reportText = null,
                error = null,
            )

        private fun DetectionCheckUiState.withCheckResult(
            result: DetectionCheckResult,
            score: Int,
            label: String,
            recommendations: List<Recommendation>,
            fixes: List<AutoTuneFix>,
            reportText: String,
        ): DetectionCheckUiState =
            copy(
                isRunning = false,
                progress = null,
                result = result,
                stealthScore = score,
                stealthLabel = label,
                recommendations = recommendations,
                autoTuneFixes = fixes,
                reportText = reportText,
            )

        private fun AppSettings.toDetectionRunnerConfig(packageName: String): DetectionRunnerConfig =
            DetectionRunnerConfig(
                ownProxyPort = proxyPort.takeIf { it > 0 },
                ownPackageName = packageName,
                encryptedDnsEnabled = dnsMode == "encrypted",
                webRtcProtectionEnabled = webrtcProtectionEnabled,
                tlsFingerprintProfile = tlsFingerprintProfile.ifEmpty { "chrome_stable" },
            )

        fun applyAllFixes() {
            viewModelScope.launch {
                appSettingsRepository.update {
                    for (fix in _uiState.value.autoTuneFixes) {
                        when (fix.id) {
                            "tls_fingerprint" -> {
                                tlsFingerprintProfile = "chrome_stable"
                            }

                            "entropy_padding" -> {
                                entropyMode = entropyModeBalanced
                                entropyPaddingTargetPermil = entropyPaddingTargetPermilValue
                                shannonEntropyTargetPermil = shannonEntropyTargetPermilValue
                            }

                            "encrypted_dns" -> {
                                dnsMode = "encrypted"
                            }

                            "full_tunnel" -> {
                                fullTunnelMode = true
                            }

                            "strategy_evolution" -> {
                                strategyEvolution = true
                                evolutionEpsilon = strategyEvolutionRecommendedEpsilon
                            }
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(autoTuneFixes = emptyList())
            }
        }

        fun stopCheck() {
            runJob?.cancel()
            runJob = null
            _uiState.value = _uiState.value.copy(isRunning = false, progress = null)
        }

        private fun saveToHistory(
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
            val editor = prefs.edit()
            for (permission in requiredPermissions()) {
                editor.putBoolean("perm_requested_$permission", true)
            }
            editor.apply()
        }

        companion object {
            private const val PREF_ONBOARDING_SHOWN = "detection_onboarding_shown"

            fun requiredPermissions(): Array<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
                }
        }
    }
