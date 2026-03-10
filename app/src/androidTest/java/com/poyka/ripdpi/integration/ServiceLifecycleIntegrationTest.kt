package com.poyka.ripdpi.integration

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class ServiceLifecycleIntegrationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        IntegrationTestOverrides.reset()
        hiltRule.inject()
    }

    @After
    fun tearDown() =
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
            delay(200)
        }

    @Test
    fun proxyServiceStartAndStopTransitionsState() {
        runBlocking {
            startService(RipDpiProxyService::class.java)

            awaitStatus(AppStatus.Running, Mode.Proxy)
            assertEquals(listOf("proxy:start"), IntegrationTestOverrides.orderSnapshot())

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun proxyServiceUsesResolvedCommandLinePreferences() {
        runBlocking {
            IntegrationTestOverrides.overrideProxyPreferencesResolver(
                com.poyka.ripdpi.testing.FixedProxyPreferencesResolver(
                    RipDpiProxyCmdPreferences("--ip 127.0.0.1 --port 1092 --split 1+s"),
                ),
            )

            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            val preferences = IntegrationTestOverrides.proxyFactory.lastRuntime.lastPreferences
            assertTrue(preferences is RipDpiProxyCmdPreferences)

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)
        }
    }

    @Test
    fun proxyServiceNonZeroExitEmitsFailure() {
        runBlocking {
            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            IntegrationTestOverrides.proxyFactory.lastRuntime.complete(23)

            awaitFailure(Sender.Proxy)
            awaitStatus(AppStatus.Halted, Mode.Proxy)
        }
    }

    @Test
    fun proxyServiceGracefulExitHaltsWithoutFailure() {
        runBlocking {
            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            IntegrationTestOverrides.proxyFactory.lastRuntime.complete(0)

            awaitStatus(AppStatus.Halted, Mode.Proxy)
            assertTrue(IntegrationTestOverrides.serviceStateStore.eventHistory.isEmpty())
            assertEquals(0, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun vpnServiceStartsInExpectedOrderAndStopsTunnelBeforeProxy() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                proxyPort = 1091
                dnsIp = "9.9.9.9"
                ipv6Enable = true
            }

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            assertEquals("9.9.9.9", IntegrationTestOverrides.vpnTunnelSessionProvider.lastDns)
            assertEquals(true, IntegrationTestOverrides.vpnTunnelSessionProvider.lastIpv6)
            assertEquals(1091, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.startedConfig?.socks5Port)
            assertContainsSubsequence(
                IntegrationTestOverrides.orderSnapshot(),
                listOf("vpn:establish", "tunnel:start"),
            )
            assertTrue(IntegrationTestOverrides.orderSnapshot().contains("proxy:start"))

            stopService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertContainsSubsequence(
                IntegrationTestOverrides.orderSnapshot(),
                listOf("vpn:establish", "tunnel:start", "tunnel:stop", "vpn:session-close", "proxy:stop"),
            )
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
        }
    }

    @Test
    fun vpnServicePublishesTunnelTelemetry() {
        assumeVpnPrepared()
        runBlocking {
            val expectedStats =
                com.poyka.ripdpi.core.TunnelStats(
                    txPackets = 7,
                    txBytes = 8,
                    rxPackets = 9,
                    rxBytes = 10,
                )
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.statsValue = expectedStats

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitTelemetry(
                mode = Mode.VPN,
                status = AppStatus.Running,
                expectedStats = expectedStats,
            )
        }
    }

    @Test
    fun vpnServiceFallsBackToZeroTelemetryWhenStatsFail() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.failOnStats =
                IOException("stats unavailable")

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitTelemetry(
                mode = Mode.VPN,
                status = AppStatus.Running,
                expectedStats = com.poyka.ripdpi.core.TunnelStats(),
            )
        }
    }

    @Test
    fun vpnServiceTunnelStartFailureEmitsFailureAndCleansUp() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.failOnStart =
                IllegalStateException("boom")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertContainsSubsequence(
                IntegrationTestOverrides.orderSnapshot(),
                listOf("vpn:establish", "tunnel:start", "vpn:session-close", "proxy:stop"),
            )
            assertTrue(IntegrationTestOverrides.orderSnapshot().contains("proxy:start"))
        }
    }

    @Test
    fun vpnServiceEstablishFailureEmitsFailureAndStopsProxy() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.vpnTunnelSessionProvider.establishFailure =
                IllegalStateException("no session")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertEquals(
                listOf("proxy:start", "vpn:establish", "proxy:stop"),
                IntegrationTestOverrides.orderSnapshot(),
            )
        }
    }

    @Test
    fun vpnServiceProxyFailureEmitsFailureAndStopsTunnel() {
        assumeVpnPrepared()
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            IntegrationTestOverrides.proxyFactory.lastRuntime.complete(17)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertTrue(
                IntegrationTestOverrides.orderSnapshot().containsAll(
                    listOf("tunnel:stop", "vpn:session-close", "proxy:stop"),
                ),
            )
        }
    }

    private fun startService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, serviceClass).setAction(START_ACTION),
        )
    }

    private fun stopService(serviceClass: Class<*>) {
        appContext.startService(Intent(appContext, serviceClass).setAction(STOP_ACTION))
    }

    private suspend fun awaitStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.serviceStateStore.status.value != status to mode) {
                delay(50)
            }
        }
    }

    private suspend fun awaitFailure(sender: Sender) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.serviceStateStore.eventHistory.none {
                    it is ServiceEvent.Failed && it.sender == sender
                }
            ) {
                delay(50)
            }
        }
    }

    private suspend fun awaitTelemetry(
        mode: Mode,
        status: AppStatus,
        expectedStats: com.poyka.ripdpi.core.TunnelStats,
    ) {
        withTimeout(10.seconds) {
            while (true) {
                val snapshot = IntegrationTestOverrides.serviceStateStore.telemetry.value
                if (snapshot.mode == mode &&
                    snapshot.status == status &&
                    snapshot.tunnelStats == expectedStats
                ) {
                    return@withTimeout
                }
                delay(50)
            }
        }
    }

    private fun assertContainsSubsequence(
        actual: List<String>,
        expected: List<String>,
    ) {
        var currentIndex = 0
        actual.forEach { event ->
            if (currentIndex < expected.size && event == expected[currentIndex]) {
                currentIndex += 1
            }
        }
        assertEquals("Expected ordered subsequence $expected in $actual", expected.size, currentIndex)
    }

    private fun assumeVpnPrepared() {
        assumeTrue("VPN consent is not prepared on this device", VpnService.prepare(appContext) == null)
    }
}
