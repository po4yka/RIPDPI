package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsInitialUiModelsTest {
    @Test
    fun `ui model defaults do not embed English fallback copy`() {
        val dpiTools = DiagnosticsDpiToolsUiModel()
        val embeddedCopy =
            listOf(
                dpiTools.dnsIntegrity.summary,
                dpiTools.dnsAvailability.summary,
                dpiTools.domainReachability.summary,
                dpiTools.rknBlockDiagnosis.headline,
                dpiTools.rknBlockDiagnosis.confidenceNote,
                dpiTools.rknBlockDiagnosis.summary,
                dpiTools.compressionProbe.summary,
                dpiTools.tcp16FatHeader.summary,
                dpiTools.allowlistSni.summary,
                dpiTools.byohCompatibility.summary,
                dpiTools.byohCompatibility.requirementsSummary,
                dpiTools.dpiSuite.summary,
                DiagnosticsCidrWhitelistToolUiModel().summary,
                DiagnosticsIpv4WhitelistToolUiModel().summary,
                DiagnosticsOverviewUiModel().headline,
                DiagnosticsOverviewUiModel().body,
                DiagnosticsLiveUiModel().statusLabel,
                DiagnosticsLiveUiModel().freshnessLabel,
                DiagnosticsLiveUiModel().headline,
                DiagnosticsLiveUiModel().body,
                DiagnosticsLiveUiModel().signalLabel,
                DiagnosticsLiveUiModel().eventSummaryLabel,
                DiagnosticsShareUiModel().previewTitle,
                DiagnosticsShareUiModel().previewBody,
            )

        assertTrue(embeddedCopy.all(String::isEmpty))
    }

    @Test
    fun `initial states resolve fallback copy through string resources`() {
        val strings = FakeStringResolver()
        val dpiTools = initialDiagnosticsDpiToolsUiModel(strings)
        val actual =
            listOf(
                dpiTools.dnsIntegrity.summary,
                dpiTools.dnsAvailability.summary,
                dpiTools.domainReachability.summary,
                dpiTools.rknBlockDiagnosis.headline,
                dpiTools.rknBlockDiagnosis.confidenceNote,
                dpiTools.rknBlockDiagnosis.summary,
                dpiTools.compressionProbe.summary,
                dpiTools.tcp16FatHeader.summary,
                dpiTools.allowlistSni.summary,
                dpiTools.byohCompatibility.summary,
                dpiTools.byohCompatibility.requirementsSummary,
                initialDiagnosticsCidrWhitelistUiModel(strings).summary,
                initialDiagnosticsIpv4WhitelistUiModel(strings).summary,
                dpiTools.dpiSuite.summary,
            )
        val expected =
            listOf(
                R.string.diagnostics_dns_integrity_idle_summary,
                R.string.diagnostics_dns_availability_idle_summary,
                R.string.diagnostics_domain_reachability_idle_summary,
                R.string.diagnostics_rkn_idle_headline,
                R.string.diagnostics_rkn_idle_confidence_note,
                R.string.diagnostics_rkn_idle_summary,
                R.string.diagnostics_compression_idle_summary,
                R.string.diagnostics_tcp16_idle_summary,
                R.string.diagnostics_allowlist_sni_idle_summary,
                R.string.diagnostics_byoh_idle_summary,
                R.string.diagnostics_byoh_requirements_summary,
                R.string.diagnostics_cidr_whitelist_idle_summary,
                R.string.diagnostics_ipv4_whitelist_idle_summary,
                R.string.diagnostics_dpi_suite_idle_summary,
            ).map(Int::toString)

        assertEquals(expected, actual)
    }

    @Test
    fun `diagnostics root initial state is resource backed`() {
        val initial = initialDiagnosticsUiState(FakeStringResolver())

        assertEquals(R.string.diagnostics_headline_no_data.toString(), initial.overview.headline)
        assertEquals(R.string.diagnostics_live_headline_standby.toString(), initial.live.headline)
        assertEquals(R.string.diagnostics_share_title.toString(), initial.share.previewTitle)
    }
}
