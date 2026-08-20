package com.poyka.ripdpi.diagnostics

internal fun CurrentStrategyAssessment.isEvaluatedStrategyApproachEvidence(): Boolean {
    val candidateWasEvaluated =
        candidateVerdict == CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH ||
            candidateVerdict == CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH
    return candidateWasEvaluated && activePathOutcome != ActiveStrategyPathOutcome.UNVERIFIED
}

internal fun CurrentStrategyAssessment.isConfirmedWorkingStrategyApproach(): Boolean {
    val candidateWorked = candidateVerdict == CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH
    return candidateWorked && activePathOutcome == ActiveStrategyPathOutcome.WORKING
}
