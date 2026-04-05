package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

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
    lateinit var diagnosticsBootstrapper: DiagnosticsBootstrapper

    @Inject
    lateinit var diagnosticsScanController: DiagnosticsScanController

    @Inject
    lateinit var diagnosticsDetailLoader: DiagnosticsDetailLoader

    @Inject
    lateinit var diagnosticsResolverActions: DiagnosticsResolverActions

    @Inject
    lateinit var profileCatalog: DiagnosticsProfileCatalog

    @Inject
    lateinit var scanRecordStore: DiagnosticsScanRecordStore

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Inject
    lateinit var resolverOverrideStore: ResolverOverrideStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val json = Json { ignoreUnknownKeys = true }
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
            diagnosticsBootstrapper.initialize()
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
        val sessionId = runBlocking { startScanSessionId(ScanPathMode.RAW_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertEquals("completed", detail.session.status)
        assertTrue(detail.results.any { it.probeType == "domain_reachability" && it.outcome == "http_ok" })
        assertTrue(
            detail.results.any { result ->
                result.probeType == "dns_integrity" && result.outcome in rawPathDnsSuccessOutcomes()
            },
        )
        assertTrue(
            detail.results.any { result ->
                result.probeType == "tcp_fat_header" &&
                    result.outcome in setOf("tcp_fat_header_ok", "tcp_16kb_blocked")
            },
        )
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

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.RAW_PATH) }
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

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(
            detail.results.any { result ->
                result.probeType == "dns_integrity" && result.outcome in inPathDnsSuccessOutcomes()
            },
        )
        assertTrue(detail.results.any { it.outcome == "http_ok" })
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
    }

    @Test
    fun inPathScanSucceedsWhileVpnServiceIsRunning() {
        ensureVpnConsentGranted(appContext)

        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = listenPort
            }
        }

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(
            detail.results.any { result ->
                result.probeType == "dns_integrity" && result.outcome in inPathDnsSuccessOutcomes()
            },
        )
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

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.RAW_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(
            detail.results.any { result ->
                result.probeType == "dns_integrity" && result.outcome in rawPathDnsFaultOutcomes()
            },
        )
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

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)

        assertTrue(
            detail.results.any { result ->
                result.probeType == "dns_integrity" && result.outcome in inPathDnsFaultOutcomes()
            },
        )
        awaitServiceStatus(AppStatus.Running, Mode.Proxy)
    }

    @Test
    fun inPathVpnScanAppliesTemporaryResolverOverrideFromConnectivityRecommendation() {
        ensureVpnConsentGranted(appContext)
        runBlocking {
            appSettingsRepository.update {
                diagnosticsActiveProfileId = "resolver-recommendation"
                diagnosticsAutoResumeAfterRawScan = true
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsMode = "plain_udp"
                dnsIp = "9.9.9.9"
            }
            seedResolverRecommendationProfile()
        }
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.DNS_UDP,
                outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
            ),
        )

        startService(RipDpiVpnService::class.java)
        awaitServiceStatus(AppStatus.Running, Mode.VPN)

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.IN_PATH) }
        val detail = awaitCompletedSession(sessionId)
        val persisted =
            json.decodeFromString(
                EngineScanReportWire.serializer(),
                runBlocking { scanRecordStore.getScanSession(sessionId)?.reportJson }.orEmpty(),
            )

        assertTrue(detail.results.any { it.probeType == "dns_integrity" && it.outcome == "udp_blocked" })
        assertEquals("cloudflare", persisted.resolverRecommendation?.selectedResolverId)
        assertTrue(persisted.resolverRecommendation?.appliedTemporarily == true)
        assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)

        awaitUntil(timeoutMs = 20_000, pollMs = 250) {
            val tunnelTelemetry = serviceStateStore.telemetry.value.tunnelTelemetry
            tunnelTelemetry.resolverFallbackActive &&
                tunnelTelemetry.resolverId == "cloudflare" &&
                tunnelTelemetry.resolverProtocol == "doh"
        }
    }

    @Test
    fun saveResolverRecommendationPersistsEncryptedDnsToFreshDatastoreRead() {
        runBlocking {
            appSettingsRepository.update {
                diagnosticsActiveProfileId = "resolver-recommendation"
                dnsMode = "plain_udp"
                dnsIp = "9.9.9.9"
            }
            seedResolverRecommendationProfile()
        }
        fixtureClient.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.DNS_UDP,
                outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
            ),
        )

        val sessionId = runBlocking { startScanSessionId(ScanPathMode.RAW_PATH) }
        awaitCompletedSession(sessionId)
        runBlocking {
            diagnosticsResolverActions.saveResolverRecommendation(sessionId)
        }

        val persisted = runBlocking { appSettingsRepository.snapshot() }
        assertEquals("encrypted", persisted.dnsMode)
        assertEquals("cloudflare", persisted.dnsProviderId)

        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val freshStore =
                DataStoreFactory.create(
                    serializer = AppSettingsSerializer,
                    scope = scope,
                    produceFile = { appContext.filesDir.resolve("datastore/app_settings.pb") },
                )
            val reread = runBlocking { freshStore.data.first() }
            assertEquals("encrypted", reread.dnsMode)
            assertEquals("cloudflare", reread.dnsProviderId)
            assertEquals(fixture.androidHost, reread.encryptedDnsHost)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun seedLocalProfile() {
        val listenPort = reserveLoopbackPort()
        val request =
            ProfileSpecWire(
                profileId = "local-e2e",
                displayName = "Local E2E",
                kind = ScanKind.CONNECTIVITY,
                family = DiagnosticProfileFamily.GENERAL,
                executionPolicy =
                    ProfileExecutionPolicyWire(
                        requiresRawPath = false,
                        probePersistencePolicy = ProbePersistencePolicyWire.MANUAL_ONLY,
                    ),
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
                            encryptedDohUrl = "http://${fixture.androidHost}:${fixture.dnsHttpPort}/dns-query",
                            encryptedBootstrapIps = listOf(fixture.androidHost),
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

        profileCatalog.upsertProfile(
            DiagnosticProfileEntity(
                id = "local-e2e",
                name = "Local-only E2E profile",
                source = "androidTest",
                version = 1,
                requestJson = json.encodeToString(ProfileSpecWire.serializer(), request),
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

    private suspend fun seedResolverRecommendationProfile() {
        val request =
            ProfileSpecWire(
                profileId = "resolver-recommendation",
                displayName = "Resolver Recommendation",
                kind = ScanKind.CONNECTIVITY,
                family = DiagnosticProfileFamily.GENERAL,
                executionPolicy =
                    ProfileExecutionPolicyWire(
                        requiresRawPath = false,
                        probePersistencePolicy = ProbePersistencePolicyWire.MANUAL_ONLY,
                    ),
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
                            encryptedResolverId = "cloudflare",
                            encryptedProtocol = "doh",
                            encryptedDohUrl = "http://${fixture.androidHost}:${fixture.dnsHttpPort}/dns-query",
                            encryptedBootstrapIps = listOf(fixture.androidHost),
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

        profileCatalog.upsertProfile(
            DiagnosticProfileEntity(
                id = "resolver-recommendation",
                name = "Resolver recommendation profile",
                source = "androidTest",
                version = 1,
                requestJson = json.encodeToString(ProfileSpecWire.serializer(), request),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun awaitCompletedSession(sessionId: String): com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail {
        awaitUntil(timeoutMs = 45_000, pollMs = 100) {
            runBlocking {
                when (val session = scanRecordStore.getScanSession(sessionId)) {
                    null -> {
                        false
                    }

                    else -> {
                        when (session.status) {
                            "completed" -> {
                                true
                            }

                            "failed" -> {
                                throw AssertionError(
                                    "Diagnostics session failed before completion: ${session.summary}",
                                )
                            }

                            else -> {
                                false
                            }
                        }
                    }
                }
            }
        }
        return runBlocking { diagnosticsDetailLoader.loadSessionDetail(sessionId) }
    }

    private suspend fun startScanSessionId(pathMode: ScanPathMode): String =
        when (val result = diagnosticsScanController.startScan(pathMode)) {
            is DiagnosticsManualScanStartResult.Started -> {
                result.sessionId
            }

            is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                throw AssertionError("Unexpected hidden automatic probe conflict in test: $result")
            }
        }

    private fun rawPathDnsSuccessOutcomes(): Set<String> = setOf("dns_match", "udp_blocked")

    private fun inPathDnsSuccessOutcomes(): Set<String> = setOf("dns_match", "udp_skipped_or_blocked")

    private fun rawPathDnsFaultOutcomes(): Set<String> = setOf("encrypted_dns_blocked", "dns_unavailable")

    private fun inPathDnsFaultOutcomes(): Set<String> = setOf("encrypted_dns_blocked", "dns_unavailable")

    private fun startService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, serviceClass).setAction(startAction),
        )
    }

    private fun stopService(serviceClass: Class<*>) {
        appContext.startService(Intent(appContext, serviceClass).setAction(stopAction))
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
