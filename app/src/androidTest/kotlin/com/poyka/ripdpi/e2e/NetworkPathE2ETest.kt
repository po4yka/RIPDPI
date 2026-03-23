package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import com.poyka.ripdpi.data.ServiceStateStore
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
class NetworkPathE2ETest {
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
        fixtureClient = LocalFixtureClient.fromInstrumentationArgs()
        fixture = fixtureClient.manifest()
        fixtureClient.resetEvents()
        fixtureClient.resetFaults()
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsIp = "1.1.1.1"
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
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)

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
        awaitServiceStatus(AppStatus.Halted, Mode.Proxy)
    }

    @Test
    fun vpnServiceRoutesShellTrafficThroughTunnelAndUpdatesTelemetry() {
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
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val payload = httpEchoPayloadShellLiteral("vpn-e2e")
        val output =
            execShell(
                "sh -c 'printf %b \"$payload\" | toybox nc -w 5 ${fixture.fixtureIpv4} ${fixture.tcpEchoPort}'",
            )
        assertTrue("Expected VPN shell round-trip, got: $output", output.contains("GET /vpn-e2e HTTP/1.1"))

        awaitUntil {
            val snapshot = serviceStateStore.telemetry.value
            snapshot.mode == Mode.VPN &&
                snapshot.status == AppStatus.Running &&
                snapshot.tunnelStats.txPackets > 0 &&
                snapshot.tunnelStats.rxPackets > 0 &&
                snapshot.proxyTelemetry.totalSessions > 0
        }

        stopService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Halted, Mode.VPN)
        awaitUntil {
            serviceStateStore.telemetry.value.status == AppStatus.Halted
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
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
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
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
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
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.TCP_ECHO,
                outcome = FixtureFaultOutcomeDto.TCP_RESET,
            ),
        )

        val payload = httpEchoPayloadShellLiteral("vpn-reset")
        val output =
            execShell(
                "sh -c 'printf %b \"$payload\" | toybox nc -w 5 ${fixture.fixtureIpv4} ${fixture.tcpEchoPort}'",
            )

        assertFalse(output.contains("GET /vpn-reset HTTP/1.1"))
        assertTrue(
            fixtureClient.events().any { event ->
                event.service == "tcp_echo" && event.detail.contains("TcpReset", ignoreCase = true)
            },
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

    private fun httpEchoPayload(pathToken: String): ByteArray =
        "GET /$pathToken HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\nConnection: close\r\n\r\n".encodeToByteArray()

    private fun httpEchoPayloadShellLiteral(pathToken: String): String =
        "GET /$pathToken HTTP/1.1\\r\\nHost: ${fixture.fixtureDomain}\\r\\nConnection: close\\r\\n\\r\\n"
}
