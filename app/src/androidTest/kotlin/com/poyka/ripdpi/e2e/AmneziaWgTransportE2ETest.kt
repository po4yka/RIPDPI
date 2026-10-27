package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import javax.inject.Inject

/** Exercises the saved-profile activator, real VpnService and native AWG engine together. */
@HiltAndroidTest
class AmneziaWgTransportE2ETest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var activator: StandaloneAmneziaWgActivator

    @Inject lateinit var profiles: AwgProfileRepository

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var selection: XrayProviderSelectionStore

    @Inject lateinit var state: ServiceStateStore

    @Test
    fun savedRangeProfileRoutesDnsDuplexStreamAndReconnectsThroughTun() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") != "true")
        exerciseSavedRangeProfile()
    }

    @Test
    fun usbSavedRangeProfileRoutesDnsDuplexStreamAndReconnectsThroughTun() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") == "true")
        exerciseSavedRangeProfile()
    }

    private fun exerciseSavedRangeProfile() {
        val args = InstrumentationRegistry.getArguments()
        check(isLikelyEmulator() || args.getString("ripdpi.authorizedPhysicalDevice") == "true")
        val controlPort = requireNotNull(args.getString("ripdpi.awgControlPort")).toInt()
        val host = args.getString("ripdpi.awgControlHost") ?: "10.0.2.2"

        fun control(
            path: String,
            method: String = "GET",
        ): String {
            val connection = URI("http://$host:$controlPort/$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                check(connection.responseCode == 200)
                return connection.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                connection.disconnect()
            }
        }
        hilt.inject()
        val context: Context = ApplicationProvider.getApplicationContext()
        val previousSettings = runBlocking { settings.snapshot() }
        val previousSelection = runBlocking { selection.current() }
        var profileId: String? = null
        var bridge: TestUdpBridge? = null
        try {
            runBlocking { controller.stop() }
            awaitUntil { state.status.value.first == AppStatus.Halted }
            ensureLocalNetworkAccessGranted(context)
            ensureLocalNetworkAccessGranted(InstrumentationRegistry.getInstrumentation().context)
            ensureVpnConsentGranted(context)
            val manifest = JSONObject(control("manifest"))
            bridge = TestUdpBridge.fromArguments()
            val endpoint = manifest.getString("endpoint").split(':')
            val request =
                AwgActivationRequest(
                    profileId = "",
                    privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                    peerPublicKey = manifest.getString("public_key"),
                    presharedKey =
                        Base64.getEncoder().encodeToString(
                            ByteArray(32) {
                                5
                            },
                        ),
                    endpointHost = args.getString("ripdpi.awgEndpointHost") ?: endpoint[0],
                    endpointPort = bridge?.port ?: endpoint[1].toInt(),
                    interfaceAddressV4 = "10.77.0.2/32",
                    interfaceAddressV6 = "fd77::2/128",
                    dnsServers = listOf("10.77.0.1", "fd77::1"),
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                    mtu = 1330,
                    obfuscation =
                        AwgActivationObfuscation(
                            wireProfile = "awg-3.1-ranges-v1",
                            jc = 4,
                            jmin = 64,
                            jmax = 96,
                            s1 = 8,
                            s2 = 12,
                            s3 = 0,
                            s4 = 0,
                            h1 = "100-199",
                            h2 = "200-299",
                            h3 = "300-399",
                            h4 = "400-499",
                            i1 = "<b 0xf0f1><r 16><rd 8><rc 8><t>",
                            i2 = "<b 0xf2>",
                            i3 = "<b 0xf3>",
                            i4 = "<b 0xf4>",
                            i5 = "<b 0xf5>",
                        ),
                )
            runBlocking {
                settings.update {
                    ripdpiMode = Mode.VPN.preferenceValue
                    ipv6Enable = true
                    rootModeEnabled = false
                    setSplitTunnelMode(SplitTunnelMode.Off)
                    clearSplitTunnelPackages()
                    enableCmdSettings = false
                    relayEnabled = false
                }
                profileId = profiles.save("Owned transport acceptance", request)
            }

            fun activateSaved() {
                val saved = runBlocking { requireNotNull(profiles.load(requireNotNull(profileId))).request }
                assertEquals("awg-3.1-ranges-v1", saved.obfuscation.wireProfile)
                assertEquals("400-499", saved.obfuscation.h4)
                runBlocking { activator.activate(saved) }
                awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000)
            }
            activateSaved()
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertTrue(probe.awaitVpnDefaultNetwork(10_000))

                fun transfer(
                    operation: String,
                    suffix: String,
                ) {
                    val id = "awg-$suffix-${SystemClock.elapsedRealtime()}"
                    val result = probe.transfer(operation, id)
                    assertTrue("AWG transaction failed: $result", result.getBoolean("ok"))
                    assertTrue(result.getInt("uid") != Process.myUid())
                    if (operation == "stream") assertTrue(result.getLong("elapsedMs") >= 59_000)
                    awaitUntil {
                        val receipts = JSONArray(control("transport-receipts"))
                        (0 until receipts.length()).any { index ->
                            val receipt = receipts.getJSONObject(index)
                            receipt.getString("id") == id && receipt.getInt("bytes") == result.getInt("bytes") &&
                                receipt.getString("sha256") == result.getString("sha256")
                        }
                    }
                }
                for ((server, queryType, expected) in listOf(
                    Triple("10.77.0.1", DnsQueryTypeA, "192.0.2.77"),
                    Triple("fd77::1", DnsQueryTypeAaaa, "fd77::53"),
                )) {
                    val before = dnsReceiptCount(JSONObject(control("dns-receipts")), queryType)
                    val dns =
                        testProcessDnsProbe(
                            queryHost = "acceptance.test",
                            serverHost = server,
                            queryType = queryType,
                            timeoutMs = 5_000,
                        )
                    assertTrue("AWG DNS through TUN failed for $server", dns.ok)
                    assertTrue(dns.probeUid != null && dns.probeUid != Process.myUid())
                    assertEquals(listOf(InetAddress.getByName(expected)), dns.answers.map(InetAddress::getByName))
                    awaitUntil { dnsReceiptCount(JSONObject(control("dns-receipts")), queryType) > before }
                }
                transfer("download", "download")
                transfer("upload", "upload")
                transfer("stream", "stream")
                control("peer/stop", "POST")
                try {
                    assertFalse(probe.transfer("download", "awg-peer-stopped").getBoolean("ok"))
                } finally {
                    control("peer/start", "POST")
                }
                runBlocking { activator.deactivate() }
                awaitUntil { state.status.value.first == AppStatus.Halted }
                activateSaved()
                assertTrue(probe.awaitVpnDefaultNetwork(10_000))
                transfer("download", "recovered")
                transfer("upload", "recovered-upload")
                // Tunnel telemetry is sampled every 1-5 s; a fresh session may not have a sample yet.
                awaitUntil(failureMessage = { "Tunnel stats: ${state.telemetry.value.tunnelStats}" }) {
                    state.telemetry.value.tunnelStats
                        .let { it.txPackets > 0 && it.rxPackets > 0 }
                }
            }
        } finally {
            runBlocking {
                activator.deactivate()
                controller.stop()
                profileId?.let { profiles.delete(it) }
                selection.update(previousSelection)
                settings.replace(previousSettings)
            }
            try {
                bridge?.close()
            } finally {
                clearTestProbeNetworkEligibility()
            }
        }
    }

    private fun dnsReceiptCount(
        receipt: JSONObject,
        queryType: Int,
    ): Int {
        assertEquals(0, receipt.getInt("errors"))
        val items = receipt.getJSONArray("receipts")
        return (0 until items.length()).count { index ->
            val item = items.getJSONObject(index)
            item.getString("query") == "acceptance.test." && item.getInt("type") == queryType
        }
    }
}
