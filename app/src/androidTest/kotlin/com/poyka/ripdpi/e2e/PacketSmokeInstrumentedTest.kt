package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.setRawStrategyChainDsl
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.debug.PacketSmokePhase
import com.poyka.ripdpi.debug.PacketSmokePrepareState
import com.poyka.ripdpi.debug.PacketSmokeRunnerProbeResult
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.IOException
import javax.inject.Inject

@HiltAndroidTest
class PacketSmokeInstrumentedTest {
    companion object {
        private const val PhysicalBaselineHost = "1.1.1.1"
        private const val PhysicalBaselinePort = 443
        private const val PhysicalTrafficHost = "example.com"
        private const val PhysicalTrafficPort = 443
        private const val PhysicalDnsProbeHost = "example.com"
        private val PhasedPhysicalVpnSmokeTests =
            setOf(
                "vpnTunnelBaselineSmokeFamilyRoutesShellTraffic",
                "vpnDohSmokeFamilyResolvesHostnameTraffic",
                "vpnDotSmokeFamilyResolvesHostnameTraffic",
                "vpnDnscryptSmokeFamilyResolvesHostnameTraffic",
                "vpnDoqSmokeFamilyResolvesHostnameTraffic",
                "vpnDohFaultSmokeFamilySurfacesDnsFailure",
                "vpnDotFaultSmokeFamilySurfacesDnsFailure",
                "vpnDnscryptFaultSmokeFamilySurfacesDnsFailure",
                "vpnDoqFaultSmokeFamilySurfacesDnsFailure",
                "vpnHostAutolearnSmokeFamilyKeepsServiceStable",
                "vpnRememberedPolicySmokeFamilyKeepsServiceStable",
                "vpnWsTunnelFallbackSmokeFamilyKeepsServiceStable",
            )
    }

    @get:Rule(order = 0)
    val e2eFixtureRule = E2eFixtureRule(allowPacketSmokeAssertWithoutFixture = true)

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 3)
    val testName = TestName()

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private var hiltInjected = false
    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto
    private val startedServices = mutableSetOf<Class<*>>()

    @Before
    fun setUp() {
        if (currentTestRequiresPhasedPhysicalRunner()) {
            assumePhasedPhysicalRunnerForVpnSmoke()
        }
        if (!packetSmokeUsesPhasedPhysicalRunner() || packetSmokePhase() != PacketSmokePhase.ASSERT) {
            assumeE2eFixtureConfigured()
        }
        hiltRule.inject()
        hiltInjected = true
        if (packetSmokeUsesPhasedPhysicalRunner() && packetSmokePhase() == PacketSmokePhase.ASSERT) {
            return
        }
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
        }
        val environment = prepareE2eEnvironment(appContext)
        fixtureClient = environment.fixtureClient
        fixture = environment.fixture
        if (packetSmokeUsesPhasedPhysicalRunner()) {
            clearPacketSmokeScenarioState(appContext, requirePacketSmokeScenarioId())
        }
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsIp = fixture.androidHost
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
        if (packetSmokeUsesPhasedPhysicalRunner() && packetSmokePhase() == PacketSmokePhase.PREPARE) {
            return
        }
        if (hiltInjected) {
            runBlocking {
                if (startedServices.isNotEmpty()) {
                    stopService(RipDpiProxyService::class.java)
                    stopService(RipDpiVpnService::class.java)
                }
            }
        }
        if (this::fixtureClient.isInitialized) {
            fixtureClient.resetEvents()
            fixtureClient.resetFaults()
        }
    }

    @Test
    fun proxyTlsrecSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
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
            chainDsl =
                """
                [tcp]
                tlsrandrec sniext+4 count=4 min=24 max=48
                split host+1
                """.trimIndent(),
            expectedScenario = "tlsrandrec",
        )
    }

    @Test
    fun proxySplitHostPlusOneRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                split host+1
                """.trimIndent(),
            expectedScenario = "split-host-plus-one",
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyHostfakeSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                hostfake endhost host=cdn.discordapp.net
                """.trimIndent(),
            expectedScenario = "hostfake",
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyFakedsplitSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                tlsrec extlen
                fakedsplit host+1
                """.trimIndent(),
            expectedScenario = "fakedsplit",
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyFakeddisorderSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                tlsrec extlen
                fakeddisorder endhost
                """.trimIndent(),
            expectedScenario = "fakeddisorder",
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyMultiDisorderSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                multidisorder host+2
                multidisorder midsld
                """.trimIndent(),
            expectedScenario = "multidisorder",
            rootModeEnabled = true,
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyIpFrag2SmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                ipfrag2 host+2
                """.trimIndent(),
            expectedScenario = "ipfrag2",
            rootModeEnabled = true,
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyIpFrag2Ipv6ExtSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                ipfrag2 host+2 ipv6ext=hopByHopDestOpt
                """.trimIndent(),
            expectedScenario = "ipfrag2_ipv6_ext",
            rootModeEnabled = true,
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxySeqOverlapSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                tlsrec extlen
                seqovl midsld overlap=16 fake=rand
                """.trimIndent(),
            expectedScenario = "seqovl",
            rootModeEnabled = true,
            requireFixtureResponse = false,
        )
    }

    @Test
    @RawPacketValidationOnly
    fun proxyFakeRstSmokeFamilyRoutesTlsTraffic() {
        runProxyTlsChainSmoke(
            chainDsl =
                """
                [tcp]
                fakerst host+2
                split host+2
                """.trimIndent(),
            expectedScenario = "fakerst",
            rootModeEnabled = true,
            requireFixtureResponse = false,
        )
    }

    @Test
    fun vpnTunnelBaselineSmokeFamilyRoutesShellTraffic() {
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnTunnelBaselineSmoke()
            return
        }
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

        val payload = httpEchoPayloadText("packet-smoke-vpn")
        val result = vpnTcpRoundTripResult(fixture.androidHost, fixture.tcpEchoPort, payload)
        assertTrue("Expected VPN TCP round-trip to succeed: $result", result.ok)
        val output = result.response.orEmpty()
        assertTrue(
            "Expected VPN TCP response to contain request line, got: $output",
            output.contains("GET /packet-smoke-vpn HTTP/1.1"),
        )

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
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnHostAutolearnSmoke()
            return
        }
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
        awaitUntil(
            timeoutMs = 10_000L,
            failureMessage = {
                serviceStateDebugSummary(
                    serviceStateStore = serviceStateStore,
                    fixtureClient = fixtureClient,
                )
            },
        ) {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.status == AppStatus.Running &&
                snapshot.mode == Mode.VPN
        }
        val baselineRestartCount = serviceStateStore.telemetry.value.restartCount

        repeat(2) { round ->
            val result =
                vpnTcpRoundTripResult(
                    fixture.fixtureDomain,
                    fixture.tcpEchoPort,
                    httpEchoPayloadText("packet-smoke-host-autolearn-$round"),
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
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnRememberedPolicySmoke()
            return
        }
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
            val result =
                vpnTcpRoundTripResult(
                    fixture.fixtureDomain,
                    fixture.tcpEchoPort,
                    httpEchoPayloadText("packet-smoke-remembered-$round"),
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
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnWsTunnelFallbackSmoke()
            return
        }
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

        val result =
            vpnTcpRoundTripResult(
                fixture.fixtureDomain,
                fixture.tcpEchoPort,
                httpEchoPayloadText("packet-smoke-ws-fallback"),
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
        rootModeEnabled: Boolean = false,
        requireFixtureResponse: Boolean = true,
    ) {
        assumeProxyFixtureCanValidateTlsHandshake(expectedScenario)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
                desyncHttp = false
                desyncHttps = true
                enableCmdSettings = false
                this.rootModeEnabled = rootModeEnabled
                setFakeTtl(packetSmokeFixtureFakeTtl())
                setRawStrategyChainDsl(chainDsl)
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)

        val probe = probeAppProcessTcpConnect(appContext, "127.0.0.1", listenPort)
        assertTrue("Expected proxy listener probe to succeed for $expectedScenario: $probe", probe.ok)

        val response =
            runCatching {
                socksTlsHandshake(
                    proxyPort = listenPort,
                    targetHost = fixture.androidHost,
                    targetPort = fixture.tlsEchoPort,
                    sniHost = fixture.fixtureDomain,
                )
            }
        if (requireFixtureResponse) {
            val body = response.getOrThrow()
            assertTrue(
                "Expected TLS fixture response for $expectedScenario, got: $body",
                body.contains("fixture tls ok"),
            )
        } else {
            response
                .onSuccess { body ->
                    assertTrue(
                        "Expected TLS fixture response for $expectedScenario when the raw-only handshake completed, got: $body",
                        body.contains("fixture tls ok"),
                    )
                }.onFailure { error ->
                    assertTrue(
                        "Expected raw-only $expectedScenario traffic to complete or close during TLS, got: $error",
                        error is IOException,
                    )
                }
        }
        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.Proxy &&
                snapshot.status == AppStatus.Running &&
                snapshot.proxyTelemetry.totalSessions > 0
        }
        if (requireFixtureResponse) {
            assertTrue(
                "Expected fixture TLS handshake event for $expectedScenario, got ${fixtureClient.events()}",
                fixtureClient.events().any { event ->
                    event.service == "tls_echo" && event.sni == fixture.fixtureDomain
                },
            )
        }
    }

    private fun packetSmokeFixtureFakeTtl(): Int =
        when (packetSmokeDeviceProfile()) {
            PacketSmokeDeviceProfile.EMULATOR_RAW,
            PacketSmokeDeviceProfile.ROOTED_ANDROID_PCAP,
            -> 1

            PacketSmokeDeviceProfile.PHYSICAL_INDIRECT -> 8
        }

    private fun runVpnEncryptedDnsSuccessSmoke(
        protocol: String,
        expectedService: String,
    ) {
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnEncryptedDnsSuccessSmoke(protocol)
            return
        }
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

        val result =
            vpnTcpRoundTripResult(
                fixture.fixtureDomain,
                fixture.tcpEchoPort,
                httpEchoPayloadText("packet-smoke-${protocol.lowercase()}"),
            )
        assertTrue("Expected hostname VPN round-trip for $protocol, got: $result", result.ok)
        val output = result.response.orEmpty()
        assertTrue(
            "Expected hostname TCP round-trip for $protocol, got: $output",
            output.contains("Host: ${fixture.fixtureDomain}"),
        )

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
        if (packetSmokeUsesPhysicalIndirectContract()) {
            runPhysicalIndirectVpnEncryptedDnsFailureSmoke(protocol, faultTarget, outcome)
            return
        }
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
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = faultTarget,
                outcome = outcome,
                scope = FixtureFaultScopeDto.PERSISTENT,
            ),
        )

        val result =
            vpnTcpRoundTripResult(
                fixture.fixtureDomain,
                fixture.tcpEchoPort,
                httpEchoPayloadText("packet-smoke-${protocol.lowercase()}-fault"),
                throwOnProbeTimeout = false,
            )
        val output = result.response.orEmpty()
        assertFalse(output.contains("GET /packet-smoke-${protocol.lowercase()}-fault HTTP/1.1"))

        awaitUntil(timeoutMs = 20_000L) {
            val snapshot = serviceStateStore.telemetry.value
            val dnsFailureVisible =
                snapshot.tunnelTelemetry.dnsFailuresTotal > 0L &&
                    !snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank()
            val resolverFallbackVisible =
                snapshot.tunnelTelemetry.resolverFallbackActive &&
                    !snapshot.tunnelTelemetry.resolverFallbackReason.isNullOrBlank()
            dnsFailureVisible || resolverFallbackVisible
        }
        assertTrue(
            fixtureClient.events().none { event -> event.service == "tcp_echo" && event.detail == "echo" },
        )
    }

    private fun runPhysicalIndirectVpnTunnelBaselineSmoke() {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                preparePhysicalIndirectPlainDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    dnsIp = PhysicalBaselineHost,
                )
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-baseline", probe)
                assertTrue("Expected physical-device VPN baseline connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions > before.proxySessions &&
                            delta.txPackets > 0 &&
                            delta.rxPackets > 0
                    }
                assertEquals(before.restartCount, after.restartCount)
            }

            PacketSmokePhase.SINGLE -> {
                ensureVpnConsentGranted(appContext)
                val listenPort = reserveLoopbackPort()
                runBlocking {
                    appSettingsRepository.applyPacketSmokePlainDns(
                        proxyPort = listenPort,
                        dnsIp = PhysicalBaselineHost,
                    )
                }

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                val probe = testProcessTcpConnect(PhysicalBaselineHost, PhysicalBaselinePort, timeoutMs = 5_000L)
                logPhysicalProbe("vpn-baseline", probe)
                assertTrue("Expected physical-device VPN baseline connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions > before.proxyTelemetry.totalSessions &&
                            delta.txPackets > 0 &&
                            delta.rxPackets > 0
                    }
                assertEquals(baselineRestartCount, after.restartCount)
            }
        }
    }

    private fun runPhysicalIndirectVpnEncryptedDnsSuccessSmoke(protocol: String) {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                val preset = packetSmokeEncryptedDnsPreset(protocol)
                preparePhysicalIndirectEncryptedDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    preset = preset,
                    expectedDnsHost = PhysicalDnsProbeHost,
                )
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                val preset = packetSmokeEncryptedDnsPreset(protocol)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-dns-success:$protocol", probe)
                assertTrue("Expected physical-device DNS probe exchange for $protocol: $probe", probe.ok)
                assertEquals("Expected NOERROR rcode for $protocol: $probe", 0, probe.rcode)
                assertTrue("Expected at least one DNS answer for $protocol: $probe", probe.answers.isNotEmpty())

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            delta.dnsQueriesTotal >= 1 &&
                            delta.dnsFailuresTotal == 0L &&
                            snapshot.tunnelTelemetry.lastDnsHost == (before.expectedDnsHost ?: PhysicalDnsProbeHost) &&
                            snapshot.tunnelTelemetry.resolverId == (before.expectedResolverId ?: preset.providerId) &&
                            snapshot.tunnelTelemetry.resolverProtocol ==
                            (before.expectedResolverProtocol ?: preset.protocol) &&
                            snapshot.tunnelTelemetry.resolverEndpoint
                                .orEmpty()
                                .contains(before.expectedResolverEndpoint ?: preset.expectedResolverEndpoint)
                    }
                assertEquals(before.restartCount, after.restartCount)

                if (protocol == EncryptedDnsProtocolDnsCrypt) {
                    val persisted = runBlocking { appSettingsRepository.snapshot() }
                    assertEquals(preset.dnscryptProviderName, persisted.encryptedDnsDnscryptProviderName)
                    assertEquals(preset.dnscryptPublicKey, persisted.encryptedDnsDnscryptPublicKey)
                }
            }

            PacketSmokePhase.SINGLE -> {
                ensureVpnConsentGranted(appContext)
                val listenPort = reserveLoopbackPort()
                val preset = packetSmokeEncryptedDnsPreset(protocol)
                val usesFixtureResolver = protocol != EncryptedDnsProtocolDoq
                runBlocking {
                    if (usesFixtureResolver) {
                        appSettingsRepository.applyFixtureEncryptedDns(
                            fixture = fixture,
                            proxyPort = listenPort,
                            protocol = protocol,
                        )
                    } else {
                        appSettingsRepository.applyPacketSmokeEncryptedDns(
                            proxyPort = listenPort,
                            preset = preset,
                        )
                    }
                }
                logEncryptedDnsSettings(
                    label = "vpn-dns-config:$protocol",
                    expectedResolverEndpoint =
                        if (usesFixtureResolver) {
                            fixture.expectedResolverEndpoint(protocol)
                        } else {
                            preset.expectedResolverEndpoint
                        },
                    usesFixtureResolver = usesFixtureResolver,
                )

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                val probe = testProcessDnsProbe(queryHost = PhysicalDnsProbeHost)
                logPhysicalProbe("vpn-dns-success:$protocol", probe)
                assertTrue("Expected physical-device DNS probe exchange for $protocol: $probe", probe.ok)
                assertEquals("Expected NOERROR rcode for $protocol: $probe", 0, probe.rcode)
                assertTrue("Expected at least one DNS answer for $protocol: $probe", probe.answers.isNotEmpty())

                val expectedResolverEndpoint =
                    if (usesFixtureResolver) {
                        fixture.expectedResolverEndpoint(protocol)
                    } else {
                        preset.expectedResolverEndpoint
                    }
                val expectedResolverId = if (usesFixtureResolver) DnsProviderCustom else preset.providerId
                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            delta.dnsQueriesTotal >= 1 &&
                            delta.dnsFailuresTotal == 0L &&
                            snapshot.tunnelTelemetry.resolverId == expectedResolverId &&
                            snapshot.tunnelTelemetry.resolverProtocol == protocol &&
                            snapshot.tunnelTelemetry.resolverEndpoint.orEmpty().contains(
                                expectedResolverEndpoint,
                            )
                    }
                assertEquals(baselineRestartCount, after.restartCount)

                if (protocol == EncryptedDnsProtocolDnsCrypt) {
                    val persisted = runBlocking { appSettingsRepository.snapshot() }
                    assertEquals(
                        if (usesFixtureResolver) fixture.dnscryptProviderName else preset.dnscryptProviderName,
                        persisted.encryptedDnsDnscryptProviderName,
                    )
                    assertEquals(
                        if (usesFixtureResolver) fixture.dnscryptPublicKey else preset.dnscryptPublicKey,
                        persisted.encryptedDnsDnscryptPublicKey,
                    )
                }
            }
        }
    }

    private fun runPhysicalIndirectVpnEncryptedDnsFailureSmoke(
        protocol: String,
        faultTarget: FixtureFaultTargetDto,
        outcome: FixtureFaultOutcomeDto,
    ) {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                val preset = packetSmokeEncryptedDnsFaultPreset(protocol)
                preparePhysicalIndirectEncryptedDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    preset = preset,
                    expectedDnsHost = PhysicalDnsProbeHost,
                )
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                val preset = packetSmokeEncryptedDnsFaultPreset(protocol)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-dns-failure:$protocol", probe)
                assertTrue(
                    "Expected a failed DNS outcome for $protocol, got: $probe",
                    !probe.ok || probe.rcode != 0 || probe.answers.isEmpty(),
                )

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            delta.dnsFailuresTotal >= 1 &&
                            snapshot.tunnelTelemetry.lastDnsHost == (before.expectedDnsHost ?: PhysicalDnsProbeHost) &&
                            snapshot.tunnelTelemetry.resolverId == (before.expectedResolverId ?: preset.providerId) &&
                            snapshot.tunnelTelemetry.resolverProtocol ==
                            (before.expectedResolverProtocol ?: preset.protocol) &&
                            snapshot.tunnelTelemetry.resolverEndpoint
                                .orEmpty()
                                .contains(before.expectedResolverEndpoint ?: preset.expectedResolverEndpoint) &&
                            !snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank()
                    }
                assertEquals(before.restartCount, after.restartCount)
            }

            PacketSmokePhase.SINGLE -> {
                ensureVpnConsentGranted(appContext)
                val listenPort = reserveLoopbackPort()
                val preset = packetSmokeEncryptedDnsFaultPreset(protocol)
                val usesFixtureResolver = protocol != EncryptedDnsProtocolDoq
                runBlocking {
                    if (usesFixtureResolver) {
                        appSettingsRepository.applyFixtureEncryptedDns(
                            fixture = fixture,
                            proxyPort = listenPort,
                            protocol = protocol,
                        )
                    } else {
                        appSettingsRepository.applyPacketSmokeEncryptedDns(
                            proxyPort = listenPort,
                            preset = preset,
                        )
                    }
                }
                logEncryptedDnsSettings(
                    label = "vpn-dns-fault-config:$protocol",
                    expectedResolverEndpoint =
                        if (usesFixtureResolver) {
                            fixture.expectedResolverEndpoint(protocol)
                        } else {
                            preset.expectedResolverEndpoint
                        },
                    usesFixtureResolver = usesFixtureResolver,
                )

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                if (usesFixtureResolver) {
                    fixtureClient.setFault(
                        FixtureFaultSpecDto(
                            target = faultTarget,
                            outcome = outcome,
                            scope = FixtureFaultScopeDto.PERSISTENT,
                        ),
                    )
                }

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                val probe = testProcessDnsProbe(queryHost = PhysicalDnsProbeHost)
                logPhysicalProbe("vpn-dns-failure:$protocol", probe)
                assertTrue(
                    "Expected a failed DNS outcome for $protocol, got: $probe",
                    !probe.ok || probe.rcode != 0 || probe.answers.isEmpty(),
                )

                val expectedResolverEndpoint =
                    if (usesFixtureResolver) {
                        fixture.expectedResolverEndpoint(protocol)
                    } else {
                        preset.expectedResolverEndpoint
                    }
                val expectedResolverId = if (usesFixtureResolver) DnsProviderCustom else preset.providerId
                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            delta.dnsFailuresTotal >= 1 &&
                            snapshot.tunnelTelemetry.resolverId == expectedResolverId &&
                            snapshot.tunnelTelemetry.resolverProtocol == protocol &&
                            snapshot.tunnelTelemetry.resolverEndpoint.orEmpty().contains(
                                expectedResolverEndpoint,
                            ) &&
                            !snapshot.tunnelTelemetry.lastDnsError.isNullOrBlank()
                    }
                assertEquals(baselineRestartCount, after.restartCount)
            }
        }
    }

    private fun runPhysicalIndirectVpnHostAutolearnSmoke() {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                preparePhysicalIndirectEncryptedDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    preset = packetSmokeEncryptedDnsPreset(EncryptedDnsProtocolDoh),
                ) {
                    update {
                        hostAutolearnEnabled = true
                        hostAutolearnPenaltyTtlHours = 4
                        hostAutolearnMaxHosts = 32
                    }
                }
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-host-autolearn", probe)
                assertTrue("Expected host autolearn connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions >= before.proxySessions + 2 &&
                            delta.dnsFailuresTotal == 0L &&
                            snapshot.proxyTelemetry.autolearnEnabled &&
                            (
                                snapshot.proxyTelemetry.learnedHostCount > 0 ||
                                    snapshot.proxyTelemetry.lastAutolearnHost == PhysicalTrafficHost
                            )
                    }
                assertEquals(before.restartCount, after.restartCount)
            }

            PacketSmokePhase.SINGLE -> {
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
                logEncryptedDnsSettings(
                    label = "vpn-host-autolearn-config",
                    expectedResolverEndpoint = fixture.expectedResolverEndpoint(EncryptedDnsProtocolDoh),
                    usesFixtureResolver = true,
                )

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                repeat(2) { round ->
                    val payload = httpEchoPayloadText("packet-smoke-autolearn-$round")
                    val probe =
                        testProcessTcpRoundTrip(
                            host = fixture.fixtureDomain,
                            port = fixture.tcpEchoPort,
                            payload = payload,
                        )
                    logPhysicalProbe("vpn-host-autolearn:$round", probe)
                    assertTrue("Expected host autolearn connect $round to succeed: $probe", probe.ok)
                    assertEquals(payload, probe.response)
                }

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions >= before.proxyTelemetry.totalSessions + 2 &&
                            delta.dnsFailuresTotal == 0L &&
                            snapshot.proxyTelemetry.autolearnEnabled &&
                            (
                                snapshot.proxyTelemetry.learnedHostCount > 0 ||
                                    snapshot.proxyTelemetry.lastAutolearnHost == fixture.fixtureDomain
                            )
                    }
                assertEquals(baselineRestartCount, after.restartCount)
            }
        }
    }

    private fun runPhysicalIndirectVpnRememberedPolicySmoke() {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                preparePhysicalIndirectEncryptedDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    preset = packetSmokeEncryptedDnsPreset(EncryptedDnsProtocolDoh),
                ) {
                    update {
                        networkStrategyMemoryEnabled = true
                    }
                }
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-remembered", probe)
                assertTrue("Expected remembered-policy connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions >= before.proxySessions + 2 &&
                            delta.dnsFailuresTotal == 0L
                    }
                assertEquals(before.restartCount, after.restartCount)
            }

            PacketSmokePhase.SINGLE -> {
                ensureVpnConsentGranted(appContext)
                val listenPort = reserveLoopbackPort()
                runBlocking {
                    appSettingsRepository.applyPacketSmokeEncryptedDns(
                        proxyPort = listenPort,
                        preset = packetSmokeEncryptedDnsPreset(EncryptedDnsProtocolDoh),
                    )
                    appSettingsRepository.update {
                        networkStrategyMemoryEnabled = true
                    }
                }

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                repeat(2) { round ->
                    val probe = testProcessTcpConnect(PhysicalTrafficHost, PhysicalTrafficPort, timeoutMs = 5_000L)
                    logPhysicalProbe("vpn-remembered:$round", probe)
                    assertTrue("Expected remembered-policy connect $round to succeed: $probe", probe.ok)
                }

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions >= before.proxyTelemetry.totalSessions + 2 &&
                            delta.dnsFailuresTotal == 0L
                    }
                assertEquals(baselineRestartCount, after.restartCount)
            }
        }
    }

    private fun runPhysicalIndirectVpnWsTunnelFallbackSmoke() {
        when (packetSmokePhase()) {
            PacketSmokePhase.PREPARE -> {
                preparePhysicalIndirectEncryptedDnsScenario(
                    scenarioId = requirePacketSmokeScenarioId(),
                    preset = packetSmokeEncryptedDnsPreset(EncryptedDnsProtocolDoh),
                ) {
                    update {
                        wsTunnelEnabled = true
                        wsTunnelMode = "fallback"
                    }
                }
            }

            PacketSmokePhase.ASSERT -> {
                val scenarioId = requirePacketSmokeScenarioId()
                awaitServiceStatus(AppStatus.Running, Mode.VPN)
                val before = requirePhysicalPrepareState(scenarioId)
                val probe = requirePhysicalProbeResult(scenarioId)
                logPhysicalProbe("vpn-ws-fallback", probe)
                assertTrue("Expected ws fallback connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions > before.proxySessions &&
                            delta.dnsFailuresTotal == 0L &&
                            delta.txPackets > 0 &&
                            delta.rxPackets > 0
                    }
                assertEquals(before.restartCount, after.restartCount)
            }

            PacketSmokePhase.SINGLE -> {
                ensureVpnConsentGranted(appContext)
                val listenPort = reserveLoopbackPort()
                runBlocking {
                    appSettingsRepository.applyPacketSmokeEncryptedDns(
                        proxyPort = listenPort,
                        preset = packetSmokeEncryptedDnsPreset(EncryptedDnsProtocolDoh),
                    )
                    appSettingsRepository.update {
                        wsTunnelEnabled = true
                        wsTunnelMode = "fallback"
                    }
                }

                startService(RipDpiVpnService::class.java)
                awaitServiceStatus(AppStatus.Running, Mode.VPN)

                val before = serviceStateStore.telemetry.value
                val baselineRestartCount = before.restartCount
                val probe = testProcessTcpConnect(PhysicalTrafficHost, PhysicalTrafficPort, timeoutMs = 5_000L)
                logPhysicalProbe("vpn-ws-fallback", probe)
                assertTrue("Expected ws fallback connect to succeed: $probe", probe.ok)

                val after =
                    awaitMatchingTelemetrySnapshot(before) { snapshot, delta ->
                        snapshot.mode == Mode.VPN &&
                            snapshot.status == AppStatus.Running &&
                            snapshot.proxyTelemetry.totalSessions > before.proxyTelemetry.totalSessions &&
                            delta.dnsFailuresTotal == 0L &&
                            delta.txPackets > 0 &&
                            delta.rxPackets > 0
                    }
                assertEquals(baselineRestartCount, after.restartCount)
            }
        }
    }

    private fun assumeProxyFixtureCanValidateTlsHandshake(expectedScenario: String) {
        val fakeSegmentScenario =
            expectedScenario == "hostfake" ||
                expectedScenario == "fakedsplit" ||
                expectedScenario == "fakeddisorder"
        val rawPrivilegedScenario =
            expectedScenario == "multidisorder" ||
                expectedScenario == "ipfrag2" ||
                expectedScenario == "ipfrag2_ipv6_ext" ||
                expectedScenario == "seqovl" ||
                expectedScenario == "fakerst"
        val fixtureIsHostLoopback = fixture.androidHost == "127.0.0.1" || fixture.androidHost == "10.0.2.2"
        val rawCaptureCanValidateFakeSegments =
            packetSmokeDeviceProfile() == PacketSmokeDeviceProfile.EMULATOR_RAW ||
                packetSmokeDeviceProfile() == PacketSmokeDeviceProfile.ROOTED_ANDROID_PCAP
        assumeFalse(
            "Host-loopback packet-smoke cannot validate $expectedScenario with a TLS fixture handshake; " +
                "the fake segment can reach the local fixture endpoint.",
            fakeSegmentScenario && fixtureIsHostLoopback && !rawCaptureCanValidateFakeSegments,
        )
        assumeFalse(
            "Packet-smoke cannot validate $expectedScenario without raw capture and rooted packet privileges.",
            rawPrivilegedScenario && !rawCaptureCanValidateFakeSegments,
        )
    }

    private fun assumePhasedPhysicalRunnerForVpnSmoke() {
        assumeFalse(
            "Physical-indirect VPN packet-smoke requires the prepare/runner/assert script; app-process SINGLE " +
                "probes are excluded from the VPN.",
            packetSmokeUsesPhysicalIndirectContract() && packetSmokePhase() == PacketSmokePhase.SINGLE,
        )
    }

    private fun currentTestRequiresPhasedPhysicalRunner(): Boolean =
        packetSmokeUsesPhysicalIndirectContract() &&
            packetSmokePhase() != PacketSmokePhase.SINGLE &&
            testName.methodName in PhasedPhysicalVpnSmokeTests

    private fun preparePhysicalIndirectPlainDnsScenario(
        scenarioId: String,
        dnsIp: String,
    ): PacketSmokePrepareState {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyPacketSmokePlainDns(
                proxyPort = listenPort,
                dnsIp = dnsIp,
            )
        }
        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
        return captureAndPersistPhysicalPrepareState(scenarioId = scenarioId)
    }

    private fun preparePhysicalIndirectEncryptedDnsScenario(
        scenarioId: String,
        preset: com.poyka.ripdpi.debug.PacketSmokeEncryptedDnsPreset,
        expectedDnsHost: String? = null,
        extraSetup: suspend AppSettingsRepository.() -> Unit = {},
    ): PacketSmokePrepareState {
        ensureVpnConsentGranted(appContext)
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyPacketSmokeEncryptedDns(
                proxyPort = listenPort,
                preset = preset,
            )
            appSettingsRepository.extraSetup()
        }
        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
        return captureAndPersistPhysicalPrepareState(
            scenarioId = scenarioId,
            expectedResolverId = preset.providerId,
            expectedResolverProtocol = preset.protocol,
            expectedResolverEndpoint = preset.expectedResolverEndpoint,
            expectedDnsHost = expectedDnsHost,
        )
    }

    private fun captureAndPersistPhysicalPrepareState(
        scenarioId: String,
        expectedResolverId: String? = null,
        expectedResolverProtocol: String? = null,
        expectedResolverEndpoint: String? = null,
        expectedDnsHost: String? = null,
    ): PacketSmokePrepareState {
        val state =
            capturePacketSmokePrepareState(
                scenarioId = scenarioId,
                deviceProfile = packetSmokeDeviceProfile(),
                snapshot = serviceStateStore.telemetry.value,
                expectedResolverId = expectedResolverId,
                expectedResolverProtocol = expectedResolverProtocol,
                expectedResolverEndpoint = expectedResolverEndpoint,
                expectedDnsHost = expectedDnsHost,
            )
        writePacketSmokePrepareState(appContext, scenarioId, state)
        println("packet-smoke[$scenarioId] wrote prepare state")
        return state
    }

    private fun requirePhysicalPrepareState(scenarioId: String): PacketSmokePrepareState =
        requireNotNull(readPacketSmokePrepareState(appContext, scenarioId)) {
            "Missing packet-smoke prepare state for scenario $scenarioId"
        }

    private fun requirePhysicalProbeResult(scenarioId: String): PacketSmokeRunnerProbeResult =
        requireNotNull(readPacketSmokeRunnerProbeResult(appContext, scenarioId)) {
            "Missing packet-smoke runner probe result for scenario $scenarioId"
        }

    private fun awaitMatchingTelemetrySnapshot(
        before: ServiceTelemetrySnapshot,
        timeoutMs: Long = 20_000L,
        matcher: (ServiceTelemetrySnapshot, PacketSmokeTelemetryDelta) -> Boolean,
    ): ServiceTelemetrySnapshot {
        var matched: ServiceTelemetrySnapshot? = null
        var lastSnapshot: ServiceTelemetrySnapshot? = null
        var lastDelta: PacketSmokeTelemetryDelta? = null
        awaitUntil(
            timeoutMs = timeoutMs,
            failureMessage = { describeTelemetryWaitFailure(lastSnapshot, lastDelta) },
        ) {
            val snapshot = serviceStateStore.telemetry.value
            val delta = snapshot.packetSmokeDeltaFrom(before)
            lastSnapshot = snapshot
            lastDelta = delta
            if (matcher(snapshot, delta)) {
                matched = snapshot
                true
            } else {
                false
            }
        }
        return requireNotNull(matched) {
            "Expected a matching telemetry snapshot after packet-smoke probe"
        }
    }

    private fun awaitMatchingTelemetrySnapshot(
        before: PacketSmokePrepareState,
        timeoutMs: Long = 20_000L,
        matcher: (ServiceTelemetrySnapshot, PacketSmokeTelemetryDelta) -> Boolean,
    ): ServiceTelemetrySnapshot {
        var matched: ServiceTelemetrySnapshot? = null
        var lastSnapshot: ServiceTelemetrySnapshot? = null
        var lastDelta: PacketSmokeTelemetryDelta? = null
        awaitUntil(
            timeoutMs = timeoutMs,
            failureMessage = { describeTelemetryWaitFailure(lastSnapshot, lastDelta) },
        ) {
            val snapshot = serviceStateStore.telemetry.value
            val delta = snapshot.packetSmokeDeltaFrom(before)
            lastSnapshot = snapshot
            lastDelta = delta
            if (matcher(snapshot, delta)) {
                matched = snapshot
                true
            } else {
                false
            }
        }
        return requireNotNull(matched) {
            "Expected a matching telemetry snapshot after packet-smoke probe"
        }
    }

    private fun describeTelemetryWaitFailure(
        snapshot: ServiceTelemetrySnapshot?,
        delta: PacketSmokeTelemetryDelta?,
    ): String {
        if (snapshot == null || delta == null) {
            return "Last packet-smoke telemetry snapshot: unavailable"
        }
        val tunnel = snapshot.tunnelTelemetry
        return buildString {
            append("Last packet-smoke telemetry snapshot: ")
            append("mode=${snapshot.mode}, status=${snapshot.status}, restartCount=${snapshot.restartCount}, ")
            append("deltaTxPackets=${delta.txPackets}, deltaRxPackets=${delta.rxPackets}, ")
            append("deltaTxBytes=${delta.txBytes}, deltaRxBytes=${delta.rxBytes}, ")
            append("deltaDnsQueries=${delta.dnsQueriesTotal}, deltaDnsFailures=${delta.dnsFailuresTotal}, ")
            append("dnsQueriesTotal=${tunnel.dnsQueriesTotal}, dnsFailuresTotal=${tunnel.dnsFailuresTotal}, ")
            append("lastDnsHost=${tunnel.lastDnsHost}, resolverId=${tunnel.resolverId}, ")
            append("resolverProtocol=${tunnel.resolverProtocol}, resolverEndpoint=${tunnel.resolverEndpoint}, ")
            append("lastDnsError=${tunnel.lastDnsError}, ")
            append("resolverFallbackActive=${tunnel.resolverFallbackActive}, ")
            append("resolverFallbackReason=${tunnel.resolverFallbackReason}, ")
            append("txPackets=${snapshot.tunnelStats.txPackets}, rxPackets=${snapshot.tunnelStats.rxPackets}, ")
            append("txBytes=${snapshot.tunnelStats.txBytes}, rxBytes=${snapshot.tunnelStats.rxBytes}")
        }
    }

    private fun FixtureManifestDto.expectedResolverEndpoint(protocol: String): String =
        when (protocol) {
            EncryptedDnsProtocolDoh -> "http://$androidHost:$dnsHttpPort/dns-query"
            EncryptedDnsProtocolDot -> "$fixtureDomain:$dnsDotPort"
            EncryptedDnsProtocolDnsCrypt -> "$fixtureDomain:$dnsDnscryptPort"
            EncryptedDnsProtocolDoq -> "$fixtureDomain:$dnsDoqPort"
            else -> error("Unsupported encrypted DNS protocol: $protocol")
        }

    private fun logPhysicalProbe(
        label: String,
        probe: Any,
    ) {
        println("packet-smoke[$label] deviceProfile=${packetSmokeDeviceProfile().argumentValue} probe=$probe")
    }

    private fun logEncryptedDnsSettings(
        label: String,
        expectedResolverEndpoint: String,
        usesFixtureResolver: Boolean,
    ) {
        val settings = runBlocking { appSettingsRepository.snapshot() }
        println(
            "packet-smoke[$label] " +
                "deviceProfile=${packetSmokeDeviceProfile().argumentValue} " +
                "usesFixtureResolver=$usesFixtureResolver " +
                "expectedResolverEndpoint=$expectedResolverEndpoint " +
                "provider=${settings.dnsProviderId} " +
                "protocol=${settings.encryptedDnsProtocol} " +
                "host=${settings.encryptedDnsHost} " +
                "port=${settings.encryptedDnsPort} " +
                "tlsServerName=${settings.encryptedDnsTlsServerName} " +
                "bootstrapIps=${settings.encryptedDnsBootstrapIpsList.joinToString()} " +
                "dohUrl=${settings.encryptedDnsDohUrl} " +
                "dnscryptProvider=${settings.encryptedDnsDnscryptProviderName}",
        )
    }

    private fun startService(serviceClass: Class<*>) {
        startedServices += serviceClass
        val mode = if (serviceClass == RipDpiVpnService::class.java) Mode.VPN else Mode.Proxy
        assertTrue(serviceController.start(mode) is ServiceStartResult.Accepted)
    }

    private fun stopService(serviceClass: Class<*>) {
        if (startedServices.remove(serviceClass)) {
            serviceController.stop()
        } else {
            appContext.stopService(Intent(appContext, serviceClass))
        }
    }

    private fun awaitServiceStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        awaitServiceStatus(
            serviceStateStore = serviceStateStore,
            status = status,
            mode = mode,
            fixtureClient = if (this::fixtureClient.isInitialized) fixtureClient else null,
        )
    }

    private fun vpnTcpRoundTripResult(
        host: String,
        port: Int,
        payload: String,
        throwOnProbeTimeout: Boolean = true,
    ): AppProcessTcpProbeResult =
        if (isLikelyEmulator()) {
            testProcessTcpRoundTrip(host, port, payload, throwOnBroadcastTimeout = throwOnProbeTimeout)
        } else {
            appProcessTcpRoundTrip(appContext, host, port, payload)
        }

    private fun httpEchoPayloadText(pathToken: String): String =
        "GET /$pathToken HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\nConnection: close\r\n\r\n"
}
