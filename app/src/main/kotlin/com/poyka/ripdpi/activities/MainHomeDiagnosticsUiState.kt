package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeDetectionVerdict
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.diagnostics.HomeStrategyEffectivenessEntry
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ownedStackBrowserLaunchUrl
import kotlinx.collections.immutable.toImmutableList

internal fun buildHomeDiagnosticsUiState(
    settings: AppSettings,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    runtime: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
): HomeDiagnosticsUiState {
    val availability = resolveHomeDiagnosticsAvailability(settings, appStatus, connectionState, runtime, stringResolver)
    return HomeDiagnosticsUiState(
        pcapRecordingRequested = runtime.pcapRecordingRequested,
        pcapToggleVisible = settings.rootModeEnabled,
        analysisAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_run_analysis),
                supportingText = availability.analysisSupportingText,
                enabled = availability.analysisEnabled,
                busy = availability.analysisBusy,
            ),
        quickScanBusy = availability.analysisBusy && runtime.quickScanActive,
        verifiedVpnAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_start_verified_vpn),
                supportingText = availability.verificationSupportingText,
                enabled = availability.verificationEnabled,
                busy = availability.verificationBusy,
            ),
        latestAudit = availability.latestAudit,
        remediationLadder = availability.remediationLadder,
        analysisProgress = buildAnalysisProgress(runtime, availability.analysisBusy),
        analysisRunStatus = runtime.analysisRunUiStatus(),
        analysisSheet =
            runtime.latestCompositeOutcome
                ?.takeIf { runtime.analysisSheetVisible }
                ?.toAnalysisSheetUiState(runtime, availability.remediationLadder, stringResolver),
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

private fun HomeDiagnosticsRuntimeState.analysisRunUiStatus(): HomeDiagnosticsRunUiStatus =
    when {
        analysisStartFailed -> HomeDiagnosticsRunUiStatus.FAILED
        analysisStarting -> HomeDiagnosticsRunUiStatus.STARTING
        activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.RUNNING -> HomeDiagnosticsRunUiStatus.RUNNING
        activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.COMPLETED -> HomeDiagnosticsRunUiStatus.COMPLETED
        activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.CANCELLED -> HomeDiagnosticsRunUiStatus.CANCELLED
        activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.FAILED -> HomeDiagnosticsRunUiStatus.FAILED
        else -> HomeDiagnosticsRunUiStatus.IDLE
    }

private data class HomeDiagnosticsAvailability(
    val fingerprintMismatch: Boolean,
    val analysisBusy: Boolean,
    val verificationBusy: Boolean,
    val analysisEnabled: Boolean,
    val analysisSupportingText: String,
    val verificationEnabled: Boolean,
    val verificationSupportingText: String,
    val latestAudit: HomeDiagnosticsLatestAuditUiState?,
    val remediationLadder: DiagnosticsRemediationLadderUiModel?,
)

private fun resolveHomeDiagnosticsAvailability(
    settings: AppSettings,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    runtime: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
): HomeDiagnosticsAvailability {
    val fingerprintMismatch = runtime.hasCompositeFingerprintMismatch()
    val analysisBusy =
        runtime.analysisStarting || runtime.activeRunId != null ||
            runtime.activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.RUNNING
    val verificationBusy = runtime.waitingForVerifiedVpnStart || runtime.activeVerificationSessionId != null
    val latestAudit =
        runtime.latestCompositeOutcome?.toLatestAuditUiState(fingerprintMismatch)
            ?: runtime.latestManualDiagnosticSession?.toLatestManualScanUiState()
    val busy = analysisBusy || verificationBusy || runtime.externalScanActive
    return HomeDiagnosticsAvailability(
        fingerprintMismatch = fingerprintMismatch,
        analysisBusy = analysisBusy,
        verificationBusy = verificationBusy,
        analysisEnabled = !busy && !settings.enableCmdSettings,
        analysisSupportingText =
            resolveAnalysisSupportingText(
                settings,
                runtime,
                analysisBusy,
                verificationBusy,
                stringResolver,
            ),
        verificationEnabled =
            !busy && appStatus == AppStatus.Halted && connectionState != ConnectionState.Connecting &&
                runtime.latestCompositeOutcome?.actionable == true && !fingerprintMismatch,
        verificationSupportingText =
            resolveVerificationSupportingText(
                appStatus,
                connectionState,
                runtime,
                analysisBusy,
                verificationBusy,
                fingerprintMismatch,
                stringResolver,
            ),
        latestAudit = latestAudit,
        remediationLadder =
            if (busy) {
                null
            } else {
                stringResolver.buildHomeRemediationLadder(
                    settings.enableCmdSettings,
                    fingerprintMismatch,
                    latestAudit,
                )
            },
    )
}

private fun HomeDiagnosticsRuntimeState.hasCompositeFingerprintMismatch(): Boolean =
    latestCompositeOutcome?.fingerprintHash != null && currentFingerprintHash != null &&
        latestCompositeOutcome.fingerprintHash != currentFingerprintHash

private fun resolveAnalysisSupportingText(
    settings: AppSettings,
    runtime: HomeDiagnosticsRuntimeState,
    analysisBusy: Boolean,
    verificationBusy: Boolean,
    stringResolver: StringResolver,
): String =
    when {
        analysisBusy -> {
            runtime.analysisProgressLabel(stringResolver)
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

        else -> {
            stringResolver.getString(R.string.home_diagnostics_analysis_body)
        }
    }

private fun HomeDiagnosticsRuntimeState.analysisProgressLabel(stringResolver: StringResolver): String {
    if (analysisStarting) {
        return stringResolver.getString(R.string.home_diagnostics_analysis_starting)
    }
    val progress = activeRunProgress
    val activeStageIndex = progress?.activeStageIndex
    val stageLabel = progress?.stages?.getOrNull(activeStageIndex ?: -1)?.stageLabel
    val stagePrefix =
        activeStageIndex?.let {
            stringResolver.getString(
                R.string.home_diagnostics_stage_counter,
                it + 1,
                progress.stages.size,
            )
        }
    return listOfNotNull(stagePrefix, activeRunStageProgress ?: stageLabel)
        .joinToString(" · ")
        .ifBlank { stringResolver.getString(R.string.home_diagnostics_analysis_running) }
}

private fun resolveVerificationSupportingText(
    appStatus: AppStatus,
    connectionState: ConnectionState,
    runtime: HomeDiagnosticsRuntimeState,
    analysisBusy: Boolean,
    verificationBusy: Boolean,
    fingerprintMismatch: Boolean,
    stringResolver: StringResolver,
): String =
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

        runtime.latestCompositeOutcome == null -> {
            stringResolver.getString(R.string.home_diagnostics_run_analysis_first)
        }

        !runtime.latestCompositeOutcome.actionable -> {
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

private fun buildAnalysisProgress(
    runtime: HomeDiagnosticsRuntimeState,
    analysisBusy: Boolean,
): AnalysisProgressUiState? =
    runtime.activeRunProgress?.takeIf { analysisBusy }?.let { progress ->
        AnalysisProgressUiState(
            stages =
                progress.stages
                    .mapIndexed { index, stage ->
                        val status = stage.status.toAnalysisStageStatus()
                        AnalysisStageUiState(
                            status = status,
                            progress =
                                if (index ==
                                    progress.activeStageIndex
                                ) {
                                    runtime.activeStageStepProgress
                                } else {
                                    status.finishedProgress()
                                },
                        )
                    }.toImmutableList(),
            activeStageIndex = progress.activeStageIndex,
        )
    }

private fun DiagnosticsHomeCompositeStageStatus.toAnalysisStageStatus(): AnalysisStageStatus =
    when (this) {
        DiagnosticsHomeCompositeStageStatus.PENDING -> AnalysisStageStatus.PENDING

        DiagnosticsHomeCompositeStageStatus.RUNNING -> AnalysisStageStatus.RUNNING

        DiagnosticsHomeCompositeStageStatus.COMPLETED,
        DiagnosticsHomeCompositeStageStatus.SKIPPED,
        -> AnalysisStageStatus.COMPLETED

        DiagnosticsHomeCompositeStageStatus.FAILED,
        DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
        -> AnalysisStageStatus.FAILED
    }

private fun AnalysisStageStatus.finishedProgress(): Float =
    if (this == AnalysisStageStatus.COMPLETED || this == AnalysisStageStatus.FAILED) 1f else 0f

private fun DiagnosticsHomeCompositeOutcome.toAnalysisSheetUiState(
    runtime: HomeDiagnosticsRuntimeState,
    remediationLadder: DiagnosticsRemediationLadderUiModel?,
    stringResolver: StringResolver,
): HomeDiagnosticsAnalysisSheetUiState =
    HomeDiagnosticsAnalysisSheetUiState(
        runId = runId,
        headline = headline,
        summary = summary,
        confidenceSummary = confidenceSummary,
        coverageSummary = coverageSummary,
        recommendationSummary = recommendationSummary,
        appliedSettings = appliedSettings.toImmutableList(),
        capabilityEvidence = capabilityEvidence.map(::toCapabilityEvidenceUiModel).toImmutableList(),
        stageSummaries = stageSummaries.map(DiagnosticsHomeCompositeStageSummary::toUiState).toImmutableList(),
        completedStageCount = completedStageCount,
        failedStageCount = failedStageCount,
        shareBusy = runtime.shareBusy,
        detectionVerdict = detectionVerdict?.label(stringResolver),
        detectionFindings = detectionFindings.toImmutableList(),
        installedVpnDetectorCount = installedVpnDetectorCount,
        installedVpnDetectorTopApps = installedVpnDetectorTopApps.toImmutableList(),
        pcapRecordingRequested = runtime.pcapRecordingRequested,
        remediationLadder = remediationLadder,
        actionableHeadline = actionableHeadline,
        actionableNextSteps = actionableNextSteps.toImmutableList(),
        networkCharacterRows = buildNetworkCharacterRows(networkCharacter, stringResolver).toImmutableList(),
        networkCharacterNotes = networkCharacter?.notes.orEmpty().toImmutableList(),
        strategyEffectivenessRows = strategyEffectiveness.map { it.toUiState(stringResolver) }.toImmutableList(),
        routingSanitySummary =
            routingSanity?.let {
                stringResolver.getString(
                    R.string.home_diagnostics_routing_sanity_summary,
                    it.confirmedDetectorCount,
                    it.totalConfiguredApps,
                )
            },
        routingSanityFindings =
            routingSanity
                ?.findings
                ?.map {
                    HomeAnalysisLabeledRow(it.packageName, "${it.severity}: ${it.description}")
                }.orEmpty()
                .toImmutableList(),
        regressionDeltaSummary =
            regressionDelta?.let {
                stringResolver.getString(
                    R.string.home_diagnostics_regression_summary,
                    it.newlyFailedStageKeys.size,
                    it.newlyRecoveredStageKeys.size,
                    it.unchangedStageCount,
                )
            },
        regressionDeltaFailures = regressionDelta?.newlyFailedStageKeys.orEmpty().toImmutableList(),
        regressionDeltaRecoveries = regressionDelta?.newlyRecoveredStageKeys.orEmpty().toImmutableList(),
        bufferbloatSummary =
            bufferbloat?.let {
                stringResolver.getString(
                    R.string.home_diagnostics_bufferbloat_summary,
                    it.grade.name,
                    it.idleRttMs?.let { value -> "${value}ms" } ?: "—",
                    it.loadedRttMs?.let { value -> "${value}ms" } ?: "—",
                )
            },
        dnsCharacterizationSummary = dnsCharacterization?.resolverClass?.label(stringResolver),
        dnsCharacterizationNotes =
            dnsCharacterization
                ?.let { dns ->
                    buildList {
                        addAll(dns.notes)
                        if (dns.poisonedHosts.isNotEmpty()) {
                            add("Poisoned hosts: ${dns.poisonedHosts.joinToString(", ")}")
                        }
                    }
                }.orEmpty()
                .toImmutableList(),
    )

private fun DiagnosticsHomeCompositeStageSummary.toUiState() =
    HomeDiagnosticsStageUiState(
        label = stageLabel,
        headline = headline,
        summary = summary,
        failed = status == DiagnosticsHomeCompositeStageStatus.FAILED,
        skipped =
            status == DiagnosticsHomeCompositeStageStatus.SKIPPED ||
                status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
        recommendationContributor = recommendationContributor,
    )

private fun HomeStrategyEffectivenessEntry.toUiState(stringResolver: StringResolver) =
    HomeAnalysisLabeledRow(
        label = label,
        value =
            stringResolver.getString(
                R.string.home_diagnostics_effectiveness_row_value,
                successCount,
                failureCount,
            ),
    )

private fun DiagnosticsHomeDetectionVerdict.label(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            DiagnosticsHomeDetectionVerdict.DETECTED -> R.string.home_diagnostics_detection_detected
            DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> R.string.home_diagnostics_detection_needs_review
            DiagnosticsHomeDetectionVerdict.NOT_DETECTED -> R.string.home_diagnostics_detection_not_detected
        },
    )

private fun HomeDnsResolverClass.label(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            HomeDnsResolverClass.SYSTEM_RESOLVER_OK -> R.string.home_diagnostics_dns_resolver_ok
            HomeDnsResolverClass.DOH_PREFERRED -> R.string.home_diagnostics_dns_resolver_doh_preferred
            HomeDnsResolverClass.POSSIBLE_TRANSPARENT_PROXY -> R.string.home_diagnostics_dns_resolver_transparent_proxy
            HomeDnsResolverClass.POSSIBLE_POISONING -> R.string.home_diagnostics_dns_resolver_poisoning
            HomeDnsResolverClass.DOH_UNREACHABLE -> R.string.home_diagnostics_dns_resolver_doh_unreachable
            HomeDnsResolverClass.UNKNOWN -> R.string.home_diagnostics_dns_resolver_unknown
        },
    )

private fun DiagnosticsHomeCompositeOutcome.toLatestAuditUiState(fingerprintMismatch: Boolean) =
    HomeDiagnosticsLatestAuditUiState(
        headline = headline,
        summary = summary,
        recommendationSummary = recommendationSummary,
        ownedStackLaunchUrl = ownedStackBrowserLaunchUrl(directModeVerdict?.authority),
        completedStageCount = completedStageCount,
        failedStageCount = failedStageCount,
        totalStageCount = stageSummaries.size,
        stale = fingerprintMismatch,
        actionable = actionable && !fingerprintMismatch,
        directModeResult = directModeVerdict?.result,
        directModeReasonCode = directModeVerdict?.reasonCode,
        directTransportClass = directModeVerdict?.transportClass,
        transportRemediationEvidence = capabilityEvidence.toTransportRemediationEvidence(),
    )

private fun DiagnosticScanSession.toLatestManualScanUiState() =
    HomeDiagnosticsLatestAuditUiState(
        headline = summary.ifBlank { profileId },
        summary =
            listOf(profileId, pathMode)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        actionable = false,
    )

private fun toCapabilityEvidenceUiModel(
    evidence: DiagnosticsCapabilityEvidence,
): DiagnosticsCapabilityEvidenceUiModel =
    DiagnosticsCapabilityEvidenceUiModel(
        authority = evidence.authority,
        summary = evidence.summary,
        fields =
            buildList {
                addAll(evidence.details.map { DiagnosticsFieldUiModel(it.label, it.value) })
                if (evidence.source.isNotBlank()) {
                    add(DiagnosticsFieldUiModel("Source", evidence.source))
                }
            }.toImmutableList(),
    )

private fun buildNetworkCharacterRows(
    summary: HomeNetworkCharacterSummary?,
    stringResolver: StringResolver,
): List<HomeAnalysisLabeledRow> {
    summary ?: return emptyList()
    val rows = mutableListOf<HomeAnalysisLabeledRow>()
    summary.transport?.takeIf { it.isNotBlank() }?.let {
        rows += HomeAnalysisLabeledRow(stringResolver.getString(R.string.home_diagnostics_network_transport), it)
    }
    summary.operatorOrSsid?.takeIf { it.isNotBlank() }?.let {
        rows += HomeAnalysisLabeledRow(stringResolver.getString(R.string.home_diagnostics_network_operator), it)
    }
    summary.asn?.takeIf { it.isNotBlank() }?.let {
        rows += HomeAnalysisLabeledRow(stringResolver.getString(R.string.home_diagnostics_network_asn), it)
    }
    summary.publicIp?.takeIf { it.isNotBlank() }?.let {
        rows += HomeAnalysisLabeledRow(stringResolver.getString(R.string.home_diagnostics_network_public_ip), it)
    }
    summary.captivePortalDetected?.let {
        rows +=
            HomeAnalysisLabeledRow(
                stringResolver.getString(R.string.home_diagnostics_network_captive_portal),
                if (it) "yes" else "no",
            )
    }
    summary.ipv6Reachable?.let {
        rows +=
            HomeAnalysisLabeledRow(
                stringResolver.getString(R.string.home_diagnostics_network_ipv6),
                if (it) "reachable" else "unreachable",
            )
    }
    summary.mtu?.let {
        rows += HomeAnalysisLabeledRow(stringResolver.getString(R.string.home_diagnostics_network_mtu), "$it")
    }
    summary.transparentProxyDetected?.let {
        rows +=
            HomeAnalysisLabeledRow(
                stringResolver.getString(R.string.home_diagnostics_network_transparent_proxy),
                if (it) "detected" else "none",
            )
    }
    return rows
}
