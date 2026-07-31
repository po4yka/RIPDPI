package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.core.NativeTun2SocksBridge
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBindings
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.SplitTunnelMode
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val VirtualDnsAddress = "198.18.0.53"
private const val VirtualDnsPort = 53
private const val EvidenceProbeTimeoutMs = 8_000L
private const val EvidenceVpnTimeoutMs = 15_000L
private const val EvidenceSignalTimeoutMs = 2_000L
private const val EvidenceCoreExitCode = 19

@HiltAndroidTest
@UninstallModules(Tun2SocksBridgeFactoryModule::class, RipDpiProxyFactoryModule::class)
class DnsNetworkEvidenceE2ETest {
    @get:Rule(order = 0)
    val fixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val evidenceTunnelFactory = EvidenceTun2SocksBridgeFactory(Tun2SocksNativeBindings())
    private val faultingProxyFactory = OrdinaryFaultingProxyFactory("dns-core-crash-behavior")

    @BindValue
    @JvmField
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory = evidenceTunnelFactory

    @BindValue
    @JvmField
    val ripDpiProxyFactory: RipDpiProxyFactory = faultingProxyFactory

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val connectivityManager: ConnectivityManager
        get() = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var settingsBeforeTest: AppSettings
    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto
    private var evidenceContext: NetworkEvidenceActionContext? = null
    private var injected = false

    @Before
    fun setUp() {
        assumeE2eFixtureConfigured()
        hiltRule.inject()
        injected = true
        settingsBeforeTest = runBlocking { appSettingsRepository.snapshot() }
        fixtureClient = LocalFixtureClient.fromInstrumentationArgs()
        fixture = fixtureClient.manifest()
        evidenceContext =
            networkEvidenceActionContextOrNull(InstrumentationRegistry.getArguments())?.also { evidence ->
                assertEquals(evidence.fixtureIdentitySha256, fixtureIdentitySha256(fixture))
                clearNetworkEvidenceActionReceipt(appContext, evidence.receiptFile)
                clearNetworkEvidenceFixtureTranscript(appContext, evidence.gateId)
            }
        stopService(RipDpiProxyService::class.java)
        stopService(RipDpiVpnService::class.java)
        awaitUntil(timeoutMs = EvidenceVpnTimeoutMs) {
            serviceStateStore.status.value.first == AppStatus.Halted &&
                serviceStateStore.telemetry.value.status == AppStatus.Halted
        }
        ensureVpnConsentGranted(appContext)
        fixtureClient.resetFaults()
        fixtureClient.resetEvents()
        evidenceTunnelFactory.reset()
    }

    @After
    fun tearDown() {
        runCatching { shell("svc wifi enable") }
        evidenceTunnelFactory.reset()
        if (injected) {
            try {
                stopService(RipDpiVpnService::class.java)
                stopService(RipDpiProxyService::class.java)
                awaitUntil(timeoutMs = EvidenceVpnTimeoutMs) {
                    serviceStateStore.status.value.first == AppStatus.Halted &&
                        serviceStateStore.telemetry.value.status == AppStatus.Halted
                }
            } finally {
                runBlocking { appSettingsRepository.replace(settingsBeforeTest) }
            }
        }
        if (this::fixtureClient.isInitialized) {
            fixtureClient.resetFaults()
            fixtureClient.resetEvents()
        }
    }

    @Test
    fun virtualVpnResolverUsesTunnelledResolver() {
        runDnsEvidenceAction(
            gateId = "dns-virtual-vpn-resolver",
            mode = EvidenceDnsMode.VIRTUAL,
            expectSuccess = true,
        )
    }

    @Test
    fun proxiedDomainUsesTunnelledResolver() {
        runDnsEvidenceAction(
            gateId = "dns-proxied-through-tunnelled-resolver",
            mode = EvidenceDnsMode.PROXIED,
            expectSuccess = true,
        )
    }

    @Test
    fun encryptedResolverOutageFailsClosed() {
        runDnsEvidenceAction(
            gateId = "dns-no-isp-fallback-on-encrypted-resolver-outage",
            mode = EvidenceDnsMode.OUTAGE,
            expectSuccess = false,
        )
    }

    /**
     * Bootstrap resolution is verified from the packet capture rather than from a
     * fixture transcript, so the query has to be a plaintext UDP exchange with the
     * fixture's own resolver port -- that is the only shape the oracle's
     * `allowlisted-bootstrap-v1` rule accepts. Reaching the fixture directly while
     * the tunnel is up is the same path the evidence markers already use.
     */
    @Test
    fun bootstrapResolutionUsesAllowlistedResolver() {
        val gateId = "dns-allowlisted-bootstrap-resolution"
        val evidence = evidenceContext
        if (evidence != null) {
            assertEquals(gateId, evidence.gateId)
        }
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDot,
            )
            appSettingsRepository.update {
                fullTunnelMode = false
                setSplitTunnelMode(SplitTunnelMode.Include)
                clearSplitTunnelPackages()
                addSplitTunnelPackages(InstrumentationRegistry.getInstrumentation().context.packageName)
                enableCmdSettings = false
                relayEnabled = false
                relayKind = "off"
            }
        }

        val probeService = bindTestProcessDnsProbeService(EvidenceVpnTimeoutMs)
        try {
            val (appUid, testUid) = distinctEvidenceUids()
            val startedAt = SystemClock.elapsedRealtime()
            startService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
            assertTrue(probeService.awaitVpnDefaultNetwork(EvidenceVpnTimeoutMs))
            awaitStableTunnelRuntime(probeService)

            val actionMarker = evidence?.let { emitEvidenceMarker(it.actionWireMarker) }
            val actionMarkerAt = SystemClock.elapsedRealtime()
            val queryHost =
                "bootstrap-${evidence?.correlationId?.take(16) ?: UUID.randomUUID()}.${fixture.fixtureDomain}"
            val signal = EvidenceProbeSignal("dns-bootstrap-${UUID.randomUUID()}")
            val result =
                probeService.dnsProbe(
                    queryHost = queryHost,
                    serverHost = fixture.androidHost,
                    serverPort = fixture.dnsUdpPort,
                    timeoutMs = EvidenceProbeTimeoutMs,
                    signalId = signal.id,
                    probeSignalBinder = signal.binder,
                )
            assertTrue("Bootstrap DNS datagram was never sent", signal.await(EvidenceSignalTimeoutMs))
            assertTrue("Expected the allowlisted bootstrap resolver to answer: $result", result.ok)
            assertEquals(0, result.rcode)
            assertEquals(
                "Bootstrap answer must be the fixture's bound address: ${result.answers}",
                listOf(fixture.dnsAnswerIpv4),
                result.answers,
            )

            val outcomeMarker = evidence?.let { emitEvidenceMarker(it.outcomeWireMarker) }
            val outcomeMarkerAt = SystemClock.elapsedRealtime()

            stopService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.VPN, fixtureClient)
            val events = fixtureClient.events()
            val bootstrapEvents = events.filter { it.service == "dns_udp" && it.detail == queryHost }
            assertEquals("Expected one exact bootstrap query: $events", 1, bootstrapEvents.size)
            val nonAllowlisted =
                events.filter { it.detail == queryHost && it.service != "dns_udp" }
            assertTrue(
                "Bootstrap query reached a resolver outside the allowlist: $nonAllowlisted",
                nonAllowlisted.isEmpty(),
            )

            evidence?.let {
                val transcriptSha = writeNetworkEvidenceFixtureTranscript(appContext, it, queryHost, events)
                writeNetworkEvidenceDnsPassReceipt(
                    context = appContext,
                    evidence = it,
                    facts =
                        NetworkEvidenceDnsFacts.fromObserved(
                            gateId = gateId,
                            queryHost = queryHost,
                            fixture = fixture,
                            transcriptSha256 = transcriptSha,
                            startedAtElapsedRealtimeMs = startedAt,
                            actionMarkerAtElapsedRealtimeMs = actionMarkerAt,
                            outcomeMarkerAtElapsedRealtimeMs = outcomeMarkerAt,
                            finishedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            appUid = appUid,
                            testUid = testUid,
                            actionMarker = requireNotNull(actionMarker),
                            outcomeMarker = requireNotNull(outcomeMarker),
                            dnsProbe = result,
                            tunnelTelemetry = serviceStateStore.telemetry.value.tunnelTelemetry,
                            faultObserved = false,
                            socksTarget = null,
                        ),
                )
            }
        } finally {
            probeService.close()
        }
    }

    @Test
    fun networkSwitchPreservesDnsTunnel() {
        val gateId = "dns-network-switch-behavior"
        val evidence = requireEvidenceContext(gateId)
        configureFixtureDnsTunnel()
        val probeService = bindTestProcessDnsProbeService(EvidenceVpnTimeoutMs)
        var wifiDisabled = false
        try {
            val (appUid, testUid) = distinctEvidenceUids()
            val startedAt = SystemClock.elapsedRealtime()
            startService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
            assertTrue(probeService.awaitVpnDefaultNetwork(EvidenceVpnTimeoutMs))
            awaitStableTunnelRuntime(probeService)
            val before = requireUnderlay(NetworkCapabilities.TRANSPORT_WIFI)

            val actionMarker = emitEvidenceMarker(evidence.actionWireMarker)
            val actionMarkerAt = SystemClock.elapsedRealtime()
            val preQuery = uniqueEvidenceQuery("pre-switch", evidence)
            val preResult = runFixtureUdpProbe(probeService, preQuery, expectSuccess = true)

            shell("svc wifi disable")
            wifiDisabled = true
            val after = awaitUnderlay(NetworkCapabilities.TRANSPORT_CELLULAR)
            awaitStableTunnelRuntime(probeService)
            val duringQuery = uniqueEvidenceQuery("during-switch", evidence)
            val duringResult = runFixtureUdpProbe(probeService, duringQuery, expectSuccess = true)
            val postQuery = uniqueEvidenceQuery("post-switch", evidence)
            val postResult = runFixtureUdpProbe(probeService, postQuery, expectSuccess = true)
            assertTrue("Network switch did not change the underlay", before.networkHandle != after.networkHandle)

            val outcomeMarker = emitEvidenceMarker(evidence.outcomeWireMarker)
            val outcomeMarkerAt = SystemClock.elapsedRealtime()
            writeNetworkEvidenceDnsScenarioPassReceipt(
                context = appContext,
                evidence = evidence,
                observation =
                    scenarioObservation(
                        startedAt,
                        actionMarkerAt,
                        outcomeMarkerAt,
                        appUid,
                        testUid,
                        actionMarker,
                        outcomeMarker,
                        listOf(preResult, duringResult, postResult),
                    ),
                semanticFacts =
                    JSONObject()
                        .put("preSwitchQuerySha256", networkEvidenceDnsQuerySha256V1(preQuery))
                        .put("duringSwitchQuerySha256", networkEvidenceDnsQuerySha256V1(duringQuery))
                        .put("postSwitchQuerySha256", networkEvidenceDnsQuerySha256V1(postQuery))
                        .put("preNetworkSha256", networkEvidenceValueSha256("network", before.networkHandle.toString()))
                        .put("postNetworkSha256", networkEvidenceValueSha256("network", after.networkHandle.toString()))
                        .put("transitionObserved", true)
                        .put("underlayChangeCount", 1)
                        .put("preTunnelResponseCount", 1)
                        .put("postTunnelResponseCount", 1)
                        .put("plainFallbackCount", 0),
            )
        } finally {
            if (wifiDisabled) {
                shell("svc wifi enable")
                awaitUnderlay(NetworkCapabilities.TRANSPORT_WIFI)
            }
            probeService.close()
        }
    }

    @Test
    fun coreCrashFailsDnsClosed() {
        val gateId = "dns-core-crash-behavior"
        val evidence = requireEvidenceContext(gateId)
        configureFixtureDnsTunnel()
        val probeService = bindTestProcessDnsProbeService(EvidenceVpnTimeoutMs)
        try {
            val (appUid, testUid) = distinctEvidenceUids()
            val startedAt = SystemClock.elapsedRealtime()
            startService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
            assertTrue(probeService.awaitVpnDefaultNetwork(EvidenceVpnTimeoutMs))
            awaitStableTunnelRuntime(probeService)
            fixtureClient.setFault(droppedUdpDnsFault())

            val actionMarker = emitEvidenceMarker(evidence.actionWireMarker)
            val actionMarkerAt = SystemClock.elapsedRealtime()
            val corePid = android.os.Process.myPid()
            faultingProxyFactory.injectExit(EvidenceCoreExitCode)
            awaitUntil(timeoutMs = EvidenceVpnTimeoutMs) {
                serviceStateStore.telemetry.value.status == AppStatus.Halted
            }
            assertTrue(
                "Test-process default network did not return to the non-VPN underlay after the core exit",
                probeService.awaitNonVpnDefaultNetwork(EvidenceVpnTimeoutMs),
            )
            val queryHost = uniqueEvidenceQuery("core-crash", evidence)
            val result = runFixtureUdpProbe(probeService, queryHost, expectSuccess = false)
            assertFixtureUdpFault(queryHost)

            val outcomeMarker = emitEvidenceMarker(evidence.outcomeWireMarker)
            val outcomeMarkerAt = SystemClock.elapsedRealtime()
            writeNetworkEvidenceDnsScenarioPassReceipt(
                context = appContext,
                evidence = evidence,
                observation =
                    scenarioObservation(
                        startedAt,
                        actionMarkerAt,
                        outcomeMarkerAt,
                        appUid,
                        testUid,
                        actionMarker,
                        outcomeMarker,
                        listOf(result),
                    ),
                semanticFacts =
                    JSONObject()
                        .put("crashQuerySha256", networkEvidenceDnsQuerySha256V1(queryHost))
                        .put(
                            "encryptedResolverProviderSha256",
                            networkEvidencePacketResolverSha256(fixture.androidHost, fixture.dnsUdpPort),
                        ).put(
                            "faultControlSha256",
                            networkEvidenceValueSha256("fault", "native-core-exit:$EvidenceCoreExitCode"),
                        ).put("corePid", corePid)
                        .put("coreExitCode", EvidenceCoreExitCode)
                        .put("crashObserved", true)
                        .put("encryptedControlCount", 1)
                        .put("plainFallbackCount", 0)
                        .put("successfulResponseAfterCrashCount", 0),
            )
        } finally {
            probeService.close()
        }
    }

    @Test
    fun privateDnsConflictFailsClosed() {
        val gateId = "dns-android-private-dns-conflict"
        val evidence = requireEvidenceContext(gateId)
        val previousMode = shell("settings get global private_dns_mode").trim()
        val previousSpecifier = shell("settings get global private_dns_specifier").trim()
        configureFixtureDnsTunnel()
        val probeService = bindTestProcessDnsProbeService(EvidenceVpnTimeoutMs)
        try {
            shell("settings put global private_dns_mode opportunistic")
            shell("settings delete global private_dns_specifier")
            assertEquals("opportunistic", shell("settings get global private_dns_mode").trim())
            val (appUid, testUid) = distinctEvidenceUids()
            val startedAt = SystemClock.elapsedRealtime()
            startService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
            assertTrue(probeService.awaitVpnDefaultNetwork(EvidenceVpnTimeoutMs))
            awaitStableTunnelRuntime(probeService)
            fixtureClient.setFault(droppedUdpDnsFault())

            val actionMarker = emitEvidenceMarker(evidence.actionWireMarker)
            val actionMarkerAt = SystemClock.elapsedRealtime()
            val queryHost = uniqueEvidenceQuery("private-dns-conflict", evidence)
            val result = runFixtureUdpProbe(probeService, queryHost, expectSuccess = false)
            assertFixtureUdpFault(queryHost)
            val outcomeMarker = emitEvidenceMarker(evidence.outcomeWireMarker)
            val outcomeMarkerAt = SystemClock.elapsedRealtime()

            writeNetworkEvidenceDnsScenarioPassReceipt(
                context = appContext,
                evidence = evidence,
                observation =
                    scenarioObservation(
                        startedAt,
                        actionMarkerAt,
                        outcomeMarkerAt,
                        appUid,
                        testUid,
                        actionMarker,
                        outcomeMarker,
                        listOf(result),
                    ),
                semanticFacts =
                    JSONObject()
                        .put("conflictQuerySha256", networkEvidenceDnsQuerySha256V1(queryHost))
                        .put(
                            "privateDnsProviderSha256",
                            networkEvidencePacketResolverSha256(fixture.androidHost, fixture.dnsUdpPort),
                        ).put("privateDnsModeSha256", networkEvidenceValueSha256("private-dns-mode", "opportunistic"))
                        .put("conflictObserved", true)
                        .put("encryptedControlCount", 1)
                        .put("plainBypassCount", 0)
                        .put("successfulBypassResponseCount", 0),
            )
        } finally {
            restoreGlobalSetting("private_dns_mode", previousMode)
            restoreGlobalSetting("private_dns_specifier", previousSpecifier)
            probeService.close()
        }
    }

    private fun runDnsEvidenceAction(
        gateId: String,
        mode: EvidenceDnsMode,
        expectSuccess: Boolean,
    ) {
        val evidence = evidenceContext
        if (evidence != null) {
            assertEquals(gateId, evidence.gateId)
        }
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDot,
            )
            appSettingsRepository.update {
                fullTunnelMode = false
                setSplitTunnelMode(SplitTunnelMode.Include)
                clearSplitTunnelPackages()
                addSplitTunnelPackages(InstrumentationRegistry.getInstrumentation().context.packageName)
                enableCmdSettings = false
                relayEnabled = false
                relayKind = "off"
            }
        }
        if (mode == EvidenceDnsMode.PROXIED) {
            evidenceTunnelFactory.routeDnsThroughFixtureSocks(fixture)
        }
        if (mode == EvidenceDnsMode.OUTAGE) {
            fixtureClient.setFault(
                FixtureFaultSpecDto(
                    target = FixtureFaultTargetDto.DNS_DOT,
                    outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
                    scope = FixtureFaultScopeDto.PERSISTENT,
                    delayMs = 1_500,
                ),
            )
        }

        val probeService = bindTestProcessDnsProbeService(EvidenceVpnTimeoutMs)
        try {
            val (appUid, testUid) = distinctEvidenceUids()
            val startedAt = SystemClock.elapsedRealtime()
            startService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Running, Mode.VPN, fixtureClient)
            assertTrue(probeService.awaitVpnDefaultNetwork(EvidenceVpnTimeoutMs))
            awaitStableTunnelRuntime(probeService)

            val actionMarker = evidence?.let { emitEvidenceMarker(it.actionWireMarker) }
            val actionMarkerAt = SystemClock.elapsedRealtime()
            val queryHost =
                "${gateId.removePrefix("dns-").take(24)}-${evidence?.correlationId?.take(16) ?: UUID.randomUUID()}" +
                    ".${fixture.fixtureDomain}"
            val signal = EvidenceProbeSignal("dns-evidence-${UUID.randomUUID()}")
            val result =
                probeService.dnsProbe(
                    queryHost = queryHost,
                    serverHost = VirtualDnsAddress,
                    serverPort = VirtualDnsPort,
                    timeoutMs = EvidenceProbeTimeoutMs,
                    signalId = signal.id,
                    probeSignalBinder = signal.binder,
                )
            assertTrue("DNS evidence probe did not enter the VPN", signal.await(EvidenceSignalTimeoutMs))
            if (expectSuccess) {
                assertTrue("Expected encrypted DNS success: $result", result.ok)
                assertEquals(0, result.rcode)
                assertEquals(1, result.answers.size)
                assertTrue(
                    "Expected a synthetic MapDNS answer, got ${result.answers}",
                    isSyntheticMapDnsIpv4Answer(result.answers.single()),
                )
            } else {
                val successfulAnswer = result.ok && result.rcode == 0 && result.answers.isNotEmpty()
                assertFalse("Resolver outage unexpectedly returned a successful DNS answer: $result", successfulAnswer)
                assertTrue(
                    "Resolver outage must time out or return SERVFAIL without answers: $result",
                    !result.ok || (result.rcode == 2 && result.answers.isEmpty()),
                )
            }

            val outcomeMarker = evidence?.let { emitEvidenceMarker(it.outcomeWireMarker) }
            val outcomeMarkerAt = SystemClock.elapsedRealtime()
            awaitUntil(timeoutMs = EvidenceVpnTimeoutMs) {
                val telemetry = serviceStateStore.telemetry.value.tunnelTelemetry
                when (mode) {
                    EvidenceDnsMode.PROXIED -> telemetry.relayDnsRoute == "socks5" && telemetry.relayDnsFailClosed
                    EvidenceDnsMode.OUTAGE -> telemetry.dnsQueriesTotal >= 1 && telemetry.dnsFailuresTotal >= 1
                    EvidenceDnsMode.VIRTUAL -> telemetry.dnsQueriesTotal >= 1
                }
            }
            val tunnelTelemetry = serviceStateStore.telemetry.value.tunnelTelemetry
            if (mode == EvidenceDnsMode.PROXIED) {
                assertEquals("socks5", tunnelTelemetry.relayDnsRoute)
                assertTrue(tunnelTelemetry.relayDnsFailClosed)
            }
            if (mode == EvidenceDnsMode.OUTAGE) {
                assertFalse(tunnelTelemetry.resolverFallbackActive)
            }

            stopService(RipDpiVpnService::class.java)
            awaitServiceStatus(serviceStateStore, AppStatus.Halted, Mode.VPN, fixtureClient)
            val events = fixtureClient.events()
            val queryEvents = events.filter { it.service == "dns_dot" && it.detail == queryHost }
            assertEquals("Expected one exact DoT query transcript: $events", 1, queryEvents.size)
            val queryPeer = queryEvents.single().peer
            val faultEvents =
                events.filter {
                    it.service == "dns_dot" && it.peer == queryPeer && it.detail.startsWith("fault:")
                }
            if (mode == EvidenceDnsMode.OUTAGE) {
                assertEquals("Expected one deterministic DoT fault: $events", 1, faultEvents.size)
            } else {
                assertTrue("Unexpected resolver fault: $events", faultEvents.isEmpty())
            }
            val socksEvents =
                events
                    .filter {
                        it.service == "socks5_relay" &&
                            it.protocol == "tcp" &&
                            it.detail == "${fixture.androidHost}:${fixture.dnsDotPort}" &&
                            it.createdAt <= queryEvents.single().createdAt
                    }.maxByOrNull { it.createdAt }
                    ?.let(::listOf)
                    .orEmpty()
            if (mode == EvidenceDnsMode.PROXIED) {
                assertEquals("Expected one fixture SOCKS event for proxied DNS: $events", 1, socksEvents.size)
                assertEquals(
                    "${fixture.androidHost}:${fixture.dnsDotPort}",
                    socksEvents.single().detail,
                )
            } else {
                assertTrue("Unexpected fixture SOCKS event: $events", socksEvents.isEmpty())
            }

            evidence?.let {
                val transcriptSha = writeNetworkEvidenceFixtureTranscript(appContext, it, queryHost, events)
                writeNetworkEvidenceDnsPassReceipt(
                    context = appContext,
                    evidence = it,
                    facts =
                        NetworkEvidenceDnsFacts.fromObserved(
                            gateId = gateId,
                            queryHost = queryHost,
                            fixture = fixture,
                            transcriptSha256 = transcriptSha,
                            startedAtElapsedRealtimeMs = startedAt,
                            actionMarkerAtElapsedRealtimeMs = actionMarkerAt,
                            outcomeMarkerAtElapsedRealtimeMs = outcomeMarkerAt,
                            finishedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            appUid = appUid,
                            testUid = testUid,
                            actionMarker = requireNotNull(actionMarker),
                            outcomeMarker = requireNotNull(outcomeMarker),
                            dnsProbe = result,
                            tunnelTelemetry = tunnelTelemetry,
                            faultObserved = faultEvents.size == 1,
                            socksTarget = socksEvents.singleOrNull()?.detail,
                        ),
                )
            }
        } finally {
            probeService.close()
        }
    }

    @Test
    fun syntheticMapDnsAnswerRangeUsesTheFullRfc2544Prefix() {
        assertTrue(isSyntheticMapDnsIpv4Answer("198.18.0.0"))
        assertTrue(isSyntheticMapDnsIpv4Answer("198.19.255.255"))
        assertFalse(isSyntheticMapDnsIpv4Answer("198.17.255.255"))
        assertFalse(isSyntheticMapDnsIpv4Answer("198.20.0.0"))
        assertFalse(isSyntheticMapDnsIpv4Answer("198.18.0"))
        assertFalse(isSyntheticMapDnsIpv4Answer("198.18.0.256"))
        assertFalse(isSyntheticMapDnsIpv4Answer("2001:db8::1"))
    }

    private fun requireEvidenceContext(gateId: String): NetworkEvidenceActionContext =
        requireNotNull(evidenceContext) { "$gateId requires the source-owned evidence arguments" }.also {
            assertEquals(gateId, it.gateId)
        }

    private fun configureFixtureDnsTunnel() {
        val listenPort = reserveLoopbackPort()
        runBlocking {
            appSettingsRepository.applyFixtureEncryptedDns(
                fixture = fixture,
                proxyPort = listenPort,
                protocol = EncryptedDnsProtocolDot,
            )
            appSettingsRepository.update {
                fullTunnelMode = false
                setSplitTunnelMode(SplitTunnelMode.Include)
                clearSplitTunnelPackages()
                addSplitTunnelPackages(InstrumentationRegistry.getInstrumentation().context.packageName)
                enableCmdSettings = false
                relayEnabled = false
                relayKind = "off"
            }
        }
    }

    private fun uniqueEvidenceQuery(
        phase: String,
        evidence: NetworkEvidenceActionContext,
    ): String = "$phase-${evidence.correlationId.take(16)}.${fixture.fixtureDomain}"

    private fun runFixtureUdpProbe(
        probeService: TestProcessDnsProbeServiceHandle,
        queryHost: String,
        expectSuccess: Boolean,
    ): AppProcessDnsProbeResult {
        val signal = EvidenceProbeSignal("dns-scenario-${UUID.randomUUID()}")
        val result =
            probeService.dnsProbe(
                queryHost = queryHost,
                serverHost = fixture.androidHost,
                serverPort = fixture.dnsUdpPort,
                timeoutMs = EvidenceProbeTimeoutMs,
                signalId = signal.id,
                probeSignalBinder = signal.binder,
            )
        assertTrue("DNS scenario probe was never sent", signal.await(EvidenceSignalTimeoutMs))
        if (expectSuccess) {
            assertTrue("Expected fixture DNS response: $result", result.ok)
            assertEquals(0, result.rcode)
            assertEquals(listOf(fixture.dnsAnswerIpv4), result.answers)
        } else {
            assertFalse(
                "Fail-closed DNS scenario returned an answer: $result",
                result.ok && result.answers.isNotEmpty(),
            )
        }
        return result
    }

    private fun droppedUdpDnsFault(): FixtureFaultSpecDto =
        FixtureFaultSpecDto(
            target = FixtureFaultTargetDto.DNS_UDP,
            outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
            scope = FixtureFaultScopeDto.PERSISTENT,
        )

    private fun assertFixtureUdpFault(queryHost: String) {
        val events = fixtureClient.events()
        assertEquals(1, events.count { it.service == "dns_udp" && it.detail == queryHost })
        assertEquals(1, events.count { it.service == "dns_udp" && it.detail == "fault:DnsTimeout" })
    }

    private fun scenarioObservation(
        startedAt: Long,
        actionMarkerAt: Long,
        outcomeMarkerAt: Long,
        appUid: Int,
        testUid: Int,
        actionMarker: AppProcessTcpProbeResult,
        outcomeMarker: AppProcessTcpProbeResult,
        dnsProbes: List<AppProcessDnsProbeResult>,
    ): NetworkEvidenceDnsScenarioObservation =
        NetworkEvidenceDnsScenarioObservation(
            startedAtElapsedRealtimeMs = startedAt,
            actionMarkerAtElapsedRealtimeMs = actionMarkerAt,
            outcomeMarkerAtElapsedRealtimeMs = outcomeMarkerAt,
            finishedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            appUid = appUid,
            testUid = testUid,
            actionMarker = actionMarker,
            outcomeMarker = outcomeMarker,
            dnsProbes = dnsProbes,
        )

    private fun requireUnderlay(transport: Int): Network =
        connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(transport) == true
        } ?: error("Missing underlay transport $transport")

    private fun awaitUnderlay(transport: Int): Network {
        var result: Network? = null
        awaitUntil(timeoutMs = EvidenceVpnTimeoutMs) {
            result = runCatching { requireUnderlay(transport) }.getOrNull()
            result != null
        }
        return requireNotNull(result)
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    }

    private fun restoreGlobalSetting(
        name: String,
        value: String,
    ) {
        if (value == "null" || value.isBlank()) {
            shell("settings delete global $name")
        } else {
            require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe saved setting value" }
            shell("settings put global $name $value")
        }
    }

    private fun distinctEvidenceUids(): Pair<Int, Int> {
        val appUid = appContext.applicationInfo.uid
        val testUid =
            InstrumentationRegistry
                .getInstrumentation()
                .context.applicationInfo.uid
        assertTrue(appUid > 0 && testUid > 0 && appUid != testUid)
        return appUid to testUid
    }

    private fun emitEvidenceMarker(marker: String): AppProcessTcpProbeResult {
        awaitEvidenceMarkerTransportReady()
        val result =
            appProcessTcpRoundTrip(
                context = appContext,
                host = fixture.androidHost,
                port = fixture.controlPort,
                payload =
                    "GET /manifest HTTP/1.1\r\nHost: fixture.test\r\n" +
                        "X-Ripdpi-Evidence: $marker\r\nConnection: close\r\n\r\n",
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
            )
        assertTrue("Evidence marker failed: $result", result.ok)
        assertNotNull(result.probePid)
        assertEquals(appContext.applicationInfo.uid, result.probeUid)
        return result
    }

    private fun awaitEvidenceMarkerTransportReady() {
        var lastProbe: AppProcessTcpProbeResult? = null
        var lastError: Throwable? = null
        awaitUntil(
            timeoutMs = EvidenceVpnTimeoutMs,
            pollMs = 100,
            failureMessage = {
                "Evidence marker transport did not become ready; lastProbe=$lastProbe lastError=$lastError"
            },
        ) {
            val result =
                runCatching {
                    appProcessTcpRoundTrip(
                        context = appContext,
                        host = fixture.androidHost,
                        port = fixture.controlPort,
                        payload =
                            "GET /health HTTP/1.1\r\nHost: fixture.test\r\n" +
                                "Connection: close\r\n\r\n",
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 2_000,
                    )
                }.onSuccess { probe ->
                    lastProbe = probe
                    lastError = null
                }.onFailure { error ->
                    lastError = error
                }.getOrNull()
            result?.let { probe ->
                val statusLine =
                    probe.response
                        .orEmpty()
                        .lineSequence()
                        .firstOrNull()
                        ?.trimEnd('\r')
                probe.ok &&
                    statusLine == "HTTP/1.1 200 OK" &&
                    probe.probePid != null &&
                    probe.probeUid == appContext.applicationInfo.uid
            } == true
        }
    }

    private fun awaitStableTunnelRuntime(probeService: TestProcessDnsProbeServiceHandle) {
        var observedStarts = evidenceTunnelFactory.successfulStartCount()
        awaitStable(
            timeoutMs = EvidenceVpnTimeoutMs,
            pollMs = 100,
            stablePollCount = 40,
            failureMessage = { serviceStateDebugSummary(serviceStateStore, fixtureClient) },
        ) {
            val currentStarts = evidenceTunnelFactory.successfulStartCount()
            val unchanged = currentStarts > 0 && currentStarts == observedStarts
            observedStarts = currentStarts
            unchanged &&
                serviceStateStore.status.value == AppStatus.Running to Mode.VPN &&
                serviceStateStore.telemetry.value.status == AppStatus.Running &&
                probeService.isVpnDefaultNetwork()
        }
    }

    private fun startService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(appContext, Intent(appContext, serviceClass).setAction(startAction))
    }

    private fun stopService(serviceClass: Class<*>) {
        appContext.startService(Intent(appContext, serviceClass).setAction(stopAction))
    }
}

private fun isSyntheticMapDnsIpv4Answer(value: String): Boolean {
    val bytes = runCatching { InetAddresses.parseNumericAddress(value).address }.getOrNull() ?: return false
    return bytes.size == 4 &&
        (bytes[0].toInt() and 0xFF) == 198 &&
        (bytes[1].toInt() and 0xFE) == 18
}

private enum class EvidenceDnsMode { VIRTUAL, PROXIED, OUTAGE }

private class EvidenceTun2SocksBridgeFactory(
    private val bindings: Tun2SocksBindings,
) : Tun2SocksBridgeFactory {
    @Volatile
    private var fixture: FixtureManifestDto? = null
    private val successfulStarts = AtomicInteger(0)

    fun routeDnsThroughFixtureSocks(value: FixtureManifestDto) {
        fixture = value
    }

    fun reset() {
        fixture = null
        successfulStarts.set(0)
    }

    fun successfulStartCount(): Int = successfulStarts.get()

    override fun create(): Tun2SocksBridge =
        EvidenceTun2SocksBridge(
            delegate = NativeTun2SocksBridge(bindings),
            transform = { config ->
                fixture?.let { target ->
                    config.copy(
                        socks5Address = target.androidHost,
                        socks5Port = target.socks5Port,
                        username = null,
                        password = null,
                        routeDnsThroughSocks5 = true,
                    )
                } ?: config
            },
            onStarted = successfulStarts::incrementAndGet,
        )
}

private class EvidenceTun2SocksBridge(
    private val delegate: Tun2SocksBridge,
    private val transform: (Tun2SocksConfig) -> Tun2SocksConfig,
    private val onStarted: () -> Unit,
) : Tun2SocksBridge {
    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
        flowAttributionBridge: Any?,
    ) {
        delegate.start(transform(config), tunFd, flowAttributionBridge)
        onStarted()
    }

    override suspend fun stop() = delegate.stop()

    override suspend fun stats(): TunnelStats = delegate.stats()

    override suspend fun telemetry(): NativeRuntimeSnapshot = delegate.telemetry()
}

private class EvidenceProbeSignal(
    val id: String,
) {
    private val sent = CountDownLatch(1)
    private val state = AtomicInteger(0)
    private val unexpected = CopyOnWriteArrayList<String>()
    val binder: IBinder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != ProbeSignalDnsDatagramSentCode) return super.onTransact(code, data, reply, flags)
                val received = data.readString()
                if (received != id || !state.compareAndSet(0, 1)) {
                    unexpected += received.orEmpty()
                    reply?.writeInt(0)
                } else {
                    sent.countDown()
                    reply?.writeInt(1)
                }
                return true
            }
        }

    fun await(timeoutMs: Long): Boolean =
        sent.await(timeoutMs, TimeUnit.MILLISECONDS).also {
            assertTrue("Unexpected probe signals: $unexpected", unexpected.isEmpty())
        }
}
