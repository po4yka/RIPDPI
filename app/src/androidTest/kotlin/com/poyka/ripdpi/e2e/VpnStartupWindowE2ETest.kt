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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val GateStartTimeoutMs = 10_000L
private const val GateClosedObservationMs = 250L
private const val ProbeDatagramSentTimeoutMs = 1_500L
private const val ProbeResponseTimeoutMs = 12_000L
private const val GateReleaseTimeoutMs = 10_000L
private const val StartupWindowAssertionBudgetMs = 4_000L
private const val ControlReadTimeoutMs = 750L
private const val ControlRequestPaddingBytes = 4_096

@HiltAndroidTest
@UninstallModules(Tun2SocksBridgeFactoryModule::class)
class VpnStartupWindowE2ETest {
    @get:Rule(order = 0)
    val e2eFixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @BindValue
    @JvmField
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory =
        StartupWindowGatedTun2SocksBridgeFactory(
            gate = VpnStartupWindowGate,
            nativeBindings = Tun2SocksNativeBindings(),
        )

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private var hiltInjected = false
    private val startedServices = mutableSetOf<Class<*>>()
    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto

    @Before
    fun setUp() {
        assumeE2eFixtureConfigured()
        VpnStartupWindowGate.reset()
        hiltRule.inject()
        hiltInjected = true
        stopService(RipDpiProxyService::class.java)
        stopService(RipDpiVpnService::class.java)
        val environment = prepareE2eEnvironment(appContext)
        fixtureClient = environment.fixtureClient
        fixture = environment.fixture
        assumeTrue(
            "VPN startup-window UDP fixture requires emulator host routing or a direct host address; " +
                "adb reverse loopback cannot carry UDP.",
            isLikelyEmulator() || fixture.androidHost != "127.0.0.1",
        )
        ensureVpnConsentGranted(appContext)
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
        VpnStartupWindowGate.release()
        if (hiltInjected) {
            stopService(RipDpiVpnService::class.java)
            stopService(RipDpiProxyService::class.java)
        }
        if (this::fixtureClient.isInitialized) {
            fixtureClient.resetEvents()
            fixtureClient.resetFaults()
        }
        VpnStartupWindowGate.reset()
    }

    @Test
    fun vpnStartupWindowHoldsDnsPacketUntilNativeReady() =
        runBlocking {
            val listenPort = reserveLoopbackPort()
            appSettingsRepository.update {
                proxyPort = listenPort
                proxyIp = "127.0.0.1"
            }
            fixtureClient.resetEvents()

            val statusHistory = CopyOnWriteArrayList<Pair<AppStatus, Mode>>()
            val statusCollector =
                launch(Dispatchers.Default) {
                    serviceStateStore.status.collect { statusHistory += it }
                }
            val gateCycle = VpnStartupWindowGate.arm()
            val signalId = "vpn-startup-${UUID.randomUUID()}"
            val probeSignalAwaiter = ProbeSignalAwaiter(signalId)
            var dnsProbe: Deferred<AppProcessDnsProbeResult>? = null

            try {
                warmTestNetworkProbeReceiver()
                startService(RipDpiVpnService::class.java)
                gateCycle.awaitStartEntered()
                val startupWindowAssertionStartedAt = SystemClock.elapsedRealtime()
                val queryHost = "startup-${SystemClock.elapsedRealtime()}.${fixture.fixtureDomain}"
                dnsProbe =
                    async(Dispatchers.IO) {
                        testProcessDnsProbe(
                            queryHost = queryHost,
                            serverHost = fixture.androidHost,
                            serverPort = fixture.dnsUdpPort,
                            timeoutMs = ProbeResponseTimeoutMs,
                            signalId = signalId,
                            probeSignalBinder = probeSignalAwaiter.signalBinder,
                        )
                    }

                assertTrue(
                    "Warm test-process DNS probe never sent its UDP datagram into the startup window",
                    probeSignalAwaiter.awaitDnsDatagramSent(ProbeDatagramSentTimeoutMs),
                )
                probeSignalAwaiter.assertNoUnexpectedEvents()
                delay(GateClosedObservationMs)
                assertFalse(
                    "DNS probe completed before native tunnel start was released",
                    requireNotNull(dnsProbe).isCompleted,
                )

                val closedWindowStatuses = statusHistory.toList()
                assertFalse(
                    "VPN reported Running before native tunnel start was released: $closedWindowStatuses",
                    closedWindowStatuses.any { (status, mode) -> status == AppStatus.Running && mode == Mode.VPN },
                )
                val closedWindowEvents = fixtureEventsViaExcludedAppProcess()
                assertNoCorrelatedDnsUdpEvent(
                    events = closedWindowEvents,
                    queryHost = queryHost,
                    message = "Fixture observed DNS UDP before native tunnel start was released",
                )
                val startupWindowAssertionElapsedMs =
                    SystemClock.elapsedRealtime() - startupWindowAssertionStartedAt
                assertTrue(
                    "Startup-window assertions exceeded the fail-closed native-start budget: " +
                        "$startupWindowAssertionElapsedMs ms",
                    startupWindowAssertionElapsedMs < StartupWindowAssertionBudgetMs,
                )

                gateCycle.release()
                awaitServiceStatus(
                    serviceStateStore = serviceStateStore,
                    status = AppStatus.Running,
                    mode = Mode.VPN,
                    timeoutMs = 15_000L,
                )

                val dnsResult =
                    withTimeout(ProbeResponseTimeoutMs) {
                        requireNotNull(dnsProbe).await()
                    }
                assertTrue("Expected DNS probe to succeed after native release: $dnsResult", dnsResult.ok)
                assertEquals("Expected fixture DNS NOERROR: $dnsResult", 0, dnsResult.rcode)
                assertEquals(
                    "Expected exact fixture DNS answer after native release: $dnsResult",
                    listOf(fixture.dnsAnswerIpv4),
                    dnsResult.answers,
                )

                awaitUntil(
                    timeoutMs = 15_000L,
                    failureMessage = { serviceStateDebugSummary(serviceStateStore, fixtureClient) },
                ) {
                    val snapshot = serviceStateStore.telemetry.value
                    snapshot.mode == Mode.VPN &&
                        snapshot.status == AppStatus.Running &&
                        snapshot.tunnelStats.txPackets > 0 &&
                        snapshot.tunnelStats.rxPackets > 0
                }

                val correlatedEvents =
                    fixtureEventsViaExcludedAppProcess().filter { event ->
                        event.service == "dns_udp" && event.protocol == "udp" && event.detail == queryHost
                    }
                assertEquals(
                    "Expected exactly one fixture DNS UDP event for $queryHost, got $correlatedEvents",
                    1,
                    correlatedEvents.size,
                )

                stopService(RipDpiVpnService::class.java)
                awaitServiceStatus(
                    serviceStateStore = serviceStateStore,
                    status = AppStatus.Halted,
                    mode = Mode.VPN,
                    timeoutMs = 15_000L,
                )
                gateCycle.assertClean()
            } finally {
                gateCycle.release()
                dnsProbe?.takeIf { it.isActive }?.cancelAndJoin()
                statusCollector.cancelAndJoin()
            }
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

    private fun fixtureEventsViaExcludedAppProcess(): List<FixtureEventDto> {
        val response =
            appProcessTcpRoundTrip(
                context = appContext,
                host = fixture.androidHost,
                port = fixture.controlPort,
                payload = httpGetFixtureControlPayload("/events"),
                connectTimeoutMs = ControlReadTimeoutMs,
                readTimeoutMs = ControlReadTimeoutMs,
            )
        assertTrue("Fixture control /events request failed via excluded app process: $response", response.ok)
        return parseFixtureEventsHttpResponse(response.response.orEmpty())
    }

    private fun httpGetFixtureControlPayload(path: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: fixture.test\r\n" +
            "Connection: close\r\n" +
            "X-Pad: ${"a".repeat(ControlRequestPaddingBytes)}\r\n\r\n"

    private fun parseFixtureEventsHttpResponse(response: String): List<FixtureEventDto> {
        val body =
            response
                .substringAfter("\r\n\r\n", response.substringAfter("\n\n", ""))
                .trim()
        assertTrue("Fixture /events response did not contain a JSON body: $response", body.startsWith("["))
        val array = JSONArray(body)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                add(
                    FixtureEventDto(
                        service = json.getString("service"),
                        protocol = json.getString("protocol"),
                        peer = json.getString("peer"),
                        target = json.getString("target"),
                        detail = json.getString("detail"),
                        bytes = json.getInt("bytes"),
                        sni = json.optString("sni").takeIf { it.isNotBlank() },
                        createdAt = json.getLong("createdAt"),
                    ),
                )
            }
        }
    }

    private fun assertNoCorrelatedDnsUdpEvent(
        events: List<FixtureEventDto>,
        queryHost: String,
        message: String,
    ) {
        assertFalse(
            "$message: $events",
            events.any { event ->
                event.service == "dns_udp" && event.protocol == "udp" && event.detail == queryHost
            },
        )
    }
}

private class ProbeSignalAwaiter(
    private val signalId: String,
) {
    private val unexpectedEvents = CopyOnWriteArrayList<String>()
    private val dnsDatagramSent = CountDownLatch(1)
    private val observedState = AtomicInteger(0)
    val signalBinder: IBinder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean =
                when (code) {
                    ProbeSignalDnsDatagramSentCode -> handleProbeSignal(data, reply)
                    else -> super.onTransact(code, data, reply, flags)
                }
        }

    private fun handleProbeSignal(
        data: Parcel,
        reply: Parcel?,
    ): Boolean {
        val receivedSignalId = data.readString()
        if (receivedSignalId != signalId) {
            unexpectedEvents += "datagram_sent id=$receivedSignalId"
            reply?.writeInt(0)
            return true
        }
        if (!observedState.compareAndSet(0, 1)) {
            unexpectedEvents += "duplicate datagram_sent at state=${observedState.get()}"
            reply?.writeInt(0)
            return true
        }
        dnsDatagramSent.countDown()
        reply?.writeInt(1)
        return true
    }

    fun awaitDnsDatagramSent(timeoutMs: Long): Boolean {
        val sent = dnsDatagramSent.await(timeoutMs, TimeUnit.MILLISECONDS)
        assertNoUnexpectedEvents()
        return sent
    }

    fun assertNoUnexpectedEvents() {
        assertTrue("Unexpected probe signal events: $unexpectedEvents", unexpectedEvents.isEmpty())
    }
}

private class StartupWindowGatedTun2SocksBridgeFactory(
    private val gate: VpnStartupWindowGateController,
    private val nativeBindings: Tun2SocksBindings,
) : Tun2SocksBridgeFactory {
    override fun create(): Tun2SocksBridge =
        StartupWindowGatedTun2SocksBridge(
            gate = gate,
            delegate = NativeTun2SocksBridge(nativeBindings),
        )
}

private class StartupWindowGatedTun2SocksBridge(
    private val gate: VpnStartupWindowGateController,
    private val delegate: Tun2SocksBridge,
) : Tun2SocksBridge {
    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
        flowAttributionBridge: Any?,
    ) {
        gate.awaitReleaseBeforeNativeStart(tunFd)
        delegate.start(config, tunFd, flowAttributionBridge)
    }

    override suspend fun stop() {
        delegate.stop()
    }

    override suspend fun stats(): TunnelStats = delegate.stats()

    override suspend fun telemetry(): NativeRuntimeSnapshot = delegate.telemetry()
}

private object VpnStartupWindowGate : VpnStartupWindowGateController {
    private val lock = Any()
    private var activeCycle: VpnStartupWindowGateCycle? = null

    override fun arm(): VpnStartupWindowGateCycle =
        synchronized(lock) {
            check(activeCycle == null) { "VPN startup window gate is already armed" }
            VpnStartupWindowGateCycle().also { activeCycle = it }
        }

    override suspend fun awaitReleaseBeforeNativeStart(tunFd: Int) {
        val cycle = synchronized(lock) { activeCycle } ?: return
        cycle.markStartEntered(tunFd)
        try {
            cycle.awaitReleaseBeforeNativeStart()
        } finally {
            cycle.markExited()
            synchronized(lock) {
                if (activeCycle === cycle && cycle.isReleased) {
                    activeCycle = null
                }
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            activeCycle?.release()
        }
    }

    override fun reset() {
        synchronized(lock) {
            activeCycle?.release()
            activeCycle = null
        }
    }
}

private interface VpnStartupWindowGateController {
    fun arm(): VpnStartupWindowGateCycle

    suspend fun awaitReleaseBeforeNativeStart(tunFd: Int)

    fun release()

    fun reset()
}

private class VpnStartupWindowGateCycle {
    private val startEntered = CompletableDeferred<Int>()
    private val released = CompletableDeferred<Unit>()
    private val exited = CompletableDeferred<Unit>()
    private val timeoutCount = AtomicInteger(0)

    val isReleased: Boolean
        get() = released.isCompleted

    fun markStartEntered(tunFd: Int) {
        startEntered.complete(tunFd)
    }

    suspend fun awaitStartEntered(): Int =
        withTimeout(GateStartTimeoutMs) {
            startEntered.await()
        }

    suspend fun awaitReleaseBeforeNativeStart() {
        try {
            withTimeout(GateReleaseTimeoutMs) {
                released.await()
            }
        } catch (error: TimeoutCancellationException) {
            timeoutCount.incrementAndGet()
            throw IOException("Timed out waiting for test release before native tunnel start", error)
        }
    }

    fun release() {
        released.complete(Unit)
    }

    fun markExited() {
        exited.complete(Unit)
    }

    suspend fun assertClean() {
        assertTrue("Native start gate was never entered", startEntered.isCompleted)
        assertTrue("Native start gate was not released", released.isCompleted)
        withTimeout(GateStartTimeoutMs) {
            exited.await()
        }
        assertEquals("Native start gate timed out", 0, timeoutCount.get())
    }
}
