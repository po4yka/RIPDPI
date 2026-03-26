package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class PacketSmokeInstrumentedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto

    @Before
    fun setUp() {
        hiltRule.inject()
        ensureLocalNetworkAccessGranted(appContext)
        fixtureClient = LocalFixtureClient.fromInstrumentationArgs()
        fixture = selectReachableFixtureManifest(appContext, fixtureClient.manifest())
        fixtureClient.resetEvents()
        fixtureClient.resetFaults()
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsIp = fixture.androidHost
                ipv6Enable = false
                enableCmdSettings = false
                desyncMethod = "none"
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                tlsrecEnabled = false
                setStrategyChains(emptyList(), emptyList())
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
        }
        fixtureClient.resetEvents()
        fixtureClient.resetFaults()
    }

    @Test
    fun proxyTlsrecSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl = """
                [tcp]
                tlsrec extlen
                split host+1
            """.trimIndent(),
            expectedScenario = "tlsrec",
        )
    }

    @Test
    fun proxyTlsrandrecSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl = """
                [tcp]
                tlsrandrec sniext+4 count=4 min=24 max=48
                split host+1
            """.trimIndent(),
            expectedScenario = "tlsrandrec",
        )
    }

    @Test
    fun proxyHostfakeSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl = """
                [tcp]
                hostfake endhost host=cdn.discordapp.net
            """.trimIndent(),
            expectedScenario = "hostfake",
        )
    }

    @Test
    fun proxyFakedsplitSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl = """
                [tcp]
                tlsrec extlen
                fakedsplit host+1
            """.trimIndent(),
            expectedScenario = "fakedsplit",
        )
    }

    @Test
    fun proxyFakeddisorderSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl = """
                [tcp]
                tlsrec extlen
                fakeddisorder endhost
            """.trimIndent(),
            expectedScenario = "fakeddisorder",
        )
    }

    @Test
    fun vpnTunnelBaselineSmokeFamilyRoutesShellTraffic() {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                dnsIp = fixture.androidHost
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val payload = httpEchoPayloadShellLiteral("packet-smoke-vpn")
        val result = vpnTcpRoundTripResult(fixture.androidHost, fixture.tcpEchoPort, payload)
        assertTrue("Expected VPN TCP round-trip to succeed: $result", result.ok)
        val output = result.response.orEmpty()
        assertTrue(output.contains("GET /packet-smoke-vpn HTTP/1.1"))

        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.tunnelStats.txPackets > 0 &&
                snapshot.tunnelStats.rxPackets > 0 &&
                snapshot.proxyTelemetry.totalSessions > 0
        }
    }

    @Test
    fun vpnDohSmokeFamilyResolvesHostnameTraffic() {
        runVpnEncryptedDnsSuccessSmoke(EncryptedDnsProtocolDoh, "dns_http")
    }

    @Test
    fun vpnDotSmokeFamilyResolvesHostnameTraffic() {
        runVpnEncryptedDnsSuccessSmoke(EncryptedDnsProtocolDot, "dns_dot")
    }

    @Test
    fun vpnDnscryptSmokeFamilyResolvesHostnameTraffic() {
        runVpnEncryptedDnsSuccessSmoke(EncryptedDnsProtocolDnsCrypt, "dns_dnscrypt")
    }

    @Test
    fun vpnDoqSmokeFamilyResolvesHostnameTraffic() {
        runVpnEncryptedDnsSuccessSmoke(EncryptedDnsProtocolDoq, "dns_doq")
    }

    @Test
    fun vpnDohFaultSmokeFamilySurfacesDnsFailure() {
        runVpnEncryptedDnsFailureSmoke(
            protocol = EncryptedDnsProtocolDoh,
            faultTarget = FixtureFaultTargetDto.DNS_HTTP,
            outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
        )
    }

    @Test
    fun vpnDotFaultSmokeFamilySurfacesDnsFailure() {
        runVpnEncryptedDnsFailureSmoke(
            protocol = EncryptedDnsProtocolDot,
            faultTarget = FixtureFaultTargetDto.DNS_DOT,
            outcome = FixtureFaultOutcomeDto.TLS_ABORT,
        )
    }

    @Test
    fun vpnDnscryptFaultSmokeFamilySurfacesDnsFailure() {
        runVpnEncryptedDnsFailureSmoke(
            protocol = EncryptedDnsProtocolDnsCrypt,
            faultTarget = FixtureFaultTargetDto.DNS_DNSCRYPT,
            outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
        )
    }

    @Test
    fun vpnDoqFaultSmokeFamilySurfacesDnsFailure() {
        runVpnEncryptedDnsFailureSmoke(
            protocol = EncryptedDnsProtocolDoq,
            faultTarget = FixtureFaultTargetDto.DNS_DOQ,
            outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
        )
    }

    @Test
    fun vpnHostAutolearnSmokeFamilyKeepsServiceStable() {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDoh,
            )
            appSettingsRepository.update {
                hostAutolearnEnabled = true
                hostAutolearnPenaltyTtlHours = 4
                hostAutolearnMaxHosts = 32
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
        val baselineRestartCount = serviceStateStore.telemetry.value.restartCount

        repeat(2) { round ->
            val result = vpnTcpRoundTripResult(
                fixture.fixtureDomain,
                fixture.tcpEchoPort,
                httpEchoPayloadShellLiteral("packet-smoke-host-autolearn-$round"),
            )
            assertTrue("Expected VPN TCP round-trip to succeed: $result", result.ok)
            val output = result.response.orEmpty()
            assertTrue(output.contains("Host: ${fixture.fixtureDomain}"))
        }

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions >= 2 &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L
        }

        val stableSnapshot = serviceStateStore.telemetry.value
        assertEquals(baselineRestartCount, stableSnapshot.restartCount)
        assertTrue(
            fixtureClient.events().any { event ->
                event.service == "dns_http" && event.detail.contains(fixture.fixtureDomain)
            },
        )
    }

    @Test
    fun vpnRememberedPolicySmokeFamilyKeepsServiceStable() {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDoh,
            )
            appSettingsRepository.update {
                networkStrategyMemoryEnabled = true
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        repeat(2) { round ->
            val result = vpnTcpRoundTripResult(
                fixture.fixtureDomain,
                fixture.tcpEchoPort,
                httpEchoPayloadShellLiteral("packet-smoke-remembered-$round"),
            )
            assertTrue("Expected VPN TCP round-trip to succeed: $result", result.ok)
            val output = result.response.orEmpty()
            assertTrue(output.contains("Host: ${fixture.fixtureDomain}"))
        }

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions >= 2 &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L
        }
    }

    @Test
    fun vpnWsTunnelFallbackSmokeFamilyKeepsServiceStable() {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDoh,
            )
            appSettingsRepository.update {
                wsTunnelEnabled = true
                wsTunnelMode = "fallback"
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val result = vpnTcpRoundTripResult(
            fixture.fixtureDomain,
            fixture.tcpEchoPort,
            httpEchoPayloadShellLiteral("packet-smoke-ws-fallback"),
        )
        assertTrue("Expected VPN TCP round-trip to succeed: $result", result.ok)
        val output = result.response.orEmpty()
        assertTrue(output.contains("Host: ${fixture.fixtureDomain}"))

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions > 0 &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L
        }
    }

    private fun runProxyTlsChainSmoke(
        chainDsl: String,
        expectedScenario: String,
    ) {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                desyncHttp = false
                desyncHttps = true
                enableCmdSettings = false
                setRawStrategyChainDsl(chainDsl)
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)

        val probe = probeAppProcessTcpConnect(appContext, "127.0.0.1", listenPort)
        assertTrue("Expected proxy listener probe to succeed for $expectedScenario: $probe", probe.ok)

        val response =
            socksTlsHandshake(
                proxyPort = listenPort,
                targetHost = fixture.androidHost,
                targetPort = fixture.tlsEchoPort,
                sniHost = fixture.fixtureDomain,
            )
        assertTrue("Expected TLS fixture response for $expectedScenario, got: $response", response.contains("fixture tls ok"))
        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.Proxy &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions > 0
        }
        assertTrue(
            "Expected fixture TLS handshake event for $expectedScenario, got ${fixtureClient.events()}",
            fixtureClient.events().any { event -> event.service == "tls_echo" && event.sni == fixture.fixtureDomain },
        )
    }

    private fun runVpnEncryptedDnsSuccessSmoke(
        protocol: String,
        expectedService: String,
    ) {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = protocol,
            )
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val result = vpnTcpRoundTripResult(
            fixture.fixtureDomain,
            fixture.tcpEchoPort,
            httpEchoPayloadShellLiteral("packet-smoke-${protocol.lowercase()}"),
        )
        assertTrue("Expected hostname VPN round-trip for $protocol, got: $result", result.ok)
        val output = result.response.orEmpty()
        assertTrue("Expected hostname shell round-trip for $protocol, got: $output", output.contains("Host: ${fixture.fixtureDomain}"))

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            val events = fixtureClient.events()
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.tunnelTelemetry.dnsFailuresTotal == 0L &&
                snapshot.proxyTelemetry.totalSessions > 0 &&
                events.any { it.service == expectedService && it.detail.contains(fixture.fixtureDomain) } &&
                events.any { it.service == "tcp_echo" && it.detail == "echo" }
        }
    }

    private fun runVpnEncryptedDnsFailureSmoke(
        protocol: String,
        faultTarget: FixtureFaultTargetDto,
        outcome: FixtureFaultOutcomeDto,
    ) {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = protocol,
            )
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
        fixtureClient.setFault(FixtureFaultSpecDto(target = faultTarget, outcome = outcome))

        val result = vpnTcpRoundTripResult(
            fixture.fixtureDomain,
            fixture.tcpEchoPort,
            httpEchoPayloadShellLiteral("packet-smoke-${protocol.lowercase()}-fault"),
        )
        val output = result.response.orEmpty()
        assertFalse(output.contains("GET /packet-smoke-${protocol.lowercase()}-fault HTTP/1.1"))

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.tunnelTelemetry.dnsFailuresTotal > 0L &&
                !snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank()
        }
        assertTrue(
            fixtureClient.events().none { event -> event.service == "tcp_echo" && event.detail == "echo" },
        )
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

    private fun awaitServiceStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        awaitUntil {
            serviceStateStore.status.value == status to mode
        }
    }

    private fun shellTcpRoundTrip(
        host: String,
        port: Int,
        payload: String,
    ): String =
        execShell(
            "sh -c 'printf %b \"$payload\" | toybox nc -w 5 $host $port'",
        )

    private fun vpnTcpRoundTripResult(
        host: String,
        port: Int,
        payload: String,
    ): AppProcessTcpProbeResult =
        if (isLikelyEmulator()) {
            AppProcessTcpProbeResult(
                host = host,
                port = port,
                ok = true,
                response = shellTcpRoundTrip(host, port, payload),
            )
        } else {
            appProcessTcpRoundTrip(appContext, host, port, payload)
        }

    private fun httpEchoPayloadShellLiteral(pathToken: String): String =
        "GET /$pathToken HTTP/1.1\\r\\nHost: ${fixture.fixtureDomain}\\r\\nConnection: close\\r\\n\\r\\n"
}
