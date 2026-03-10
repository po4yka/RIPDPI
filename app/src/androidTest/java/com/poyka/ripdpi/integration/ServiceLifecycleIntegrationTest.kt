package com.poyka.ripdpi.integration

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun proxyServiceStartAndStopTransitionsState() =
        runBlocking {
            startService(RipDpiProxyService::class.java)

            awaitStatus(AppStatus.Running, Mode.Proxy)
            assertEquals(listOf("proxy:start"), IntegrationTestOverrides.orderSnapshot())

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }

    @Test
    fun proxyServiceNonZeroExitEmitsFailure() =
        runBlocking {
            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            IntegrationTestOverrides.proxyFactory.lastRuntime.complete(23)

            awaitFailure(Sender.Proxy)
            awaitStatus(AppStatus.Halted, Mode.Proxy)
        }

    @Test
    fun vpnServiceStartsInExpectedOrderAndStopsTunnelBeforeProxy() =
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
            assertEquals(
                listOf("proxy:start", "vpn:establish", "tunnel:start"),
                IntegrationTestOverrides.orderSnapshot(),
            )

            stopService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertEquals(
                listOf(
                    "proxy:start",
                    "vpn:establish",
                    "tunnel:start",
                    "tunnel:stop",
                    "vpn:session-close",
                    "proxy:stop",
                ),
                IntegrationTestOverrides.orderSnapshot(),
            )
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
        }

    @Test
    fun vpnServiceTunnelStartFailureEmitsFailureAndCleansUp() =
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.failOnStart =
                IllegalStateException("boom")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertEquals(
                listOf(
                    "proxy:start",
                    "vpn:establish",
                    "tunnel:start",
                    "vpn:session-close",
                    "proxy:stop",
                ),
                IntegrationTestOverrides.orderSnapshot(),
            )
        }

    private fun startService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, serviceClass).setAction(START_ACTION),
        )
    }

    private fun stopService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, serviceClass).setAction(STOP_ACTION),
        )
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
                    it is com.poyka.ripdpi.services.ServiceEvent.Failed && it.sender == sender
                }
            ) {
                delay(50)
            }
        }
    }
}
