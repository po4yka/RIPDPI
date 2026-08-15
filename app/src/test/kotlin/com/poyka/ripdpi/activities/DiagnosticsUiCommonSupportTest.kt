package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.platform.AndroidStringResolver
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
}
