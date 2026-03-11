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
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanRequest
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class DiagnosticsNetworkE2ETest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var diagnosticsManager: DiagnosticsManager

    @Inject
    lateinit var historyRepository: DiagnosticsHistoryRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val json = Json { ignoreUnknownKeys = true }
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
            diagnosticsManager.initialize()
            seedLocalProfile()
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
    fun rawPathScanPersistsLocalOnlyResults() {
        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.RAW_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertEquals("completed", detail.session.status)
        assertTrue(detail.results.any { it.probeType == "domain_reachability" && it.outcome == "http_ok" })
        assertTrue(detail.results.any { it.probeType == "dns_resolution" && it.outcome == "dns_match" })
        assertTrue(detail.results.any { it.probeType == "tcp_fat_header" && it.outcome == "tcp_fat_header_ok" })
        assertTrue(detail.snapshots.isNotEmpty())
        assertTrue(detail.events.isNotEmpty())
    }

    @Test
    fun rawPathScanStopsAndResumesProxyService() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = listenPort
                diagnosticsAutoResumeAfterRawScan = true
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)

        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.RAW_PATH) }
        awaitCompletedSession(sessionId)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
    }

    @Test
    fun inPathScanSucceedsWhileProxyServiceIsRunning() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = listenPort
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)

        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(detail.results.any { it.outcome == "dns_match" })
        assertTrue(detail.results.any { it.outcome == "http_ok" })
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
    }

    @Test
    fun inPathScanSucceedsWhileVpnServiceIsRunning() {
        ensureVpnPrepared(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = listenPort
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(detail.results.any { it.outcome == "dns_match" })
        assertTrue(detail.results.any { it.outcome == "http_ok" })
        awaitServiceStatus(AppStatus.Running, Mode.VPN)
    }

    @Test
    fun rawPathScanCapturesLocalDnsTimeoutFault() {
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.DNS_HTTP,
                outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
            ),
        )

        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.RAW_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(detail.results.any { it.probeType == "dns_resolution" && it.outcome == "doh_blocked" })
        assertTrue(detail.events.isNotEmpty())
    }

    @Test
    fun inPathScanCapturesFaultedTargetsWhileProxyServiceStaysRunning() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = listenPort
            }
        }

        startService(RipDpiProxyService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.DNS_HTTP,
                outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
            ),
        )

        val sessionId = runBlocking { diagnosticsManager.startScan(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(detail.results.any { it.probeType == "dns_resolution" && it.outcome == "doh_blocked" })
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
    }

    private suspend fun seedLocalProfile() {
        val listenPort = reserveLoopbackPort()
        val request =
            ScanRequest(
                profileId = "local-e2e",
                displayName = "Local E2E",
                pathMode = ScanPathMode.RAW_PATH,
                domainTargets =
                    listOf(
                        DomainTarget(
                            host = fixture.fixtureDomain,
                            connectIp = fixture.androidHost,
                            httpsPort = 9,
                            httpPort = fixture.dnsHttpPort,
                            httpPath = "/",
                        ),
                    ),
                dnsTargets =
                    listOf(
                        DnsTarget(
                            domain = fixture.fixtureDomain,
                            udpServer = "${fixture.androidHost}:${fixture.dnsUdpPort}",
                            dohUrl = "http://${fixture.androidHost}:${fixture.dnsHttpPort}/dns-query",
                            expectedIps = listOf(fixture.dnsAnswerIpv4),
                        ),
                    ),
                tcpTargets =
                    listOf(
                        TcpTarget(
                            id = "fixture-fat",
                            provider = "fixture-http",
                            ip = fixture.androidHost,
                            port = fixture.dnsHttpPort,
                            hostHeader = fixture.fixtureDomain,
                            fatHeaderRequests = 2,
                        ),
                    ),
                whitelistSni = listOf(fixture.fixtureDomain),
            )

        historyRepository.upsertProfile(
            DiagnosticProfileEntity(
                id = "local-e2e",
                name = "Local-only E2E profile",
                source = "androidTest",
                version = 1,
                requestJson = json.encodeToString(ScanRequest.serializer(), request),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        appSettingsRepository.update {
            diagnosticsActiveProfileId = "local-e2e"
            diagnosticsAutoResumeAfterRawScan = true
            proxyIp = "127.0.0.1"
            proxyPort = listenPort
        }
    }

    private fun awaitCompletedSession(sessionId: String): com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail {
        awaitUntil(timeoutMs = 20_000, pollMs = 100) {
            runBlocking {
                historyRepository.getScanSession(sessionId)?.status == "completed"
            }
        }
        return runBlocking { diagnosticsManager.loadSessionDetail(sessionId) }
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
}
