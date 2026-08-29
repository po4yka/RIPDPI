package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the typed Xray provider diagnostics surface: stage
 * derivation, transition rules, redaction of every fixture export, and
 * serialization round-trips.
 */
class XrayProviderDiagnosticsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `healthy snapshot derives Connected stage`() {
        val stage = XrayConnectionStage.fromSnapshot(XrayProviderDiagnosticsFixtures.healthy.snapshot)
        assertEquals(XrayConnectionStage.Connected, stage)
    }

    @Test
    fun `config invalid derives ConfigInvalid provider failure distinct from tunnel`() {
        val stage = XrayConnectionStage.fromSnapshot(XrayProviderDiagnosticsFixtures.configInvalid.snapshot)
        assertTrue(stage is XrayConnectionStage.ProviderFailed)
        assertEquals(
            XrayProviderFailureClass.ConfigInvalid,
            (stage as XrayConnectionStage.ProviderFailed).failureClass,
        )
    }

    @Test
    fun `protect failure derives a ProviderFailed stage carrying ProtectFailure`() {
        val stage = XrayConnectionStage.fromSnapshot(XrayProviderDiagnosticsFixtures.protectFailure.snapshot)
        assertTrue(stage is XrayConnectionStage.ProviderFailed)
        assertEquals(
            XrayProviderFailureClass.ProtectFailure,
            (stage as XrayConnectionStage.ProviderFailed).failureClass,
        )
    }

    @Test
    fun `dns loop and outbound unreachable map to their own failure classes`() {
        val dns = XrayConnectionStage.fromSnapshot(XrayProviderDiagnosticsFixtures.dnsLoopSuspected.snapshot)
        val unreachable =
            XrayConnectionStage.fromSnapshot(XrayProviderDiagnosticsFixtures.outboundUnreachable.snapshot)
        assertEquals(
            XrayProviderFailureClass.DnsLoopSuspected,
            (dns as XrayConnectionStage.ProviderFailed).failureClass,
        )
        assertEquals(
            XrayProviderFailureClass.OutboundUnreachable,
            (unreachable as XrayConnectionStage.ProviderFailed).failureClass,
        )
    }

    @Test
    fun `happy-path stage transitions are linear and reject skips`() {
        val path =
            listOf(
                XrayConnectionStage.Idle,
                XrayConnectionStage.ValidatingConfig,
                XrayConnectionStage.StartingEngine,
                XrayConnectionStage.ListenerReady,
                XrayConnectionStage.ProbingOutbound,
                XrayConnectionStage.Connected,
            )
        for (i in 0 until path.size - 1) {
            assertTrue(
                "expected ${path[i]} -> ${path[i + 1]}",
                XrayConnectionStage.canTransition(path[i], path[i + 1]),
            )
        }
        assertFalse(
            XrayConnectionStage.canTransition(
                XrayConnectionStage.Idle,
                XrayConnectionStage.Connected,
            ),
        )
    }

    @Test
    fun `provider failure is reachable from any non-terminal stage`() {
        val fail = XrayConnectionStage.ProviderFailed(XrayProviderFailureClass.OutboundUnreachable)
        assertTrue(XrayConnectionStage.canTransition(XrayConnectionStage.StartingEngine, fail))
        assertTrue(XrayConnectionStage.canTransition(XrayConnectionStage.ProbingOutbound, fail))
        // Idle reset is always allowed.
        assertTrue(XrayConnectionStage.canTransition(fail, XrayConnectionStage.Idle))
        // Cannot chain failure -> failure.
        assertFalse(XrayConnectionStage.canTransition(fail, fail))
    }

    @Test
    fun `readiness isServing only for OutboundHealthy`() {
        assertTrue(XrayProviderReadiness.OutboundHealthy.isServing)
        XrayProviderReadiness.entries
            .filter { it != XrayProviderReadiness.OutboundHealthy }
            .forEach { assertFalse(it.isServing) }
    }

    @Test
    fun `allHealthy verdict only when probes present, all ok, no config errors`() {
        assertTrue(XrayProviderDiagnosticsFixtures.healthy.allHealthy)
        assertFalse(XrayProviderDiagnosticsFixtures.configInvalid.allHealthy)
        assertFalse(XrayProviderDiagnosticsFixtures.protectFailure.allHealthy)
        assertFalse(XrayProviderDiagnosticsFixtures.outboundUnreachable.allHealthy)
    }

    @Test
    fun `every fixture export is free of secrets and live endpoints`() {
        XrayProviderDiagnosticsFixtures.all.forEach { (name, report) ->
            val export = XrayProviderTelemetrySummaries.export(report)
            assertFalse("$name export leaked a server domain", export.contains("vpn.example.com"))
            assertFalse(
                "$name export leaked a UUID",
                Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-").containsMatchIn(export),
            )
            // The redactor placeholder must appear when an endpoint was scrubbed.
            assertFalse("$name export contains a raw address field value", export.contains("\"address\":\"vpn"))
        }
    }

    @Test
    fun `protect failure export redacts the leaked endpoint`() {
        val export = XrayProviderTelemetrySummaries.export(XrayProviderDiagnosticsFixtures.protectFailure)
        assertFalse(export.contains("vpn.example.com"))
        assertTrue(export.contains(XrayProfileRedactor.REDACTED))
    }

    @Test
    fun `one-line summary names states without leaking endpoint`() {
        val line = XrayProviderTelemetrySummaries.summarize(XrayProviderDiagnosticsFixtures.healthy.snapshot)
        assertTrue(line.contains("readiness=OutboundHealthy"))
        assertTrue(line.contains("listener=Bound"))
        assertFalse(line.contains("vpn.example.com"))
    }

    @Test
    fun `exports never emit arbitrary profile labels or diagnostic detail`() {
        val fixtureSecret = "private-owner-token"
        val report =
            XrayProviderDiagnosticsFixtures.healthy.copy(
                snapshot =
                    XrayProviderDiagnosticsFixtures.healthy.snapshot.copy(
                        profileName = fixtureSecret,
                        xrayVersion = fixtureSecret,
                        outboundProtocol = fixtureSecret,
                        outboundSecurity = fixtureSecret,
                        tunnelTopology = fixtureSecret,
                        localInboundListen = fixtureSecret,
                        lastFailureDetailRedacted = fixtureSecret,
                        configFindings =
                            listOf(
                                XrayConfigValidationFinding(
                                    XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED,
                                    fixtureSecret,
                                    fixtureSecret,
                                ),
                            ),
                    ),
                probes = XrayProviderDiagnosticsFixtures.healthy.probes.map { it.copy(detailRedacted = fixtureSecret) },
            )
        assertFalse(XrayProviderTelemetrySummaries.export(report).contains(fixtureSecret))
        assertFalse(XrayProviderTelemetrySummaries.summarize(report.snapshot).contains(fixtureSecret))
    }

    @Test
    fun `share export retains the canonical version from the real bridge`() {
        val report =
            XrayProviderDiagnosticsFixtures.healthy.copy(
                snapshot = XrayProviderDiagnosticsFixtures.healthy.snapshot.copy(xrayVersion = "Xray 26.3.27"),
            )
        assertTrue(XrayProviderTelemetrySummaries.export(report).contains("version: 26.3.27"))
        assertTrue(XrayProviderTelemetrySummaries.summarize(report.snapshot).contains("v=26.3.27"))
    }

    @Test
    fun `config finding maps from validator error`() {
        val error =
            XrayConfigValidator.ValidationError(
                code = XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED,
                path = "outbounds[0].streamSettings.tlsSettings.allowInsecure",
                message = "allowInsecure is auto-disabled.",
            )
        val finding = XrayConfigValidationFinding.from(error)
        assertEquals(error.code, finding.code)
        assertEquals(error.path, finding.path)
        assertEquals(error.message, finding.message)
    }

    @Test
    fun `snapshot serialization round-trips through json`() {
        XrayProviderDiagnosticsFixtures.all.forEach { (name, report) ->
            val encoded = json.encodeToString(XrayProviderProbeReport.serializer(), report)
            val decoded = json.decodeFromString(XrayProviderProbeReport.serializer(), encoded)
            assertEquals("$name round-trip", report, decoded)
        }
    }

    @Test
    fun `stage serialization round-trips including ProviderFailed`() {
        val stage: XrayConnectionStage =
            XrayConnectionStage.ProviderFailed(
                failureClass = XrayProviderFailureClass.DnsLoopSuspected,
                detailRedacted = "loop suspected",
            )
        val encoded = json.encodeToString(XrayConnectionStage.serializer(), stage)
        val decoded = json.decodeFromString(XrayConnectionStage.serializer(), encoded)
        assertEquals(stage, decoded)
    }

    @Test
    fun `idle snapshot derives Idle stage`() {
        assertEquals(
            XrayConnectionStage.Idle,
            XrayConnectionStage.fromSnapshot(XrayProviderSnapshot.idle()),
        )
    }
}
