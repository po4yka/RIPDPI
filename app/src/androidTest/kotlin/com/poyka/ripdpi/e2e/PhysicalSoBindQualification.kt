package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.FlowAttributionBridge
import com.poyka.ripdpi.services.SoBindToDeviceUidPolicyEligibility
import com.poyka.ripdpi.services.SplitTunnelMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun physicalSoBindQualificationJson(bridge: FlowAttributionBridge): JSONObject =
    bridge.snapshot().let {
        JSONObject()
            .put("unprivilegedBindToDevice", it.unprivilegedBindToDevice)
            .put("uidPolicyEligible", it.uidPolicyEligible)
            .put("uidPolicyArmed", it.uidPolicyArmed)
    }

/** Exercises the real production capability gate; no independently-created bridge can attest runtime state. */
internal class PhysicalSoBindQualification(
    private val context: Context,
    private val eligibility: SoBindToDeviceUidPolicyEligibility,
    private val bridge: FlowAttributionBridge,
    private val settings: AppSettingsRepository,
    private val state: ServiceStateStore,
    private val fixture: FixtureManifestDto,
    private val client: LocalFixtureClient,
) {
    fun runLegacyIfRequired(
        testUid: Int,
        testPackage: String,
        startVpn: () -> Unit,
    ): Boolean {
        val facts = eligibility.qualification()
        if (eligibility.isEligible()) {
            assertEquals(
                "Physical armed proof requires a successful capability probe",
                "supported",
                facts.unprivilegedBindToDevice,
            )
            return false
        }
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("physical_kernel_lt57", arguments.getString("ripdpi.soBindEvidenceProfile"))
        assertEquals("permission_denied", facts.unprivilegedBindToDevice)
        runBlocking {
            settings.applyFixtureEncryptedDns(fixture = fixture, proxyPort = reserveLoopbackPort())
            settings.update {
                fullTunnelMode = false
                ipv6Enable = false
                setSplitTunnelMode(SplitTunnelMode.Include)
                clearSplitTunnelPackages()
                addSplitTunnelPackages(testPackage)
            }
        }
        startVpn()
        awaitServiceStatus(state, AppStatus.Running, Mode.VPN, client)
        assertFalse("Legacy live UID policy must remain disarmed", bridge.snapshot().uidPolicyArmed)
        val blocked =
            testProcessTcpRoundTrip(fixture.androidHost, fixture.tcpEchoPort, "legacy-bind", bindDevice = "tun0")
        assertFalse("Legacy kernel unexpectedly permits SO_BINDTODEVICE", blocked.ok)
        assertEquals("ERRNO", blocked.failureKind)
        assertEquals("bind", blocked.failureStage)
        assertTrue(blocked.errno in setOf(OsConstants.EPERM, OsConstants.EACCES))
        assertEquals(testUid, blocked.probeUid)
        assertTrue(blocked.probePid != Process.myPid())
        val liveness = verifyVpnLiveness(testUid)
        assertFalse("Legacy live UID policy must remain disarmed before evidence", bridge.snapshot().uidPolicyArmed)
        writeEvidence(blocked, liveness)
        return true
    }

    private fun verifyVpnLiveness(testUid: Int): Pair<Int, Int> {
        val arguments = InstrumentationRegistry.getArguments()
        client.resetEvents()
        val before = state.telemetry.value.tunnelStats
        val marker = arguments.getString("ripdpi.soBindRunId").orEmpty()
        val tcpPayload = "GET /legacy-$marker HTTP/1.1\r\nHost: ${fixture.fixtureDomain}\r\n\r\n"
        val tcp = testProcessTcpRoundTrip(fixture.androidHost, fixture.tcpEchoPort, tcpPayload)
        val udpPayload = "legacy-udp-$marker"
        val udp = testProcessUdpRoundTrip(fixture.androidHost, fixture.udpEchoPort, udpPayload)
        assertTrue("Ordinary included-UID VPN TCP failed", tcp.ok)
        assertTrue("Ordinary included-UID VPN UDP failed", udp.ok)
        assertEquals(tcpPayload, tcp.response)
        assertEquals(udpPayload, udp.response)
        assertEquals(testUid, tcp.probeUid)
        assertEquals(testUid, udp.probeUid)
        awaitUntil(timeoutMs = 5_000L, failureMessage = { "Legacy VPN packet path was not observed" }) {
            state.telemetry.value.tunnelStats
                .let { it.txPackets > before.txPackets && it.rxPackets > before.rxPackets }
        }
        val events = client.events()
        val tcpEvents =
            events.count {
                it.matchesEcho(
                    "tcp_echo",
                    "tcp",
                    fixture.tcpEchoPort,
                    tcpPayload.toByteArray().size,
                )
            }
        val udpEvents =
            events.count {
                it.matchesEcho(
                    "udp_echo",
                    "udp",
                    fixture.udpEchoPort,
                    udpPayload.toByteArray().size,
                )
            }
        assertEquals(1, tcpEvents)
        assertEquals(1, udpEvents)
        return tcpEvents to udpEvents
    }

    private fun writeEvidence(
        blocked: AppProcessTcpProbeResult,
        liveness: Pair<Int, Int>,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        val evidence = JSONObject()
        val provenance = listOf("RunId" to 32, "SourceSha" to 40, "AppApkSha256" to 64, "TestApkSha256" to 64)
        provenance.forEach { (field, width) ->
            val value = arguments.getString("ripdpi.soBind$field").orEmpty()
            assertTrue("Missing source-bound evidence argument", value.matches(Regex("[0-9a-f]{$width}")))
            evidence.put(field.replaceFirstChar(Char::lowercaseChar), value)
        }
        val sha = evidence.getString("sourceSha")
        assertTrue(BuildConfig.GIT_COMMIT.matches(Regex("[0-9a-f]{7,40}")) && sha.startsWith(BuildConfig.GIT_COMMIT))
        evidence
            .put("version", "android_so_bind_physical_evidence_v4")
            .put("status", "PASS")
            .put("profile", "physical_kernel_lt57")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceCodename", Build.DEVICE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("kernelFamily", eligibility.qualification().kernelMajorMinorBand)
            .put("qualification", physicalSoBindQualificationJson(bridge))
            .put("realTun", true)
            .put("tunPacketPathObserved", true)
            .put("families", JSONArray())
            .put("mapDns", JSONObject.NULL)
            .put(
                "legacy",
                JSONObject()
                    .put("bindFailureKind", blocked.failureKind)
                    .put("bindFailureStage", blocked.failureStage)
                    .put("bindErrno", blocked.errno)
                    .put("distinctUidVerified", true)
                    .put("vpnTcpRoundTrips", 1)
                    .put("vpnUdpRoundTrips", 1)
                    .put("vpnTcpFixtureEvents", liveness.first)
                    .put("vpnUdpFixtureEvents", liveness.second),
            )
        context.openFileOutput("so-bind-physical-evidence.json", Context.MODE_PRIVATE).bufferedWriter().use {
            it.write(evidence.toString())
        }
    }
}
