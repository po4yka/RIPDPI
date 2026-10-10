package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidLocalNetworkContextCollectorTest {
    @Test
    fun `selection change discards every SIM observation`() {
        val platform = FakeLocalPlatform(ids = mutableListOf(7, 8))
        val snapshot = AndroidLocalNetworkContextCollector(platform) { 10 }.capture()
        assertEquals(LocalSimConstraints(scope = LocalSimScope.CHANGED), snapshot.sim)
        assertEquals(LocalObservationState.ENABLED, snapshot.device.airplaneMode)
        assertEquals(listOf(7), platform.queriedIds)
    }

    @Test
    fun `missing default subscription never means physical SIM absent`() {
        val platform = FakeLocalPlatform(ids = mutableListOf(-1))
        val snapshot = AndroidLocalNetworkContextCollector(platform).capture()
        assertEquals(LocalSimScope.NOT_CONFIGURED, snapshot.sim.scope)
        assertEquals(LocalSimState.UNKNOWN, snapshot.sim.simState)
        assertEquals(emptyList<Int>(), platform.queriedIds)
    }

    @Test
    fun `all transports collect default data SIM independently`() {
        for (transport in listOf(LocalTransport.WIFI, LocalTransport.VPN, LocalTransport.NONE)) {
            val platform = FakeLocalPlatform(transport = transport)
            val snapshot = AndroidLocalNetworkContextCollector(platform) { -1 }.capture()
            assertEquals(transport, snapshot.transport)
            assertEquals(0, snapshot.capturedAt)
            assertEquals(LocalSimScope.DEFAULT_DATA, snapshot.sim.scope)
            assertEquals(LocalObservationState.DISABLED, snapshot.sim.mobileDataEnabled)
            assertEquals(listOf(7), platform.queriedIds)
        }
    }

    @Test
    fun `selection failures preserve categorical cause without raw text`() {
        for ((failure, expected) in listOf(
            SecurityException("private-id") to LocalSimScope.PERMISSION_DENIED,
            UnsupportedOperationException("private-id") to LocalSimScope.UNSUPPORTED,
            IllegalStateException("private-id") to LocalSimScope.UNAVAILABLE,
        )) {
            val platform = FakeLocalPlatform(failure = failure)
            val snapshot = AndroidLocalNetworkContextCollector(platform).capture()
            assertEquals(expected, snapshot.sim.scope)
            assertEquals(expected.name, snapshot.sim.mobileDataEnabled.name)
            assertFalse(snapshot.toString().contains("private-id"))
        }
    }

    @Test
    fun `permission revoked after capture discards SIM facts`() {
        val platform = FakeLocalPlatform(ids = mutableListOf(7), failAfterRead = true)
        val snapshot = AndroidLocalNetworkContextCollector(platform).capture()
        assertEquals(LocalSimConstraints(scope = LocalSimScope.CHANGED), snapshot.sim)
    }

    @Test
    fun `field observation maps individual platform failures`() {
        listOf(
            SecurityException() to LocalObservationState.PERMISSION_DENIED,
            UnsupportedOperationException() to LocalObservationState.UNSUPPORTED,
            IllegalArgumentException() to LocalObservationState.UNAVAILABLE,
        ).forEach { (error, expected) ->
            assertEquals(expected, localObservation<LocalObservationState> { throw error })
        }
    }
}

private class FakeLocalPlatform(
    private val ids: MutableList<Int> = mutableListOf(7, 7),
    private val transport: LocalTransport = LocalTransport.WIFI,
    private val failure: RuntimeException? = null,
    private val failAfterRead: Boolean = false,
) : AndroidLocalNetworkPlatform {
    val queriedIds = mutableListOf<Int>()

    override fun transport() = transport

    override fun device() = LocalDeviceConstraints(airplaneMode = LocalObservationState.ENABLED)

    override fun defaultDataSubscriptionId(): Int {
        failure?.let { throw it }
        if (failAfterRead && queriedIds.isNotEmpty()) throw SecurityException()
        return ids.removeAt(0)
    }

    override fun sim(subscriptionId: Int): LocalSimConstraints {
        queriedIds += subscriptionId
        return LocalSimConstraints(
            scope = LocalSimScope.DEFAULT_DATA,
            simState = LocalSimState.READY,
            mobileDataEnabled = LocalObservationState.DISABLED,
            dataState = LocalDataConnectionState.CONNECTED,
        )
    }
}
