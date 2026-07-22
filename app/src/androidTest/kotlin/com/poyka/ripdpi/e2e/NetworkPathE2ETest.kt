package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.OsConstants
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.BuildConfig
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject

private const val VpnEncryptedDnsAutoFailoverReasonPrefix = "vpn_encrypted_dns_auto_failover: "
private const val FixtureDnsTimeoutFaultDelayMs = 5_000L
private const val SoBindEvidenceProfileArg = "ripdpi.soBindEvidenceProfile"
private const val SoBindIpv6HostArg = "ripdpi.soBindIpv6Host"
private const val SoBindTcpEchoPortArg = "ripdpi.soBindTcpEchoPort"
private const val SoBindUdpEchoPortArg = "ripdpi.soBindUdpEchoPort"
private const val SoBindRunIdArg = "ripdpi.soBindRunId"
private const val SoBindSourceShaArg = "ripdpi.soBindSourceSha"
private const val SoBindAppApkSha256Arg = "ripdpi.soBindAppApkSha256"
private const val SoBindTestApkSha256Arg = "ripdpi.soBindTestApkSha256"
private const val PhysicalSoBindEvidenceProfile = "physical_pixel_api37_kernel61"
private const val PhysicalSoBindEvidenceFile = "so-bind-physical-evidence.json"
private const val PhysicalSoBindEvidenceVersion = "android_so_bind_physical_evidence_v2"

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
    fun dualStackFixtureEventsClassifyUdpByPeerFamily() {
        val mappedIpv4 =
            FixtureEventDto(
                service = "udp_echo",
                protocol = "udp",
                peer = "[::ffff:192.0.2.10]:52123",
                target = "[::]:46002",
                detail = "echo",
                bytes = 8,
                createdAt = 1L,
            )
        val ulaIpv6 = mappedIpv4.copy(peer = "[fd00::10]:52124")

        assertFalse(mappedIpv4.peerAddressIsIpv6() ?: true)
        assertTrue(ulaIpv6.peerAddressIsIpv6() ?: false)
        assertTrue(mappedIpv4.matchesEcho("udp_echo", "udp", 46002, 8))
        assertFalse(mappedIpv4.matchesEcho("udp_echo", "udp", 46003, 8))
        assertFalse(mappedIpv4.matchesEcho("udp_echo", "udp", 46002, 9))
    }

    @Test
    fun vpnServiceDeniesExcludedTestUidBoundToTun0() {
        assumePhysicalSoBindEvidencePrerequisites()
        ensureVpnConsentGranted(appContext)
        val ipv6Host = requirePhysicalIpv6FixtureHost()
        requirePhysicalFixturePort(SoBindTcpEchoPortArg, fixture.tcpEchoPort)
        requirePhysicalFixturePort(SoBindUdpEchoPortArg, fixture.udpEchoPort)
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
        val families =
            listOf(
                PhysicalSoBindFamily("ipv4", fixture.androidHost, false, 0),
                PhysicalSoBindFamily("ipv6", ipv6Host, true, 41),
            )
        val counters = families.associate { it.id to PhysicalSoBindCounters() }

        families.forEach { family ->
            val tcpPayload = physicalSoBindPayload("direct", "tcp", family, nonce)
            val directTcp =
                testProcessTcpRoundTrip(
                    host = family.host,
                    port = fixture.tcpEchoPort,
                    payload = tcpPayload,
                )
            assertSuccessfulPhysicalProbe(
                "${family.id} unbound TCP",
                directTcp.ok,
                directTcp.failureKind,
                directTcp.errno,
            )
            assertEquals(tcpPayload, directTcp.response)
            assertEquals(testUid, directTcp.probeUid)
            assertTrue("Probe receiver must run outside instrumentation", directTcp.probePid != Process.myPid())
            assertPhysicalSourceFamily(family, directTcp.localAddress)

            val udpPayload = physicalSoBindPayload("direct", "udp", family, nonce)
            val directUdp =
                testProcessUdpRoundTrip(
                    host = family.host,
                    port = fixture.udpEchoPort,
                    payload = udpPayload,
                )
            assertSuccessfulPhysicalProbe(
                "${family.id} unbound UDP",
                directUdp.ok,
                directUdp.failureKind,
                directUdp.errno,
            )
            assertEquals(udpPayload, directUdp.response)
            assertEquals(testUid, directUdp.probeUid)
            assertEquals(directTcp.probePid, directUdp.probePid)
            assertPhysicalSourceFamily(family, directUdp.localAddress)
            counters.getValue(family.id).apply {
                directTcpRoundTrips = 1
                directUdpRoundTrips = 1
            }
        }
        val directEvents = fixtureClient.events()
        families.forEach { family ->
            val familyCounters = counters.getValue(family.id)
            familyCounters.directTcpFixtureEvents =
                directEvents.countPhysicalEcho("tcp_echo", "tcp", fixture.tcpEchoPort, "direct", "tcp", family, nonce)
            familyCounters.directUdpFixtureEvents =
                directEvents.countPhysicalEcho("udp_echo", "udp", fixture.udpEchoPort, "direct", "udp", family, nonce)
            assertTrue("${family.id} direct TCP was not observed by fixture", familyCounters.directTcpFixtureEvents > 0)
            assertTrue("${family.id} direct UDP was not observed by fixture", familyCounters.directUdpFixtureEvents > 0)
        }
        fixtureClient.resetEvents()

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
                ipv6Enable = true
                fullTunnelMode = true
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
        val allowedBaselineTelemetry = serviceStateStore.telemetry.value
        families.forEach { family ->
            val tcpPayload = physicalSoBindPayload("allowed", "tcp", family, nonce)
            val allowedTcp =
                testProcessTcpRoundTrip(
                    host = family.host,
                    port = fixture.tcpEchoPort,
                    payload = tcpPayload,
                    bindDevice = "tun0",
                )
            assertEquals("${family.id} allowed TCP did not retain SO_BINDTODEVICE", "tun0", allowedTcp.boundDevice)
            assertSuccessfulPhysicalProbe(
                "${family.id} allowed bound TCP",
                allowedTcp.ok,
                allowedTcp.failureKind,
                allowedTcp.errno,
            )
            assertEquals(tcpPayload, allowedTcp.response)
            assertTrue("${family.id} allowed TCP source port is missing", allowedTcp.localPort != null)
            assertPhysicalSourceFamily(family, allowedTcp.localAddress)

            val udpPayload = physicalSoBindPayload("allowed", "udp", family, nonce)
            val allowedUdp =
                testProcessUdpRoundTrip(
                    host = family.host,
                    port = fixture.udpEchoPort,
                    payload = udpPayload,
                    bindDevice = "tun0",
                )
            assertEquals("${family.id} allowed UDP did not retain SO_BINDTODEVICE", "tun0", allowedUdp.boundDevice)
            assertSuccessfulPhysicalProbe(
                "${family.id} allowed bound UDP",
                allowedUdp.ok,
                allowedUdp.failureKind,
                allowedUdp.errno,
            )
            assertEquals(udpPayload, allowedUdp.response)
            assertTrue("${family.id} allowed UDP source port is missing", allowedUdp.localPort != null)
            assertPhysicalSourceFamily(family, allowedUdp.localAddress)
            counters.getValue(family.id).apply {
                allowedTcpRoundTrips = 1
                allowedUdpRoundTrips = 1
            }
        }
        awaitUntil(
            timeoutMs = 5_000L,
            failureMessage = { redactedTunnelSummary() },
        ) {
            val delta = serviceStateStore.telemetry.value.packetSmokeDeltaFrom(allowedBaselineTelemetry)
            delta.txPackets > 0 && delta.rxPackets > 0
        }
        val allowedEvents = fixtureClient.events()
        families.forEach { family ->
            val familyCounters = counters.getValue(family.id)
            familyCounters.allowedTcpFixtureEvents =
                allowedEvents.countPhysicalEcho("tcp_echo", "tcp", fixture.tcpEchoPort, "allowed", "tcp", family, nonce)
            familyCounters.allowedUdpFixtureEvents =
                allowedEvents.countPhysicalEcho("udp_echo", "udp", fixture.udpEchoPort, "allowed", "udp", family, nonce)
            assertTrue(
                "${family.id} allowed TCP was not observed by fixture",
                familyCounters.allowedTcpFixtureEvents > 0,
            )
            assertTrue(
                "${family.id} allowed UDP was not observed by fixture",
                familyCounters.allowedUdpFixtureEvents > 0,
            )
        }

        stopService(RipDpiVpnService::class.java)
        awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.VPN, fixtureClient)
        fixtureClient.resetEvents()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = "1.1.1.1"
                ipv6Enable = true
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
        families.forEach { family ->
            val deniedTcp =
                testProcessTcpRoundTrip(
                    host = family.host,
                    port = fixture.tcpEchoPort,
                    payload = physicalSoBindPayload("denied", "tcp", family, nonce),
                    readTimeoutMs = 1_000L,
                    throwOnBroadcastTimeout = false,
                    bindDevice = "tun0",
                )
            assertEquals("${family.id} denied TCP did not retain SO_BINDTODEVICE", "tun0", deniedTcp.boundDevice)
            assertFalse("${family.id} bound TCP unexpectedly reached fixture", deniedTcp.ok)
            val deniedTcpAtNetworkStage = deniedTcp.failureStage in setOf("connect", "send", "receive")
            val deniedTcpOutcomeIsBlocked =
                deniedTcp.failureKind in setOf("CONNECTION_RESET", "TIMEOUT") ||
                    (
                        deniedTcp.failureKind == "ERRNO" &&
                            deniedTcp.failureStage == "connect" &&
                            deniedTcp.errno in setOf(OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH)
                    )
            assertTrue(
                "${family.id} bound TCP denial must be reset, timeout, or unreachable " +
                    "kind=${deniedTcp.failureKind} stage=${deniedTcp.failureStage} errno=${deniedTcp.errno}",
                deniedTcpOutcomeIsBlocked,
            )
            assertTrue(
                "${family.id} bound TCP denial occurred before the network operation " +
                    "stage=${deniedTcp.failureStage} errno=${deniedTcp.errno}",
                deniedTcpAtNetworkStage && deniedTcp.errno != null,
            )

            val deniedUdp =
                testProcessUdpRoundTrip(
                    host = family.host,
                    port = fixture.udpEchoPort,
                    payload = physicalSoBindPayload("denied", "udp", family, nonce),
                    timeoutMs = 1_000L,
                    bindDevice = "tun0",
                )
            assertEquals("${family.id} denied UDP did not retain SO_BINDTODEVICE", "tun0", deniedUdp.boundDevice)
            assertFalse("${family.id} bound UDP unexpectedly reached fixture", deniedUdp.ok)
            val deniedUdpAtNetworkStage = deniedUdp.failureStage in setOf("connect", "send", "receive")
            val deniedUdpOutcomeIsBlocked =
                deniedUdp.failureKind == "TIMEOUT" ||
                    (
                        deniedUdp.failureKind == "ERRNO" &&
                            deniedUdp.failureStage == "connect" &&
                            deniedUdp.errno in setOf(OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH)
                    )
            assertTrue(
                "${family.id} bound UDP denial must be timeout or unreachable " +
                    "kind=${deniedUdp.failureKind} stage=${deniedUdp.failureStage} errno=${deniedUdp.errno}",
                deniedUdpOutcomeIsBlocked,
            )
            assertTrue(
                "${family.id} bound UDP denial occurred before the network operation " +
                    "stage=${deniedUdp.failureStage} errno=${deniedUdp.errno}",
                deniedUdpAtNetworkStage && deniedUdp.errno != null,
            )
            counters.getValue(family.id).apply {
                deniedTcpBlockedAttempts = 1
                deniedTcpFailureKind = deniedTcp.failureKind
                deniedTcpFailureStage = deniedTcp.failureStage
                deniedTcpErrno = deniedTcp.errno
                deniedUdpBlockedAttempts = 1
                deniedUdpFailureKind = deniedUdp.failureKind
                deniedUdpFailureStage = deniedUdp.failureStage
                deniedUdpErrno = deniedUdp.errno
            }
        }

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

        families.forEach { family ->
            val livenessTcpPayload = physicalSoBindPayload("liveness", "tcp", family, nonce)
            val livenessUdpPayload = physicalSoBindPayload("liveness", "udp", family, nonce)
            val livenessTcp = testProcessTcpRoundTrip(family.host, fixture.tcpEchoPort, livenessTcpPayload)
            val livenessUdp = testProcessUdpRoundTrip(family.host, fixture.udpEchoPort, livenessUdpPayload)
            assertSuccessfulPhysicalProbe(
                "${family.id} post-denial TCP",
                livenessTcp.ok,
                livenessTcp.failureKind,
                livenessTcp.errno,
            )
            assertEquals(livenessTcpPayload, livenessTcp.response)
            assertPhysicalSourceFamily(family, livenessTcp.localAddress)
            assertSuccessfulPhysicalProbe(
                "${family.id} post-denial UDP",
                livenessUdp.ok,
                livenessUdp.failureKind,
                livenessUdp.errno,
            )
            assertEquals(livenessUdpPayload, livenessUdp.response)
            assertPhysicalSourceFamily(family, livenessUdp.localAddress)
            counters.getValue(family.id).apply {
                livenessTcpRoundTrips = 1
                livenessUdpRoundTrips = 1
            }
        }
        val livenessEvents = fixtureClient.events()
        families.forEach { family ->
            val familyCounters = counters.getValue(family.id)
            familyCounters.livenessTcpFixtureEvents =
                livenessEvents.countPhysicalEcho(
                    "tcp_echo",
                    "tcp",
                    fixture.tcpEchoPort,
                    "liveness",
                    "tcp",
                    family,
                    nonce,
                )
            familyCounters.livenessUdpFixtureEvents =
                livenessEvents.countPhysicalEcho(
                    "udp_echo",
                    "udp",
                    fixture.udpEchoPort,
                    "liveness",
                    "udp",
                    family,
                    nonce,
                )
            assertTrue("${family.id} post-denial TCP was not observed", familyCounters.livenessTcpFixtureEvents > 0)
            assertTrue("${family.id} post-denial UDP was not observed", familyCounters.livenessUdpFixtureEvents > 0)
            familyCounters.deniedTcpFixtureEvents = 0
            familyCounters.deniedUdpFixtureEvents = 0
        }
        writePhysicalSoBindEvidence(families, counters)
    }

    private fun requirePhysicalIpv6FixtureHost(): String {
        val rawHost =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString(SoBindIpv6HostArg)
                ?.trim()
                .orEmpty()
        assertTrue("Physical SO_BIND evidence requires a numeric IPv6 fixture host", rawHost.contains(':'))
        val address = runCatching { InetAddress.getByName(rawHost) }.getOrNull()
        assertTrue("Physical SO_BIND IPv6 fixture host is malformed", address is Inet6Address)
        require(address is Inet6Address)
        assertTrue(
            "Physical SO_BIND IPv6 fixture must be non-loopback, non-link-local, and non-multicast",
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isMulticastAddress,
        )
        return requireNotNull(address.hostAddress).substringBefore('%')
    }

    private fun requirePhysicalFixturePort(
        argument: String,
        expected: Int,
    ) {
        val value =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString(argument)
                ?.toIntOrNull()
        assertEquals("Physical fixture port $argument does not match the fetched manifest", expected, value)
    }

    private fun assertSuccessfulPhysicalProbe(
        label: String,
        ok: Boolean,
        failureKind: String?,
        errno: Int?,
    ) {
        assertTrue("$label failed kind=$failureKind errno=$errno", ok)
    }

    private fun assertPhysicalSourceFamily(
        family: PhysicalSoBindFamily,
        localAddress: String?,
    ) {
        val parsed = localAddress?.let { runCatching { InetAddress.getByName(it.substringBefore('%')) }.getOrNull() }
        assertTrue("${family.id} probe did not expose a ${family.id} source address", parsed != null)
        assertEquals("${family.id} probe used the wrong source family", family.ipv6, parsed is Inet6Address)
    }

    private fun physicalSoBindPayload(
        stage: String,
        protocol: String,
        family: PhysicalSoBindFamily,
        nonce: String,
    ): String {
        val marker = "so-bind-$stage-$protocol-${family.id}-$nonce${"x".repeat(family.payloadPadding)}"
        return if (protocol == "tcp") httpEchoPayloadText(marker) else marker
    }

    private fun List<FixtureEventDto>.countPhysicalEcho(
        service: String,
        protocol: String,
        targetPort: Int,
        stage: String,
        payloadProtocol: String,
        family: PhysicalSoBindFamily,
        nonce: String,
    ): Int =
        count {
            it.matchesEcho(
                service = service,
                protocol = protocol,
                targetPort = targetPort,
                payloadBytes = physicalSoBindPayload(stage, payloadProtocol, family, nonce).toByteArray().size,
            ) && it.peerAddressIsIpv6() == family.ipv6
        }

    private fun writePhysicalSoBindEvidence(
        families: List<PhysicalSoBindFamily>,
        counters: Map<String, PhysicalSoBindCounters>,
    ) {
        val arguments =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
        val runId = requirePhysicalEvidenceArgument(arguments, SoBindRunIdArg, Regex("[0-9a-f]{32}"))
        val sourceSha = requirePhysicalEvidenceArgument(arguments, SoBindSourceShaArg, Regex("[0-9a-f]{40}"))
        assertTrue(
            "Installed app APK was not built from the attributed source SHA",
            BuildConfig.GIT_COMMIT.matches(Regex("[0-9a-f]{7,40}")) && sourceSha.startsWith(BuildConfig.GIT_COMMIT),
        )
        val appApkSha256 = requirePhysicalEvidenceArgument(arguments, SoBindAppApkSha256Arg, Regex("[0-9a-f]{64}"))
        val testApkSha256 = requirePhysicalEvidenceArgument(arguments, SoBindTestApkSha256Arg, Regex("[0-9a-f]{64}"))
        val familyEvidence =
            JSONArray().apply {
                families.forEach { family ->
                    val values = counters.getValue(family.id)
                    put(
                        JSONObject()
                            .put("family", family.id)
                            .put("sourceFamilyVerified", true)
                            .put("directTcpRoundTrips", values.directTcpRoundTrips)
                            .put("directUdpRoundTrips", values.directUdpRoundTrips)
                            .put("directTcpFixtureEvents", values.directTcpFixtureEvents)
                            .put("directUdpFixtureEvents", values.directUdpFixtureEvents)
                            .put("allowedTcpRoundTrips", values.allowedTcpRoundTrips)
                            .put("allowedUdpRoundTrips", values.allowedUdpRoundTrips)
                            .put("allowedTcpFixtureEvents", values.allowedTcpFixtureEvents)
                            .put("allowedUdpFixtureEvents", values.allowedUdpFixtureEvents)
                            .put("deniedTcpBlockedAttempts", values.deniedTcpBlockedAttempts)
                            .put("deniedTcpFailureKind", values.deniedTcpFailureKind)
                            .put("deniedTcpFailureStage", values.deniedTcpFailureStage)
                            .put("deniedTcpErrno", values.deniedTcpErrno)
                            .put("deniedUdpBlockedAttempts", values.deniedUdpBlockedAttempts)
                            .put("deniedUdpFailureKind", values.deniedUdpFailureKind)
                            .put("deniedUdpFailureStage", values.deniedUdpFailureStage)
                            .put("deniedUdpErrno", values.deniedUdpErrno)
                            .put("deniedTcpFixtureEvents", values.deniedTcpFixtureEvents)
                            .put("deniedUdpFixtureEvents", values.deniedUdpFixtureEvents)
                            .put("livenessTcpRoundTrips", values.livenessTcpRoundTrips)
                            .put("livenessUdpRoundTrips", values.livenessUdpRoundTrips)
                            .put("livenessTcpFixtureEvents", values.livenessTcpFixtureEvents)
                            .put("livenessUdpFixtureEvents", values.livenessUdpFixtureEvents),
                    )
                }
            }
        val evidence =
            JSONObject()
                .put("version", PhysicalSoBindEvidenceVersion)
                .put("status", "PASS")
                .put("profile", PhysicalSoBindEvidenceProfile)
                .put("runId", runId)
                .put("sourceSha", sourceSha)
                .put("appApkSha256", appApkSha256)
                .put("testApkSha256", testApkSha256)
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceCodename", Build.DEVICE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("kernelFamily", "6.1")
                .put("realTun", true)
                .put("tunPacketPathObserved", true)
                .put("families", familyEvidence)
        appContext
            .openFileOutput(PhysicalSoBindEvidenceFile, Context.MODE_PRIVATE)
            .bufferedWriter()
            .use { output -> output.write(evidence.toString() + "\n") }
    }

    private fun requirePhysicalEvidenceArgument(
        arguments: Bundle,
        key: String,
        pattern: Regex,
    ): String {
        val value = arguments.getString(key).orEmpty()
        assertTrue("Physical SO_BIND evidence argument $key is missing or malformed", pattern.matches(value))
        return value
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
        assumeTrue(
            "SO_BINDTODEVICE evidence is qualified on the Google Pixel 7 hardware profile",
            Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.DEVICE == "panther",
        )
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

private data class PhysicalSoBindFamily(
    val id: String,
    val host: String,
    val ipv6: Boolean,
    val payloadPadding: Int,
)

private data class PhysicalSoBindCounters(
    var directTcpRoundTrips: Int = 0,
    var directUdpRoundTrips: Int = 0,
    var directTcpFixtureEvents: Int = 0,
    var directUdpFixtureEvents: Int = 0,
    var allowedTcpRoundTrips: Int = 0,
    var allowedUdpRoundTrips: Int = 0,
    var allowedTcpFixtureEvents: Int = 0,
    var allowedUdpFixtureEvents: Int = 0,
    var deniedTcpBlockedAttempts: Int = 0,
    var deniedTcpFailureKind: String? = null,
    var deniedTcpFailureStage: String? = null,
    var deniedTcpErrno: Int? = null,
    var deniedUdpBlockedAttempts: Int = 0,
    var deniedUdpFailureKind: String? = null,
    var deniedUdpFailureStage: String? = null,
    var deniedUdpErrno: Int? = null,
    var deniedTcpFixtureEvents: Int = 0,
    var deniedUdpFixtureEvents: Int = 0,
    var livenessTcpRoundTrips: Int = 0,
    var livenessUdpRoundTrips: Int = 0,
    var livenessTcpFixtureEvents: Int = 0,
    var livenessUdpFixtureEvents: Int = 0,
)

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

private fun FixtureEventDto.peerAddressIsIpv6(): Boolean? {
    val host =
        if (peer.startsWith("[")) {
            peer.substringAfter('[').substringBefore(']')
        } else {
            peer.substringBeforeLast(':')
        }
    if (host.startsWith("::ffff:", ignoreCase = true)) {
        return false
    }
    val isIpv6Literal = host.contains(':') && host.matches(Regex("[0-9A-Fa-f:.%]+"))
    val isIpv4Literal = !host.contains(':') && host.matches(Regex("[0-9.]+"))
    if (!isIpv6Literal && !isIpv4Literal) return null
    return runCatching { InetAddress.getByName(host.substringBefore('%')) is Inet6Address }.getOrNull()
}

private fun String?.describesDnsTimeout(): Boolean =
    this != null &&
        (contains("timeout", ignoreCase = true) || contains("timed out", ignoreCase = true))
