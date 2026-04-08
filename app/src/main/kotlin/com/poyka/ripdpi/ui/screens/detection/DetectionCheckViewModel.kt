package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.DetectionRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.Recommendation
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
    val recommendations: List<Recommendation> = emptyList(),
    val reportText: String? = null,
    val error: String? = null,
    val showOnboarding: Boolean = false,
    val permissionAction: DetectionPermissionPlanner.Action = DetectionPermissionPlanner.Action.NONE,
    val missingPermissions: List<String> = emptyList(),
)

@HiltViewModel
class DetectionCheckViewModel
    @Inject
    constructor(
        private val application: Application,
        private val appSettingsRepository: AppSettingsRepository,
    ) : AndroidViewModel(application) {
        private val _uiState = MutableStateFlow(DetectionCheckUiState())
        val uiState: StateFlow<DetectionCheckUiState> = _uiState.asStateFlow()

        private var runJob: Job? = null
        private val prefs by lazy {
            application.getSharedPreferences("detection_check_prefs", android.content.Context.MODE_PRIVATE)
        }

        init {
            checkInitialState()
        }

        private fun checkInitialState() {
            val onboardingShown = prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)
            if (!onboardingShown) {
                _uiState.value = _uiState.value.copy(showOnboarding = true)
            }
            refreshPermissionState()
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
                            recommendations = emptyList(),
                            reportText = null,
                            error = null,
                        )
                    try {
                        val settings = appSettingsRepository.settings.first()
                        val config =
                            DetectionRunnerConfig(
                                ownProxyPort = settings.proxyPort.takeIf { it > 0 },
                                ownPackageName = application.packageName,
                            )
                        val result =
                            DetectionRunner.run(
                                context = application,
                                config = config,
                                onProgress = { progress ->
                                    _uiState.value = _uiState.value.copy(progress = progress)
                                },
                            )
                        val recommendations = DetectionRecommendations.generate(result)
                        val reportText = DetectionReportFormatter.format(result)
                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                progress = null,
                                result = result,
                                recommendations = recommendations,
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

        fun stopCheck() {
            runJob?.cancel()
            runJob = null
            _uiState.value = _uiState.value.copy(isRunning = false, progress = null)
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
