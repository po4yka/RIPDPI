package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.finalization.NetworkScopeUnverifiedDiagnosis

internal fun summarizeHomeVerification(
    sessionId: String,
    session: ScanSessionEntity?,
    report: ScanReport?,
): DiagnosticsHomeVerificationOutcome {
    val complete =
        session != null && report != null && session.hasCompleteHomeReport(sessionId, report) &&
            session.serviceMode == "VPN" && session.pathMode == ScanPathMode.IN_PATH.name &&
            report.pathMode == ScanPathMode.IN_PATH
    val success = complete && report?.hasHealthyVerificationTargets() == true
    return DiagnosticsHomeVerificationOutcome(
        sessionId = sessionId,
        success = success,
        headline =
            when {
                !complete -> "VPN verification was incomplete"
                success -> "VPN access confirmed"
                else -> "VPN started, but access is still limited"
            },
        summary = session?.displaySummary(report) ?: "Verification session could not be loaded.",
        detail = report?.diagnoses?.firstOrNull()?.summary,
    )
}

internal fun ScanSessionEntity.hasCompleteHomeReport(
    sessionId: String,
    report: ScanReport,
): Boolean =
    id == sessionId && report.sessionId == sessionId &&
        profileId == report.profileId &&
        status == "completed" && finishedAt != null &&
        pathMode == report.pathMode.name &&
        report.completionKind == ScanCompletionKind.NORMAL && report.terminationReason == null &&
        report.diagnoses.none { it.code == NetworkScopeUnverifiedDiagnosis }

private fun ScanReport.hasHealthyVerificationTargets(): Boolean =
    results.any { it.probeType in VerificationApplicationProbeTypes } &&
        results.all { result ->
            DiagnosticsOutcomeTaxonomy.classifyProbeResult(pathMode, result, results).healthyEnoughForSummary
        }

// DNS resolution or a QUIC Initial response alone does not establish application access.
private val VerificationApplicationProbeTypes =
    setOf("domain_reachability", "service_reachability", "circumvention_reachability", "telegram_availability", "http3")
