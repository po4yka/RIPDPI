package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

internal fun initialDiagnosticsDpiToolsUiModel(strings: StringResolver): DiagnosticsDpiToolsUiModel =
    DiagnosticsDpiToolsUiModel(
        dnsIntegrity = initialDiagnosticsDnsIntegrityUiModel(strings),
        dnsAvailability = initialDiagnosticsDnsAvailabilityUiModel(strings),
        domainReachability = initialDiagnosticsDomainReachabilityUiModel(strings),
        rknBlockDiagnosis = initialDiagnosticsRknBlockDiagnosisUiModel(strings),
        compressionProbe = initialDiagnosticsCompressionProbeUiModel(strings),
        tcp16FatHeader = initialDiagnosticsTcp16FatHeaderUiModel(strings),
        allowlistSni = initialDiagnosticsAllowlistSniUiModel(strings),
        byohCompatibility = initialDiagnosticsByohUiModel(strings),
        dpiSuite = initialDiagnosticsDpiSuiteUiModel(strings),
    )

internal fun initialDiagnosticsDnsIntegrityUiModel(strings: StringResolver) =
    DiagnosticsDnsIntegrityToolUiModel(
        summary = strings.getString(R.string.diagnostics_dns_integrity_idle_summary),
    )

internal fun initialDiagnosticsDnsAvailabilityUiModel(strings: StringResolver) =
    DiagnosticsDnsAvailabilityToolUiModel(
        summary = strings.getString(R.string.diagnostics_dns_availability_idle_summary),
    )

internal fun initialDiagnosticsDomainReachabilityUiModel(strings: StringResolver) =
    DiagnosticsDomainReachabilityToolUiModel(
        summary = strings.getString(R.string.diagnostics_domain_reachability_idle_summary),
    )

internal fun initialDiagnosticsRknBlockDiagnosisUiModel(strings: StringResolver) =
    DiagnosticsRknBlockDiagnosisToolUiModel(
        headline = strings.getString(R.string.diagnostics_rkn_idle_headline),
        confidenceNote = strings.getString(R.string.diagnostics_rkn_idle_confidence_note),
        summary = strings.getString(R.string.diagnostics_rkn_idle_summary),
    )

internal fun initialDiagnosticsCompressionProbeUiModel(strings: StringResolver) =
    DiagnosticsCompressionProbeToolUiModel(
        summary = strings.getString(R.string.diagnostics_compression_idle_summary),
    )

internal fun initialDiagnosticsTcp16FatHeaderUiModel(strings: StringResolver) =
    DiagnosticsTcp16FatHeaderToolUiModel(
        summary = strings.getString(R.string.diagnostics_tcp16_idle_summary),
    )

internal fun initialDiagnosticsAllowlistSniUiModel(strings: StringResolver) =
    DiagnosticsAllowlistSniToolUiModel(
        summary = strings.getString(R.string.diagnostics_allowlist_sni_idle_summary),
    )

internal fun initialDiagnosticsByohUiModel(strings: StringResolver) =
    DiagnosticsByohCompatibilityToolUiModel(
        summary = strings.getString(R.string.diagnostics_byoh_idle_summary),
        requirementsSummary = strings.getString(R.string.diagnostics_byoh_requirements_summary),
    )

internal fun initialDiagnosticsCidrWhitelistUiModel(strings: StringResolver) =
    DiagnosticsCidrWhitelistToolUiModel(
        summary = strings.getString(R.string.diagnostics_cidr_whitelist_idle_summary),
    )

internal fun initialDiagnosticsIpv4WhitelistUiModel(strings: StringResolver) =
    DiagnosticsIpv4WhitelistToolUiModel(
        summary = strings.getString(R.string.diagnostics_ipv4_whitelist_idle_summary),
    )

internal fun initialDiagnosticsDpiSuiteUiModel(strings: StringResolver) =
    DiagnosticsDpiSuiteToolUiModel(
        summary = strings.getString(R.string.diagnostics_dpi_suite_idle_summary),
    )

internal fun initialDiagnosticsUiState(strings: StringResolver): DiagnosticsUiState =
    DiagnosticsUiState(
        overview =
            DiagnosticsOverviewUiModel(
                headline = strings.getString(R.string.diagnostics_headline_no_data),
                body = strings.getString(R.string.diagnostics_body_idle),
            ),
        live =
            DiagnosticsLiveUiModel(
                statusLabel = strings.getString(R.string.diagnostics_metric_idle),
                freshnessLabel = strings.getString(R.string.diagnostics_live_no_telemetry),
                headline = strings.getString(R.string.diagnostics_live_headline_standby),
                body = strings.getString(R.string.diagnostics_live_body_waiting),
                signalLabel = strings.getString(R.string.diagnostics_live_no_transfer),
                eventSummaryLabel = strings.getString(R.string.diagnostics_live_feed_quiet),
            ),
        share =
            DiagnosticsShareUiModel(
                previewTitle = strings.getString(R.string.diagnostics_share_title),
                previewBody = strings.getString(R.string.diagnostics_share_no_session),
            ),
    )
