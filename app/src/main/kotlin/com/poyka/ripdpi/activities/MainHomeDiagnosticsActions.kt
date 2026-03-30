package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private const val HomeAutomaticAuditProfileId = "automatic-audit"
private const val HomeVerificationProfileId = "default"

internal data class HomeDiagnosticsRuntimeState(
    val activeAuditSessionId: String? = null,
    val activeAuditProgress: String? = null,
    val latestAuditOutcome: DiagnosticsHomeAuditOutcome? = null,
    val analysisSheetVisible: Boolean = false,
    val activeVerificationSessionId: String? = null,
    val waitingForVerifiedVpnStart: Boolean = false,
    val verificationProgress: String? = null,
    val verificationSheet: DiagnosticsHomeVerificationOutcome? = null,
    val currentFingerprintHash: String? = null,
    val externalScanActive: Boolean = false,
    val externalScanMessage: String? = null,
)

internal class MainHomeDiagnosticsActions(
    private val mutations: MainMutationRunner,
    private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
    private val diagnosticsScanController: DiagnosticsScanController,
    private val diagnosticsShareService: DiagnosticsShareService,
    private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
    private val serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
    private val runtimeState: MutableStateFlow<ConnectionRuntimeState>,
    private val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val homeDiagnosticsState: MutableStateFlow<HomeDiagnosticsRuntimeState>,
    private val stringResolver: StringResolver,
    private val requestVpnStart: () -> Unit,
) {
    fun initialize() {
        mutations.launch {
            refreshFingerprint()
        }
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                val progressSessionId = progress?.sessionId
                val progressMessage = progress?.message
                homeDiagnosticsState.update { current ->
                    current.copy(
                        activeAuditProgress =
                            if (progressSessionId == current.activeAuditSessionId) {
                                progressMessage
                            } else {
                                current.activeAuditProgress
                            },
                        verificationProgress =
                            when {
                                progressSessionId == current.activeVerificationSessionId -> {
                                    progressMessage
                                }

                                current.waitingForVerifiedVpnStart -> {
                                    current.verificationProgress
                                }

                                else -> {
                                    null
                                }
                            },
                        externalScanActive =
                            progress != null &&
                                progressSessionId != current.activeAuditSessionId &&
                                progressSessionId != current.activeVerificationSessionId,
                        externalScanMessage =
                            if (
                                progress != null &&
                                progressSessionId != current.activeAuditSessionId &&
                                progressSessionId != current.activeVerificationSessionId
                            ) {
                                progressMessage
                            } else {
                                null
                            },
                    )
                }
                if (progress == null) {
                    homeDiagnosticsState.update { current ->
                        current.copy(
                            activeAuditProgress =
                                if (current.activeAuditSessionId == null) {
                                    null
                                } else {
                                    current.activeAuditProgress
                                },
                            verificationProgress =
                                if (current.activeVerificationSessionId == null &&
                                    !current.waitingForVerifiedVpnStart
                                ) {
                                    null
                                } else {
                                    current.verificationProgress
                                },
                        )
                    }
                }
            }
        }
        mutations.launch {
            diagnosticsTimelineSource.sessions.collect { sessions ->
                val current = homeDiagnosticsState.value
                current.activeAuditSessionId?.let { sessionId ->
                    val session = sessions.firstOrNull { it.id == sessionId && it.status != "running" }
                    if (session != null) {
                        val outcome =
                            runCatching { diagnosticsHomeWorkflowService.finalizeHomeAudit(sessionId) }
                                .getOrElse {
                                    DiagnosticsHomeAuditOutcome(
                                        sessionId = sessionId,
                                        actionable = false,
                                        fingerprintHash = current.currentFingerprintHash,
                                        headline = "Analysis finished without a reusable result",
                                        summary = session.summary,
                                    )
                                }
                        homeDiagnosticsState.update {
                            it.copy(
                                activeAuditSessionId = null,
                                activeAuditProgress = null,
                                latestAuditOutcome = outcome,
                                analysisSheetVisible = true,
                            )
                        }
                        refreshFingerprint(outcome.fingerprintHash)
                    }
                }
                current.activeVerificationSessionId?.let { sessionId ->
                    val session = sessions.firstOrNull { it.id == sessionId && it.status != "running" }
                    if (session != null) {
                        val outcome =
                            runCatching { diagnosticsHomeWorkflowService.summarizeVerification(sessionId) }
                                .getOrElse {
                                    DiagnosticsHomeVerificationOutcome(
                                        sessionId = sessionId,
                                        success = false,
                                        headline = "VPN verification was incomplete",
                                        summary = session.summary,
                                    )
                                }
                        homeDiagnosticsState.update {
                            it.copy(
                                activeVerificationSessionId = null,
                                waitingForVerifiedVpnStart = false,
                                verificationProgress = null,
                                verificationSheet = outcome,
                            )
                        }
                        refreshFingerprint()
                    }
                }
            }
        }
        mutations.launch {
            runtimeState
                .map { it.connectionState }
                .distinctUntilChanged()
                .collect { state ->
                    when {
                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                            state == ConnectionState.Connected &&
                            serviceStateStore.status.value.first == AppStatus.Running &&
                            serviceStateStore.status.value.second == Mode.VPN
                        -> {
                            startVerificationScan()
                        }

                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                            state == ConnectionState.Error
                        -> {
                            homeDiagnosticsState.update {
                                it.copy(
                                    waitingForVerifiedVpnStart = false,
                                    verificationProgress = null,
                                    verificationSheet =
                                        DiagnosticsHomeVerificationOutcome(
                                            sessionId = "",
                                            success = false,
                                            headline = "VPN failed to start",
                                            summary =
                                                mutations.currentUiState().errorMessage
                                                    ?: stringResolver.getString(R.string.connection_timed_out),
                                        ),
                                )
                            }
                        }
                    }
                }
        }
        mutations.launch {
            permissionState
                .map { it.issue?.kind }
                .distinctUntilChanged()
                .collect { issueKind ->
                    if (
                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                        issueKind == PermissionKind.VpnConsent
                    ) {
                        homeDiagnosticsState.update {
                            it.copy(
                                waitingForVerifiedVpnStart = false,
                                verificationProgress = null,
                                verificationSheet =
                                    DiagnosticsHomeVerificationOutcome(
                                        sessionId = "",
                                        success = false,
                                        headline = "VPN permission is required",
                                        summary =
                                            stringResolver.getString(
                                                R.string.home_diagnostics_permission_required,
                                            ),
                                    ),
                            )
                        }
                    }
                }
        }
        mutations.launch {
            serviceStateStore.status.collect {
                refreshFingerprint()
            }
        }
    }

    fun runFullAnalysis() {
        mutations.launch {
            homeDiagnosticsState.update {
                it.copy(
                    latestAuditOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                )
            }
            runCatching {
                diagnosticsScanController.startScan(
                    pathMode = ScanPathMode.RAW_PATH,
                    selectedProfileId = HomeAutomaticAuditProfileId,
                )
            }.onSuccess { result ->
                when (result) {
                    is DiagnosticsManualScanStartResult.Started -> {
                        homeDiagnosticsState.update {
                            it.copy(
                                activeAuditSessionId = result.sessionId,
                                activeAuditProgress =
                                    stringResolver.getString(
                                        R.string.home_diagnostics_analysis_running,
                                    ),
                                latestAuditOutcome = null,
                                analysisSheetVisible = false,
                            )
                        }
                    }

                    is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                        mutations.emit(
                            MainEffect.ShowError(
                                stringResolver.getString(R.string.diagnostics_error_hidden_probe_running),
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                val message =
                    when (error) {
                        is DiagnosticsScanStartRejectedException -> {
                            stringResolver.getString(R.string.diagnostics_error_start_failed)
                        }

                        else -> {
                            stringResolver.getString(R.string.diagnostics_error_start_failed)
                        }
                    }
                mutations.emit(MainEffect.ShowError(message))
            }
        }
    }

    fun startVerifiedVpn() {
        mutations.launch {
            val latestOutcome = homeDiagnosticsState.value.latestAuditOutcome ?: return@launch
            val currentFingerprint = diagnosticsHomeWorkflowService.currentFingerprintHash()
            homeDiagnosticsState.update { it.copy(currentFingerprintHash = currentFingerprint) }
            if (
                latestOutcome.fingerprintHash != null &&
                currentFingerprint != null &&
                latestOutcome.fingerprintHash != currentFingerprint
            ) {
                mutations.emit(MainEffect.ShowError(stringResolver.getString(R.string.home_diagnostics_run_again)))
                return@launch
            }
            homeDiagnosticsState.update {
                it.copy(
                    waitingForVerifiedVpnStart = true,
                    verificationProgress = stringResolver.getString(R.string.home_diagnostics_vpn_starting),
                    verificationSheet = null,
                )
            }
            requestVpnStart()
        }
    }

    fun shareLatestAuditArchive() {
        mutations.launch {
            val sessionId = homeDiagnosticsState.value.latestAuditOutcome?.sessionId ?: return@launch
            runCatching {
                diagnosticsShareService.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = sessionId,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = System.currentTimeMillis(),
                    ),
                )
            }.onSuccess { archive ->
                mutations.emit(
                    MainEffect.ShareDiagnosticsArchive(
                        absolutePath = archive.absolutePath,
                        fileName = archive.fileName,
                    ),
                )
            }.onFailure {
                mutations.emit(
                    MainEffect.ShowError(
                        stringResolver.getString(R.string.home_diagnostics_share_failed),
                    ),
                )
            }
        }
    }

    fun dismissAnalysisSheet() {
        homeDiagnosticsState.update { it.copy(analysisSheetVisible = false) }
    }

    fun dismissVerificationSheet() {
        homeDiagnosticsState.update { it.copy(verificationSheet = null) }
    }

    private suspend fun startVerificationScan() {
        if (homeDiagnosticsState.value.activeVerificationSessionId != null) {
            return
        }
        runCatching {
            diagnosticsScanController.startScan(
                pathMode = ScanPathMode.IN_PATH,
                selectedProfileId = HomeVerificationProfileId,
            )
        }.onSuccess { result ->
            when (result) {
                is DiagnosticsManualScanStartResult.Started -> {
                    homeDiagnosticsState.update {
                        it.copy(
                            waitingForVerifiedVpnStart = false,
                            activeVerificationSessionId = result.sessionId,
                            verificationProgress = stringResolver.getString(R.string.home_diagnostics_verifying),
                        )
                    }
                }

                is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                    homeDiagnosticsState.update {
                        it.copy(
                            waitingForVerifiedVpnStart = false,
                            verificationProgress = null,
                            verificationSheet =
                                DiagnosticsHomeVerificationOutcome(
                                    sessionId = "",
                                    success = false,
                                    headline = "VPN verification is busy",
                                    summary = stringResolver.getString(R.string.diagnostics_error_hidden_probe_running),
                                ),
                        )
                    }
                }
            }
        }.onFailure {
            homeDiagnosticsState.update {
                it.copy(
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                    verificationSheet =
                        DiagnosticsHomeVerificationOutcome(
                            sessionId = "",
                            success = false,
                            headline = "VPN verification could not start",
                            summary = stringResolver.getString(R.string.home_diagnostics_verification_failed),
                        ),
                )
            }
        }
    }

    private suspend fun refreshFingerprint(fallback: String? = null) {
        val fingerprint = fallback ?: diagnosticsHomeWorkflowService.currentFingerprintHash()
        homeDiagnosticsState.update { it.copy(currentFingerprintHash = fingerprint) }
    }
}

internal fun buildHomeDiagnosticsUiState(
    settings: AppSettings,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    permissionSummary: PermissionSummaryUiState,
    runtime: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
): HomeDiagnosticsUiState {
    val fingerprintMismatch =
        runtime.latestAuditOutcome?.fingerprintHash != null &&
            runtime.currentFingerprintHash != null &&
            runtime.latestAuditOutcome.fingerprintHash != runtime.currentFingerprintHash
    val analysisBusy = runtime.activeAuditSessionId != null
    val verificationBusy = runtime.waitingForVerifiedVpnStart || runtime.activeVerificationSessionId != null
    val analysisEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            !settings.enableCmdSettings &&
            permissionSummary.snapshot.vpnConsent == PermissionStatus.Granted
    val analysisSupportingText =
        when {
            analysisBusy -> {
                runtime.activeAuditProgress
                    ?: stringResolver.getString(R.string.home_diagnostics_analysis_running)
            }

            verificationBusy -> {
                stringResolver.getString(R.string.home_diagnostics_busy_verifying)
            }

            runtime.externalScanActive -> {
                runtime.externalScanMessage
                    ?: stringResolver.getString(R.string.home_diagnostics_busy_other_scan)
            }

            settings.enableCmdSettings -> {
                stringResolver.getString(R.string.home_diagnostics_command_line_blocked)
            }

            permissionSummary.snapshot.vpnConsent != PermissionStatus.Granted -> {
                stringResolver.getString(R.string.home_diagnostics_permission_required)
            }

            else -> {
                stringResolver.getString(R.string.home_diagnostics_analysis_body)
            }
        }
    val verificationEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            appStatus == AppStatus.Halted &&
            connectionState != ConnectionState.Connecting &&
            runtime.latestAuditOutcome?.actionable == true &&
            !fingerprintMismatch
    val verificationSupportingText =
        when {
            verificationBusy -> {
                runtime.verificationProgress
                    ?: stringResolver.getString(R.string.home_diagnostics_verifying)
            }

            analysisBusy -> {
                stringResolver.getString(R.string.home_diagnostics_finish_analysis_first)
            }

            runtime.externalScanActive -> {
                runtime.externalScanMessage
                    ?: stringResolver.getString(R.string.home_diagnostics_busy_other_scan)
            }

            runtime.latestAuditOutcome == null -> {
                stringResolver.getString(R.string.home_diagnostics_run_analysis_first)
            }

            runtime.latestAuditOutcome.actionable.not() -> {
                stringResolver.getString(R.string.home_diagnostics_no_actionable_result)
            }

            fingerprintMismatch -> {
                stringResolver.getString(R.string.home_diagnostics_run_again)
            }

            appStatus == AppStatus.Running || connectionState == ConnectionState.Connected -> {
                stringResolver.getString(R.string.home_diagnostics_disconnect_first)
            }

            else -> {
                stringResolver.getString(R.string.home_diagnostics_verified_vpn_body)
            }
        }

    return HomeDiagnosticsUiState(
        analysisAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_run_analysis),
                supportingText = analysisSupportingText,
                enabled = analysisEnabled,
                busy = analysisBusy,
            ),
        verifiedVpnAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_start_verified_vpn),
                supportingText = verificationSupportingText,
                enabled = verificationEnabled,
                busy = verificationBusy,
            ),
        latestAudit =
            runtime.latestAuditOutcome?.let { outcome ->
                HomeDiagnosticsLatestAuditUiState(
                    headline = outcome.headline,
                    summary = outcome.summary,
                    recommendationSummary = outcome.recommendationSummary,
                    stale = fingerprintMismatch,
                    actionable = outcome.actionable && !fingerprintMismatch,
                )
            },
        analysisSheet =
            runtime.latestAuditOutcome
                ?.takeIf { runtime.analysisSheetVisible }
                ?.let { outcome ->
                    HomeDiagnosticsAnalysisSheetUiState(
                        sessionId = outcome.sessionId,
                        headline = outcome.headline,
                        summary = outcome.summary,
                        confidenceSummary = outcome.confidenceSummary,
                        coverageSummary = outcome.coverageSummary,
                        recommendationSummary = outcome.recommendationSummary,
                        appliedSettings = outcome.appliedSettings,
                    )
                },
        verificationSheet =
            runtime.verificationSheet?.let { outcome ->
                HomeDiagnosticsVerificationSheetUiState(
                    sessionId = outcome.sessionId,
                    success = outcome.success,
                    headline = outcome.headline,
                    summary = outcome.summary,
                    detail = outcome.detail,
                )
            },
    )
}
