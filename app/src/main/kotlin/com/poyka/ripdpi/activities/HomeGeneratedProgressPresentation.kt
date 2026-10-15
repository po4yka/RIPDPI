package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

internal fun StringResolver.homeProgressText(raw: String): String {
    val wrapper =
        homeProgressWrappers.firstNotNullOfOrNull { (pattern, resource) ->
            pattern.matchEntire(raw)?.let { it to resource }
        }
    return wrapper?.let { (match, resource) ->
        // Candidate labels, target names and display names can be user-controlled.
        getString(resource, match.groupValues[1])
    } ?: homeGeneratedText(raw)
}

private val homeProgressWrappers =
    listOf(
        Regex("Preparing (.+)") to R.string.home_generated_progress_preparing,
        Regex("Testing TCP candidate (.+)") to R.string.home_generated_progress_tcp,
        Regex("Testing QUIC candidate (.+)") to R.string.home_generated_progress_quic,
        Regex("Skipped (.+)") to R.string.home_generated_progress_skip,
        Regex("Service (.+)") to R.string.home_generated_progress_service,
        Regex("Reachability (.+)") to R.string.home_generated_progress_reachability,
        Regex("Throughput (.+)") to R.string.home_generated_progress_throughput,
        Regex("Circumvention (.+)") to R.string.home_generated_progress_circumvention,
    )

internal fun StringResolver.homeDetectionProgress(raw: String): String? {
    val verdict = homeDetectionVerdictCount.matchEntire(raw)
    val bypassPrefix = "Bypass: "
    return when {
        verdict != null -> {
            val (message, count) = verdict.destructured
            count.toIntOrNull()?.let { quantity ->
                getQuantityString(
                    R.plurals.home_generated_detection_count,
                    quantity,
                    homeGeneratedText(message),
                    homeCount(count),
                )
            }
        }

        raw.startsWith(bypassPrefix) -> {
            getString(R.string.detection_check_category_bypass) + ": " +
                homeDetectionProgress(
                    raw.removePrefix(bypassPrefix),
                ).orEmpty().ifEmpty { raw.removePrefix(bypassPrefix) }
        }

        else -> {
            homeDetectionDetail(raw)
        }
    }
}

private val homeDetectionVerdictCount =
    Regex(
        "(Detection signals were observed\\.|Detection results need review\\.|No detection signals " +
            "observed\\.) ([0-9]+) detection signals? observed\\.",
    )

private fun StringResolver.homeDetectionDetail(raw: String): String? {
    val label = homeDetectionLabelResources.entries.firstOrNull { raw.startsWith("${it.key}: ") } ?: return null
    val detail = raw.removePrefix("${label.key}: ")
    val port = homeDetectionPort.matchEntire(detail)
    val translatedDetail =
        when {
            detail == "Done" -> {
                getString(R.string.xray_import_done_action)
            }

            port != null -> {
                getString(
                    R.string.home_generated_check_port,
                    port.groupValues[1],
                    homeCount(port.groupValues[2]),
                )
            }

            else -> {
                homeGeneratedSentenceResources[detail]?.let(::getString) ?: detail
            }
        }
    return (label.value?.let(::getString) ?: label.key) + ": " + translatedDetail
}

private val homeDetectionPort = Regex("Port ([0-9]+) \\(([0-9]+)%\\)")
private val homeDetectionLabelResources =
    mapOf<String, Int?>(
        "GeoIP" to R.string.detection_check_category_geoip,
        "Direct signs" to R.string.detection_check_category_direct,
        "Indirect signs" to R.string.detection_check_category_indirect,
        "Location" to R.string.detection_check_category_location,
        "Bypass" to R.string.detection_check_category_bypass,
        "DNS Leak" to R.string.detection_check_category_dns_leak,
        "WebRTC Leak" to R.string.detection_check_category_webrtc,
        "TLS Fingerprint" to R.string.detection_check_category_tls,
        "Timing Analysis" to R.string.detection_check_category_timing,
        "ICMP Spoofing" to R.string.detection_check_category_icmp_spoofing,
        "IP Comparison" to R.string.detection_check_category_ip_comparison,
        "RTT Triangulation" to R.string.detection_check_category_rtt_triangulation,
        "CDN Pulling" to R.string.detection_check_category_cdn_pulling,
        "Native Signs" to R.string.detection_check_category_native_signs,
        "Call Transport" to R.string.detection_check_category_call_transport,
        "Port scanning" to R.string.home_generated_check_port_scanning,
        "Popular ports" to R.string.home_generated_check_popular_ports,
        "Full range scan" to R.string.home_generated_check_full_range,
        "Xray API" to null,
        "IP check" to R.string.home_generated_check_ip_check,
    )
