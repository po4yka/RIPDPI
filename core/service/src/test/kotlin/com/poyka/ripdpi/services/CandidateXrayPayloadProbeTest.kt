package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.XrayProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateXrayPayloadProbeTest {
    @Test fun `real runtime lane starts protected TLS candidate verifies payload and releases`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val protection = ActiveProtectSocketPathProvider()
            var protectedFd: Int? = null
            protection.set("owned") { fd ->
                protectedFd = fd
                true
            }
            val probe =
                CandidateXrayPayloadProbe(
                    XrayRuntimeOwner(bridge, StandardTestDispatcher(testScheduler)),
                    protection,
                    { environment(true) },
                    CandidateHttpPayloadProbe(
                        RelayTcpProbe { endpoint, _ ->
                            assertEquals("127.0.0.1", endpoint.host)
                            assertTrue(endpoint.port > 0)
                            assertTrue(checkNotNull(bridge.registeredProtectController).protect(42))
                            RelayTcpProbeResult(true, 204)
                        },
                        System::nanoTime,
                    ),
                )
            assertTrue(probe.measure(profile(), "https://probe.example/payload") is CandidateRelayMeasurement.Succeeded)
            assertEquals(42, protectedFd)
            assertEquals(listOf("registerProtect", "start"), bridge.callLog.take(2))
            assertEquals(1, bridge.stopCount)
            assertFalse(probe.cleanupPending.value)
        }

    @Test fun `failed cleanup retains same native owner and retry is required`() =
        runTest {
            val bridge = FakeXrayNativeBridge(stopBehavior = FakeXrayNativeBridge.StopBehavior.Throw)
            val owner = XrayRuntimeOwner(bridge, StandardTestDispatcher(testScheduler))
            val probe =
                CandidateXrayPayloadProbe(
                    owner,
                    ActiveProtectSocketPathProvider(),
                    { environment(false) },
                    CandidateHttpPayloadProbe(
                        RelayTcpProbe {
                            _,
                            _,
                            ->
                            RelayTcpProbeResult(true, 200)
                        },
                        System::nanoTime,
                    ),
                )
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(profile(), "https://probe"))
            assertTrue(owner.isOccupied)
            assertTrue(probe.cleanupPending.value)
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(profile(), "https://probe"))
            assertEquals(1, bridge.startCount)
            bridge.stopBehavior = FakeXrayNativeBridge.StopBehavior.Clean
            assertTrue(probe.retryCleanup())
            assertFalse(owner.isOccupied)
        }

    @Test fun `cancellation releases runtime and preserves cancellation`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val owner = XrayRuntimeOwner(bridge, StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val probe =
                CandidateXrayPayloadProbe(
                    owner,
                    ActiveProtectSocketPathProvider(),
                    { environment(false) },
                    CandidateHttpPayloadProbe(
                        RelayTcpProbe {
                            _,
                            _,
                            ->
                            entered.complete(Unit)
                            CompletableDeferred<RelayTcpProbeResult>().await()
                        },
                        System::nanoTime,
                    ),
                )
            val command = async { probe.measure(profile(), "https://probe") }
            entered.await()
            command.cancelAndJoin()
            assertTrue(command.isCancelled)
            assertFalse(owner.isOccupied)
            assertEquals(1, bridge.stopCount)
        }

    @Test fun `withdrawn or replaced protection never authorizes an old callback`() {
        val protection = ActiveProtectSocketPathProvider()
        protection.set("first") { true }
        val first = checkNotNull(protection.captureDirectProtection())
        assertTrue(first.protect(42))
        val secondLease = protection.set("second") { true }
        assertFalse(first.protect(42))
        val second = checkNotNull(protection.captureDirectProtection())
        protection.clear(secondLease)
        assertFalse(second.protect(42))
    }

    @Test fun `VPN without its owned protection cannot start candidate`() =
        runTest {
            val bridge = FakeXrayNativeBridge()
            val probe =
                CandidateXrayPayloadProbe(
                    XrayRuntimeOwner(bridge, StandardTestDispatcher(testScheduler)),
                    ActiveProtectSocketPathProvider(),
                    { environment(true) },
                    CandidateHttpPayloadProbe(
                        RelayTcpProbe {
                            _,
                            _,
                            ->
                            error("No HTTP before protected runtime")
                        },
                        System::nanoTime,
                    ),
                )
            assertEquals(CandidateRelayMeasurement.EnvironmentChanged, probe.measure(profile(), "https://probe"))
            assertEquals(0, bridge.startCount)
        }

    private fun environment(vpn: Boolean) =
        CandidateRelayProbeEnvironment(vpn, "chrome_stable", emptyMap(), false, false)

    private fun profile() =
        XrayProfile(
            "candidate",
            XrayProfile.Outbound(
                serverAddress = "example.com",
                serverPort = 443,
                uuid = "550e8400-e29b-41d4-a716-446655440000",
                security = XrayProfile.Security.TLS,
                network = XrayProfile.Network.TCP,
                tls = XrayProfile.Tls("example.com"),
            ),
        )
}
