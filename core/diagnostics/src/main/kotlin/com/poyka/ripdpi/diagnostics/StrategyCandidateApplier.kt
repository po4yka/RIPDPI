package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Applies a strategy that the user picked from the candidates of a strategy test. */
class StrategyCandidateApplier
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        /** The candidates of [report] that [apply] accepts and that would change the current settings. */
        fun applicableIds(report: StrategyProbeReport): Flow<Set<String>> =
            appSettingsRepository.settings
                .map { settings ->
                    (report.tcpCandidates.map { it to true } + report.quicCandidates.map { it to false })
                        .filter { (candidate, tcpLane) ->
                            DiagnosticsScanWorkflow
                                .candidateStrategySettings(candidate, tcpLane, settings)
                                .let { it != null && it != settings }
                        }.mapTo(mutableSetOf()) { (candidate, _) -> candidate.id }
                }.distinctUntilChanged()

        /** Returns false, and changes nothing, when the candidate is not in the test or did not pass. */
        suspend fun apply(
            report: StrategyProbeReport,
            candidateId: String,
        ): Boolean {
            val tcp = report.tcpCandidates.firstOrNull { it.id == candidateId }
            val candidate = tcp ?: report.quicCandidates.firstOrNull { it.id == candidateId } ?: return false
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
    }
