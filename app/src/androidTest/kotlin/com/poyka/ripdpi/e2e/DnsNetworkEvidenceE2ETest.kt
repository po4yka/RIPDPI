package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.core.NativeTun2SocksBridge
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

@HiltAndroidTest
@UninstallModules(Tun2SocksBridgeFactoryModule::class)
class DnsNetworkEvidenceE2ETest {
    @get:Rule(order = 0)
    val fixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val evidenceTunnelFactory = EvidenceTun2SocksBridgeFactory(Tun2SocksNativeBindings())

    @BindValue
    @JvmField
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory = evidenceTunnelFactory

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

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
                fullTunnelMode = true
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
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
                    result.answers.single().startsWith("198.18."),
                )
            } else {
                assertFalse("Resolver outage unexpectedly returned a DNS answer: $result", result.ok)
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
            val faultEvents = events.filter { it.service == "dns_dot" && it.detail.startsWith("fault:") }
            if (mode == EvidenceDnsMode.OUTAGE) {
                assertEquals("Expected one deterministic DoT fault: $events", 1, faultEvents.size)
            } else {
                assertTrue("Unexpected resolver fault: $events", faultEvents.isEmpty())
            }
            assertTrue(
                "Plain or alternate fixture resolver was contacted: $events",
                events.none { it.service in setOf("dns_udp", "dns_http", "dns_dnscrypt", "dns_doq") },
            )
            val socksEvents = events.filter { it.service == "socks5_relay" && it.protocol == "tcp" }
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

    private fun startService(serviceClass: Class<*>) {
        ContextCompat.startForegroundService(appContext, Intent(appContext, serviceClass).setAction(startAction))
    }

    private fun stopService(serviceClass: Class<*>) {
        appContext.startService(Intent(appContext, serviceClass).setAction(stopAction))
    }
}

private enum class EvidenceDnsMode { VIRTUAL, PROXIED, OUTAGE }

private class EvidenceTun2SocksBridgeFactory(
    private val bindings: Tun2SocksBindings,
) : Tun2SocksBridgeFactory {
    @Volatile
    private var fixture: FixtureManifestDto? = null

    fun routeDnsThroughFixtureSocks(value: FixtureManifestDto) {
        fixture = value
    }

    fun reset() {
        fixture = null
    }

    override fun create(): Tun2SocksBridge =
        EvidenceTun2SocksBridge(NativeTun2SocksBridge(bindings)) { config ->
            fixture?.let { target ->
                config.copy(
                    socks5Address = target.androidHost,
                    socks5Port = target.socks5Port,
                    username = null,
                    password = null,
                    routeDnsThroughSocks5 = true,
                )
            } ?: config
        }
}

private class EvidenceTun2SocksBridge(
    private val delegate: Tun2SocksBridge,
    private val transform: (Tun2SocksConfig) -> Tun2SocksConfig,
) : Tun2SocksBridge {
    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
        flowAttributionBridge: Any?,
    ) {
        delegate.start(transform(config), tunFd, flowAttributionBridge)
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
