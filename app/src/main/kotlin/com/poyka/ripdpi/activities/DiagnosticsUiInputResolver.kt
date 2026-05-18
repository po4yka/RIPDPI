package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsJurisdictionProfileAccess
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.resolveLegalSafetyPolicy
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel
import javax.inject.Inject

private const val MaxOverviewWarnings = 3

internal class DiagnosticsUiInputResolver
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) {
        fun resolve(
            input: DiagnosticsUiStateInput,
            eventModels: List<DiagnosticsEventUiModel>,
        ): ResolvedDiagnosticsUiInput {
            val visibleProfiles = input.visibleProfiles()
            val activeProfile =
                visibleProfiles.firstOrNull { it.id == input.selectedProfileId }
                    ?: visibleProfiles.firstOrNull()
            val latestSnapshot =
                input.snapshots
                    .firstOrNull()
                    ?.let { support.toNetworkSnapshotUiModel(it, showSensitiveDetails = false) }
            val latestContext =
                input.contexts
                    .firstOrNull { it.sessionId == null }
                    ?.context
                    ?: input.contexts.firstOrNull()?.context
            val latestCompletedSession = input.sessions.firstCompletedOrLatest()
            val latestAutomaticBackgroundSession =
                input.sessions.latestFinishedSessionForLaunchOrigin(DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND)
            val latestUserInitiatedSession =
                input.sessions.latestFinishedSessionForLaunchOrigin(DiagnosticsScanLaunchOrigin.USER_INITIATED)
            val latestProfileSession = input.sessions.latestSessionForProfile(activeProfile?.id, latestCompletedSession)
            val latestProfileReport = latestProfileSession?.report
            val latestStrategyProbeReport = latestProfileSession.toStrategyProbeReport(latestProfileReport)
            val sessionDetailWithVisibility =
                input.selectedSessionDetail?.copy(
                    sensitiveDetailsVisible = input.sensitiveSessionDetailsVisible,
                )

            return ResolvedDiagnosticsUiInput(
                activeProfile = activeProfile,
                visibleProfiles = visibleProfiles,
                omittedProfileCount = (input.profiles.size - visibleProfiles.size).coerceAtLeast(0),
                activeProfileRequest = activeProfile?.request,
                selectedProfileUi = activeProfile?.let(support::toProfileOptionUiModel),
                latestSnapshot = latestSnapshot,
                latestContext = latestContext,
                latestCompletedSession = latestCompletedSession,
                recentAutomaticProbe =
                    latestAutomaticBackgroundSession
                        ?.takeIf { automaticSession ->
                            latestUserInitiatedSession?.let { userSession ->
                                automaticSession.sortTimestamp() > userSession.sortTimestamp()
                            } != false
                        }?.let(support::toAutomaticProbeCalloutUiModel),
                latestProfileSession = latestProfileSession,
                latestReport = latestCompletedSession?.report,
                latestReportResults = latestProfileReport.probeResultRows(latestProfileSession),
                latestResolverRecommendation =
                    latestProfileReport?.resolverRecommendation?.let(support::toResolverRecommendationUiModel),
                latestStrategyProbeReport = latestStrategyProbeReport,
                currentTelemetry = input.currentTelemetry,
                health =
                    support.deriveHealth(
                        input.progress,
                        latestCompletedSession,
                        input.currentTelemetry,
                        input.nativeEvents,
                    ),
                warnings = buildWarnings(latestContext, eventModels),
                rememberedNetworkRows =
                    input.rememberedPolicies.map { policy ->
                        support.toRememberedNetworkUiModel(policy, input.activeConnectionPolicy)
                    },
                selectedSection =
                    if (input.progress != null) {
                        DiagnosticsSection.Scan
                    } else {
                        input.selectedSectionRequest
                    },
                sessionDetailWithVisibility = sessionDetailWithVisibility,
                selectedStrategyProbeCandidate =
                    resolveSelectedStrategyProbeCandidate(
                        candidate = input.selectedStrategyProbeCandidate,
                        sessionDetailWithVisibility = sessionDetailWithVisibility,
                        latestStrategyProbeReport = latestStrategyProbeReport,
                    ),
            )
        }

        private fun DiagnosticsUiStateInput.visibleProfiles() =
            profiles.filter { profile ->
                profile.request?.resolveLegalSafetyPolicy()?.access != DiagnosticsJurisdictionProfileAccess.BLOCKED
            }

        private fun DiagnosticsSessionProjection?.probeResultRows(
            latestProfileSession: DiagnosticScanSession?,
        ): List<DiagnosticsProbeResultUiModel> {
            val projectionResults = this?.results.orEmpty()
            return projectionResults.mapIndexed { index, result ->
                support.toProbeResultUiModel(
                    index = index,
                    pathMode = latestProfileSession?.pathMode?.let(support::parsePathMode) ?: ScanPathMode.RAW_PATH,
                    result = result,
                    reportResults = projectionResults,
                )
            }
        }

        private fun buildWarnings(
            latestContext: DiagnosticContextModel?,
            eventModels: List<DiagnosticsEventUiModel>,
        ): List<DiagnosticsEventUiModel> =
            (
                support.buildContextWarnings(latestContext) +
                    eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }
            ).take(MaxOverviewWarnings)

        private fun resolveSelectedStrategyProbeCandidate(
            candidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
            sessionDetailWithVisibility: DiagnosticsSessionDetailUiModel?,
            latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
        ): DiagnosticsStrategyProbeCandidateDetailUiModel? =
            candidate?.let { selected ->
                sessionDetailWithVisibility
                    ?.strategyProbeReport
                    ?.candidateDetails
                    ?.get(selected.id)
                    ?: latestStrategyProbeReport?.candidateDetails?.get(selected.id)
                    ?: selected
            }

        private fun List<DiagnosticScanSession>.firstCompletedOrLatest(): DiagnosticScanSession? =
            firstOrNull { it.report != null } ?: firstOrNull()

        private fun List<DiagnosticScanSession>.latestSessionForProfile(
            profileId: String?,
            fallbackSession: DiagnosticScanSession?,
        ): DiagnosticScanSession? =
            firstOrNull { it.profileId == profileId && it.report != null }
                ?: firstOrNull { it.profileId == profileId }
                ?: fallbackSession

        private fun List<DiagnosticScanSession>.latestFinishedSessionForLaunchOrigin(
            launchOrigin: DiagnosticsScanLaunchOrigin,
        ): DiagnosticScanSession? =
            filter { session ->
                session.launchOrigin == launchOrigin && session.finishedAt != null
            }.maxByOrNull(DiagnosticScanSession::sortTimestamp)

        private fun DiagnosticScanSession?.toStrategyProbeReport(
            latestProfileReport: DiagnosticsSessionProjection?,
        ): DiagnosticsStrategyProbeReportUiModel? =
            latestProfileReport?.strategyProbeReport?.let { report ->
                support.toStrategyProbeReportUiModel(
                    report = report,
                    reportResults = latestProfileReport.results,
                    serviceMode = this?.serviceMode,
                )
            }
    }

private fun DiagnosticScanSession.sortTimestamp(): Long = finishedAt ?: startedAt
