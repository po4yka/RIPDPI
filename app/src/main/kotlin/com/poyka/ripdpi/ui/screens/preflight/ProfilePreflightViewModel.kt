package com.poyka.ripdpi.ui.screens.preflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ShareSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DefaultProfilePreflightId = "default"
private const val ProfilePreflightDeadlineMs = 75_000L
private const val ProfilePreflightMaxCandidates = 1

enum class ProfilePreflightRunState {
    Idle,
    Running,
    Passed,
    Failed,
    Error,
}

data class ProfilePreflightUiState(
    val runState: ProfilePreflightRunState = ProfilePreflightRunState.Idle,
    val activeProfileId: String = DefaultProfilePreflightId,
    val sessionId: String? = null,
    val summary: String? = null,
    val shareTitle: String? = null,
    val shareBody: String? = null,
    val errorMessage: String? = null,
) {
    val isRunning: Boolean = runState == ProfilePreflightRunState.Running
    val hasVerdict: Boolean = runState == ProfilePreflightRunState.Passed || runState == ProfilePreflightRunState.Failed
    val canShare: Boolean = !shareBody.isNullOrBlank()
}

@HiltViewModel
class ProfilePreflightViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val runner: ProfilePreflightRunner,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ProfilePreflightUiState())
        val uiState: StateFlow<ProfilePreflightUiState> = mutableUiState.asStateFlow()

        private var runJob: Job? = null

        init {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    mutableUiState.update {
                        it.copy(
                            activeProfileId =
                                settings.diagnosticsActiveProfileId.ifBlank {
                                    DefaultProfilePreflightId
                                },
                        )
                    }
                }
            }
        }

        fun runPreflight() {
            if (runJob?.isActive == true) return
            runJob =
                viewModelScope.launch {
                    val profileId =
                        appSettingsRepository.snapshot().diagnosticsActiveProfileId.ifBlank {
                            DefaultProfilePreflightId
                        }
                    mutableUiState.value =
                        ProfilePreflightUiState(
                            runState = ProfilePreflightRunState.Running,
                            activeProfileId = profileId,
                            summary = "Starting profile preflight",
                        )
                    runCatching {
                        val session = runner.run(profileId)
                        val passed = session.isPassingPreflight()
                        val shareSummary = runner.shareSummary(session.id)
                        mutableUiState.value =
                            ProfilePreflightUiState(
                                runState =
                                    if (passed) {
                                        ProfilePreflightRunState.Passed
                                    } else {
                                        ProfilePreflightRunState.Failed
                                    },
                                activeProfileId = profileId,
                                sessionId = session.id,
                                summary = session.summary.ifBlank { session.status },
                                shareTitle = shareSummary.title,
                                shareBody = shareSummary.toPreflightShareBody(passed, profileId, session),
                            )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        mutableUiState.update {
                            it.copy(
                                runState = ProfilePreflightRunState.Error,
                                errorMessage = error.userMessage(),
                                summary = "Profile preflight could not finish",
                            )
                        }
                    }
                }
        }
    }

class ProfilePreflightRunner
    @Inject
    constructor(
        private val diagnosticsScanController: DiagnosticsScanController,
        private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        private val diagnosticsShareService: DiagnosticsShareService,
    ) {
        suspend fun run(profileId: String): DiagnosticScanSession {
            val result =
                diagnosticsScanController.startScan(
                    pathMode = ScanPathMode.IN_PATH,
                    selectedProfileId = profileId,
                    scanDeadlineMs = ProfilePreflightDeadlineMs,
                    maxCandidates = ProfilePreflightMaxCandidates,
                )
            val sessionId =
                when (result) {
                    is DiagnosticsManualScanStartResult.Started -> {
                        result.sessionId
                    }

                    is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                        error("Another diagnostics probe is running")
                    }
                }
            return diagnosticsTimelineSource.sessions
                .map { sessions -> sessions.firstOrNull { it.id == sessionId && it.status != "running" } }
                .first { it != null }
                ?: error("Profile preflight session disappeared")
        }

        suspend fun shareSummary(sessionId: String): ShareSummary = diagnosticsShareService.buildShareSummary(sessionId)
    }

private fun DiagnosticScanSession.isPassingPreflight(): Boolean {
    val results = report?.results.orEmpty()
    return status.equals("completed", ignoreCase = true) &&
        (
            results.isEmpty() ||
                results.any { result ->
                    result.outcome.isPassingPreflightOutcome()
                }
        )
}

private fun String.isPassingPreflightOutcome(): Boolean {
    val outcome = lowercase()
    return outcome.contains("ok") ||
        outcome == "http_redirect" ||
        outcome == "tls_version_split" ||
        outcome == "quic_initial_response" ||
        outcome == "quic_response"
}

private fun ShareSummary.toPreflightShareBody(
    passed: Boolean,
    profileId: String,
    session: DiagnosticScanSession,
): String =
    buildString {
        appendLine("RIPDPI profile preflight")
        appendLine("verdict=${if (passed) "pass" else "fail"}")
        appendLine("profile=$profileId")
        appendLine("session=${session.id}")
        appendLine()
        append(body)
    }.trim()

private fun Throwable.userMessage(): String = localizedMessage ?: javaClass.simpleName
