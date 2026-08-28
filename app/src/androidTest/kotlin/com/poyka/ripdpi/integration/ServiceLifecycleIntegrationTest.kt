package com.poyka.ripdpi.integration

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultScope
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStateStoreModule
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.NetworkHandoverMonitorModule
import com.poyka.ripdpi.services.PermissionChangeEvent
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.PermissionWatchdogModule
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.services.explicitUserIntentGenerationExtra
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySourceModule
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import com.poyka.ripdpi.testing.ProxyRuntimeFaultTarget
import com.poyka.ripdpi.testing.TunnelBridgeFaultTarget
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltAndroidTest
@UninstallModules(
    AppSettingsRepositoryModule::class,
    ProxyPreferencesResolverModule::class,
    RipDpiProxyFactoryModule::class,
    Tun2SocksBridgeFactoryModule::class,
    ServiceStateStoreModule::class,
    VpnTunnelSessionProviderModule::class,
    NetworkHandoverMonitorModule::class,
    PermissionWatchdogModule::class,
    DestinationRoutingPolicySourceModule::class,
)
class ServiceLifecycleIntegrationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }

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

    @BindValue
    @JvmField
    var networkHandoverMonitor: NetworkHandoverMonitor = IntegrationTestOverrides.networkHandoverMonitor

    @BindValue
    @JvmField
    var permissionWatchdog: PermissionWatchdog = IntegrationTestOverrides.permissionWatchdog

    @BindValue
    @JvmField
    var destinationRoutingPolicySource: DestinationRoutingPolicySource =
        DestinationRoutingPolicySource {
            DestinationRoutingPolicySnapshot.Available(
                DestinationRoutingPolicy(canonicalDigest = ""),
            )
        }

    @Inject
    lateinit var serviceIntentArbiter: ServiceIntentArbiter

    @Inject
    lateinit var activeConnectionPolicyStore: ActiveConnectionPolicyStore

    @Before
    fun setUp() {
        val bindings = resetServiceLifecycleIntegrationBindings()
        appSettingsRepository = bindings.appSettingsRepository
        proxyPreferencesResolver = bindings.proxyPreferencesResolver
        proxyFactory = bindings.proxyFactory
        tun2SocksBridgeFactory = bindings.tun2SocksBridgeFactory
        serviceStateStore = bindings.serviceStateStore
        vpnTunnelSessionProvider = bindings.vpnTunnelSessionProvider
        networkHandoverMonitor = bindings.networkHandoverMonitor
        permissionWatchdog = bindings.permissionWatchdog
        hiltRule.inject()
        assertTrue(
            "VPN integration tests must use IntegrationTestOverrides.vpnTunnelSessionProvider and not platform consent UX.",
            vpnTunnelSessionProvider === IntegrationTestOverrides.vpnTunnelSessionProvider,
        )
    }

    @After
    fun tearDown() =
        runBlocking {
            stopIntegrationTestServices(appContext)
        }

    @Test
    fun proxyServiceStartAndStopTransitionsState() {
        runBlocking {
            startService(RipDpiProxyService::class.java)

            awaitStatus(AppStatus.Running, Mode.Proxy)
            assertEquals(listOf("proxy:start"), IntegrationTestOverrides.orderSnapshot())

            stopService()
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

            stopService()
            awaitStatus(AppStatus.Halted, Mode.Proxy)
            awaitClearedActivePolicy(Mode.Proxy)
        }
    }

    @Test
    fun proxyServiceUsesResolvedCommandLinePreferences() {
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                setEnableCmdSettings(true)
                setCmdArgs("--ip 127.0.0.1 --port 1092 --split host+1")
            }

            startService(RipDpiProxyService::class.java)
            awaitStatus(AppStatus.Running, Mode.Proxy)

            val preferences = IntegrationTestOverrides.proxyFactory.lastRuntime.lastPreferences
            assertTrue(
                "Expected command-line native payload but was $preferences",
                preferences
                    ?.toNativeConfigJson()
                    ?.contains("\"kind\":\"command_line\"") == true,
            )

            stopService()
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

            stopService()
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
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                proxyPort = 1091
                dnsMode = DnsModePlainUdp
                dnsIp = "9.9.9.9"
                ipv6Enable = true
            }

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            assertEquals("198.18.0.53", IntegrationTestOverrides.vpnTunnelSessionProvider.lastDns)
            assertEquals(
                "9.9.9.9",
                IntegrationTestOverrides.vpnTunnelSessionProvider.lastInterfaceSettings?.dnsIp,
            )
            assertEquals(true, IntegrationTestOverrides.vpnTunnelSessionProvider.lastIpv6)
            assertEquals(
                1090,
                IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.startedConfig
                    ?.socks5Port,
            )
            assertContainsSubsequence(
                IntegrationTestOverrides.orderSnapshot(),
                listOf("vpn:establish", "tunnel:start"),
            )
            assertTrue(IntegrationTestOverrides.orderSnapshot().contains("proxy:start"))

            stopService()
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
        runBlocking {
            startService(RipDpiVpnService::class.java)

            awaitStatus(AppStatus.Running, Mode.VPN)
            val activePolicy = awaitActivePolicy(Mode.VPN)

            assertEquals(Mode.VPN, activePolicy.mode)

            stopService()
            awaitStatus(AppStatus.Halted, Mode.VPN)
            awaitClearedActivePolicy(Mode.VPN)
        }
    }

    @Test
    fun vpnServicePublishesTunnelTelemetry() {
        runBlocking {
            val expectedStats =
                com.poyka.ripdpi.data.TunnelStats(
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
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.failOnStats =
                IOException("stats unavailable")

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitTelemetry(
                mode = Mode.VPN,
                status = AppStatus.Running,
                expectedStats =
                    com.poyka.ripdpi.data
                        .TunnelStats(),
            )
        }
    }

    @Test
    fun vpnServiceTunnelStartFailureEmitsFailureAndCleansUp() {
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.failOnStart =
                IllegalStateException("boom")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            awaitProxyStopCount(1)
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
        runBlocking {
            IntegrationTestOverrides.vpnTunnelSessionProvider.establishFailure =
                IllegalStateException("no session")

            startService(RipDpiVpnService::class.java)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)

            awaitProxyStopCount(1)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertEquals(
                listOf("proxy:start", "vpn:establish", "proxy:stop"),
                IntegrationTestOverrides.orderSnapshot(),
            )
        }
    }

    @Test
    fun vpnServiceProxyStartupFailureEmitsFailureBeforeTunnelStarts() {
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
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            IntegrationTestOverrides.proxyFactory.lastRuntime.complete(17)

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)
            awaitVpnSessionClosed()

            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertTrue(
                IntegrationTestOverrides.orderSnapshot().containsAll(
                    listOf("tunnel:stop", "vpn:session-close"),
                ),
            )
        }
    }

    @Test
    fun vpnServiceUnexpectedTunnelExitEmitsFailureAndStopsProxy() {
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                dnsMode = DnsModePlainUdp
                dnsIp = "9.9.9.9"
            }

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
            awaitProxyStopCount(1)
            awaitVpnSessionClosed()

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
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)

            stopService()
            awaitStatus(AppStatus.Halted, Mode.VPN)

            stopService()
            delay(200)

            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "tunnel:stop" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "proxy:stop" })
        }
    }

    @Test
    fun vpnServiceStickyRestartDoesNotDuplicateTunnelOrDowngradeEncryptedDns() {
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                dnsMode = DnsModeEncrypted
                dnsIp = "1.1.1.1"
            }
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitOrderEventCount("vpn:establish", 1)

            appContext.startService(Intent(appContext, RipDpiVpnService::class.java))
            delay(200)

            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "proxy:start" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "vpn:establish" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "tunnel:start" })
            assertEquals(
                "198.18.0.53",
                IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.startedConfig
                    ?.mapdnsAddress,
            )
        }
    }

    @Test
    fun vpnServiceNetworkHandoverRebuildsTunnelWithoutPlainDnsFallback() {
        runBlocking {
            IntegrationTestOverrides.appSettingsRepository.update {
                dnsMode = DnsModeEncrypted
                dnsIp = "1.1.1.1"
            }

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitHandoverMonitorSubscribers(1)
            awaitOrderEventCount("vpn:establish", 1)

            IntegrationTestOverrides.networkHandoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = testFingerprint(transport = "wifi", dnsServers = listOf("1.1.1.1")),
                    currentFingerprint = testFingerprint(transport = "cellular", dnsServers = listOf("9.9.9.9")),
                    classification = "transport_switch",
                    occurredAt = System.currentTimeMillis(),
                ),
            )

            awaitOrderEventCount("vpn:establish", 2)
            awaitOrderEventCount("tunnel:start", 2)

            val tunnelConfigs = IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.startedConfigs
            assertEquals(2, tunnelConfigs.size)
            tunnelConfigs.forEach { config ->
                assertEquals("198.18.0.53", config.mapdnsAddress)
                assertEquals(53, config.mapdnsPort)
            }
            assertEquals(2, IntegrationTestOverrides.orderSnapshot().count { it == "vpn:establish" })
            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun vpnServicePermissionWatchdogRevocationFailsClosed() {
        runBlocking {
            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitPermissionWatchdogSubscribers(1)

            IntegrationTestOverrides.permissionWatchdog.emit(
                PermissionChangeEvent(
                    kind = PermissionChangeEvent.KIND_VPN_CONSENT,
                    detectedAt = System.currentTimeMillis(),
                ),
            )

            awaitFailure(Sender.VPN)
            awaitStatus(AppStatus.Halted, Mode.VPN)
            awaitVpnSessionClosed()
            // TUN closes before the composed runtime finishes stopping its upstream proxy.
            awaitProxyStopCount(1)

            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
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

            stopService()
            awaitStatus(AppStatus.Halted, Mode.Proxy)

            stopService()
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
                    listenerAddress = "127.0.0.1:1090",
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

            stopService()
            awaitStatus(AppStatus.Halted, Mode.VPN)

            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
        }
    }

    @Test
    fun vpnServiceTelemetryFailureFailsClosedAndCleansUpTunnel() {
        runBlocking {
            IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.telemetryValue =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    activeSessions = 1,
                    tunnelStats =
                        com.poyka.ripdpi.data
                            .TunnelStats(txPackets = 5, rxPackets = 6),
                )

            startService(RipDpiVpnService::class.java)
            awaitStatus(AppStatus.Running, Mode.VPN)
            awaitActivePolicy(Mode.VPN)
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

            val failure = awaitFailure(Sender.VPN)
            assertEquals(FailureReason.NativeError("tunnel telemetry failed"), failure.reason)
            awaitStatus(AppStatus.Halted, Mode.VPN)
            awaitOrderEventCount("tunnel:stop", 1)
            awaitOrderEventCount("vpn:session-close", 1)
            awaitOrderEventCount("proxy:stop", 1)
            awaitProxyStopCount(1)
            awaitVpnSessionClosed()
            awaitClearedActivePolicy(Mode.VPN)
            awaitTelemetrySnapshot { snapshot ->
                snapshot.mode == Mode.VPN && snapshot.status == AppStatus.Halted
            }

            assertEquals(
                AppStatus.Halted to Mode.VPN,
                IntegrationTestOverrides.serviceStateStore.status.value,
            )
            assertEquals(AppStatus.Halted, IntegrationTestOverrides.serviceStateStore.telemetry.value.status)
            assertEquals(1, failedEvents(Sender.VPN).size)
            assertTrue(IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed)
            assertEquals(1, IntegrationTestOverrides.tun2SocksBridgeFactory.bridge.stopCount)
            assertEquals(1, IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount)
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "tunnel:stop" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "vpn:session-close" })
            assertEquals(1, IntegrationTestOverrides.orderSnapshot().count { it == "proxy:stop" })
            assertContainsSubsequence(
                IntegrationTestOverrides.orderSnapshot(),
                listOf("tunnel:stop", "vpn:session-close", "proxy:stop"),
            )
        }
    }

    private fun startService(serviceClass: Class<*>) {
        serviceIntentArbiter.userStart(
            action = {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, serviceClass).setAction(startAction).putExtra(
                        explicitUserIntentGenerationExtra,
                        serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                    ),
                )
            },
            isAccepted = { true },
        )
    }

    private fun stopService() {
        serviceIntentArbiter.userStop {
            val mode = IntegrationTestOverrides.serviceStateStore.status.value.second
            val serviceClass =
                if (mode == Mode.VPN) RipDpiVpnService::class.java else RipDpiProxyService::class.java
            appContext.startService(
                Intent(appContext, serviceClass).setAction(stopAction).putExtra(
                    explicitUserIntentGenerationExtra,
                    serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                ),
            )
        }
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

    private suspend fun awaitFailure(sender: Sender): ServiceEvent.Failed =
        withTimeout(10.seconds) {
            while (true) {
                val failure = failedEvents(sender).firstOrNull()
                if (failure != null) return@withTimeout failure
                delay(50)
            }
            error("Unreachable")
        }

    private fun failedEvents(sender: Sender): List<ServiceEvent.Failed> =
        IntegrationTestOverrides.serviceStateStore.eventHistory
            .filterIsInstance<ServiceEvent.Failed>()
            .filter { it.sender == sender }

    private suspend fun awaitProxyStopCount(expected: Int) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.proxyFactory.lastRuntime.stopCount != expected) {
                delay(50)
            }
        }
    }

    private suspend fun awaitVpnSessionClosed() {
        withTimeout(10.seconds) {
            while (!IntegrationTestOverrides.vpnTunnelSessionProvider.session.isClosed) {
                delay(50)
            }
        }
    }

    private suspend fun awaitTelemetry(
        mode: Mode,
        status: AppStatus,
        expectedStats: com.poyka.ripdpi.data.TunnelStats,
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
        predicate: (com.poyka.ripdpi.data.ServiceTelemetrySnapshot) -> Boolean,
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

    private suspend fun awaitOrderEventCount(
        event: String,
        expected: Int,
    ) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.orderSnapshot().count { it == event } != expected) {
                delay(50)
            }
        }
    }

    private suspend fun awaitHandoverMonitorSubscribers(expected: Int) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.networkHandoverMonitor.subscriberCount < expected) {
                delay(50)
            }
        }
    }

    private suspend fun awaitPermissionWatchdogSubscribers(expected: Int) {
        withTimeout(10.seconds) {
            while (IntegrationTestOverrides.permissionWatchdog.subscriberCount < expected) {
                delay(50)
            }
        }
    }

    private fun testFingerprint(
        transport: String,
        dnsServers: List<String>,
    ): NetworkFingerprint =
        NetworkFingerprint(
            transport = transport,
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = dnsServers,
        )

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
}
