package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.platform.StringResolver

internal fun DiagnosticProfile.localizedBundledName(strings: StringResolver): String {
    if (source != "bundled") return name
    val bundled = bundledProfileTitles[id]
    return if (bundled != null && name == bundled.first) strings.getString(bundled.second) else name
}

private val bundledProfileTitles =
    mapOf(
        "default" to ("Default diagnostics" to R.string.diagnostics_profile_connectivity_title),
        "resolver-audit" to ("Resolver audit" to R.string.diagnostics_profile_resolver_audit_title),
        "selective-availability-matrix" to ("Selective availability matrix" to R.string.diagnostics_matrix_title),
        "ip-family-connectivity" to ("IPv4, IPv6 and NAT64 connectivity" to R.string.diagnostics_ip_title),
        "http3-connectivity" to ("HTTP/3 connectivity" to R.string.diagnostics_http3_title),
        "pmtu-connectivity" to ("Active packet sizes / PMTU" to R.string.diagnostics_pmtu_title),
        "automatic-probing" to ("Automatic probing" to R.string.diagnostics_scan_automatic_probing),
        "automatic-audit" to ("Automatic audit" to R.string.diagnostics_scan_automatic_audit),
        "dpi-detector-full" to ("DPI Detector Full" to R.string.diagnostics_profile_dpi_detector_full_title),
        "ru-web-connectivity" to ("Russia Web Connectivity" to R.string.diagnostics_profile_ru_web_connectivity_title),
        "ru-messaging" to ("Russia Messaging Services" to R.string.diagnostics_profile_ru_messaging_title),
        "ru-circumvention" to
            ("Russia Sensitive Services Reachability" to R.string.diagnostics_profile_ru_circumvention_title),
        "ru-throttling" to ("Russia Throttling Check" to R.string.diagnostics_profile_ru_throttling_title),
        "path-comparison" to ("VPN vs direct path" to R.string.diagnostics_profile_path_comparison_title),
        "ru-dpi-full" to ("Russia DPI Full" to R.string.diagnostics_profile_ru_dpi_full_title),
        "ru-dpi-strategy" to ("Russia DPI Strategy Probe" to R.string.diagnostics_profile_ru_dpi_strategy_title),
    )
