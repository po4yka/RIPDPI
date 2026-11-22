package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import com.poyka.ripdpi.diagnostics.ConnectivityEvidence

internal fun redactConnectivityAssessment(assessment: ConnectivityAssessment?): ConnectivityAssessment? =
    assessment?.copy(
        assessmentSummary = redactDiagnosticsArchiveText(assessment.assessmentSummary),
        rawPathEvidence = assessment.rawPathEvidence.redactForArchive(),
        inPathEvidence = assessment.inPathEvidence.redactForArchive(),
        pathComparison =
            assessment.pathComparison?.let { comparison ->
                comparison.copy(
                    stages = comparison.stages.map { it.copy(target = "redacted") },
                    unmeasuredTargets = archiveTargetListProjection(comparison.unmeasuredTargets),
                    httpRecoveredTargets = archiveTargetListProjection(comparison.httpRecoveredTargets),
                )
            },
        affectedTargets = archiveTargetListProjection(assessment.affectedTargets),
        resolverAssessment =
            assessment.resolverAssessment.copy(
                mismatchTargets = archiveTargetListProjection(assessment.resolverAssessment.mismatchTargets),
                summary = redactDiagnosticsArchiveText(assessment.resolverAssessment.summary),
            ),
        serviceRuntimeAssessment =
            assessment.serviceRuntimeAssessment.copy(
                lastNativeErrorHeadline =
                    redactDiagnosticsArchiveText(assessment.serviceRuntimeAssessment.lastNativeErrorHeadline),
                summary = redactDiagnosticsArchiveText(assessment.serviceRuntimeAssessment.summary),
            ),
        recommendedNextAction = redactDiagnosticsArchiveText(assessment.recommendedNextAction),
    )

private fun ConnectivityEvidence.redactForArchive(): ConnectivityEvidence =
    copy(
        controls = archiveTargetListProjection(controls),
        affectedTargets = archiveTargetListProjection(affectedTargets),
    )
