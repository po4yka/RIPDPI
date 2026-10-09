package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.platform.StringResolver

/** Tracks an observed run until its persisted result settles after progress disappears. */
internal class DiagnosticsScanCompletionTracker {
    private var activeSessionId: String? = null
    private var activeHomeRun = false
    private var pendingSessionId: String? = null
    private var cancellationSessionId: String? = null

    fun observe(
        progress: ScanProgress?,
        sessions: List<DiagnosticScanSession>,
        homeRun: Boolean,
    ): DiagnosticScanSession? {
        if (progress != null) {
            if (progress.sessionId != activeSessionId) {
                activeSessionId = progress.sessionId
                activeHomeRun = homeRun
                pendingSessionId = null
                if (cancellationSessionId != progress.sessionId) cancellationSessionId = null
            }
        } else {
            activeSessionId?.let { sessionId ->
                pendingSessionId = sessionId.takeUnless { activeHomeRun || cancellationSessionId == sessionId }
                activeSessionId = null
                activeHomeRun = false
            }
        }
        return settledCompletion(sessions)
    }

    fun cancellationTarget(progress: ScanProgress?): String? =
        progress?.sessionId ?: activeSessionId ?: pendingSessionId

    fun beginCancellation(sessionId: String?): Boolean {
        val alreadyCancelling = sessionId != null && cancellationSessionId == sessionId
        if (!alreadyCancelling) cancellationSessionId = sessionId
        return !alreadyCancelling
    }

    fun cancellationSucceeded(sessionId: String?) {
        if (pendingSessionId == sessionId) pendingSessionId = null
    }

    fun cancellationFailed(sessionId: String?) {
        if (cancellationSessionId == sessionId) cancellationSessionId = null
    }

    private fun settledCompletion(sessions: List<DiagnosticScanSession>): DiagnosticScanSession? {
        val pendingSession = sessions.firstOrNull { it.id == pendingSessionId }
        val waitingForSettlement = pendingSession == null || pendingSession.status == "running"
        val cancelling = cancellationSessionId != null && cancellationSessionId == pendingSessionId
        return if (waitingForSettlement || cancelling) {
            null
        } else {
            pendingSessionId = null
            pendingSession?.takeIf { it.status == "completed" }
        }
    }
}

internal fun buildScanCompletionEffect(
    scan: DiagnosticsScanUiModel,
    stringResolver: StringResolver,
): DiagnosticsEffect.ScanCompleted {
    val latestSummary =
        scan.latestSession?.summary
            ?: stringResolver.getString(R.string.diagnostics_snackbar_scan_complete)
    val resolverMessage =
        when {
            scan.resolverRecommendation != null -> {
                stringResolver.getString(
                    R.string.diagnostics_snackbar_dns_recommendation_format,
                    scan.resolverRecommendation.headline,
                )
            }

            latestSummary.contains("resolver override recommended", ignoreCase = true) -> {
                stringResolver.getString(R.string.diagnostics_snackbar_dns_recommendation_generic)
            }

            else -> {
                null
            }
        }
    return DiagnosticsEffect.ScanCompleted(
        summary = resolverMessage ?: latestSummary,
        tone = if (resolverMessage != null) DiagnosticsTone.Warning else scanCompletedTone(scan.latestSession),
        actionLabel = scan.resolverRecommendation?.let { stringResolver.getString(R.string.title_dns_settings) },
        action = scan.resolverRecommendation?.let { DiagnosticsEffect.SnackbarAction.OpenDnsSettings },
    )
}
