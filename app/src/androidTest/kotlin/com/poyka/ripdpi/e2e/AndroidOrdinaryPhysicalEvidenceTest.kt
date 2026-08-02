package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.core.NativeTun2SocksBridge
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.setStrategyChains
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val OrdinaryObservationFile = "android-ordinary-physical-observations.json"
private const val OrdinaryObservationVersion = "android_ordinary_physical_observations_v1"
private const val OrdinaryObservationResultKey = "ripdpi.ordinaryObservationsBase64"
private const val OrdinaryObservationMaxBytes = 512 * 1024
private const val OrdinaryDnsAttemptLimit = 3
private const val OrdinaryConnectedProbeAttempts = 5
private const val OrdinaryMarkerPrefix = "RIPDPI-ORDINARY-V1"
private const val OrdinaryAttestationPrefix = "RIPDPI-ORDINARY-ATTESTATION-V1"
private const val OrdinaryTimeoutMs = 25_000L
private const val OrdinaryProtectedRestartAction = "transport_failover_restart"

@HiltAndroidTest
@UninstallModules(Tun2SocksBridgeFactoryModule::class, RipDpiProxyFactoryModule::class)
class AndroidOrdinaryPhysicalEvidenceTest {
    @get:Rule(order = 0)
    val fixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val tunnelFactory = OrdinaryTun2SocksBridgeFactory(Tun2SocksNativeBindings())
    private val faultingProxyFactory = OrdinaryFaultingProxyFactory()

    @BindValue
    @JvmField
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory = tunnelFactory

    @BindValue
    @JvmField
    val ripDpiProxyFactory: RipDpiProxyFactory = faultingProxyFactory

    @Inject lateinit var appSettingsRepository: AppSettingsRepository

    @Inject lateinit var serviceStateStore: ServiceStateStore

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val arguments
        get() = InstrumentationRegistry.getArguments()
    private val connectivityManager
        get() = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var settingsBefore: AppSettings
    private lateinit var sourceSha: String
    private lateinit var appApkSha256: String
    private lateinit var testApkSha256: String
    private lateinit var runId: String
    private lateinit var controlIpv4: String
    private lateinit var controlIpv6: String
    private var markerPort: Int = 0
    private var dnsPort: Int = 0
    private var markerRelayPort: Int = 0
    private var fixtureTcpEchoPort: Int = 0
    private var fixtureSocksPort: Int = 0
    private var lastVpnInterface: String = "tun0"
    private val actions = JSONArray()
    private lateinit var hardwareAttestation: JSONObject

    @Before
    fun setUp() {
        assumeE2eFixtureConfigured()
        hiltRule.inject()
        settingsBefore = runBlocking { appSettingsRepository.snapshot() }
        sourceSha = requiredArg("ripdpi.ordinarySourceSha", Regex("[0-9a-f]{40}"))
        appApkSha256 = requiredArg("ripdpi.ordinaryAppApkSha256", Regex("[0-9a-f]{64}"))
        testApkSha256 = requiredArg("ripdpi.ordinaryTestApkSha256", Regex("[0-9a-f]{64}"))
        runId = requiredArg("ripdpi.ordinaryRunId", Regex("[0-9a-f]{64}"))
        controlIpv4 = requiredArg("ripdpi.ordinaryControlIpv4", Regex("[0-9.]{7,15}"))
        controlIpv6 = requiredArg("ripdpi.ordinaryControlIpv6", Regex("[0-9a-fA-F:]{2,64}"))
        markerPort = requiredPort("ripdpi.ordinaryMarkerPort")
        dnsPort = requiredPort("ripdpi.ordinaryDnsPort")
        markerRelayPort = requiredPort("ripdpi.ordinaryMarkerRelayPort")
        fixtureTcpEchoPort = requiredPort("ripdpi.ordinaryTcpEchoPort")
        fixtureSocksPort = requiredPort("ripdpi.ordinarySocksPort")
        require(sourceSha.startsWith(BuildConfig.GIT_COMMIT)) { "APK source binding mismatch" }
        require(appApkSha256 != testApkSha256)
        appContext.deleteFile(OrdinaryObservationFile)
        File(appContext.filesDir, "$OrdinaryObservationFile.tmp").delete()
        require(shell("settings get secure always_on_vpn_app").trim() == appContext.packageName) {
            "Host runner did not activate the exact app as Android always-on VPN"
        }
        require(shell("settings get secure always_on_vpn_lockdown").trim() == "1") {
            "Host runner did not activate Android lockdown"
        }
        shell("cmd appops set ${appContext.packageName} ACTIVATE_VPN allow")
        ensureVpnConsentGranted(appContext)
        tunnelFactory.routeThrough(controlIpv4, fixtureSocksPort)
    }

    @After
    fun tearDown() {
        runCatching { shell("cmd appops set ${appContext.packageName} ACTIVATE_VPN allow") }
        runCatching { shell("svc wifi enable") }
        runCatching { shell("input keyevent KEYCODE_WAKEUP") }
        if (this::settingsBefore.isInitialized) {
            runBlocking { appSettingsRepository.replace(settingsBefore) }
        }
        tunnelFactory.reset()
    }

    @Test
    fun produceSevenOrdinaryPhysicalActions() {
        hardwareAttestation = createHardwareAttestation()
        actions.put(runSteadyAction("ipv4-only", ipv6 = false))
        actions.put(runSteadyAction("dual-stack", ipv6 = true))
        actions.put(runPermissionRevokeClosedAction())
        shell("cmd appops set ${appContext.packageName} ACTIVATE_VPN allow")
        ensureVpnConsentGranted(appContext)
        actions.put(
            runClosedAction("core-fault") {
                val pid = android.os.Process.myPid()
                faultingProxyFactory.injectExit(19)
                awaitUntil(
                    timeoutMs = OrdinaryTimeoutMs,
                ) { serviceStateStore.telemetry.value.status == AppStatus.Halted }
                JSONObject()
                    .put("coreExitCode", 19)
                    .put("corePid", pid)
                    .put("kind", "native-core-exited")
                    .put("observedAtEpochMs", now())
            },
        )
        actions.put(runNetworkSwitchAction())
        actions.put(runSleepWakeAction())
        actions.put(
            runAlwaysOnProtectedAction(),
        )
        val output =
            JSONObject()
                .put("actions", actions)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("appApkSha256", appApkSha256)
                .put("deviceCodename", Build.DEVICE)
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("hardwareAttestation", hardwareAttestation)
                .put("kernelRelease", System.getProperty("os.version"))
                .put("runId", runId)
                .put("sourceSha", sourceSha)
                .put("testApkSha256", testApkSha256)
                .put("version", OrdinaryObservationVersion)
        publishPrivate(output)
        assertTrue(actions.length() == 7)
    }

    private fun runSteadyAction(
        actionId: String,
        ipv6: Boolean,
    ): JSONObject {
        configureAndStart(ipv6)
        val started = now()
        val correlation = correlation(actionId)
        sendMarker(actionId, correlation, "action")
        val eventAt = now()
        val event = JSONObject().put("kind", "mode-applied").put("mode", actionId).put("observedAtEpochMs", eventAt)
        val probes = JSONArray()
        if (actionId == "ipv4-only") {
            probes.put(probe("ipv4-control", "ipv4", controlIpv4, connected = true))
            probes.put(probe("ipv6-only", "ipv6", controlIpv6, connected = false))
        } else {
            probes.put(probe("ipv6-via-tunnel", "ipv6", controlIpv6, connected = true))
        }
        val dns = queryAaaa(actionId)
        val phases = JSONArray().put(capturePhase("steady", expectVpn = true))
        sendMarker(actionId, correlation, "outcome")
        val finished = now()
        return actionDocument(actionId, correlation, started, finished, event, probes, dns, phases)
    }

    private fun runClosedAction(
        actionId: String,
        action: () -> JSONObject,
    ): JSONObject {
        configureAndStart(ipv6 = true)
        val started = now()
        val correlation = correlation(actionId)
        sendMarker(actionId, correlation, "action")
        val event = action()
        awaitTestProcessLockdown()
        val probes = blockedTransitionProbes()
        val phases = JSONArray().put(capturePhase("closed", expectVpn = false))
        sendMarker(actionId, correlation, "outcome")
        val finished = now()
        return actionDocument(actionId, correlation, started, finished, event, probes, JSONObject.NULL, phases)
    }

    private fun runPermissionRevokeClosedAction(): JSONObject {
        configureAndStart(ipv6 = true)
        val started = now()
        val correlation = correlation("forced-revoke")
        sendMarker("forced-revoke", correlation, "action")
        shell("cmd appops set ${appContext.packageName} ACTIVATE_VPN ignore")
        val appOpState = shell("cmd appops get ${appContext.packageName} ACTIVATE_VPN")
        assertTrue("ACTIVATE_VPN was not revoked: $appOpState", appOpState.contains("ignore"))
        awaitTestProcessLockdown()
        val event =
            JSONObject()
                .put("appOpMode", "ignore")
                .put("kind", "vpn-permission-revoke-failed-closed-under-lockdown")
                .put("observedAtEpochMs", now())
        val probes =
            JSONArray()
                .put(probe("post-revoke-ipv4", "ipv4", controlIpv4, connected = false))
                .put(probe("post-revoke-ipv6", "ipv6", controlIpv6, connected = false))
        val closed = capturePhase("closed", expectVpn = false)
        sendMarker("forced-revoke", correlation, "outcome")
        val finished = now()
        return actionDocument(
            "forced-revoke",
            correlation,
            started,
            finished,
            event,
            probes,
            JSONObject.NULL,
            JSONArray().put(closed),
        )
    }

    private fun runNetworkSwitchAction(): JSONObject {
        configureAndStart(ipv6 = false)
        val completedStartCount = tunnelFactory.completedStartCount()
        val before = requireUnderlay(NetworkCapabilities.TRANSPORT_WIFI)
        val started = now()
        val correlation = correlation("wifi-lte-switch")
        sendMarker("wifi-lte-switch", correlation, "action")
        shell("svc wifi disable")
        val after = awaitUnderlay(NetworkCapabilities.TRANSPORT_CELLULAR)
        val event =
            JSONObject()
                .put("fromNetworkHandleSha256", hash("network:${before.networkHandle}"))
                .put("fromTransport", "WIFI")
                .put("kind", "underlay-changed")
                .put("observedAtEpochMs", now())
                .put("toNetworkHandleSha256", hash("network:${after.networkHandle}"))
                .put("toTransport", "CELLULAR")
        var lastObservedStartCount = completedStartCount
        awaitStable(
            timeoutMs = OrdinaryTimeoutMs,
            pollMs = 250,
            stablePollCount = 12,
            failureMessage = {
                "Network handover did not settle on a completed native bridge: " +
                    "starts=${tunnelFactory.completedStartCount()} " +
                    "state=${serviceStateStore.telemetry.value.networkHandoverState}"
            },
        ) {
            val currentStartCount = tunnelFactory.completedStartCount()
            val generationUnchanged = currentStartCount == lastObservedStartCount
            lastObservedStartCount = currentStartCount
            val telemetry = serviceStateStore.telemetry.value
            generationUnchanged &&
                telemetry.status == AppStatus.Running &&
                telemetry.networkHandoverState == NetworkHandoverStates.Revalidated &&
                vpnNetwork() != null &&
                tunnelFactory.completedStartAfter(completedStartCount, ipv6 = false)
        }
        val transitionProbes = JSONArray().put(probe("post-switch-ipv4", "ipv4", controlIpv4, connected = true))
        val protected = capturePhase("protected", expectVpn = true)
        sendMarker("wifi-lte-switch", correlation, "outcome")
        val finished = now()
        shell("svc wifi enable")
        awaitUnderlay(NetworkCapabilities.TRANSPORT_WIFI)
        return actionDocument(
            "wifi-lte-switch",
            correlation,
            started,
            finished,
            event,
            transitionProbes,
            JSONObject.NULL,
            JSONArray().put(protected),
        )
    }

    private fun runSleepWakeAction(): JSONObject {
        configureAndStart(ipv6 = false)
        val started = now()
        val correlation = correlation("sleep-wake")
        val sleepAt = now()
        shell("input keyevent KEYCODE_SLEEP")
        sendMarker("sleep-wake", correlation, "action")
        Thread.sleep(1_000)
        shell("input keyevent KEYCODE_WAKEUP")
        val wakeAt = now()
        awaitStablePhysicalRuntime("after wake", requireInteractive = true)
        val event = JSONObject().put("kind", "device-wake").put("sleepAtEpochMs", sleepAt).put("wakeAtEpochMs", wakeAt)
        val probes = JSONArray().put(probe("post-wake-ipv4", "ipv4", controlIpv4, connected = true))
        val protected = capturePhase("protected", expectVpn = true)
        sendMarker("sleep-wake", correlation, "outcome")
        val finished = now()
        return actionDocument(
            "sleep-wake",
            correlation,
            started,
            finished,
            event,
            probes,
            JSONObject.NULL,
            JSONArray().put(protected),
        )
    }

    private fun awaitStablePhysicalRuntime(
        label: String,
        requireInteractive: Boolean,
    ) {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        var lastObservedStartCount = tunnelFactory.completedStartCount()
        awaitStable(
            timeoutMs = OrdinaryTimeoutMs,
            pollMs = 250,
            stablePollCount = 12,
            failureMessage = {
                val telemetry = serviceStateStore.telemetry.value
                "Physical runtime did not settle $label: " +
                    "starts=${tunnelFactory.completedStartCount()} " +
                    "status=${telemetry.status} interactive=${powerManager.isInteractive}"
            },
        ) {
            val currentStartCount = tunnelFactory.completedStartCount()
            val generationUnchanged = currentStartCount == lastObservedStartCount
            lastObservedStartCount = currentStartCount
            val telemetry = serviceStateStore.telemetry.value
            generationUnchanged &&
                telemetry.status == AppStatus.Running &&
                vpnNetwork() != null &&
                (!requireInteractive || powerManager.isInteractive)
        }
    }

    private fun runAlwaysOnProtectedAction(): JSONObject {
        configureAndStart(ipv6 = false)
        val started = now()
        val correlation = correlation("android-always-on-block")
        sendMarker("android-always-on-block", correlation, "action")
        requestVpnStop()
        Thread.sleep(500)
        awaitUntil(timeoutMs = OrdinaryTimeoutMs) {
            serviceStateStore.telemetry.value.status == AppStatus.Running && vpnNetwork() != null
        }
        val event =
            JSONObject()
                .put("kind", "vpn-stop-absorbed-under-lockdown")
                .put("observedAtEpochMs", now())
                .put("packageName", appContext.packageName)
        val probes = JSONArray().put(probe("post-stop-ipv4", "ipv4", controlIpv4, connected = true))
        val protected = capturePhase("protected", expectVpn = true)
        sendMarker("android-always-on-block", correlation, "outcome")
        val finished = now()
        return actionDocument(
            "android-always-on-block",
            correlation,
            started,
            finished,
            event,
            probes,
            JSONObject.NULL,
            JSONArray().put(protected),
        )
    }

    private fun configureAndStart(ipv6: Boolean) {
        val completedStartCount = tunnelFactory.completedStartCount()
        val wasRunning = serviceStateStore.telemetry.value.status == AppStatus.Running && vpnNetwork() != null
        val wasIpv6Enabled = runBlocking { appSettingsRepository.snapshot().ipv6Enable }
        shell("cmd appops set ${appContext.packageName} ACTIVATE_VPN allow")
        runBlocking {
            appSettingsRepository.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                dnsIp = controlIpv4
                ipv6Enable = ipv6
                fullTunnelMode = true
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
                enableCmdSettings = false
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                setStrategyChains(emptyList(), emptyList())
            }
        }
        val requestedAction =
            when {
                !wasRunning -> startAction
                wasIpv6Enabled == ipv6 -> OrdinaryProtectedRestartAction
                else -> null
            }
        requestedAction?.let { action ->
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, RipDpiVpnService::class.java).setAction(action),
            )
        }
        awaitUntil(timeoutMs = OrdinaryTimeoutMs) {
            val network = vpnNetwork()
            val hasIpv6 =
                network
                    ?.let(connectivityManager::getLinkProperties)
                    ?.linkAddresses
                    ?.any { it.address.address.size == 16 } == true
            serviceStateStore.telemetry.value.status == AppStatus.Running &&
                network != null &&
                hasIpv6 == ipv6 &&
                tunnelFactory.completedStartAfter(completedStartCount, ipv6)
        }
        awaitStablePhysicalRuntime("after configure", requireInteractive = false)
        lastVpnInterface =
            connectivityManager.getLinkProperties(requireNotNull(vpnNetwork()))?.interfaceName ?: lastVpnInterface
    }

    private fun requestVpnStop() {
        appContext.startService(Intent(appContext, RipDpiVpnService::class.java).setAction(stopAction))
        appContext.startService(Intent(appContext, RipDpiProxyService::class.java).setAction(stopAction))
    }

    private fun blockedTransitionProbes(): JSONArray =
        JSONArray()
            .put(probe("transition-ipv4", "ipv4", controlIpv4, connected = false))
            .put(probe("transition-ipv6", "ipv6", controlIpv6, connected = false))

    private fun awaitTestProcessLockdown() {
        var lastIpv4: AppProcessTcpProbeResult? = null
        var lastIpv6: AppProcessTcpProbeResult? = null
        awaitStable(
            timeoutMs = OrdinaryTimeoutMs,
            pollMs = 100,
            stablePollCount = 3,
            failureMessage = {
                "Test-process sockets remained reachable under Android lockdown: " +
                    "ipv4=$lastIpv4 ipv6=$lastIpv6"
            },
        ) {
            val ipv4 = testProcessTcpConnect(controlIpv4, fixtureTcpEchoPort, timeoutMs = 1_000)
            val ipv6 = testProcessTcpConnect(controlIpv6, fixtureTcpEchoPort, timeoutMs = 1_000)
            lastIpv4 = ipv4
            lastIpv6 = ipv6
            !ipv4.ok && !ipv6.ok
        }
    }

    private fun probe(
        id: String,
        family: String,
        target: String,
        connected: Boolean,
    ): JSONObject {
        val started = now()
        val payload = "ordinary-${hash("$runId:$id").take(24)}"
        val result =
            if (connected) {
                connectedProbe(target, payload)
            } else {
                testProcessTcpRoundTrip(
                    host = target,
                    port = fixtureTcpEchoPort,
                    payload = payload,
                    connectTimeoutMs = 4_000,
                    readTimeoutMs = 4_000,
                )
            }
        val finished = now()
        if (connected) {
            assertTrue(
                "$id must connect: $result",
                result.ok && result.response == payload,
            )
        } else {
            assertFalse("$id must fail closed: $result", result.ok)
        }
        return JSONObject()
            .put("error", if (connected) JSONObject.NULL else normalizedFailure(result))
            .put("family", family)
            .put("finishedAtEpochMs", finished)
            .put("id", id)
            .put("outcome", if (connected) "connected" else "blocked")
            .put("startedAtEpochMs", started)
            .put("targetAddress", target)
            .put("targetPort", fixtureTcpEchoPort)
    }

    private fun connectedProbe(
        target: String,
        payload: String,
    ): AppProcessTcpProbeResult {
        lateinit var lastResult: AppProcessTcpProbeResult
        repeat(OrdinaryConnectedProbeAttempts) { attempt ->
            lastResult =
                testProcessTcpRoundTrip(
                    host = target,
                    port = fixtureTcpEchoPort,
                    payload = payload,
                    connectTimeoutMs = 4_000,
                    readTimeoutMs = 4_000,
                )
            if (lastResult.ok && lastResult.response == payload) {
                return lastResult
            }
            if (attempt + 1 < OrdinaryConnectedProbeAttempts) {
                Thread.sleep(250)
            }
        }
        return lastResult
    }

    private fun normalizedFailure(result: AppProcessTcpProbeResult): String =
        when (result.failureKind) {
            "ERRNO" -> if (result.errno in setOf(1, 13)) "permission-denied" else "network-unreachable"
            else -> "timeout"
        }

    private fun queryAaaa(actionId: String): JSONObject {
        val started = now()
        val signalId = "ordinary-dns-${hash("$runId:$actionId").take(16)}"
        var attemptCount = 0
        var result: AppProcessDnsProbeResult? = null
        for (attempt in 1..OrdinaryDnsAttemptLimit) {
            attemptCount = attempt
            val current =
                bindTestProcessDnsProbeService(OrdinaryTimeoutMs).use { probeService ->
                    assertTrue(
                        "Test-process default network did not converge to the physical VPN",
                        probeService.awaitVpnDefaultNetwork(OrdinaryTimeoutMs),
                    )
                    probeService.dnsProbe(
                        queryHost = "$actionId.ordinary.fixture.test",
                        serverHost = controlIpv4,
                        serverPort = dnsPort,
                        queryType = DnsQueryTypeAaaa,
                        transport = DnsTransportTcp,
                        timeoutMs = 4_000,
                        signalId = signalId,
                        probeSignalBinder = acceptingDnsSignalBinder(signalId),
                    )
                }
            result = current
            if (current.ok) {
                break
            }
            Thread.sleep(250)
        }
        val finalResult = requireNotNull(result)
        val finished = now()
        assertTrue(
            "$actionId AAAA probe failed after $attemptCount attempts: " +
                "kind=${finalResult.failureKind} stage=${finalResult.failureStage} " +
                "errno=${finalResult.errno} bound=${finalResult.boundDevice} " +
                "class=${finalResult.errorClass} message=${finalResult.errorMessage}",
            finalResult.ok,
        )
        assertTrue("AAAA probe returned rcode ${finalResult.rcode}", finalResult.rcode == 0)
        val answers = finalResult.answers
        val recordedAnswers =
            if (actionId == "ipv4-only") {
                assertTrue(answers.isEmpty())
                emptyList()
            } else {
                val expected = InetAddress.getByName(controlIpv6).address
                val observed = answers.singleOrNull()?.let(InetAddress::getByName)?.address
                assertTrue(observed?.contentEquals(expected) == true)
                listOf(controlIpv6)
            }
        return JSONObject()
            .put("answers", JSONArray(recordedAnswers))
            .put("attemptCount", attemptCount)
            .put("finishedAtEpochMs", finished)
            .put("queryNameSha256", hash("$actionId.ordinary.fixture.test"))
            .put("responseCode", 0)
            .put("startedAtEpochMs", started)
    }

    private fun acceptingDnsSignalBinder(signalId: String): IBinder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != ProbeSignalDnsDatagramSentCode || data.readString() != signalId) {
                    reply?.writeInt(0)
                } else {
                    reply?.writeInt(1)
                }
                return true
            }
        }

    private fun capturePhase(
        name: String,
        expectVpn: Boolean,
    ): JSONObject {
        val network = vpnNetwork()
        if (expectVpn) requireNotNull(network) else require(network == null)
        val properties = network?.let(connectivityManager::getLinkProperties)
        properties?.interfaceName?.let { lastVpnInterface = it }
        val interfaceName = lastVpnInterface
        val addresses =
            properties?.linkAddresses.orEmpty().map {
                requireNotNull(
                    it.address.hostAddress,
                ).substringBefore('%')
            }
        val ipv4 = addresses.filter { !it.contains(':') }
        val ipv6 = addresses.filter { it.contains(':') && !it.startsWith("fe80", ignoreCase = true) }
        val routes = properties?.routes.orEmpty()
        val ipv4Default =
            routes.any {
                it.isDefaultRoute &&
                    !requireNotNull(it.destination.address.hostAddress).contains(':')
            }
        val ipv6Default =
            routes.any {
                it.isDefaultRoute &&
                    requireNotNull(it.destination.address.hostAddress).contains(':')
            }
        val flags = if (expectVpn) "POINTOPOINT,UP" else "POINTOPOINT,DOWN"
        val header = "7: $interfaceName: <$flags> mtu 1500\n"
        val combined =
            buildString {
                append(header)
                ipv4.forEach { append("    inet $it/32 scope global $interfaceName\n") }
                ipv6.forEach { append("    inet6 $it/128 scope global\n") }
            }
        val ipv6Output =
            buildString {
                append(header)
                ipv6.forEach { append("    inet6 $it/128 scope global\n") }
            }
        val settings =
            "always_on_vpn_app=${shell("settings get secure always_on_vpn_app").trim()}\n" +
                "always_on_vpn_lockdown=${shell("settings get secure always_on_vpn_lockdown").trim()}\n"
        return JSONObject()
            .put("capturedAtEpochMs", now())
            .put(
                "commands",
                JSONObject()
                    .put("connectivity", "vpn_active=$expectVpn\nlockdown_active=true\n")
                    .put(
                        "dnsServers",
                        properties?.dnsServers.orEmpty().joinToString("\n") { "nameserver ${it.hostAddress}" } + "\n",
                    ).put("ip6AddressShow", ipv6Output)
                    .put("ip6RouteShow", if (ipv6Default) "default dev $interfaceName metric 1024\n" else "")
                    .put("ipAddressShow", combined)
                    .put("ipRouteShow", if (ipv4Default) "default dev $interfaceName scope link\n" else "")
                    .put("secureSettings", settings),
            ).put("name", name)
            .put("vpnInterface", interfaceName)
    }

    private fun actionDocument(
        actionId: String,
        correlation: String,
        started: Long,
        finished: Long,
        event: JSONObject,
        probes: JSONArray,
        dns: Any,
        phases: JSONArray,
    ): JSONObject =
        JSONObject()
            .put("actionId", actionId)
            .put("correlationId", correlation)
            .put("dnsObservation", dns)
            .put("event", event)
            .put("fixture", fixtureDocument())
            .put("probes", probes)
            .put("routePhases", phases)
            .put("windowFinishedAtEpochMs", finished)
            .put("windowStartedAtEpochMs", started)

    private fun fixtureDocument(): JSONObject =
        JSONObject()
            .put("controlIpv4", controlIpv4)
            .put("controlIpv6", controlIpv6)
            .put("markerAddress", controlIpv4)
            .put("markerPort", markerPort)
            .put("probePort", fixtureTcpEchoPort)
            .put("tunnelEndpoints", JSONArray().put(controlIpv4))
            .put("tunnelPort", fixtureSocksPort)

    private fun sendMarker(
        actionId: String,
        correlation: String,
        phase: String,
    ) {
        val payload = "$OrdinaryMarkerPrefix:$actionId:$correlation:$phase"
        require(payload.all { it.isLetterOrDigit() || it in "-:" })
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", markerRelayPort), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$payload\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            val response = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            require(response == "OK") { "Fixture marker relay rejected $actionId/$phase" }
        }
        Thread.sleep(75)
    }

    private fun requireUnderlay(transport: Int): Network =
        connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(transport) == true
        } ?: error("Missing underlay transport $transport")

    private fun awaitUnderlay(transport: Int): Network {
        var result: Network? = null
        awaitUntil(timeoutMs = OrdinaryTimeoutMs) {
            result = runCatching { requireUnderlay(transport) }.getOrNull()
            result != null
        }
        return requireNotNull(result)
    }

    private fun vpnNetwork(): Network? =
        connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    private fun requiredArg(
        name: String,
        regex: Regex,
    ): String =
        requireNotNull(
            arguments.getString(name),
        ) { "Missing $name" }.also { require(regex.matches(it)) { "Malformed $name" } }

    private fun requiredPort(name: String): Int =
        requiredArg(name, Regex("[1-9][0-9]{0,4}")).toInt().also {
            require(it <= 65535)
        }

    private fun correlation(actionId: String): String = hash("$runId:$actionId")

    private fun createHardwareAttestation(): JSONObject {
        val challenge =
            MessageDigest.getInstance("SHA-256").digest(
                listOf(
                    OrdinaryAttestationPrefix,
                    sourceSha,
                    appApkSha256,
                    testApkSha256,
                    runId,
                ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
            )
        val alias = "ripdpi-ordinary-${runId.take(16)}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(alias)
        try {
            fun generate(strongBox: Boolean) {
                val builder =
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .setAttestationChallenge(challenge)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox)
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(builder.build())
                    generateKeyPair()
                }
            }
            val requestedStrongBox = runCatching { generate(strongBox = true) }.isSuccess
            if (!requestedStrongBox) {
                keyStore.deleteEntry(alias)
                generate(strongBox = false)
            }
            val chain = requireNotNull(keyStore.getCertificateChain(alias))
            require(chain.size >= 2) { "Hardware attestation certificate chain is incomplete" }
            return JSONObject()
                .put(
                    "certificateChainDerBase64",
                    JSONArray(chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }),
                ).put("challengeSha256", challenge.joinToString("") { "%02x".format(it) })
                .put("requestedStrongBox", requestedStrongBox)
                .put("version", "android_key_attestation_v1")
        } finally {
            keyStore.deleteEntry(alias)
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return descriptor.use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    }

    private fun publishPrivate(value: JSONObject) {
        val payload = (value.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        check(payload.size in 1..OrdinaryObservationMaxBytes)
        val temporary = File(appContext.filesDir, "$OrdinaryObservationFile.tmp")
        val destination = File(appContext.filesDir, OrdinaryObservationFile)
        appContext.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        check(temporary.renameTo(destination))
        instrumentation.addResults(
            Bundle().apply {
                putString(
                    OrdinaryObservationResultKey,
                    Base64.encodeToString(payload, Base64.NO_WRAP),
                )
            },
        )
    }

    private fun now(): Long = System.currentTimeMillis()
}

private class OrdinaryTun2SocksBridgeFactory(
    private val bindings: Tun2SocksNativeBindings,
) : Tun2SocksBridgeFactory {
    @Volatile private var endpoint: Pair<String, Int>? = null

    @Volatile private var lastCompletedIpv6: Boolean? = null

    private val completedStarts = AtomicInteger(0)

    fun routeThrough(
        host: String,
        port: Int,
    ) {
        endpoint = host to port
    }

    fun reset() {
        endpoint = null
    }

    fun completedStartCount(): Int = completedStarts.get()

    fun completedStartAfter(
        previousCount: Int,
        ipv6: Boolean,
    ): Boolean = completedStarts.get() > previousCount && lastCompletedIpv6 == ipv6

    override fun create(): Tun2SocksBridge =
        object : Tun2SocksBridge {
            private val delegate = NativeTun2SocksBridge(bindings)

            override suspend fun start(
                config: Tun2SocksConfig,
                tunFd: Int,
                flowAttributionBridge: Any?,
            ) {
                val target = requireNotNull(endpoint)
                delegate.start(
                    config.copy(
                        socks5Address = target.first,
                        socks5Port = target.second,
                        username = null,
                        password = null,
                        routeDnsThroughSocks5 = true,
                    ),
                    tunFd,
                    flowAttributionBridge,
                )
                lastCompletedIpv6 = config.tunnelIpv6 != null
                completedStarts.incrementAndGet()
            }

            override suspend fun stop() = delegate.stop()

            override suspend fun stats(): TunnelStats = delegate.stats()

            override suspend fun telemetry(): NativeRuntimeSnapshot = delegate.telemetry()
        }
}

internal class OrdinaryFaultingProxyFactory(
    private val faultGateId: String? = null,
) : RipDpiProxyFactory {
    private val forcedExit = AtomicInteger(0)

    @Volatile private var active: RipDpiProxyRuntime? = null

    override fun create(): RipDpiProxyRuntime {
        val delegate = RipDpiProxy(RipDpiProxyNativeBindings())
        if (faultGateId != null &&
            InstrumentationRegistry.getArguments().getString("ripdpi.networkEvidenceGateId") != faultGateId
        ) {
            return delegate
        }
        return object : RipDpiProxyRuntime {
            override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
                active = delegate
                val actual = delegate.startProxy(preferences)
                active = null
                return forcedExit.getAndSet(0).takeIf { it != 0 } ?: actual
            }

            override suspend fun awaitReady(timeoutMillis: Long) = delegate.awaitReady(timeoutMillis)

            override suspend fun stopProxy() = delegate.stopProxy()

            override suspend fun pollTelemetry(): NativeRuntimeSnapshot = delegate.pollTelemetry()

            override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) =
                delegate.updateNetworkSnapshot(snapshot)
        }
    }

    fun injectExit(code: Int) {
        forcedExit.set(code)
        runBlocking { requireNotNull(active).stopProxy() }
    }
}
