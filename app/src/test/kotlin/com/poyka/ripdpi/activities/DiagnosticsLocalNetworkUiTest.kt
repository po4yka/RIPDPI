package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.LocalConstraintCode
import com.poyka.ripdpi.diagnostics.LocalDataConnectionState
import com.poyka.ripdpi.diagnostics.LocalDataSaverState
import com.poyka.ripdpi.diagnostics.LocalDeviceConstraints
import com.poyka.ripdpi.diagnostics.LocalNetworkContextModel
import com.poyka.ripdpi.diagnostics.LocalObservationState
import com.poyka.ripdpi.diagnostics.LocalServiceState
import com.poyka.ripdpi.diagnostics.LocalSimConstraints
import com.poyka.ripdpi.diagnostics.LocalSimScope
import com.poyka.ripdpi.diagnostics.LocalSimState
import com.poyka.ripdpi.diagnostics.LocalTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsLocalNetworkUiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val support = DiagnosticsUiFactorySupport(app)

    @Test
    fun missingSnapshotDoesNotInventHealthySettings() {
        val group = support.toLocalNetworkGroups(null).single()
        assertEquals(app.getString(R.string.diagnostics_local_not_collected), group.fields.single().value)
        assertTrue(group.stackedFields)
    }

    @Test
    fun unavailableCategoriesRemainDistinctAndReadable() {
        val labels = LocalObservationState.entries.map(support::localValue)
        assertEquals(6, labels.toSet().size)
        assertEquals(
            app.getString(R.string.diagnostics_local_value_permission_denied),
            support.localValue(LocalObservationState.PERMISSION_DENIED),
        )
        val all =
            LocalTransport.entries + LocalDataSaverState.entries + LocalSimScope.entries + LocalSimState.entries +
                LocalServiceState.entries +
                LocalDataConnectionState.entries
        all.forEach { assertTrue(support.localValue(it).isNotBlank()) }
        LocalConstraintCode.entries.forEach { assertTrue(app.getString(localConstraintText(it)).isNotBlank()) }
    }

    @Test
    fun wifiSnapshotKeepsSimScopeAndLocalFactsSeparate() {
        val groups =
            support.toLocalNetworkGroups(
                LocalNetworkContextModel(
                    capturedAt = 1_700_000_000_000,
                    transport = LocalTransport.WIFI,
                    device = LocalDeviceConstraints(airplaneMode = LocalObservationState.ENABLED),
                    sim =
                        LocalSimConstraints(
                            scope = LocalSimScope.DEFAULT_DATA,
                            mobileDataEnabled = LocalObservationState.DISABLED,
                        ),
                ),
            )
        val fields = groups.flatMap { it.fields }
        assertTrue(fields.any { it.value == app.getString(R.string.diagnostics_local_value_wifi) })
        assertTrue(fields.any { it.value == app.getString(R.string.diagnostics_local_sim_caution) })
        assertTrue(fields.any { it.value == app.getString(R.string.diagnostics_local_caution) })
        assertTrue(fields.any { it.value == app.getString(R.string.diagnostics_local_hint_mobile_data_disabled) })
        assertTrue(groups.all { it.stackedFields })
    }

    @Test
    fun mixedSubscriptionSnapshotDoesNotShowStaleSimFacts() {
        val fields =
            support
                .toLocalNetworkGroups(
                    LocalNetworkContextModel(
                        capturedAt = -1,
                        sim =
                            LocalSimConstraints(
                                scope = LocalSimScope.CHANGED,
                                simState = LocalSimState.READY,
                                mobileDataEnabled = LocalObservationState.DISABLED,
                            ),
                    ),
                ).flatMap { it.fields }
        assertFalse(fields.any { it.value == app.getString(R.string.diagnostics_local_value_ready) })
        assertFalse(fields.any { it.value == app.getString(R.string.diagnostics_local_hint_mobile_data_disabled) })
        assertTrue(fields.any { it.value == app.getString(R.string.diagnostics_local_hint_subscription_changed) })
        assertEquals(
            app.getString(R.string.diagnostics_local_unknown),
            fields
                .first {
                    it.label ==
                        app.getString(R.string.diagnostics_local_captured)
                }.value,
        )
    }
}
