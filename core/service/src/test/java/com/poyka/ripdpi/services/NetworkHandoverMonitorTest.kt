package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.CellularNetworkIdentityTuple
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkHandoverMonitorTest {
    @Test
    fun `callback bursts collapse into one actionable handover after debounce`() =
        runTest {
            val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            var currentFingerprint: NetworkFingerprint? = wifiFingerprint(dnsServer = "1.1.1.1")
            val events = mutableListOf<NetworkHandoverEvent>()
            val job =
                backgroundScope.launch {
                    observeNetworkHandoverEvents(
                        signals = signals,
                        captureFingerprint = { currentFingerprint },
                        debounceMs = 2_000L,
                        clock = { testScheduler.currentTime },
                    ).toList(events)
                }

            runCurrent()
            currentFingerprint = cellularFingerprint()
            repeat(3) { signals.emit(Unit) }
            runCurrent()

            assertTrue(events.isEmpty())

            testScheduler.advanceTimeBy(1_999L)
            runCurrent()
            assertTrue(events.isEmpty())

            testScheduler.advanceTimeBy(1L)
            runCurrent()

            assertEquals(1, events.size)
            assertEquals("transport_switch", events.single().classification)
            assertTrue(events.single().isActionable)
            assertEquals(testScheduler.currentTime, events.single().occurredAt)

            job.cancel()
        }

    @Test
    fun `monitor classifies link refresh and connectivity loss separately`() =
        runTest {
            val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
            var currentFingerprint: NetworkFingerprint? = wifiFingerprint(dnsServer = "1.1.1.1")
            val events = mutableListOf<NetworkHandoverEvent>()
            val job =
                backgroundScope.launch {
                    observeNetworkHandoverEvents(
                        signals = signals,
                        captureFingerprint = { currentFingerprint },
                        debounceMs = 0L,
                        clock = { testScheduler.currentTime },
                    ).toList(events)
                }

            runCurrent()
            currentFingerprint = wifiFingerprint(dnsServer = "8.8.8.8")
            signals.emit(Unit)
            runCurrent()

            currentFingerprint = null
            signals.emit(Unit)
            runCurrent()

            assertEquals(listOf("link_refresh", "connectivity_loss"), events.map(NetworkHandoverEvent::classification))
            assertTrue(events.first().isActionable)
            assertFalse(events.last().isActionable)

            job.cancel()
        }

    private fun wifiFingerprint(dnsServer: String): NetworkFingerprint =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf(dnsServer),
            wifi =
                WifiNetworkIdentityTuple(
                    ssid = "ripdpi-lab",
                    bssid = "aa:bb:cc:dd:ee:ff",
                    gateway = "192.0.2.1",
                ),
        )

    private fun cellularFingerprint(): NetworkFingerprint =
        NetworkFingerprint(
            transport = "cellular",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
            cellular =
                CellularNetworkIdentityTuple(
                    operatorCode = "25001",
                    simOperatorCode = "25001",
                    carrierId = 1,
                    dataNetworkType = "lte",
                    roaming = false,
                ),
        )
}
