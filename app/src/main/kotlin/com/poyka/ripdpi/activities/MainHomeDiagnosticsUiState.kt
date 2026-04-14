package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeDetectionVerdict
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun buildHomeDiagnosticsUiState(
    settings: AppSettings,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    runtime: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
): HomeDiagnosticsUiState {
    val fingerprintMismatch =
        runtime.latestCompositeOutcome?.fingerprintHash != null &&
            runtime.currentFingerprintHash != null &&
            runtime.latestCompositeOutcome.fingerprintHash != runtime.currentFingerprintHash
    val analysisBusy = runtime.activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.RUNNING
    val verificationBusy = runtime.waitingForVerifiedVpnStart || runtime.activeVerificationSessionId != null
    val analysisEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            !settings.enableCmdSettings
    val analysisSupportingText =
        when {
            analysisBusy -> {
                val progress = runtime.activeRunProgress
                val activeStageIndex = progress.activeStageIndex
                val stageLabel = progress.stages.getOrNull(activeStageIndex ?: -1)?.stageLabel
                val stagePrefix =
                    if (activeStageIndex != null) {
                        "Stage ${activeStageIndex + 1} of ${progress.stages.size}"
                    } else {
                        null
                    }
                listOfNotNull(stagePrefix, runtime.activeRunStageProgress ?: stageLabel)
                    .joinToString(" · ")
                    .ifBlank { stringResolver.getString(R.string.home_diagnostics_analysis_running) }
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
    val verificationEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            appStatus == AppStatus.Halted &&
            connectionState != ConnectionState.Connecting &&
            runtime.latestCompositeOutcome?.actionable == true &&
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

            runtime.latestCompositeOutcome == null -> {
                stringResolver.getString(R.string.home_diagnostics_run_analysis_first)
            }

            runtime.latestCompositeOutcome.actionable.not() -> {
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

    val quickScanBusy = analysisBusy && runtime.quickScanActive
    val pcapToggleVisible = settings.rootModeEnabled
    return HomeDiagnosticsUiState(
        pcapRecordingRequested = runtime.pcapRecordingRequested,
        pcapToggleVisible = pcapToggleVisible,
        analysisAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_run_analysis),
                supportingText = analysisSupportingText,
                enabled = analysisEnabled,
                busy = analysisBusy,
            ),
        quickScanBusy = quickScanBusy,
        verifiedVpnAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_start_verified_vpn),
                supportingText = verificationSupportingText,
                enabled = verificationEnabled,
                busy = verificationBusy,
            ),
        latestAudit =
            runtime.latestCompositeOutcome?.let { outcome ->
                HomeDiagnosticsLatestAuditUiState(
                    headline = outcome.headline,
                    summary = outcome.summary,
                    recommendationSummary = outcome.recommendationSummary,
                    completedStageCount = outcome.completedStageCount,
                    failedStageCount = outcome.failedStageCount,
                    totalStageCount = outcome.stageSummaries.size,
                    stale = fingerprintMismatch,
                    actionable = outcome.actionable && !fingerprintMismatch,
                )
            },
        analysisProgress =
            runtime.activeRunProgress?.takeIf { analysisBusy }?.let { progress ->
                AnalysisProgressUiState(
                    stages =
                        progress.stages.mapIndexed { stageIndex, stage ->
                            val stageStatus =
                                when (stage.status) {
                                    DiagnosticsHomeCompositeStageStatus.PENDING -> AnalysisStageStatus.PENDING

                                    DiagnosticsHomeCompositeStageStatus.RUNNING -> AnalysisStageStatus.RUNNING

                                    DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                    DiagnosticsHomeCompositeStageStatus.SKIPPED,
                                    -> AnalysisStageStatus.COMPLETED

                                    DiagnosticsHomeCompositeStageStatus.FAILED,
                                    DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                    -> AnalysisStageStatus.FAILED
                                }
                            AnalysisStageUiState(
                                status = stageStatus,
                                progress =
                                    if (stageIndex == progress.activeStageIndex) {
                                        runtime.activeStageStepProgress
                                    } else {
                                        when (stageStatus) {
                                            AnalysisStageStatus.COMPLETED, AnalysisStageStatus.FAILED -> 1f
                                            else -> 0f
                                        }
                                    },
                            )
                        },
                    activeStageIndex = progress.activeStageIndex,
                )
            },
        analysisSheet =
            runtime.latestCompositeOutcome
                ?.takeIf { runtime.analysisSheetVisible }
                ?.let { outcome ->
                    HomeDiagnosticsAnalysisSheetUiState(
                        runId = outcome.runId,
                        headline = outcome.headline,
                        summary = outcome.summary,
                        confidenceSummary = outcome.confidenceSummary,
                        coverageSummary = outcome.coverageSummary,
                        recommendationSummary = outcome.recommendationSummary,
                        appliedSettings = outcome.appliedSettings,
                        capabilityEvidence = outcome.capabilityEvidence.map(::toCapabilityEvidenceUiModel),
                        stageSummaries =
                            outcome.stageSummaries.map { stage ->
                                HomeDiagnosticsStageUiState(
                                    label = stage.stageLabel,
                                    headline = stage.headline,
                                    summary = stage.summary,
                                    failed =
                                        stage.status == DiagnosticsHomeCompositeStageStatus.FAILED,
                                    skipped =
                                        stage.status == DiagnosticsHomeCompositeStageStatus.SKIPPED ||
                                            stage.status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                    recommendationContributor = stage.recommendationContributor,
                                )
                            },
                        completedStageCount = outcome.completedStageCount,
                        failedStageCount = outcome.failedStageCount,
                        shareBusy = runtime.shareBusy,
                        detectionVerdict =
                            outcome.detectionVerdict?.let { verdict ->
                                stringResolver.getString(
                                    when (verdict) {
                                        DiagnosticsHomeDetectionVerdict.DETECTED -> {
                                            R.string.home_diagnostics_detection_detected
                                        }

                                        DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> {
                                            R.string.home_diagnostics_detection_needs_review
                                        }

                                        DiagnosticsHomeDetectionVerdict.NOT_DETECTED -> {
                                            R.string.home_diagnostics_detection_not_detected
                                        }
                                    },
                                )
                            },
                        detectionFindings = outcome.detectionFindings,
                        installedVpnDetectorCount = outcome.installedVpnDetectorCount,
                        installedVpnDetectorTopApps = outcome.installedVpnDetectorTopApps,
                        pcapRecordingRequested = outcome.pcapRecordingRequested,
                        actionableHeadline = outcome.actionableHeadline,
                        actionableNextSteps = outcome.actionableNextSteps,
                        networkCharacterRows = buildNetworkCharacterRows(outcome.networkCharacter, stringResolver),
                        networkCharacterNotes = outcome.networkCharacter?.notes.orEmpty(),
                        strategyEffectivenessRows =
                            outcome.strategyEffectiveness.map { entry ->
                                HomeAnalysisLabeledRow(
                                    label = entry.label,
                                    value =
                                        stringResolver.getString(
                                            R.string.home_diagnostics_effectiveness_row_value,
                                            entry.successCount,
                                            entry.failureCount,
                                        ),
                                )
                            },
                        routingSanitySummary =
                            outcome.routingSanity?.let { sanity ->
                                stringResolver.getString(
                                    R.string.home_diagnostics_routing_sanity_summary,
                                    sanity.confirmedDetectorCount,
                                    sanity.totalConfiguredApps,
                                )
                            },
                        routingSanityFindings =
                            outcome.routingSanity
                                ?.findings
                                ?.map { finding ->
                                    HomeAnalysisLabeledRow(
                                        label = finding.packageName,
                                        value = "${finding.severity}: ${finding.description}",
                                    )
                                }.orEmpty(),
                        regressionDeltaSummary =
                            outcome.regressionDelta?.let { delta ->
                                stringResolver.getString(
                                    R.string.home_diagnostics_regression_summary,
                                    delta.newlyFailedStageKeys.size,
                                    delta.newlyRecoveredStageKeys.size,
                                    delta.unchangedStageCount,
                                )
                            },
                        regressionDeltaFailures = outcome.regressionDelta?.newlyFailedStageKeys.orEmpty(),
                        regressionDeltaRecoveries = outcome.regressionDelta?.newlyRecoveredStageKeys.orEmpty(),
                        bufferbloatSummary =
                            outcome.bufferbloat?.let { result ->
                                val gradeLabel = result.grade.name
                                val idle = result.idleRttMs?.let { "${it}ms" } ?: "—"
                                val loaded = result.loadedRttMs?.let { "${it}ms" } ?: "—"
                                stringResolver.getString(
                                    R.string.home_diagnostics_bufferbloat_summary,
                                    gradeLabel,
                                    idle,
                                    loaded,
                                )
                            },
                        dnsCharacterizationSummary =
                            outcome.dnsCharacterization?.let { dns ->
                                stringResolver.getString(
                                    when (dns.resolverClass) {
                                        HomeDnsResolverClass.SYSTEM_RESOLVER_OK -> {
                                            R.string.home_diagnostics_dns_resolver_ok
                                        }

                                        HomeDnsResolverClass.DOH_PREFERRED -> {
                                            R.string.home_diagnostics_dns_resolver_doh_preferred
                                        }

                                        HomeDnsResolverClass.POSSIBLE_TRANSPARENT_PROXY -> {
                                            R.string.home_diagnostics_dns_resolver_transparent_proxy
                                        }

                                        HomeDnsResolverClass.POSSIBLE_POISONING -> {
                                            R.string.home_diagnostics_dns_resolver_poisoning
                                        }

                                        HomeDnsResolverClass.DOH_UNREACHABLE -> {
                                            R.string.home_diagnostics_dns_resolver_doh_unreachable
                                        }

                                        HomeDnsResolverClass.UNKNOWN -> {
                                            R.string.home_diagnostics_dns_resolver_unknown
                                        }
                                    },
                                )
                            },
                        dnsCharacterizationNotes =
                            outcome.dnsCharacterization
                                ?.let { dns ->
                                    buildList {
                                        addAll(dns.notes)
                                        if (dns.poisonedHosts.isNotEmpty()) {
                                            add("Poisoned hosts: ${dns.poisonedHosts.joinToString(", ")}")
                                        }
                                    }
                                }.orEmpty(),
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
            },
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
