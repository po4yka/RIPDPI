package com.poyka.ripdpi.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real socket/receipt helpers. No service status, runtime factory or applied receipt is replaced. */
internal class HomeReconnectNetworkAcceptanceSupport(
    private val instrumentation: Instrumentation,
    private val fixtureClient: LocalFixtureClient,
    private val fixture: FixtureManifestDto,
    private val state: ServiceStateStore,
    private val directory: File,
) {
    private val events = JSONArray()
    private val appliedIds = linkedSetOf<String>()

    @Synchronized
    fun record(
        stage: String,
        detail: JSONObject,
    ) {
        events.put(
            JSONObject().put("stage", stage).put("elapsedMillis", SystemClock.elapsedRealtime()).put("detail", detail),
        )
        write("runtime-events.json", events.toString(2))
    }

    @Synchronized
    fun applied(configuration: AppliedRuntimeConfiguration) {
        appliedIds += configuration.runtimeId
        record("applied", configuration.toEvidence())
    }

    fun assertOnlySessions(vararg expected: String) {
        await("Applied observer did not retain the required runtime identities") {
            synchronized(this) { appliedIds.containsAll(expected.toSet()) }
        }
        synchronized(this) {
            assertEquals(
                "Only initial and confirmed replacement runtimes may be acknowledged",
                expected.toSet(),
                appliedIds,
            )
        }
    }

    fun probe(
        stage: String,
        mode: Mode,
        proxyPort: Int,
        expectedDnsProtocol: String,
    ) {
        val probeNonce = "home-${mode.name.lowercase()}-$stage-${UUID.randomUUID()}"
        val queryHost = "r-${UUID.randomUUID().toString().replace("-", "")}.${fixture.fixtureDomain}"
        val payload = "GET /$probeNonce HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\nConnection: close\r\n\r\n"
        val before = state.telemetry.value
        val result =
            if (mode == Mode.Proxy) {
                val response = socksTcpRoundTrip(proxyPort, queryHost, fixture.tcpEchoPort, payload.encodeToByteArray())
                JSONObject().put("ok", true).put("response", response.decodeToString()).put("path", "native_socks")
            } else {
                testUidTcpRoundTrip(queryHost, fixture.tcpEchoPort, payload)
            }
        write(
            "$stage-probe.json",
            result
                .put("token", probeNonce)
                .put("queryHost", queryHost)
                .put("mode", mode.name)
                .toString(2),
        )
        assertTrue("Actual fixture request failed: $result", result.getBoolean("ok"))
        assertEquals("Fixture must echo the complete unique request", payload, result.getString("response"))
        if (mode == Mode.VPN) assertVpnProbe(stage, before, result)
        recordFixtureProof(stage, mode, probeNonce, queryHost, expectedDnsProtocol)
    }

    private fun assertVpnProbe(
        stage: String,
        before: ServiceTelemetrySnapshot,
        result: JSONObject,
    ) {
        val targetUid = instrumentation.targetContext.applicationInfo.uid
        val testUid = instrumentation.context.applicationInfo.uid
        assertTrue("The probe must use the separate test APK UID", targetUid != testUid)
        assertEquals(testUid, result.getInt("probe_uid"))
        assertEquals(
            "Probe default network must be VPN before socket creation",
            1,
            result.getInt("default_network_state_before"),
        )
        assertEquals(
            "Probe default network must remain VPN after exchange",
            1,
            result.getInt("default_network_state_after"),
        )
        await("TUN packet counters did not advance for $stage") {
            val after = state.telemetry.value
            after.tunnelStats.txPackets > before.tunnelStats.txPackets &&
                after.tunnelStats.rxPackets > before.tunnelStats.rxPackets &&
                after.proxyTelemetry.totalSessions > 0
        }
    }

    private fun recordFixtureProof(
        stage: String,
        mode: Mode,
        token: String,
        queryHost: String,
        expectedDnsProtocol: String,
    ) {
        val dnsService =
            when (expectedDnsProtocol) {
                "doh" -> "dns_http"
                "dot" -> "dns_dot"
                else -> error("Unowned DNS fixture protocol: $expectedDnsProtocol")
            }
        val dnsEvents = fixtureClient.events().filter { it.detail.contains(queryHost) }
        assertTrue(
            "No actual $dnsService query for the unique probe hostname: $dnsEvents",
            dnsEvents.any {
                it.service ==
                    dnsService
            },
        )
        assertTrue(
            "A pending/unapplied DNS protocol handled the probe: $dnsEvents",
            dnsEvents.all {
                it.service ==
                    dnsService
            },
        )
        val matchingEvents = fixtureClient.events().filter { it.service == "tcp_echo" && it.detail == "echo" }
        assertTrue("The real fixture did not observe request bytes", matchingEvents.any { it.bytes > 0 })
        record(
            "traffic-$stage",
            JSONObject()
                .put(
                    "mode",
                    mode.name,
                ).put("token", token)
                .put("queryHost", queryHost)
                .put("dnsService", dnsService)
                .put("proxySessions", state.telemetry.value.proxyTelemetry.totalSessions)
                .put("txPackets", state.telemetry.value.tunnelStats.txPackets)
                .put("rxPackets", state.telemetry.value.tunnelStats.rxPackets),
        )
        write("$stage-fixture-events.json", JSONArray(fixtureClient.events().map { it.toEvidence() }).toString(2))
    }

    fun assertTcpListenerOpen(port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        }
    }

    fun assertListenerOpen(port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            socket.soTimeout = 1_000
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            socket.getOutputStream().flush()
            assertTrue(
                "Replacement listener must serve real native SOCKS",
                socket.getInputStream().readNBytes(2).contentEquals(byteArrayOf(5, 0)),
            )
        }
    }

    fun assertListenerClosed(port: Int) {
        val failure =
            runCatching {
                Socket().use {
                    it.connect(
                        InetSocketAddress("127.0.0.1", port),
                        500,
                    )
                }
            }.exceptionOrNull()
        assertTrue("Unapplied/previous listener $port must be closed", failure is java.io.IOException)
    }

    fun write(
        name: String,
        text: String,
    ) {
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(text)
        check(temporary.renameTo(file))
    }

    /** Existing helper calls default UiAutomation; this broadcast path preserves our non-suppressing connection. */
    private fun testUidTcpRoundTrip(
        host: String,
        port: Int,
        payload: String,
    ): JSONObject {
        val context = instrumentation.context
        val result = AtomicReference<JSONObject?>()
        val completed = CountDownLatch(1)
        val intent =
            Intent("com.poyka.ripdpi.debug.PROBE_TCP").apply {
                setClassName(context.packageName, TestNetworkProbeReceiver::class.java.name)
                putExtra("host", host)
                putExtra("port", port)
                putExtra("payload", payload)
                putExtra("connect_timeout_ms", 3_000)
                putExtra("read_timeout_ms", 5_000)
            }
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val extras = getResultExtras(false) ?: Bundle.EMPTY
                    result.set(
                        JSONObject()
                            .put("ok", resultCode == Activity.RESULT_OK && extras.getBoolean("ok"))
                            .put("response", extras.getString("response").orEmpty())
                            .put("probe_uid", extras.getInt("probe_uid", -1))
                            .put("probe_pid", extras.getInt("probe_pid", -1))
                            .put("default_network_state_before", extras.getInt("default_network_state_before", -1))
                            .put("default_network_state_after", extras.getInt("default_network_state_after", -1))
                            .put("local_address", extras.getString("local_address").orEmpty())
                            .put("local_port", extras.getInt("local_port", -1))
                            .put("error_class", extras.getString("error_class").orEmpty())
                            .put("error_message", extras.getString("error_message").orEmpty()),
                    )
                    completed.countDown()
                }
            },
            null,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
        check(completed.await(15, TimeUnit.SECONDS)) { "Separate-UID network probe did not return" }
        return checkNotNull(result.get())
    }

    companion object {
        fun await(
            message: String,
            timeoutMillis: Long = 15_000,
            condition: () -> Boolean,
        ) {
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!condition()) {
                check(SystemClock.uptimeMillis() < deadline) { message }
                SystemClock.sleep(50)
            }
        }
    }
}

internal fun AppliedRuntimeConfiguration.toEvidence(): JSONObject =
    JSONObject()
        .put("runtimeId", runtimeId)
        .put("revision", revision)
        .put("mode", mode.name)
        .put("provider", effectiveSelection.provider)
        .put("dnsProtocol", dns.protocol)
        .put("dnsMode", dns.mode)
        .put("appliedAt", appliedAt)
        .put("reason", reason.name)

private fun FixtureEventDto.toEvidence(): JSONObject =
    JSONObject()
        .put("service", service)
        .put("protocol", protocol)
        .put("detail", detail)
        .put("bytes", bytes)
        .put("peer", peer)
        .put("target", target)
        .put("createdAt", createdAt)

/** Setup only: a real Activity caller opens the platform VPN consent UI before TalkBack is enabled. */
@dagger.hilt.android.testing.HiltAndroidTest
class HomeReconnectVpnConsentTest {
    @get:org.junit.Rule
    val hilt =
        dagger.hilt.android.testing
            .HiltAndroidRule(this)

    @org.junit.Test
    fun prepareRealVpnConsentBeforeTalkBack() {
        requireExternalReconnectDriver()
        hilt.inject()
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        val context = instrumentation.targetContext
        val manager =
            context.getSystemService(
                Context.ACCESSIBILITY_SERVICE,
            ) as android.view.accessibility.AccessibilityManager
        check(
            manager
                .getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN,
                ).none { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" },
        ) {
            "Genuine consent setup must run before TalkBack is enabled"
        }
        val runId =
            checkNotNull(
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                    .getString("ripdpi.acceptanceRunId"),
            )
        check(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val directory = File(context.filesDir, "home-reconnect-acceptance/$runId/consent")
        val ui = HomeReconnectTalkBackSupport(instrumentation, directory, false)
        val consent = android.net.VpnService.prepare(context)
        val activityResultCode = AtomicReference<Int?>()
        if (consent != null) {
            androidx.test.core.app.ActivityScenario
                .launch<com.poyka.ripdpi.activities.MainActivity>(
                    Intent(context, com.poyka.ripdpi.activities.MainActivity::class.java)
                        .putExtra(com.poyka.ripdpi.automation.AutomationLaunchContract.Enabled, false),
                ).use { activity ->
                    lateinit var launcher: ActivityResultLauncher<Intent>
                    // ActivityScenario is already resumed; explicitly own and unregister this one result launcher.
                    activity.onActivity { host ->
                        launcher =
                            host.activityResultRegistry.register(
                                "home-reconnect-consent-$runId",
                                ActivityResultContracts.StartActivityForResult(),
                            ) { result -> activityResultCode.set(result.resultCode) }
                    }
                    try {
                        activity.onActivity { launcher.launch(consent) }
                        ui.observe("vpn-consent")
                        HomeReconnectNetworkAcceptanceSupport.await("No actual platform VPN consent result") {
                            activityResultCode.get() != null
                        }
                        assertEquals("Platform VPN consent was rejected", Activity.RESULT_OK, activityResultCode.get())
                        HomeReconnectNetworkAcceptanceSupport.await("Actual platform VPN consent was not granted") {
                            android.net.VpnService.prepare(context) == null
                        }
                    } finally {
                        activity.onActivity { launcher.unregister() }
                    }
                }
        }
        persistPrivateBytes(
            File(directory, "vpn-consent.json"),
            JSONObject()
                .put("setupOnly", true)
                .put("alreadyGranted", consent == null)
                .put("activityResultCode", activityResultCode.get() ?: JSONObject.NULL)
                .put("genuinePrepareReturnsNull", android.net.VpnService.prepare(context) == null)
                .toString(2)
                .encodeToByteArray(),
        )
    }
}
