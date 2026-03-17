package com.poyka.ripdpi.integration

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultScope
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.ActiveConnectionPolicy
import com.poyka.ripdpi.services.ActiveConnectionPolicyStore
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceStateStoreModule
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import com.poyka.ripdpi.testing.ProxyRuntimeFaultTarget
import com.poyka.ripdpi.testing.TunnelBridgeFaultTarget
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.IOException
import javax.inject.Inject
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
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    VpnTunnelSessionProviderModule::class,
)
class ServiceLifecycleIntegrationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @BindValue
    @JvmField
    var appSettingsRepository: AppSettingsRepository = IntegrationTestOverrides.appSettingsRepository

    @BindValue
    @JvmField
    var proxyPreferencesResolver: ProxyPreferencesResolver = IntegrationTestOverrides.proxyPreferencesResolver

    @BindValue
    @JvmField
    var proxyFactory: RipDpiProxyFactory = IntegrationTestOverrides.proxyFactory

    @BindValue
    @JvmField
    var tun2SocksBridgeFactory: Tun2SocksBridgeFactory = IntegrationTestOverrides.tun2SocksBridgeFactory

    @BindValue
    @JvmField
    var serviceStateStore: ServiceStateStore = IntegrationTestOverrides.serviceStateStore

    @BindValue
    @JvmField
    var vpnTunnelSessionProvider: VpnTunnelSessionProvider = IntegrationTestOverrides.vpnTunnelSessionProvider

    @Inject
    lateinit var activeConnectionPolicyStore: ActiveConnectionPolicyStore

    @Before
    fun setUp() {
        IntegrationTestOverrides.reset()
        appSettingsRepository = IntegrationTestOverrides.appSettingsRepository
        proxyPreferencesResolver = IntegrationTestOverrides.proxyPreferencesResolver
        proxyFactory = IntegrationTestOverrides.proxyFactory
        tun2SocksBridgeFactory = IntegrationTestOverrides.tun2SocksBridgeFactory
        serviceStateStore = IntegrationTestOverrides.serviceStateStore
        vpnTunnelSessionProvider = IntegrationTestOverrides.vpnTunnelSessionProvider
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
    fun proxyServicePublishesActivePolicyProjectionAndClearsOnStop() {
        runBlocking {
            startService(RipDpiProxyService::class.java)

            awaitStatus(AppStatus.Running, Mode.Proxy)
            val activePolicy = awaitActivePolicy(Mode.Proxy)

            assertEquals(Mode.Proxy, activePolicy.mode)

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)
            awaitClearedActivePolicy(Mode.Proxy)
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
    fun proxyServiceDuplicateStartDoesNotLaunchSecondRuntime() {
        runBlocking {
            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            startService(RipDpiProxyService::class.java)
            delay(200)

            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "proxy:start" })

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun proxyServiceStartupFailureEmitsFailureWithoutReportingRunning() {
        runBlocking {
            IntegrationTestOverrides.proxyFactory.lastRuntime.startFailure = IOException("proxy boom")

            startService(RipDpiProxyService::class.java)

            awaitFailure(Sender.Proxy)
            awaitStatus(AppStatus.Halted, Mode.Proxy)

            assertEquals(listOf("proxy:start"), IntegrationTestOverrides.orderSnapshot())
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
    fun vpnServicePublishesActivePolicyProjectionAndClearsOnStop() {
        assumeVpnPrepared()
        runBlocking {
            startService(RipDpiVpnService::class.java)

            awaitStatus(AppStatus.Running, Mode.VPN)
            val activePolicy = awaitActivePolicy(Mode.VPN)

            assertEquals(Mode.VPN, activePolicy.mode)

            stopService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Halted, Mode.VPN)
            awaitClearedActivePolicy(Mode.VPN)
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
    fun vpnServiceProxyStartupFailureEmitsFailureBeforeTunnelStarts() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.proxyFactory.lastRuntime.startFailure = IOException("proxy boom")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertEquals(listOf("proxy:start"), IntegrationTestOverrides.orderSnapshot())
            assertEquals(null, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.startedConfig)
            assertEquals(0, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
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

    @Test
    fun vpnServiceUnexpectedTunnelExitEmitsFailureAndStopsProxy() {
        assumeVpnPrepared()
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.telemetryValue =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "idle",
                    health = "degraded",
                    lastError = "worker died",
                )

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

    @Test
    fun vpnServiceRepeatedStopDoesNotDuplicateTunnelOrProxyShutdown() {
        assumeVpnPrepared()
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            stopService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            stopService(RipDpiVpnService::class.java)
            delay(200)

            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "tunnel:stop" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "proxy:stop" })
        }
    }

    @Test
    fun proxyServiceStopFailureStillHaltsAndSecondStopDoesNotLoop() {
        runBlocking {
            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            IntegrationTestOverrides.proxyFactory.lastRuntime.faults.enqueue(
                FaultSpec(
                    target = ProxyRuntimeFaultTarget.STOP,
                    outcome = FaultOutcome.EXCEPTION,
                    scope = FaultScope.ONE_SHOT,
                    message = "proxy stop failed",
                ),
            )

            stopService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Halted, Mode.Proxy)

            stopService(RipDpiProxyService::class.java)
            delay(200)

            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun proxyServiceTelemetryFailureFallsBackToIdleSnapshot() {
        runBlocking {
            IntegrationTestOverrides.proxyFactory.lastRuntime.telemetryValue =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    activeSessions = 1,
                    totalSessions = 2,
                )

            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)
            awaitTelemetrySnapshot { snapshot ->
                snapshot.mode == Mode.Proxy && snapshot.proxyTelemetry.state == "running"
            }

            IntegrationTestOverrides.proxyFactory.lastRuntime.faults.enqueue(
                FaultSpec(
                    target = ProxyRuntimeFaultTarget.TELEMETRY,
                    outcome = FaultOutcome.EXCEPTION,
                    scope = FaultScope.PERSISTENT,
                    message = "telemetry unavailable",
                ),
            )

            awaitTelemetrySnapshot { snapshot ->
                snapshot.mode == Mode.Proxy &&
                    snapshot.status == AppStatus.Running &&
                    snapshot.proxyTelemetry.state == "idle"
            }
        }
    }

    @Test
    fun vpnServiceTunnelStopFailureStillClosesSessionAndHalts() {
        assumeVpnPrepared()
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.faults.enqueue(
                FaultSpec(
                    target = TunnelBridgeFaultTarget.STOP,
                    outcome = FaultOutcome.EXCEPTION,
                    message = "tunnel stop failed",
                ),
            )

            stopService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun vpnServiceTelemetryFailureFallsBackToIdleTunnelSnapshot() {
        assumeVpnPrepared()
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.telemetryValue =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    activeSessions = 1,
                    tunnelStats = com.poyka.ripdpi.core.TunnelStats(txPackets = 5, rxPackets = 6),
                )

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitTelemetrySnapshot { snapshot ->
                snapshot.mode == Mode.VPN && snapshot.tunnelTelemetry.state == "running"
            }

            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.faults.enqueue(
                FaultSpec(
                    target = TunnelBridgeFaultTarget.TELEMETRY,
                    outcome = FaultOutcome.EXCEPTION,
                    scope = FaultScope.PERSISTENT,
                    message = "tunnel telemetry failed",
                ),
            )

            awaitTelemetrySnapshot { snapshot ->
                snapshot.mode == Mode.VPN &&
                    snapshot.status == AppStatus.Running &&
                    snapshot.tunnelTelemetry.state == "idle" &&
                    snapshot.tunnelStats == com.poyka.ripdpi.core.TunnelStats()
            }
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

    private suspend fun awaitTelemetrySnapshot(
        predicate: (com.poyka.ripdpi.services.ServiceTelemetrySnapshot) -> Boolean,
    ) {
        withTimeout(10.seconds) {
            while (!predicate(IntegrationTestOverrides.serviceStateStore.telemetry.value)) {
                delay(50)
            }
        }
    }

    private suspend fun awaitActivePolicy(mode: Mode): ActiveConnectionPolicy =
        withTimeout(10.seconds) {
            while (true) {
                activeConnectionPolicyStore.current(mode)?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }

    private suspend fun awaitClearedActivePolicy(mode: Mode) {
        withTimeout(10.seconds) {
            while (activeConnectionPolicyStore.current(mode) != null) {
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
