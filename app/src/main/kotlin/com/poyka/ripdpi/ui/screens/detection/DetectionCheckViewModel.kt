package com.poyka.ripdpi.ui.screens.detection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.DetectionRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
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
    val progressStage: String = "",
    val progressDetail: String = "",
    val result: DetectionCheckResult? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val reportText: String? = null,
    val error: String? = null,
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

        fun startCheck() {
            if (_uiState.value.isRunning) return
            runJob =
                viewModelScope.launch {
                    _uiState.value = DetectionCheckUiState(isRunning = true)
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
                                    _uiState.value =
                                        _uiState.value.copy(
                                            progressStage = progress.stage,
                                            progressDetail = progress.detail,
                                        )
                                },
                            )
                        val recommendations = DetectionRecommendations.generate(result)
                        val reportText = DetectionReportFormatter.format(result)
                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                result = result,
                                recommendations = recommendations,
                                reportText = reportText,
                            )
                    } catch (e: Exception) {
                        _uiState.value =
                            _uiState.value.copy(
                                isRunning = false,
                                error = e.message ?: "Unknown error",
                            )
                    }
                }
        }

        fun stopCheck() {
            runJob?.cancel()
            runJob = null
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }
