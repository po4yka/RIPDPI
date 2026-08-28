package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.activities.DiagnosticsXrayProviderController
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayListenerState
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.SplitTunnelMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject

/** Real VpnService, Keystore, gomobile engine and TUN; independent host peer, never a public server. */
@HiltAndroidTest
class XrayProviderE2ETest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var profiles: DurableXrayProfileStore

    @Inject lateinit var selection: XrayProviderSelectionStore

    @Inject lateinit var providerProbes: XrayProviderProbeCoordinator

    private val diagnosticsScope = MainScope()
    private lateinit var diagnostics: DiagnosticsXrayProviderController
    private var initialized = false
    private lateinit var previousSettings: AppSettings
    private lateinit var previousSelection: XrayProviderSelectionRecord
    private var previousProfile: XrayProfile? = null
    private lateinit var manifest: JSONObject
    private var controlPort = 0
    private var failureJob: Job? = null

    @Volatile private var lastFailure: String? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepare() {
        val configuredPort = InstrumentationRegistry.getArguments().getString("ripdpi.xrayFixturePort")
        assumeTrue("Requires independent owned Xray fixture", configuredPort != null)
        check(isLikelyEmulator()) { "This acceptance lane only targets the owned emulator" }
        controlPort = requireNotNull(configuredPort).toInt().also { require(it in 1..65_535) }
        hilt.inject()
        diagnostics = DiagnosticsXrayProviderController(diagnosticsScope, state, providerProbes)
        failureJob =
            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                state.events.collect { event ->
                    if (event is ServiceEvent.Failed) {
                        lastFailure = XrayProfileRedactor.redactText(event.reason.displayMessage)
                    }
                }
            }
        previousSettings = runBlocking { settings.snapshot() }
        previousSelection = runBlocking { selection.current() }
        previousProfile = runBlocking { profiles.load(ProfileId) }
        initialized = true
        controller.stop()
        awaitUntil { state.status.value.first == AppStatus.Halted }
        ensureLocalNetworkAccessGranted(context)
        ensureVpnConsentGranted(context)
        manifest = readControl("manifest")
        runBlocking {
            settings.update {
                ripdpiMode = Mode.VPN.preferenceValue
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                ipv6Enable = false
                rootModeEnabled = false
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
                enableCmdSettings = false
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                setStrategyChains(emptyList(), emptyList())
            }
            settings.applyPacketSmokePlainDns(proxyPort = settings.snapshot().proxyPort, dnsIp = "192.0.2.53")
        }
    }

    @After
    fun restore() {
        try {
            if (!initialized) return
            controller.stop()
            awaitUntil { state.status.value.first == AppStatus.Halted }
            runBlocking {
                previousProfile?.let { profiles.save(ProfileId, it) } ?: profiles.clear(ProfileId)
                selection.update(previousSelection)
                settings.replace(previousSettings)
            }
        } finally {
            diagnosticsScope.cancel()
            failureJob?.cancel()
            clearTestProbeNetworkEligibility()
        }
    }

    @Test
    fun bothTransportsRouteDistinctUidTrafficThroughRealTunAndRestart() {
        for (network in listOf("tcp", "xhttp")) {
            start(network, wrongIdentity = false)
            val before = readControl("receipts").getInt("count")
            val response = exchange("owned-$network")
            assertTrue(
                "A distinct test UID must traverse VPN",
                response.probeUid != null && response.probeUid != Process.myUid(),
            )
            assertTrue("Expected independent peer echo", response.response.orEmpty().contains("xray-owned-echo"))
            assertOwnedDnsThroughProvider()
            awaitUntil {
                val current = state.telemetry.value
                current.tunnelStats.txPackets > 0 && current.tunnelStats.rxPackets > 0 &&
                    readControl("receipts").getInt("count") > before
            }
            assertEquals(
                XrayListenerState.Bound,
                state.telemetry.value.xrayProviderSnapshot
                    ?.listenerState,
            )
            assertTrue(
                state.telemetry.value.xrayProviderSnapshot
                    ?.xrayVersion
                    ?.contains("26.3.27") == true,
            )
            assertLiveDiagnosticsAction()
            controller.stop()
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            awaitUntil { diagnostics.probeReport.value == null }
            assertNull("Stopped provider must unbind its diagnostics action", providerProbes.runProbes())
            assertFalse("TEST-NET destination must not succeed outside the provider", exchange("stopped").ok)
        }
    }

    private fun assertLiveDiagnosticsAction() {
        assertNull("Diagnostics must not run automatically", diagnostics.probeReport.value)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { diagnostics.runProbe() }
        awaitUntil { diagnostics.probeReport.value != null && !diagnostics.probeRunning.value }
        val report = requireNotNull(diagnostics.probeReport.value)
        assertTrue(report.snapshot.xrayVersion?.contains("26.3.27") == true)
        assertEquals(XrayListenerState.Bound, report.snapshot.listenerState)
        assertEquals(
            mapOf(
                XrayProviderProbeKind.Version to true,
                XrayProviderProbeKind.ListenerReadiness to true,
                XrayProviderProbeKind.WrapperPing to true,
                XrayProviderProbeKind.StatApi to false,
            ),
            report.probes.associate { it.kind to it.ok },
        )
        val stat = report.probes.single { it.kind == XrayProviderProbeKind.StatApi }
        assertTrue(
            "Stat API must remain explicitly inapplicable",
            stat.detailRedacted?.contains("not applicable") == true,
        )
    }

    private fun assertOwnedDnsThroughProvider() {
        val before = readControl("dns-receipts").getInt("count")
        val packetsBefore = state.telemetry.value.tunnelStats
        val response = testProcessDnsProbe(queryHost = "owned.test", serverHost = "192.0.2.53", timeoutMs = 3_000L)
        assertTrue(
            "DNS must originate from a distinct test UID",
            response.probeUid != null && response.probeUid != Process.myUid(),
        )
        assertTrue("Owned UDP DNS query must succeed through the active provider", response.ok)
        assertEquals(0, response.rcode)
        assertEquals(listOf("192.0.2.77"), response.answers)
        val receipts = readControl("dns-receipts")
        assertEquals(before + 1, receipts.getInt("count"))
        assertEquals("owned.test.", receipts.getString("lastQuery"))
        awaitUntil {
            val packets = state.telemetry.value.tunnelStats
            packets.txPackets > packetsBefore.txPackets && packets.rxPackets > packetsBefore.rxPackets
        }
        assertEquals(AppStatus.Running, state.status.value.first)
    }

    @Test
    fun wrongIdentityCannotReachPeerOrFallBackToDirect() {
        for (network in listOf("tcp", "xhttp")) {
            val probeUid = assertDirectSentinelReachable("before-$network")
            start(network, wrongIdentity = true)
            val before = readControl("receipts").getInt("count")
            val directBefore = readControl("direct-receipts").getInt("count")
            // TUN may acknowledge TCP before upstream authentication; no application data may return.
            assertTrue(exchange("wrong-identity").response.isNullOrEmpty())
            val direct = exchange("wrong-identity-direct", "10.0.2.2", manifest.getInt("directPort"))
            assertEquals("The same distinct UID must probe while VPN is running", probeUid, direct.probeUid)
            assertTrue(
                "A reachable direct target must return no application data through bad identity",
                direct.response.isNullOrEmpty(),
            )
            assertEquals(
                "Bad identity must not fall back to the direct target",
                directBefore,
                readControl("direct-receipts").getInt("count"),
            )
            assertEquals(before, readControl("receipts").getInt("count"))
            assertEquals(AppStatus.Running, state.status.value.first)
            controller.stop()
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            assertDirectSentinelReachable("after-$network")
        }
    }

    private fun assertDirectSentinelReachable(label: String): Int {
        val before = readControl("direct-receipts").getInt("count")
        val response = exchange(label, "10.0.2.2", manifest.getInt("directPort"))
        assertTrue(
            "Direct baseline must come from a distinct test UID",
            response.probeUid != null && response.probeUid != Process.myUid(),
        )
        assertTrue(
            "Direct baseline must reach the owned sentinel",
            response.ok && response.response.orEmpty().contains("xray-direct-sentinel"),
        )
        assertEquals(
            "The independent direct receipt must confirm the baseline",
            before + 1,
            readControl("direct-receipts").getInt("count"),
        )
        return requireNotNull(response.probeUid)
    }

    private fun start(
        network: String,
        wrongIdentity: Boolean,
    ) {
        val identity = if (wrongIdentity) "00000000-0000-4000-8000-000000000001" else manifest.getString("uuid")
        val port = manifest.getInt(if (network == "tcp") "tcpPort" else "xhttpPort")
        val link =
            "vless://$identity@10.0.2.2:$port?type=$network&security=reality" +
                "&sni=fixture.test&pbk=${manifest.getString("publicKey")}&sid=ab12&fp=chrome" +
                if (network == "xhttp") "&path=%2Fowned-xhttp&mode=auto" else "&flow=xtls-rprx-vision"
        val accepted = XrayImportParser().parse(link, XrayProviderBuildInfo.upstreamTag)
        check(accepted is XrayImportParser.Result.Accepted) { "Owned profile failed typed validation" }
        val profile = accepted.profile.copy(inbound = accepted.profile.inbound.copy(port = reserveLoopbackPort()))
        runBlocking {
            profiles.save(ProfileId, profile)
            assertEquals(profile, profiles.load(ProfileId))
            selection.update(
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                    activeProfileId = ProfileId,
                ),
            )
        }
        assertTrue(controller.start(Mode.VPN) is ServiceStartResult.Accepted)
        awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000L)
        awaitUntil(failureMessage = {
            val telemetry = state.telemetry.value
            "status=${state.status.value} tunnel=${telemetry.tunnelTelemetry.state} " +
                "tunnelStatus=${telemetry.tunnelTelemetryStatus.state} " +
                "cause=${telemetry.tunnelTelemetryStatus.causeClass} " +
                "detail=${XrayProfileRedactor.redactText(telemetry.tunnelTelemetryStatus.message.orEmpty())} " +
                "xray=${telemetry.xrayProviderSnapshot?.readiness}/${telemetry.xrayProviderSnapshot?.failureClass} " +
                "failure=$lastFailure"
        }) {
            state.telemetry.value.xrayProviderSnapshot
                ?.listenerState == XrayListenerState.Bound
        }
    }

    private fun exchange(
        label: String,
        host: String = "192.0.2.77",
        port: Int = 80,
    ): AppProcessTcpProbeResult =
        testProcessTcpRoundTrip(
            host,
            port,
            // The shared probe reads at most the request length, so leave room for the HTTP response headers.
            "GET /$label HTTP/1.1\r\nHost: fixture.test\r\nX-Padding: ${"x".repeat(256)}\r\nConnection: close\r\n\r\n",
            connectTimeoutMs = 2_000L,
            readTimeoutMs = 3_000L,
        )

    private fun readControl(path: String): JSONObject {
        val connection = URI("http://10.0.2.2:$controlPort/$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200)
            JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ProfileId = "xray-provider-acceptance"
    }
}
