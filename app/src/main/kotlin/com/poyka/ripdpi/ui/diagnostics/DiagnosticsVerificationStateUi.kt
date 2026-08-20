package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ActiveStrategyPathOutcome
import com.poyka.ripdpi.diagnostics.ActiveStrategyPathUnavailableReason
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.CurrentStrategyCandidateVerdict
import com.poyka.ripdpi.platform.StringResolver

internal fun BypassApproachSummary.uiVerificationLabel(stringResolver: StringResolver): String =
    stringResolver.getString(verificationLabelRes())

internal fun BypassApproachSummary.uiEvidenceNote(stringResolver: StringResolver): String {
    val assessment = currentStrategyAssessment
    return when {
        assessment?.activePathOutcome == ActiveStrategyPathOutcome.WORKING -> {
            stringResolver.getString(R.string.diagnostics_strategy_active_service_working)
        }

        assessment?.activePathOutcome == ActiveStrategyPathOutcome.INEFFECTIVE -> {
            stringResolver.getString(R.string.diagnostics_strategy_active_service_ineffective)
        }

        assessment?.activePathUnavailableReason == ActiveStrategyPathUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED -> {
            stringResolver.getString(R.string.diagnostics_strategy_active_vpn_path_not_observed)
        }

        else -> {
            stringResolver.getString(R.string.diagnostics_strategy_active_service_unverified)
        }
    }
}

internal fun BypassApproachSummary.verificationLabelRes(): Int {
    val assessment = currentStrategyAssessment
    if (assessment != null) {
        return when (assessment.candidateVerdict) {
            CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH -> {
                R.string.diagnostics_strategy_candidate_working_test_path
            }

            CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH -> {
                R.string.diagnostics_strategy_candidate_ineffective_test_path
            }

            CurrentStrategyCandidateVerdict.INCOMPLETE -> {
                R.string.diagnostics_strategy_verdict_incomplete
            }

            CurrentStrategyCandidateVerdict.UNVERIFIED_EXECUTION -> {
                R.string.diagnostics_strategy_verdict_execution_unverified
            }
        }
    }
    return when (verificationState) {
        BypassApproachVerificationState.NOT_EVALUATED -> R.string.diagnostics_approach_state_not_evaluated
        BypassApproachVerificationState.INCOMPLETE_EVIDENCE -> R.string.diagnostics_approach_state_incomplete
        BypassApproachVerificationState.EVALUATED_NO_SUCCESS -> R.string.diagnostics_approach_state_no_success
        BypassApproachVerificationState.EVALUATED_PARTIAL_SUCCESS -> R.string.diagnostics_approach_state_partial
        BypassApproachVerificationState.CONFIRMED_WORKING -> R.string.diagnostics_approach_state_working
    }
}
