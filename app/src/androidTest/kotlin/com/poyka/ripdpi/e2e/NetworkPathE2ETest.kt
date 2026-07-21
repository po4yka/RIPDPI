package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.SplitTunnelMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

private const val VpnEncryptedDnsAutoFailoverReasonPrefix = "vpn_encrypted_dns_auto_failover: "
private const val FixtureDnsTimeoutFaultDelayMs = 5_000L
private const val SoBindEvidenceProfileArg = "ripdpi.soBindEvidenceProfile"
private const val PhysicalSoBindEvidenceProfile = "physical_pixel_api37_kernel61"

@HiltAndroidTest
class NetworkPathE2ETest {
    @get:Rule(order = 0)
    val e2eFixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private var hiltInjected = false
    private lateinit var settingsBeforeTest: AppSettings
    private val startedServices = mutableSetOf<Class<*>>()
    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto

    @Before
    fun setUp() {
        assumeE2eFixtureConfigured()
        hiltRule.inject()
        hiltInjected = true
        settingsBeforeTest = runBlocking { appSettingsRepository.snapshot() }
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
        }
        awaitUntil(
            timeoutMs = 10_000L,
            failureMessage = { serviceStateDebugSummary(serviceStateStore) },
        ) {
            serviceStateStore.status.value.first == AppStatus.Halted &&
                serviceStateStore.telemetry.value.status == AppStatus.Halted
        }
        val environment = prepareE2eEnvironment(appContext)
        fixtureClient = environment.fixtureClient
        fixture = environment.fixture
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsIp = "1.1.1.1"
                ipv6Enable = false
                enableCmdSettings = false
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                setStrategyChains(emptyList(), emptyList())
            }
        }
    }

    @After
    fun tearDown() {
        try {
            if (hiltInjected) {
                runBlocking {
                    stopService(RipDpiProxyService::class.java)
                    stopService(RipDpiVpnService::class.java)
                    if (this@NetworkPathE2ETest::settingsBeforeTest.isInitialized) {
                        appSettingsRepository.replace(settingsBeforeTest)
                    }
                }
            }
            if (this::fixtureClient.isInitialized) {
                fixtureClient.resetEvents()
                fixtureClient.resetFaults()
            }
        } finally {
            clearTestProbeNetworkEligibility()
        }
    }

    @Test
    fun proxyServiceRoutesSocksAndTlsTrafficToLocalFixture() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
            }
        }

        val socksPayload = httpEchoPayload("fixture-proxy")
        val directTcpEcho = directTcpRoundTrip(fixture.androidHost, fixture.tcpEchoPort, socksPayload)
        assertEquals(
            "Direct fixture TCP path failed before proxy routing was exercised",
            socksPayload.decodeToString(),
            directTcpEcho.decodeToString(),
        )
        val directTlsResponse =
            directTlsHandshake(
                targetHost = fixture.androidHost,
                targetPort = fixture.tlsEchoPort,
                sniHost = fixture.fixtureDomain,
            )
        assertTrue(
            "Direct fixture TLS path failed before proxy routing was exercised: $directTlsResponse",
            directTlsResponse.contains("fixture tls ok"),
        )
        val directEvents = fixtureClient.events()
        assertTrue(
            "Direct fixture TCP path was not observed in fixture events: $directEvents",
            directEvents.any { it.service == "tcp_echo" && it.detail == "echo" },
        )
        assertTrue(
            "Direct fixture TLS path was not observed in fixture events: $directEvents",
            directEvents.any { it.service == "tls_echo" && it.sni == fixture.fixtureDomain },
        )
        fixtureClient.resetEvents()

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.Proxy, fixtureClient)

        val socksEcho = socksTcpRoundTrip(listenPort, fixture.androidHost, fixture.tcpEchoPort, socksPayload)
        assertEquals(socksPayload.decodeToString(), socksEcho.decodeToString())

        val tlsResponse =
            socksTlsHandshake(
                proxyPort = listenPort,
                targetHost = fixture.androidHost,
                targetPort = fixture.tlsEchoPort,
                sniHost = fixture.fixtureDomain,
            )
        val tlsEvents = fixtureClient.events()
        assertTrue(
            "Expected fixture TLS response, got: $tlsResponse; fixture events: $tlsEvents",
            tlsResponse.contains("fixture tls ok"),
        )

        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.Proxy &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions > 0
        }

        val events = tlsEvents
        assertTrue(events.any { it.service == "tcp_echo" && it.detail == "echo" })
        assertTrue(events.any { it.service == "tls_echo" && it.sni == fixture.fixtureDomain })

        stopService(RipDpiProxyService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.Proxy, fixtureClient)
    }

    @Test
    fun vpnServiceRoutesShellTrafficThroughTunnelAndUpdatesTelemetry() {
        assumeEmulatorLocalVpnFixture()
        ensureVpnConsentGranted(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)

        val payload = httpEchoPayloadText("vpn-e2e")
        val output = vpnTcpRoundTrip(fixture.androidHost, fixture.tcpEchoPort, payload)
        assertTrue("Expected VPN TCP round-trip, got: $output", output.contains("GET /vpn-e2e HTTP/1.1"))

        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.tunnelStats.txPackets > 0 &&
                snapshot.tunnelStats.rxPackets > 0 &&
                snapshot.proxyTelemetry.totalSessions > 0
        }

        stopService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.VPN, fixtureClient)
        awaitUntil {
            serviceStateStore.telemetry.value.status == AppStatus.Halted
        }
    }

    @Test
    fun vpnServiceRoutesHostnameTrafficThroughEncryptedDnsWithoutRestartLoop() {
        assumeEmulatorLocalVpnFixture()
        ensureVpnConsentGranted(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
            )
        }

        val restartCountBeforeStart = serviceStateStore.telemetry.value.restartCount
        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        val expectedStartupRestartCount = restartCountBeforeStart + 1
        awaitUntil {
            serviceStateStore.telemetry.value.restartCount == expectedStartupRestartCount
        }

        val baselineRestartCount = serviceStateStore.telemetry.value.restartCount
        val payload = httpEchoPayloadText("vpn-hostname")
        val output = vpnTcpRoundTrip(fixture.fixtureDomain, fixture.tcpEchoPort, payload)
        assertTrue(
            "Expected VPN hostname TCP round-trip, got: $output",
            output.contains("GET /vpn-hostname HTTP/1.1"),
        )

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            val events = fixtureClient.events()
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L &&
                snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank() &&
                snapshot.proxyTelemetry.totalSessions > 0 &&
                events.any { it.service == "dns_http" && it.detail.contains(fixture.fixtureDomain) } &&
                events.any { it.service == "tcp_echo" && it.detail == "echo" }
        }

        val events = fixtureClient.events()
        assertTrue(
            "Expected encrypted DNS fixture event for ${fixture.fixtureDomain}, got: $events",
            events.any { it.service == "dns_http" && it.detail.contains(fixture.fixtureDomain) },
        )
        assertTrue(
            "Expected hostname TCP echo fixture event, got: $events",
            events.any { it.service == "tcp_echo" && it.detail == "echo" },
        )

        awaitStable(
            timeoutMs = 5_000L,
            pollMs = 200L,
            stablePollCount = 5,
            failureMessage = {
                "VPN service did not remain stable after successful hostname traffic.\n" +
                    "baselineRestartCount=$baselineRestartCount\n" +
                    serviceStateDebugSummary(serviceStateStore, fixtureClient)
            },
        ) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.status == AppStatus.Running &&
                snapshot.mode == Mode.VPN &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L &&
                snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank() &&
                snapshot.restartCount == baselineRestartCount
        }
        val stableSnapshot = serviceStateStore.telemetry.value
        assertEquals(AppStatus.Running, stableSnapshot.status)
        assertEquals(Mode.VPN, stableSnapshot.mode)
        assertEquals(0L, stableSnapshot.tunnelTelemetry.dnsFailuresTotal)
        assertTrue(stableSnapshot.tunnelTelemetry.lastDnsError.isNullOrBlank())
        assertEquals(
            "VPN restart count increased after successful hostname traffic",
            baselineRestartCount,
            stableSnapshot.restartCount,
        )
    }

    @Test
    fun vpnServiceEncryptedDnsFaultBreaksHostnameShellRoundTrip() {
        assumeEmulatorLocalVpnFixture()
        ensureVpnConsentGranted(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
            )
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        fixtureClient.resetFaults()
        fixtureClient.resetEvents()
        val baselineTelemetry = serviceStateStore.telemetry.value
        val baselineRestartCount = baselineTelemetry.restartCount
        val baselineTunnelRecoveryRetryCount = baselineTelemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount
        val baselineDnsQueriesTotal = baselineTelemetry.tunnelTelemetry.dnsQueriesTotal
        val baselineDnsFailuresTotal = baselineTelemetry.tunnelTelemetry.dnsFailuresTotal
        val dnsFailureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val correlatedDnsRecovery =
            dnsFailureScope.async(start = CoroutineStart.UNDISPATCHED) {
                val failureSnapshot =
                    serviceStateStore.telemetry.first { snapshot ->
                        snapshot.tunnelTelemetry.dnsQueriesTotal > baselineDnsQueriesTotal &&
                            snapshot.tunnelTelemetry.dnsFailuresTotal > baselineDnsFailuresTotal &&
                            snapshot.tunnelTelemetry.lastDnsError.describesDnsTimeout()
                    }
                val expectedFallbackReason =
                    VpnEncryptedDnsAutoFailoverReasonPrefix +
                        requireNotNull(failureSnapshot.tunnelTelemetry.lastDnsError).trim()
                val recoverySnapshot =
                    serviceStateStore.telemetry.first { snapshot ->
                        snapshot.status == AppStatus.Running &&
                            snapshot.mode == Mode.VPN &&
                            snapshot.restartCount == baselineRestartCount &&
                            snapshot.runtimeFieldTelemetry.tunnelRecoveryRetryCount >
                            baselineTunnelRecoveryRetryCount &&
                            snapshot.tunnelTelemetry.resolverFallbackActive &&
                            snapshot.tunnelTelemetry.resolverFallbackReason == expectedFallbackReason
                    }
                failureSnapshot to recoverySnapshot
            }
        try {
            fixtureClient.setFault(
                FixtureFaultSpecDto(
                    target = FixtureFaultTargetDto.DNS_HTTP,
                    outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
                    scope = FixtureFaultScopeDto.PERSISTENT,
                    delayMs = FixtureDnsTimeoutFaultDelayMs,
                ),
            )

            val payload = httpEchoPayloadText("vpn-dns-timeout")
            val output = vpnTcpRoundTrip(fixture.fixtureDomain, fixture.tcpEchoPort, payload)

            assertFalse(output.contains("GET /vpn-dns-timeout HTTP/1.1"))
            val (dnsTimeoutSnapshot, recoverySnapshot) =
                runBlocking {
                    withTimeout(20_000L) {
                        correlatedDnsRecovery.await()
                    }
                }
            assertTrue(dnsTimeoutSnapshot.tunnelTelemetry.dnsQueriesTotal > baselineDnsQueriesTotal)
            assertTrue(dnsTimeoutSnapshot.tunnelTelemetry.dnsFailuresTotal > baselineDnsFailuresTotal)
            assertTrue(dnsTimeoutSnapshot.tunnelTelemetry.lastDnsError.describesDnsTimeout())
            assertTrue(
                recoverySnapshot.runtimeFieldTelemetry.tunnelRecoveryRetryCount >
                    baselineTunnelRecoveryRetryCount,
            )
            assertEquals(
                VpnEncryptedDnsAutoFailoverReasonPrefix +
                    requireNotNull(dnsTimeoutSnapshot.tunnelTelemetry.lastDnsError).trim(),
                recoverySnapshot.tunnelTelemetry.resolverFallbackReason,
            )
            val events = fixtureClient.events()
            assertTrue(events.any { it.service == "dns_http" && it.detail == "fault:DnsTimeout" })
            assertTrue(events.none { it.service == "tcp_echo" && it.detail == "echo" })
        } finally {
            dnsFailureScope.cancel()
        }
    }

    @Test
    fun proxyServicePropagatesTcpResetFaultFromFixture() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.Proxy, fixtureClient)
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.TCP_ECHO,
                outcome = FixtureFaultOutcomeDto.TCP_RESET,
            ),
        )

        val payload = httpEchoPayload("fixture-reset")
        val result = runCatching { socksTcpRoundTrip(listenPort, fixture.androidHost, fixture.tcpEchoPort, payload) }

        if (result.isSuccess) {
            assertFalse(result.getOrThrow().contentEquals(payload))
        } else {
            assertTrue(result.exceptionOrNull() != null)
        }
        assertTrue(
            fixtureClient.events().any { event ->
                event.service == "tcp_echo" && event.detail.contains("TcpReset", ignoreCase = true)
            },
        )
    }

    @Test
    fun proxyServicePropagatesTlsAbortFaultFromFixture() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.Proxy, fixtureClient)
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.TLS_ECHO,
                outcome = FixtureFaultOutcomeDto.TLS_ABORT,
            ),
        )

        val error =
            runCatching {
                socksTlsHandshake(
                    proxyPort = listenPort,
                    targetHost = fixture.androidHost,
                    targetPort = fixture.tlsEchoPort,
                    sniHost = fixture.fixtureDomain,
                )
            }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(
            fixtureClient.events().any { event ->
                event.service == "tls_echo" && event.detail.contains("tls_abort", ignoreCase = true)
            },
        )
    }

    @Test
    fun vpnServiceSurfacedFixtureFaultBreaksShellRoundTrip() {
        assumeEmulatorLocalVpnFixture()
        ensureVpnConsentGranted(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.TCP_ECHO,
                outcome = FixtureFaultOutcomeDto.TCP_RESET,
            ),
        )

        val payload = httpEchoPayloadText("vpn-reset")
        val output = vpnTcpRoundTrip(fixture.androidHost, fixture.tcpEchoPort, payload)

        assertFalse(output.contains("GET /vpn-reset HTTP/1.1"))
        assertTrue(
            fixtureClient.events().any { event ->
                event.service == "tcp_echo" && event.detail.contains("TcpReset", ignoreCase = true)
            },
        )
    }

    @Test
    fun emulatorSocketBindProbeRunsInStandaloneTestUidProcess() {
        assumeTrue("SO_BINDTODEVICE runtime smoke requires an emulator", isLikelyEmulator())
        assertEquals(
            "Expected emulator /sys/class/net/eth0",
            "/sys/class/net/eth0",
            execShell("ls -d /sys/class/net/eth0").trim(),
        )

        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val testUid = assertDistinctPackageUids(appContext.packageName, testPackage)
        val nonce =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val tcpPayload = httpEchoPayloadText("so-bind-emulator-tcp-$nonce")
        val udpPayload = "so-bind-emulator-udp-$nonce"

        val tcpResult =
            testProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = tcpPayload,
                bindDevice = "eth0",
            )
        assertTrue(
            "Emulator bound TCP failed stage=${tcpResult.failureStage} kind=${tcpResult.failureKind} " +
                "errno=${tcpResult.errno} class=${tcpResult.errorClass}",
            tcpResult.ok,
        )
        assertEquals(tcpPayload, tcpResult.response)
        assertEquals("eth0", tcpResult.boundDevice)
        assertEquals(testUid, tcpResult.probeUid)
        assertTrue("TCP probe receiver must run outside instrumentation", tcpResult.probePid != Process.myPid())

        val udpResult =
            testProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = udpPayload,
                bindDevice = "eth0",
            )
        assertTrue(
            "Emulator bound UDP failed stage=${udpResult.failureStage} kind=${udpResult.failureKind} " +
                "errno=${udpResult.errno}",
            udpResult.ok,
        )
        assertEquals(udpPayload, udpResult.response)
        assertEquals("eth0", udpResult.boundDevice)
        assertEquals(testUid, udpResult.probeUid)
        assertEquals(tcpResult.probePid, udpResult.probePid)

        val appTcpPayload = httpEchoPayloadText("so-bind-emulator-app-tcp-$nonce")
        val appUdpPayload = "so-bind-emulator-app-udp-$nonce"
        val appTcp =
            instrumentationProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = appTcpPayload,
                bindDevice = "eth0",
            )
        val appUdp =
            instrumentationProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = appUdpPayload,
                bindDevice = "eth0",
            )
        assertTrue("Emulator app-UID TCP JNI control failed kind=${appTcp.failureKind}", appTcp.ok)
        assertEquals(appTcpPayload, appTcp.response)
        assertEquals("eth0", appTcp.boundDevice)
        assertTrue("Emulator app-UID UDP JNI control failed kind=${appUdp.failureKind}", appUdp.ok)
        assertEquals(appUdpPayload, appUdp.response)
        assertEquals("eth0", appUdp.boundDevice)
        assertEquals(Process.myUid(), appTcp.probeUid)
        assertEquals(Process.myPid(), appTcp.probePid)
    }

    @Test
    fun vpnServiceDeniesExcludedTestUidBoundToTun0() {
        assumePhysicalSoBindEvidencePrerequisites()
        ensureVpnConsentGranted(appContext)
        val testPackage =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .context
                .packageName
        val appPackage = appContext.packageName
        val testUid = assertDistinctPackageUids(appPackage, testPackage)
        val nonce =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

        val tcpPayload = httpEchoPayloadText("so-bind-direct-tcp-$nonce")
        val directTcp =
            testProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = tcpPayload,
            )
        assertTrue(
            "Unbound test-process TCP control failed kind=${directTcp.failureKind} errno=${directTcp.errno}",
            directTcp.ok,
        )
        assertEquals(tcpPayload, directTcp.response)
        assertEquals(testUid, directTcp.probeUid)
        assertTrue("Probe receiver must run outside the instrumentation process", directTcp.probePid != Process.myPid())

        val udpPayload = "so-bind-direct-udp-$nonce"
        val directUdp =
            testProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = udpPayload,
            )
        assertTrue(
            "Unbound test-process UDP control failed kind=${directUdp.failureKind} errno=${directUdp.errno}",
            directUdp.ok,
        )
        assertEquals(udpPayload, directUdp.response)
        assertEquals(testUid, directUdp.probeUid)
        assertEquals(directTcp.probePid, directUdp.probePid)
        fixtureClient.resetEvents()

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
                fullTunnelMode = true
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        val allowedBaselineTelemetry = serviceStateStore.telemetry.value
        val allowedTcpPayload = httpEchoPayloadText("so-bind-allowed-tcp-$nonce")
        val allowedUdpPayload = "so-bind-allowed-udp-$nonce"

        val allowedTcp =
            testProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = allowedTcpPayload,
                bindDevice = "tun0",
            )
        assertEquals(
            "Allowed bound TCP did not retain SO_BINDTODEVICE " +
                "ok=${allowedTcp.ok} kind=${allowedTcp.failureKind} " +
                "stage=${allowedTcp.failureStage} errno=${allowedTcp.errno} " +
                "error=${allowedTcp.errorClass}: ${allowedTcp.errorMessage}",
            "tun0",
            allowedTcp.boundDevice,
        )
        assertTrue(
            "Allowed bound TCP did not reach fixture kind=${allowedTcp.failureKind} errno=${allowedTcp.errno}",
            allowedTcp.ok,
        )
        assertEquals(allowedTcpPayload, allowedTcp.response)
        assertTrue("Allowed TCP probe must expose a concrete source tuple", allowedTcp.localPort != null)
        val allowedUdp =
            testProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = allowedUdpPayload,
                bindDevice = "tun0",
            )
        assertEquals("tun0", allowedUdp.boundDevice)
        assertTrue(
            "Allowed bound UDP did not reach fixture kind=${allowedUdp.failureKind} errno=${allowedUdp.errno}",
            allowedUdp.ok,
        )
        assertEquals(allowedUdpPayload, allowedUdp.response)
        assertTrue("Allowed UDP probe must expose a concrete source tuple", allowedUdp.localPort != null)
        awaitUntil(
            timeoutMs = 5_000L,
            failureMessage = { redactedTunnelSummary() },
        ) {
            val delta = serviceStateStore.telemetry.value.packetSmokeDeltaFrom(allowedBaselineTelemetry)
            delta.txPackets > 0 && delta.rxPackets > 0
        }
        val allowedEvents = fixtureClient.events()
        val allowedTcpEvents =
            allowedEvents.count {
                it.matchesEcho(
                    service = "tcp_echo",
                    protocol = "tcp",
                    targetPort = fixture.tcpEchoPort,
                    payloadBytes = allowedTcpPayload.toByteArray().size,
                )
            }
        val allowedUdpEvents =
            allowedEvents.count {
                it.matchesEcho(
                    service = "udp_echo",
                    protocol = "udp",
                    targetPort = fixture.udpEchoPort,
                    payloadBytes = allowedUdpPayload.toByteArray().size,
                )
            }
        assertTrue(
            "Allowed bound TCP was not observed by fixture tcpEvents=$allowedTcpEvents udpEvents=$allowedUdpEvents",
            allowedTcpEvents > 0,
        )
        assertTrue(
            "Allowed bound UDP was not observed by fixture tcpEvents=$allowedTcpEvents udpEvents=$allowedUdpEvents",
            allowedUdpEvents > 0,
        )

        stopService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.VPN, fixtureClient)
        fixtureClient.resetEvents()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
                fullTunnelMode = false
                setSplitTunnelMode(SplitTunnelMode.Exclude)
                clearSplitTunnelPackages()
                addSplitTunnelPackages(testPackage)
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        fixtureClient.resetEvents()
        val baselineTelemetry = serviceStateStore.telemetry.value
        val deniedTcpPayload = httpEchoPayloadText("so-bind-denied-tcp-$nonce")
        val deniedUdpPayload = "so-bind-denied-udp-$nonce"

        val boundTcp =
            testProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = deniedTcpPayload,
                readTimeoutMs = 1_000L,
                throwOnBroadcastTimeout = false,
                bindDevice = "tun0",
            )
        assertEquals("tun0", boundTcp.boundDevice)
        assertFalse(
            "Bound TCP unexpectedly reached fixture kind=${boundTcp.failureKind} errno=${boundTcp.errno}",
            boundTcp.ok,
        )
        assertEquals(
            "Bound TCP denial should be a canonical reset kind=${boundTcp.failureKind} errno=${boundTcp.errno}",
            "CONNECTION_RESET",
            boundTcp.failureKind,
        )

        val boundUdp =
            testProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = deniedUdpPayload,
                timeoutMs = 1_000L,
                bindDevice = "tun0",
            )
        assertEquals("tun0", boundUdp.boundDevice)
        assertFalse(
            "Bound UDP unexpectedly reached fixture kind=${boundUdp.failureKind} errno=${boundUdp.errno}",
            boundUdp.ok,
        )
        assertEquals(
            "Bound UDP denial should drop until timeout kind=${boundUdp.failureKind} errno=${boundUdp.errno}",
            "TIMEOUT",
            boundUdp.failureKind,
        )

        awaitUntil(
            timeoutMs = 5_000L,
            failureMessage = { redactedTunnelSummary() },
        ) {
            val delta = serviceStateStore.telemetry.value.packetSmokeDeltaFrom(baselineTelemetry)
            delta.txPackets > 0 || delta.rxPackets > 0
        }
        val deniedEvents = fixtureClient.events()
        val deniedTcpEvents = deniedEvents.count { it.service == "tcp_echo" }
        val deniedUdpEvents = deniedEvents.count { it.service == "udp_echo" }
        assertTrue(
            "Denied SO_BINDTODEVICE traffic reached fixture tcpEvents=$deniedTcpEvents udpEvents=$deniedUdpEvents",
            deniedTcpEvents == 0 && deniedUdpEvents == 0,
        )

        val livenessTcpPayload = httpEchoPayloadText("so-bind-liveness-tcp-$nonce")
        val livenessUdpPayload = "so-bind-liveness-udp-$nonce"
        val livenessTcp =
            testProcessTcpRoundTrip(
                host = fixture.androidHost,
                port = fixture.tcpEchoPort,
                payload = livenessTcpPayload,
            )
        val livenessUdp =
            testProcessUdpRoundTrip(
                host = fixture.androidHost,
                port = fixture.udpEchoPort,
                payload = livenessUdpPayload,
            )
        assertTrue("Fixture TCP liveness failed after denial kind=${livenessTcp.failureKind}", livenessTcp.ok)
        assertEquals(livenessTcpPayload, livenessTcp.response)
        assertTrue("Fixture UDP liveness failed after denial kind=${livenessUdp.failureKind}", livenessUdp.ok)
        assertEquals(livenessUdpPayload, livenessUdp.response)
        val livenessEvents = fixtureClient.events()
        assertTrue(
            "Fixture did not record the correlated post-denial TCP control",
            livenessEvents.any {
                it.matchesEcho(
                    service = "tcp_echo",
                    protocol = "tcp",
                    targetPort = fixture.tcpEchoPort,
                    payloadBytes = livenessTcpPayload.toByteArray().size,
                )
            },
        )
        assertTrue(
            "Fixture did not record the correlated post-denial UDP control",
            livenessEvents.any {
                it.matchesEcho(
                    service = "udp_echo",
                    protocol = "udp",
                    targetPort = fixture.udpEchoPort,
                    payloadBytes = livenessUdpPayload.toByteArray().size,
                )
            },
        )
    }

    private fun startService(serviceClass: Class<*>) {
        startedServices += serviceClass
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, serviceClass).setAction(startAction),
        )
    }

    private fun stopService(serviceClass: Class<*>) {
        if (startedServices.remove(serviceClass)) {
            appContext.startService(Intent(appContext, serviceClass).setAction(stopAction))
        } else {
            appContext.stopService(Intent(appContext, serviceClass))
        }
    }

    private fun assumeEmulatorLocalVpnFixture() {
        assumeTrue(
            "Local fixture VPN round-trips require emulator loopback routing; physical devices use packet-smoke " +
                "physical-indirect VPN coverage.",
            isLikelyEmulator(),
        )
    }

    private fun vpnTcpRoundTrip(
        host: String,
        port: Int,
        payload: String,
    ): String = testProcessTcpRoundTrip(host, port, payload).response.orEmpty()

    private fun httpEchoPayload(pathToken: String): ByteArray =
        "GET /$pathToken HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\nConnection: close\r\n\r\n".encodeToByteArray()

    private fun httpEchoPayloadText(pathToken: String): String =
        "GET /$pathToken HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\nConnection: close\r\n\r\n"

    private fun assertDistinctPackageUids(
        appPackage: String,
        testPackage: String,
    ): Int {
        val packageManager = appContext.packageManager
        val appUid = packageManager.getApplicationInfo(appPackage, 0).uid
        val testUid = packageManager.getApplicationInfo(testPackage, 0).uid
        assertTrue("Expected distinct app/test UIDs", appUid != testUid)
        return testUid
    }

    private fun instrumentationProcessTcpRoundTrip(
        host: String,
        port: Int,
        payload: String,
        bindDevice: String,
    ): AppProcessTcpProbeResult {
        val extras = Bundle()
        TestSocketBinder.tcpRoundTrip(
            host,
            port,
            payload,
            3_000,
            5_000,
            bindDevice,
            extras,
        )
        return AppProcessTcpProbeResult(
            host = host,
            port = port,
            ok = extras.getBoolean(ExtraOk, false),
            localPort = extras.optionalProbeInt(ExtraLocalPort),
            response = extras.getString(ExtraResponse),
            boundDevice = extras.getString(ExtraBoundDevice),
            failureKind = extras.getString(ExtraFailureKind),
            failureStage = extras.getString(ExtraFailureStage),
            errno = extras.optionalProbeInt(ExtraErrno),
            probePid = Process.myPid(),
            probeUid = Process.myUid(),
        )
    }

    private fun instrumentationProcessUdpRoundTrip(
        host: String,
        port: Int,
        payload: String,
        bindDevice: String,
    ): AppProcessUdpProbeResult {
        val extras = Bundle()
        TestSocketBinder.udpRoundTrip(
            host,
            port,
            payload,
            5_000,
            bindDevice,
            extras,
        )
        return AppProcessUdpProbeResult(
            host = host,
            port = port,
            ok = extras.getBoolean(ExtraOk, false),
            localPort = extras.optionalProbeInt(ExtraLocalPort),
            response = extras.getString(ExtraResponse),
            boundDevice = extras.getString(ExtraBoundDevice),
            failureKind = extras.getString(ExtraFailureKind),
            failureStage = extras.getString(ExtraFailureStage),
            errno = extras.optionalProbeInt(ExtraErrno),
            probePid = Process.myPid(),
            probeUid = Process.myUid(),
        )
    }

    private fun assumePhysicalSoBindEvidencePrerequisites() {
        assumeTrue(
            "SO_BINDTODEVICE evidence is physical-only; emulator runs are preflight, not physical evidence",
            !isLikelyEmulator(),
        )
        val evidenceProfile =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString(SoBindEvidenceProfileArg)
        assumeTrue(
            "SO_BINDTODEVICE evidence requires an explicit dedicated physical profile",
            evidenceProfile == PhysicalSoBindEvidenceProfile,
        )
        assumeTrue("SO_BINDTODEVICE evidence is qualified on Android API 37", Build.VERSION.SDK_INT == 37)
        val kernelRelease = System.getProperty("os.version").orEmpty()
        assumeTrue(
            "SO_BINDTODEVICE evidence is qualified on the Pixel 6.1 kernel family",
            kernelRelease.startsWith("6.1."),
        )
    }

    private fun redactedTunnelSummary(): String {
        val telemetry = serviceStateStore.telemetry.value
        return "mode=${telemetry.mode} status=${telemetry.status} " +
            "txPackets=${telemetry.tunnelStats.txPackets} rxPackets=${telemetry.tunnelStats.rxPackets}"
    }
}

private fun Bundle.optionalProbeInt(key: String): Int? = getInt(key).takeIf { containsKey(key) }

private fun FixtureEventDto.matchesEcho(
    service: String,
    protocol: String,
    targetPort: Int,
    payloadBytes: Int,
): Boolean =
    this.service == service &&
        this.protocol == protocol &&
        detail == "echo" &&
        bytes == payloadBytes &&
        target.substringAfterLast(':').toIntOrNull() == targetPort

private fun String?.describesDnsTimeout(): Boolean =
    this != null &&
        (contains("timeout", ignoreCase = true) || contains("timed out", ignoreCase = true))
