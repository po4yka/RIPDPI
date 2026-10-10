package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkAssessmentTest {
    @Test
    fun `mobile data setting remains observable on WiFi without provider diagnosis`() {
        val context =
            LocalNetworkContextModel(
                transport = LocalTransport.WIFI,
                sim =
                    LocalSimConstraints(
                        scope = LocalSimScope.DEFAULT_DATA,
                        mobileDataEnabled = LocalObservationState.DISABLED,
                    ),
            )
        assertEquals(true, LocalConstraintCode.MOBILE_DATA_DISABLED in context.localConstraintCodes())
    }

    @Test
    fun `unknown denied and unsupported evidence never become disabled settings`() {
        for (state in listOf(
            LocalObservationState.UNKNOWN,
            LocalObservationState.PERMISSION_DENIED,
            LocalObservationState.UNSUPPORTED,
            LocalObservationState.UNAVAILABLE,
        )) {
            val result =
                LocalNetworkContextModel(
                    sim =
                        LocalSimConstraints(
                            scope = LocalSimScope.DEFAULT_DATA,
                            mobileDataEnabled = state,
                            roamingEnabled = state,
                            roaming = LocalObservationState.ENABLED,
                            dataConnectionAllowed = state,
                        ),
                ).localConstraintCodes()
            assertEquals(false, LocalConstraintCode.MOBILE_DATA_DISABLED in result)
            assertEquals(false, LocalConstraintCode.ROAMING_DISABLED in result)
            assertEquals(false, LocalConstraintCode.DATA_CONNECTION_DISALLOWED in result)
        }
    }

    @Test
    fun `roaming toggle only yields a condition while roaming is observed`() {
        for (state in LocalObservationState.entries) {
            val result =
                LocalNetworkContextModel(
                    sim =
                        LocalSimConstraints(
                            scope = LocalSimScope.DEFAULT_DATA,
                            roaming = state,
                            roamingEnabled = LocalObservationState.DISABLED,
                        ),
                ).localConstraintCodes()
            assertEquals(state == LocalObservationState.ENABLED, LocalConstraintCode.ROAMING_DISABLED in result)
        }
    }

    @Test
    fun `data saver exemption and disabled state do not imply background blocking`() {
        for (state in LocalDataSaverState.entries) {
            val result =
                LocalNetworkContextModel(
                    device = LocalDeviceConstraints(dataSaver = state),
                ).localConstraintCodes()
            assertEquals(state == LocalDataSaverState.ENABLED, LocalConstraintCode.BACKGROUND_DATA_RESTRICTED in result)
        }
    }

    @Test
    fun `unstable or absent subscription never attributes stale mobile facts`() {
        for (scope in LocalSimScope.entries.filterNot { it == LocalSimScope.DEFAULT_DATA }) {
            val context =
                LocalNetworkContextModel(
                    sim =
                        LocalSimConstraints(
                            scope = scope,
                            mobileDataEnabled = LocalObservationState.DISABLED,
                            simState = LocalSimState.PIN_REQUIRED,
                            activeDataMatchesDefault = LocalObservationState.DISABLED,
                        ),
                )
            val result = context.localConstraintCodes()
            assertEquals(false, LocalConstraintCode.SIM_NOT_READY in result)
            assertEquals(false, LocalConstraintCode.MOBILE_DATA_DISABLED in result)
            assertEquals(false, LocalConstraintCode.ACTIVE_DATA_DIFFERS in result)
            assertEquals(false, context.toSafeLocalNetworkContext().sim.simState == LocalSimState.PIN_REQUIRED)
        }
    }

    @Test
    fun `SIM registration and suspension have independent condition codes`() {
        val context =
            LocalNetworkContextModel(
                sim =
                    LocalSimConstraints(
                        scope = LocalSimScope.DEFAULT_DATA,
                        simState = LocalSimState.PUK_REQUIRED,
                        voiceServiceState = LocalServiceState.EMERGENCY_ONLY,
                        dataState = LocalDataConnectionState.SUSPENDED,
                        activeDataMatchesDefault = LocalObservationState.DISABLED,
                    ),
            )
        val result = context.localConstraintCodes()
        assertEquals(true, LocalConstraintCode.SIM_NOT_READY in result)
        assertEquals(true, LocalConstraintCode.VOICE_SERVICE_UNAVAILABLE in result)
        assertEquals(true, LocalConstraintCode.MOBILE_DATA_SUSPENDED in result)
        assertEquals(true, LocalConstraintCode.ACTIVE_DATA_DIFFERS in result)
    }

    @Test
    fun `airplane mode is a device observation even with WiFi and VPN`() {
        for (transport in listOf(LocalTransport.WIFI, LocalTransport.VPN, LocalTransport.NONE)) {
            val result =
                LocalNetworkContextModel(
                    transport = transport,
                    device = LocalDeviceConstraints(airplaneMode = LocalObservationState.ENABLED),
                ).localConstraintCodes()
            assertEquals(true, LocalConstraintCode.AIRPLANE_MODE in result)
            assertEquals(false, LocalConstraintCode.VOICE_SERVICE_UNAVAILABLE in result)
        }
    }

    @Test
    fun `voice unavailable does not contradict a connected data session`() {
        val local =
            LocalNetworkContextModel(
                sim =
                    LocalSimConstraints(
                        scope = LocalSimScope.DEFAULT_DATA,
                        voiceServiceState = LocalServiceState.OUT_OF_SERVICE,
                        dataState = LocalDataConnectionState.CONNECTED,
                        mobileDataEnabled = LocalObservationState.ENABLED,
                        dataConnectionAllowed = LocalObservationState.ENABLED,
                    ),
            )
        val codes = local.localConstraintCodes()
        assertEquals(true, LocalConstraintCode.VOICE_SERVICE_UNAVAILABLE in codes)
        assertEquals(false, LocalConstraintCode.DATA_CONNECTION_DISALLOWED in codes)
        assertEquals(false, LocalConstraintCode.MOBILE_DATA_DISABLED in codes)
        assertEquals(false, LocalConstraintCode.MOBILE_DATA_SUSPENDED in codes)
        assertEquals(LocalDataConnectionState.CONNECTED, local.toSafeLocalNetworkContext().sim.dataState)
    }

    @Test
    fun `safe scope normalization retains permission failure instead of generic unknown`() {
        val local =
            LocalNetworkContextModel(
                sim =
                    LocalSimConstraints(
                        scope = LocalSimScope.PERMISSION_DENIED,
                        mobileDataEnabled = LocalObservationState.DISABLED,
                    ),
            ).toSafeLocalNetworkContext()
        assertEquals(LocalObservationState.PERMISSION_DENIED, local.sim.mobileDataEnabled)
        assertEquals(LocalSimState.PERMISSION_DENIED, local.sim.simState)
    }
}
