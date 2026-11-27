package com.poyka.ripdpi.e2e

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationServicePreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LocalNetworkPermissionApi
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.ui.journeys.clickTag
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.journeys.home.HomePage
import com.poyka.ripdpi.ui.journeys.pressBack
import com.poyka.ripdpi.ui.journeys.settings.ConnectionPage
import com.poyka.ripdpi.ui.journeys.tagExists
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.AwgTestTags
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

private const val PeerDestination = "192.0.2.77"
private const val ConnectMillis = 30_000L
private const val JourneyMillis = 600_000L
private const val MaxTaps = 3
private const val TapSettleMillis = 3_000L
private const val LogTag = "OwnServerJourney"
private val EchoRequest =
    "GET /owned-tcp HTTP/1.1\r\nHost: fixture.test\r\nX-Padding: ${"x".repeat(256)}\r\nConnection: close\r\n\r\n"

@HiltAndroidTest
class OwnServerJourneyE2ETest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            dataPreset = AutomationDataPreset.CleanHome,
            servicePreset = AutomationServicePreset.Live,
        ),
        ComposeUiTestConfig(testTimeout = JourneyMillis.milliseconds),
    ) {
    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var controller: ServiceController

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Before
    fun grantDeviceAccess() {
        assumeTrue(
            "Requires an owned local VLESS or AmneziaWG peer",
            arguments.containsKey("ripdpi.xrayFixturePort") || arguments.containsKey("ripdpi.awgControlPort"),
        )
        check(isLikelyEmulator()) { "The own-server journey runs on a leased emulator" }
        ensureLocalNetworkAccessGranted(context)
        if (android.os.Build.VERSION.SDK_INT >= LocalNetworkPermissionApi) {
            ensureLocalNetworkAccessGranted(InstrumentationRegistry.getInstrumentation().context)
        }
        ensureVpnConsentGranted(context)
    }

    @After
    fun stopService() {
        try {
            runBlocking { controller.stop() }
        } finally {
            clearTestProbeNetworkEligibility()
        }
    }

    @Test
    fun vlessLinkFromClipboardConnectsThroughVpnAndProxyOnly() =
        run {
            val host = arguments.getString("ripdpi.xrayFixtureHost") ?: "10.0.2.2"
            val control = Control(arguments.getString("ripdpi.xrayControlHost") ?: host, "ripdpi.xrayFixturePort")
            val manifest = JSONObject(control.get("manifest"))
            val link =
                "vless://${manifest.getString("uuid")}@$host:${manifest.getInt("tcpPort")}" +
                    "?type=tcp&security=reality&sni=fixture.test&fp=firefox" +
                    "&pbk=${manifest.getString("publicKey")}&sid=ab12&flow=xtls-rprx-vision#Own%20VLESS"
            step("A new user skips setup and pastes a VLESS link from the clipboard") {
                skipOnboarding()
                pasteFromClipboard(link)
                composeRule.waitForTag(RipDpiTestTags.screen(Route.ProfileImportConfirm()))
                composeRule.clickTag(RipDpiTestTags.ImportAdd)
                composeRule.waitForTag(RipDpiTestTags.ImportConnect, ConnectMillis)
            }
            step("Connect starts the VPN through the server") {
                tapUntilAccepted(RipDpiTestTags.ImportConnect)
                awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = ConnectMillis)
                awaitPeerEcho(control) {
                    testProcessTcpRoundTrip(PeerDestination, 80, EchoRequest, 2_000L, 3_000L).let {
                        it.response.takeIf { _ -> it.ok && it.probeUid != Process.myUid() }
                    }
                }
            }
            step("Status shows the VLESS connection, then the user disconnects") {
                awaitServerName("Own VLESS")
                disconnect()
            }
            step("Proxy only routes a SOCKS connection through the same server") {
                openTab(Route.Connection)
                with(ConnectionPage(composeRule)) {
                    click(proxyChip)
                    awaitSelected(proxyChip)
                }
                composeRule.waitForSettings(settings) { it.ripdpiMode == Mode.Proxy.preferenceValue }
                openTab(Route.Status)
                composeRule.clickTag(RipDpiTestTags.ConnectionActuatorButton)
                awaitServiceStatus(state, AppStatus.Running, Mode.Proxy, timeoutMs = ConnectMillis)
                val proxyPort = runBlocking { settings.snapshot() }.proxyPort
                awaitPeerEcho(control) {
                    runCatching {
                        socksTcpRoundTrip(proxyPort, PeerDestination, 80, EchoRequest.encodeToByteArray())
                            .decodeToString()
                    }.getOrNull()
                }
                awaitServerName("Own VLESS")
                disconnect()
            }
        }

    @Test
    fun awgConfFromClipboardConnectsThroughVpnAndReconnects() =
        run {
            val control = Control(arguments.getString("ripdpi.awgControlHost") ?: "10.0.2.2", "ripdpi.awgControlPort")
            val manifest = JSONObject(control.get("manifest"))
            val endpointPort = manifest.getString("endpoint").substringAfterLast(':')
            val endpointHost = arguments.getString("ripdpi.awgEndpointHost") ?: "10.0.2.2"
            step("A new user skips setup and pastes an AmneziaWG configuration from the clipboard") {
                skipOnboarding()
                pasteFromClipboard(
                    awgConf(manifest.getString("public_key"), "$endpointHost:$endpointPort"),
                )
            }
            step("Connect in the filled editor starts the VPN through the server") {
                composeRule.waitForTag(AwgTestTags.AwgConnectAction, ConnectMillis)
                tapUntilAccepted(AwgTestTags.AwgConnectAction)
                awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = ConnectMillis)
                transferThroughAwg(control, "journey-awg")
            }
            step("Status shows the connected server, then the user disconnects") {
                leaveEditorToAddServer()
                openTab(Route.Status)
                composeRule.waitForTag(RipDpiTestTags.StatusServerMetric, ConnectMillis)
                disconnect()
            }
            step("Connecting again from Status reuses the saved server") {
                composeRule.clickTag(RipDpiTestTags.ConnectionActuatorButton)
                awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = ConnectMillis)
                transferThroughAwg(control, "journey-awg-again")
                composeRule.waitForTag(RipDpiTestTags.StatusServerMetric, ConnectMillis)
                disconnect()
            }
        }

    private fun skipOnboarding() {
        composeRule.waitForTag(RipDpiTestTags.OnboardingSkip, ConnectMillis)
        composeRule.clickTag(RipDpiTestTags.OnboardingSkip)
        composeRule.clickTag(RipDpiTestTags.OnboardingCompletionDone)
        HomePage(composeRule).awaitScreen(ConnectMillis)
    }

    private fun openTab(route: Route) =
        composeRule.waitWithTree("Tab ${route.stableRoute} never opened", ConnectMillis) {
            composeRule.tagExists(RipDpiTestTags.screen(route)) ||
                runCatching {
                    composeRule.onAllNodes(hasTestTag(RipDpiTestTags.bottomNav(route))).onFirst().performClick()
                }.let { false }
        }

    private fun leaveEditorToAddServer() {
        composeRule.waitWithTree("Editor never closed", ConnectMillis) {
            val open =
                composeRule.tagExists(AwgTestTags.AwgConnectAction) ||
                    composeRule.tagExists(RipDpiTestTags.ConnectionAddServerSheet)
            if (open) composeRule.pressBack()
            !open
        }
        composeRule.waitForTag(RipDpiTestTags.screen(Route.ConnectionAddServer), ConnectMillis)
    }

    private fun tapUntilAccepted(tag: String) {
        var taps = 0
        composeRule.waitWithTree("$tag never accepted a tap", ConnectMillis) {
            val waiting = composeRule.exists(hasTestTag(tag) and isEnabled())
            if (waiting && taps < MaxTaps && state.status.value.first == AppStatus.Halted) {
                taps++
                composeRule.onAllNodes(hasTestTag(tag)).onFirst().performClick()
                composeRule.waitForIdle()
                SystemClock.sleep(TapSettleMillis)
            }
            !waiting || state.status.value.first != AppStatus.Halted
        }
        if (taps > 1) Log.w(LogTag, "$tag needed $taps taps")
    }

    private fun pasteFromClipboard(text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("server", text))
        }
        composeRule.clickTag(RipDpiTestTags.StatusAddServer)
        composeRule.waitForTag(RipDpiTestTags.ConnectionAddServerSheet)
        composeRule.clickTag(RipDpiTestTags.ConnectionRelayAddServerPaste)
    }

    private fun awaitServerName(name: String) =
        composeRule.waitWithTree("Status never named $name", ConnectMillis) {
            composeRule.exists(hasTestTag(RipDpiTestTags.StatusServerMetric) and hasAnyDescendant(hasText(name)))
        }

    private fun disconnect() {
        with(HomePage(composeRule)) {
            click(actuatorButton)
            click(disconnectConfirm)
        }
        awaitUntil(timeoutMs = ConnectMillis) { state.status.value.first == AppStatus.Halted }
    }

    private fun awaitPeerEcho(
        control: Control,
        exchange: () -> String?,
    ) {
        val before = JSONObject(control.get("receipts")).getInt("count")
        var response: String? = null
        awaitUntil(timeoutMs = ConnectMillis, failureMessage = { "No echo from the server: $response" }) {
            response = exchange()
            response.orEmpty().contains("xray-owned-echo")
        }
        assertTrue(
            "The server did not record the echo",
            JSONObject(control.get("receipts")).getInt("count") > before,
        )
    }

    private fun transferThroughAwg(
        control: Control,
        label: String,
    ) {
        bindTestProcessDnsProbeService(10_000).use { probe ->
            assertTrue(probe.awaitVpnDefaultNetwork(10_000))
            val id = "$label-${SystemClock.elapsedRealtime()}"
            val result = probe.transfer("download", id, size = 262_144)
            assertTrue("AWG transfer failed: $result", result.getBoolean("ok"))
            assertTrue(result.getInt("uid") != Process.myUid())
            awaitUntil {
                val receipts = JSONArray(control.get("transport-receipts"))
                (0 until receipts.length()).any {
                    val receipt = receipts.getJSONObject(it)
                    receipt.getString("id") == id && receipt.getString("sha256") == result.getString("sha256")
                }
            }
        }
    }

    private fun awgConf(
        peerPublicKey: String,
        endpoint: String,
    ) = """
        [Interface]
        PrivateKey = ${key(7)}
        Address = 10.77.0.2/32, fd77::2/128
        DNS = 10.77.0.1
        MTU = 1330
        Jc = 4
        Jmin = 64
        Jmax = 96
        S1 = 8
        S2 = 12
        H1 = 100-199
        H2 = 200-299
        H3 = 300-399
        H4 = 400-499
        I1 = <b 0xf0f1><r 16><rd 8><rc 8><t>
        I2 = <b 0xf2>
        I3 = <b 0xf3>
        I4 = <b 0xf4>
        I5 = <b 0xf5>
        [Peer]
        PublicKey = $peerPublicKey
        PresharedKey = ${key(5)}
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()

    private fun key(value: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { value.toByte() })

    private class Control(
        private val host: String,
        portArgument: String,
    ) {
        private val port =
            requireNotNull(InstrumentationRegistry.getArguments().getString(portArgument)).toInt().also {
                require(it in 1..65_535)
            }

        fun get(path: String): String {
            val connection = URI("http://$host:$port/$path").toURL().openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                check(connection.responseCode == 200) { "Peer control $path returned ${connection.responseCode}" }
                connection.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
