package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.HomeBufferbloatGrade
import com.poyka.ripdpi.diagnostics.HomeBufferbloatResult
import com.poyka.ripdpi.diagnostics.HomeDnsCharacterization
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.diagnostics.HomeRoutingSanityFinding
import com.poyka.ripdpi.platform.StringResolver

private const val ScopeUnavailableHeadline = "Network scope unavailable"
private const val ScopeUnavailableSummary =
    "Network changed during analysis or its original scope could not be verified. " +
        "Repeat analysis on a stable network."
private const val RoutingDetectorPrefix = "VPN-detector app — verify per-app routing override (detection: "

internal fun DiagnosticsHomeCompositeOutcome.hasGeneratedUnavailableScope(): Boolean =
    fingerprintHash == null && !actionable && headline == ScopeUnavailableHeadline && summary == ScopeUnavailableSummary

internal fun DiagnosticsHomeCompositeOutcome.homeHeadline(strings: StringResolver): String =
    if (hasGeneratedUnavailableScope()) {
        strings.getString(
            R.string.home_diagnostics_network_scope_unavailable,
        )
    } else {
        headline
    }

internal fun DiagnosticsHomeCompositeOutcome.homeSummary(strings: StringResolver): String =
    if (hasGeneratedUnavailableScope()) {
        strings.getString(
            R.string.home_diagnostics_network_scope_unavailable_body,
        )
    } else {
        summary
    }

internal fun StringResolver.homeNetworkNote(note: String): String =
    when (note) {
        "Active network is a VPN" -> getString(R.string.home_diagnostics_network_note_vpn)
        "Network reports as metered" -> getString(R.string.home_diagnostics_network_note_metered)
        else -> note
    }

internal fun HomeNetworkCharacterSummary.homeOperator(strings: StringResolver): String? =
    operatorOrSsid?.let { operator ->
        val code = operator.removePrefix("Carrier ")
        val generatedCarrier = transport == "cellular" && operator.startsWith("Carrier ")
        if (generatedCarrier && code.takeUnless { it.isBlank() || it == "unknown" } != null) {
            strings.getString(R.string.home_diagnostics_network_carrier_format, code)
        } else {
            operator
        }
    }

internal fun HomeRoutingSanityFinding.homeDescription(strings: StringResolver): String {
    val generated = description.startsWith(RoutingDetectorPrefix) && description.endsWith(")")
    if (!generated) return "$severity: $description"
    val method = description.removePrefix(RoutingDetectorPrefix).dropLast(1)
    val level =
        when (severity) {
            "high" -> strings.getString(R.string.home_diagnostics_routing_severity_high)
            "medium" -> strings.getString(R.string.home_diagnostics_routing_severity_medium)
            "low" -> strings.getString(R.string.home_diagnostics_routing_severity_low)
            else -> severity
        }
    return "$level: ${strings.getString(R.string.home_diagnostics_routing_detector_description_format, method)}"
}

internal fun HomeDnsCharacterization.homeNote(
    note: String,
    strings: StringResolver,
): String =
    when (note) {
        "DNS comparison is incomplete" -> {
            strings.getString(R.string.home_diagnostics_dns_note_comparison_incomplete)
        }

        "System and DoH answers differ; location and CDN routing can cause this" -> {
            strings.getString(R.string.home_diagnostics_dns_note_answers_differ)
        }

        "DoH endpoint $dohEndpoint unreachable" -> {
            dohEndpoint?.let { endpoint ->
                strings.getString(R.string.home_diagnostics_dns_note_endpoint_unreachable_format, endpoint)
            } ?: note
        }

        else -> {
            note
        }
    }

internal fun HomeBufferbloatResult.homeSummary(strings: StringResolver): String =
    strings.getString(
        R.string.home_diagnostics_bufferbloat_summary,
        if (grade == HomeBufferbloatGrade.UNKNOWN) {
            strings.getString(R.string.home_diagnostics_bufferbloat_unknown)
        } else {
            grade.name
        },
        idleRttMs?.let { "${it}ms" } ?: "—",
        loadedRttMs?.let { "${it}ms" } ?: "—",
    )
