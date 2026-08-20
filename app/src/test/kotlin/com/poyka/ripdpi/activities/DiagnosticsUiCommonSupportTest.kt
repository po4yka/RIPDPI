package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.ActiveStrategyPathOutcome
import com.poyka.ripdpi.diagnostics.ActiveStrategyPathUnavailableReason
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.CurrentStrategyAssessment
import com.poyka.ripdpi.diagnostics.CurrentStrategyCandidateVerdict
import com.poyka.ripdpi.diagnostics.CurrentStrategyEvidenceReason
import com.poyka.ripdpi.diagnostics.StrategyProbeObservationRole
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.diagnostics.uiEvidenceNote
import com.poyka.ripdpi.ui.diagnostics.uiVerificationLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiCommonSupportTest {
    private val context = RuntimeEnvironment.getApplication()
    private val stringResolver = AndroidStringResolver(context)

    @Test
    fun `remembered policy source labels cover full taxonomy`() {
        assertEquals(
            context.getString(R.string.diagnostics_source_manual_session),
            RememberedNetworkPolicySource.MANUAL_SESSION.displaySourceLabel(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_probing_background),
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND.displaySourceLabel(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_probing_manual),
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_MANUAL.displaySourceLabel(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_audit_manual),
            RememberedNetworkPolicySource.AUTOMATIC_AUDIT_MANUAL.displaySourceLabel(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_strategy_probe_manual),
            RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL.displaySourceLabel(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_unknown),
            RememberedNetworkPolicySource.UNKNOWN.displaySourceLabel(stringResolver),
        )
    }

    @Test
    fun `runtime errors and restarts downgrade confirmed approach tone without changing evidence`() {
        val confirmed =
            sampleApproachSummary(BypassApproachKind.Strategy, "strategy")
                .copy(
                    verificationState = BypassApproachVerificationState.CONFIRMED_WORKING,
                    validatedScanCount = 2,
                    validatedSuccessCount = 2,
                    validatedSuccessRate = 1f,
                    recentRuntimeHealth = BypassRuntimeHealthSummary(),
                )

        assertEquals(DiagnosticsTone.Positive, confirmed.toDiagnosticsTone())
        assertEquals(
            DiagnosticsTone.Warning,
            confirmed
                .copy(recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 1, restartCount = 1))
                .toDiagnosticsTone(),
        )
        assertEquals(2, confirmed.validatedScanCount)
        assertEquals(2, confirmed.validatedSuccessCount)
    }

    @Test
    fun `incomplete evidence remains visually neutral`() {
        val incomplete =
            sampleApproachSummary(BypassApproachKind.Profile, "profile")
                .copy(
                    verificationState = BypassApproachVerificationState.INCOMPLETE_EVIDENCE,
                    validatedScanCount = 0,
                    validatedSuccessCount = 0,
                    validatedSuccessRate = null,
                )

        assertEquals(
            DiagnosticsTone.Neutral to DiagnosticsTone.Neutral,
            incomplete.toDiagnosticsTone() to incomplete.successMetricTone(),
        )
    }

    @Test
    fun `isolated candidate failure is warning rather than active path failure`() {
        val assessment =
            CurrentStrategyAssessment(
                candidateVerdict = CurrentStrategyCandidateVerdict.INEFFECTIVE_ON_TESTED_CANDIDATE_PATH,
                observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                reason = CurrentStrategyEvidenceReason.APPLIED_ATTEMPTS_FAILED,
            )
        val summary =
            sampleApproachSummary(BypassApproachKind.Strategy, "strategy")
                .copy(
                    verificationState = BypassApproachVerificationState.EVALUATED_NO_SUCCESS,
                    validatedScanCount = 1,
                    validatedSuccessCount = 0,
                    validatedSuccessRate = 0f,
                    currentStrategyAssessment = assessment,
                )

        assertEquals(DiagnosticsTone.Warning, summary.toDiagnosticsTone())
        assertEquals(DiagnosticsTone.Warning, summary.successMetricTone())
    }

    @Test
    fun `working isolated candidate remains informational until active path is verified`() {
        val assessment =
            CurrentStrategyAssessment(
                candidateVerdict = CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH,
                observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                reason = CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED,
            )
        val summary =
            sampleApproachSummary(BypassApproachKind.Strategy, "strategy")
                .copy(currentStrategyAssessment = assessment)

        assertEquals(DiagnosticsTone.Info, summary.toDiagnosticsTone())
        assertEquals(DiagnosticsTone.Info, summary.successMetricTone())
        assertEquals(
            context.getString(R.string.diagnostics_strategy_candidate_working_test_path),
            summary.uiVerificationLabel(stringResolver),
        )
    }

    @Test
    fun `active service outcome is shown separately from candidate verdict`() {
        val baseAssessment =
            CurrentStrategyAssessment(
                candidateVerdict = CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH,
                observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                reason = CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED,
            )
        val summary = sampleApproachSummary(BypassApproachKind.Strategy, "strategy")

        assertEquals(
            context.getString(R.string.diagnostics_strategy_active_service_working),
            summary
                .copy(
                    currentStrategyAssessment =
                        baseAssessment.copy(activePathOutcome = ActiveStrategyPathOutcome.WORKING),
                ).uiEvidenceNote(stringResolver),
        )
        assertEquals(
            context.getString(R.string.diagnostics_strategy_active_service_ineffective),
            summary
                .copy(
                    currentStrategyAssessment =
                        baseAssessment.copy(activePathOutcome = ActiveStrategyPathOutcome.INEFFECTIVE),
                ).uiEvidenceNote(stringResolver),
        )
    }

    @Test
    fun `isolated VPN candidate explains that active VPN path was not observed`() {
        val assessment =
            CurrentStrategyAssessment(
                candidateVerdict = CurrentStrategyCandidateVerdict.WORKING_ON_TESTED_CANDIDATE_PATH,
                observationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
                reason = CurrentStrategyEvidenceReason.APPLIED_ATTEMPT_SUCCEEDED,
                activePathUnavailableReason = ActiveStrategyPathUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED,
            )
        val summary =
            sampleApproachSummary(BypassApproachKind.Strategy, "strategy")
                .copy(currentStrategyAssessment = assessment, lastValidatedResult = "unrelated matrix failure")

        assertEquals(
            context.getString(R.string.diagnostics_strategy_active_vpn_path_not_observed),
            summary.uiEvidenceNote(stringResolver),
        )
    }
}
