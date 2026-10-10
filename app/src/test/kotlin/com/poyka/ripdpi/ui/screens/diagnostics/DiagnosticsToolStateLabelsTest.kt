package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityState
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityState
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteState
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistState
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportState
import com.poyka.ripdpi.activities.DiagnosticsRknBlockDiagnosisState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU")
class DiagnosticsToolStateLabelsTest {
    @Test
    fun `each tool state has its correct localized label`() {
        assertLabels(DiagnosticsDnsIntegrityState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsDnsAvailabilityState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsDomainReachabilityState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsCompressionProbeState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsTcp16FatHeaderState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsAllowlistSniState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsByohCompatibilityState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsIpv4WhitelistState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsCidrWhitelistState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsRknBlockDiagnosisState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsDpiSuiteState.entries, ::diagnosticToolStateLabelRes)
        assertLabels(DiagnosticsPluggableTransportState.entries, ::diagnosticToolStateLabelRes)
    }

    private fun <T : Enum<T>> assertLabels(
        states: List<T>,
        resource: (T) -> Int,
    ) {
        val resources = RuntimeEnvironment.getApplication().resources
        for (state in states) {
            val expected =
                when (state.name) {
                    "Idle" -> R.string.diagnostics_tool_state_idle
                    "Running" -> R.string.diagnostics_tool_state_running
                    "Complete" -> R.string.diagnostics_tool_state_complete
                    "Failed" -> R.string.diagnostics_tool_state_failed
                    "Cancelled" -> R.string.diagnostics_tool_state_cancelled
                    "Disabled" -> R.string.diagnostics_tool_state_disabled
                    else -> error("Uncovered state: $state")
                }
            assertEquals(state.name, expected, resource(state))
            assertNotEquals(state.name.lowercase(), resources.getString(resource(state)).lowercase())
        }
    }
}
