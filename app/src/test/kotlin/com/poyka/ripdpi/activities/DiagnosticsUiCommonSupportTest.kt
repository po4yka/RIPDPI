package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiCommonSupportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `remembered policy source labels cover full taxonomy`() {
        assertEquals(
            context.getString(R.string.diagnostics_source_manual_session),
            RememberedNetworkPolicySource.MANUAL_SESSION.displaySourceLabel(context),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_probing_background),
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND.displaySourceLabel(context),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_probing_manual),
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_MANUAL.displaySourceLabel(context),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_automatic_audit_manual),
            RememberedNetworkPolicySource.AUTOMATIC_AUDIT_MANUAL.displaySourceLabel(context),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_strategy_probe_manual),
            RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL.displaySourceLabel(context),
        )
        assertEquals(
            context.getString(R.string.diagnostics_source_unknown),
            RememberedNetworkPolicySource.UNKNOWN.displaySourceLabel(context),
        )
    }
}
