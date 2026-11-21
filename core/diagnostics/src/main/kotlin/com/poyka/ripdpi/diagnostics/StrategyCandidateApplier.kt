package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.isMeasuredOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Applies a strategy that the user picked from the candidates of a strategy test. */
class StrategyCandidateApplier
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        /**
         * The candidates of [session] that [apply] accepts on [currentNetworkScopeKey] and that would change the
         * current settings.
         */
        fun applicableIds(
            session: DiagnosticScanSession,
            currentNetworkScopeKey: String?,
        ): Flow<Set<String>> {
            val report = session.applicableReport(currentNetworkScopeKey) ?: return flowOf(emptySet())
            return appSettingsRepository.settings
                .map { settings ->
                    (report.tcpCandidates.map { it to true } + report.quicCandidates.map { it to false })
                        .filter { (candidate, tcpLane) ->
                            DiagnosticsScanWorkflow
                                .candidateStrategySettings(candidate, tcpLane, settings)
                                .let { it != null && it != settings }
                        }.mapTo(mutableSetOf()) { (candidate, _) -> candidate.id }
                }.distinctUntilChanged()
        }

        /**
         * Returns false, and changes nothing, when the test did not complete normally, was measured on another
         * network than [currentNetworkScopeKey], or the candidate is not in the test or did not pass.
         */
        suspend fun apply(
            session: DiagnosticScanSession,
            currentNetworkScopeKey: String?,
            candidateId: String,
        ): Boolean {
            val report = session.applicableReport(currentNetworkScopeKey)
            val tcp = report?.tcpCandidates?.firstOrNull { it.id == candidateId }
            val candidate = tcp ?: report?.quicCandidates?.firstOrNull { it.id == candidateId } ?: return false
            var applied = false
            // Validate and apply against one current value, so concurrent settings changes are preserved.
            appSettingsRepository.update {
                DiagnosticsScanWorkflow.candidateStrategySettings(candidate, tcpLane = tcp != null, build())?.let {
                    clear().mergeFrom(it)
                    applied = true
                }
            }
            return applied
        }

        // A partial test, or one from another network (or an old row that does not say which), shows nothing
        // about the strategy this network needs.
        private fun DiagnosticScanSession.applicableReport(currentNetworkScopeKey: String?): StrategyProbeReport? =
            report
                ?.strategyProbeReport
                ?.takeIf { isMeasuredOn(networkScopeKey, currentNetworkScopeKey) }
                ?.takeIf { it.completionKind == StrategyProbeCompletionKind.NORMAL }
    }
