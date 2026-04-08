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
import com.poyka.ripdpi.core.detection.community.CommunitySubmission
import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val communityEnabled: Boolean = false,
)

@HiltViewModel
class DetectionCheckViewModel
    @Inject
    constructor(
        private val application: Application,
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider,
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
            viewModelScope.launch {
                val settings = appSettingsRepository.settings.first()
                val enabled = settings.communityComparisonEnabled
                val cached = communityStore.getCachedStats()
                _uiState.value =
                    _uiState.value.copy(
                        communityEnabled = enabled,
                        communityStats = cached,
                    )
            }
        }

        private fun submitToCommunity(
            result: DetectionCheckResult,
            score: Int,
            fingerprint: String,
        ) {
            viewModelScope.launch {
                val settings = appSettingsRepository.settings.first()
                if (!settings.communityComparisonEnabled) return@launch
                val apiUrl = settings.communityApiUrl
                if (apiUrl.isBlank()) return@launch
                if (!communityStore.canSubmit(fingerprint)) return@launch

                val countryCode =
                    result.geoIp.findings
                        .firstOrNull { it.description.contains("Country:") }
                        ?.description
                        ?.substringAfter("(")
                        ?.substringBefore(")")
                        ?: "unknown"

                val ispCategory =
                    when {
                        result.geoIp.evidence.any { it.description.contains("hosting") } -> "hosting"
                        result.geoIp.evidence.any { it.description.contains("proxy") } -> "proxy"
                        else -> "residential"
                    }

                val submission =
                    CommunitySubmission(
                        fingerprintHash = fingerprint,
                        verdict = result.verdict.name,
                        stealthScore = score,
                        countryCode = countryCode,
                        ispCategory = ispCategory,
                        methodologyVersion = result.methodologyVersion,
                        checkerCount = MethodologyVersion.CHECKER_COUNT,
                        timestamp = System.currentTimeMillis(),
                    )

                val client = CommunityComparisonClient(apiUrl)
                client.submit(submission).onSuccess {
                    communityStore.markSubmitted(fingerprint)
                }
                client.fetchStats().onSuccess { stats ->
                    communityStore.cacheStats(stats)
                    _uiState.value = _uiState.value.copy(communityStats = stats)
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
                    _uiState.value =
                        _uiState.value.copy(
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
                    try {
                        val settings = appSettingsRepository.settings.first()
                        val config =
                            DetectionRunnerConfig(
                                ownProxyPort = settings.proxyPort.takeIf { it > 0 },
                                ownPackageName = application.packageName,
                                encryptedDnsEnabled = settings.dnsMode == "encrypted",
                                webRtcProtectionEnabled = settings.webrtcProtectionEnabled,
                                tlsFingerprintProfile =
                                    settings.tlsFingerprintProfile
                                        .ifEmpty { "native_default" },
                            )
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
                        val recommendations = DetectionRecommendations.generate(result)
                        val reportText = DetectionReportFormatter.format(result)
                        val fixes =
                            DetectionAutoTuner.suggestFixes(
                                result = result,
                                tlsFingerprintEnabled = settings.tlsFingerprintProfile != "native_default",
                                entropyPaddingEnabled = settings.entropyMode != 0,
                                encryptedDnsEnabled = settings.dnsMode == "encrypted",
                                fullTunnelEnabled = settings.fullTunnelMode,
                                strategyEvolutionEnabled = settings.strategyEvolution,
                            )

                        saveToHistory(result, score)
                        val fingerprint = networkFingerprintProvider.capture()?.scopeKey() ?: "unknown"
                        submitToCommunity(result, score, fingerprint)

                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                progress = null,
                                result = result,
                                stealthScore = score,
                                stealthLabel = label,
                                recommendations = recommendations,
                                autoTuneFixes = fixes,
                                reportText = reportText,
                            )
                    } catch (e: Exception) {
                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                progress = null,
                                error = e.message ?: "Unknown error",
                            )
                    }
                }
        }

        fun applyAllFixes() {
            viewModelScope.launch {
                appSettingsRepository.update {
                    for (fix in _uiState.value.autoTuneFixes) {
                        when (fix.id) {
                            "tls_fingerprint" -> {
                                tlsFingerprintProfile = "chrome_stable"
                            }

                            "entropy_padding" -> {
                                entropyMode = 3
                                entropyPaddingTargetPermil = 3400
                                shannonEntropyTargetPermil = 7920
                            }

                            "encrypted_dns" -> {
                                dnsMode = "encrypted"
                            }

                            "full_tunnel" -> {
                                fullTunnelMode = true
                            }

                            "strategy_evolution" -> {
                                strategyEvolution = true
                                evolutionEpsilon = 0.1
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
