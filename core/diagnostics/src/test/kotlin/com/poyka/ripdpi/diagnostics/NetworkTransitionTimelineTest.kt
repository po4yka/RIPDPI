package com.poyka.ripdpi.diagnostics

import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkTransitionTimelineTest {
    @Test
    fun `timeline keeps ordered redacted callback facts and rotates generation after loss`() =
        runTest {
            val events = mutableListOf<NetworkTransitionEvent>()
            var tick = 0L
            var connectionSessionId: String? = "session-a"
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = {
                        tick += 1
                        NetworkTransitionTimestamp(elapsedRealtimeMs = tick, epochMs = 10_000L + tick)
                    },
                    connectionSessionIdProvider = { connectionSessionId },
                    persist = { events += it },
                )
            val hostileKey =
                object {
                    override fun toString() = "wlan0/198.51.100.7/secret.example"
                }

            timeline.recordAvailable(hostileKey)
            timeline.recordCapabilities(hostileKey, vpnCapabilities())
            timeline.recordLinkProperties(hostileKey)
            timeline.recordLosing(hostileKey, 500)
            timeline.recordLost(hostileKey)
            timeline.recordLost(hostileKey)
            timeline.recordAvailable(hostileKey)
            connectionSessionId = "session-b"
            runCurrent()

            assertEquals((1L..6L).toList(), events.map(NetworkTransitionEvent::sequence))
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 2L), events.map(NetworkTransitionEvent::generation))
            assertEquals(NetworkTransitionPath.Vpn, events[1].path)
            assertEquals(NetworkTransitionState.Present, events[1].internet)
            assertEquals(NetworkTransitionState.Absent, events[1].validated)
            assertEquals(NetworkLosingDeadlineBand.Imminent, events[3].losingDeadlineBand)
            assertTrue(events.all { it.connectionSessionId == "session-a" })
            events.map(NetworkTransitionEvent::toRedactedMessage).forEach { message ->
                assertFalse(message.contains("elapsed_ms"))
                listOf("wlan0", "198.51.100.7", "secret.example").forEach { forbidden ->
                    assertFalse(message.contains(forbidden, ignoreCase = true))
                }
            }
        }

    @Test
    fun `capture-time correlation follows the active session for every callback and stays bounded`() =
        runTest {
            val events = mutableListOf<NetworkTransitionEvent>()
            var connectionSessionId: String? = null
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = { NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L) },
                    connectionSessionIdProvider = { connectionSessionId },
                    persist = { events += it },
                )
            val networkKey = Any()

            timeline.recordAvailable(networkKey)
            connectionSessionId = "session-a"
            timeline.recordCapabilities(networkKey, vpnCapabilities())
            connectionSessionId = "session-b"
            runCurrent()
            assertEquals(1, events.size)
            assertEquals("session-a", events.single().connectionSessionId)
            assertEquals(1L, events.single().generation)

            timeline.recordCapabilities(networkKey, vpnCapabilities())
            timeline.recordLost(networkKey)
            timeline.recordAvailable(networkKey)
            timeline.recordLinkProperties(networkKey)
            repeat(MaxPersistedNetworkTransitionsPerSession + 10) {
                timeline.recordCapabilities(networkKey, vpnCapabilities())
            }
            runCurrent()

            assertEquals(1, events.count { it.connectionSessionId == "session-a" })
            assertEquals(
                MaxPersistedNetworkTransitionsPerSession,
                events.count { it.connectionSessionId == "session-b" },
            )
            val sessionBEvents = events.filter { it.connectionSessionId == "session-b" }
            assertTrue(
                sessionBEvents.any {
                    it.kind == NetworkTransitionKind.CapabilitiesChanged && it.generation == 1L
                },
            )
            assertTrue(
                sessionBEvents.any {
                    it.kind == NetworkTransitionKind.Lost && it.generation == 1L
                },
            )
            assertTrue(sessionBEvents.any { it.kind == NetworkTransitionKind.Available && it.generation == 2L })
        }

    private fun vpnCapabilities(): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN)
        }

    private fun addCapability(
        capabilities: NetworkCapabilities,
        capability: Int,
    ) {
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(capabilities, capability)
    }

    private fun addTransport(
        capabilities: NetworkCapabilities,
        transport: Int,
    ) {
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
            .invoke(capabilities, transport)
    }
}
