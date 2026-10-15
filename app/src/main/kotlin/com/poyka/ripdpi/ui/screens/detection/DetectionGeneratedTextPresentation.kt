package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.ExposureVerdict
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.platform.StringResolver

/** Resolve known generated prose at render time. Stored reports and unknown text stay unchanged. */
internal fun StringResolver.detectionGeneratedText(raw: String): String =
    detectionGeneratedResources[raw]?.let(::getString) ?: raw

internal fun ExposureVerdict.localized(
    strings: StringResolver,
    score: Int,
): ExposureVerdict {
    val generatedHeadline = "Your traffic is distinguishable by $dominantSignal"
    val lowScoreReason = "Detection probes were clean; masking stealth is ${StealthScore.label(score).lowercase()}."
    return copy(
        headline =
            if (headline == generatedHeadline && dominantSignal in detectionGeneratedResources) {
                strings.getString(
                    R.string.detection_exposure_headline_format,
                    strings.detectionGeneratedText(dominantSignal),
                )
            } else {
                strings.detectionGeneratedText(headline)
            },
        reason =
            if (reason == lowScoreReason) {
                strings.getString(
                    R.string.detection_exposure_low_reason_format,
                    strings.detectionGeneratedText(StealthScore.label(score)),
                )
            } else {
                strings.detectionGeneratedText(reason)
            },
        adjustmentHint = strings.detectionGeneratedText(adjustmentHint),
        dominantSignal = strings.detectionGeneratedText(dominantSignal),
    )
}

internal fun Recommendation.localized(strings: StringResolver): Recommendation =
    copy(
        title = strings.detectionGeneratedText(title),
        description = strings.detectionGeneratedText(description),
    )

private val detectionGeneratedResources =
    mapOf(
        "You look like ordinary HTTPS traffic" to R.string.detection_headline_ordinary_https,
        "TLS fingerprint" to R.string.home_generated_tls_fingerprint,
        "Excellent" to R.string.home_quality_excellent,
        "Good" to R.string.home_quality_good,
        "split tunnel bypass" to R.string.detection_exposure_split_tunnel_label,
        "Direct and proxied paths do not present the same network identity." to
            R.string.detection_exposure_split_tunnel_reason,
        "Route apps and domains through one path, then rerun the detection check." to
            R.string.detection_exposure_split_tunnel_hint,
        "Xray API access" to R.string.detection_exposure_xray_api_label,
        "A local API or outbound configuration was reachable from the device." to
            R.string.detection_exposure_xray_api_reason,
        "Restrict local API access and hide exported outbound metadata." to
            R.string.detection_exposure_xray_api_hint,
        "IP consensus mismatch" to R.string.detection_exposure_ip_consensus_label,
        "Observed public IPs diverge across detection channels." to R.string.detection_exposure_ip_consensus_reason,
        "Align DNS, proxy, VPN, and app routing so every checker sees the same exit." to
            R.string.detection_exposure_ip_consensus_hint,
        "IP comparison mismatch" to R.string.detection_exposure_ip_comparison_label,
        "Regional IP comparison probes saw inconsistent public identities." to
            R.string.detection_exposure_ip_comparison_reason,
        "Use one exit path for both local and remote comparison probes." to
            R.string.detection_exposure_ip_comparison_hint,
        "GeoIP or location conflict" to R.string.detection_exposure_geo_ip_label,
        "GeoIP, SIM, or network-country signals do not agree." to R.string.detection_exposure_geo_ip_reason,
        "Choose an exit region that matches the network context or disable mismatched routing." to
            R.string.detection_exposure_geo_ip_hint,
        "Android VPN binding" to R.string.detection_exposure_vpn_binding_label,
        "Android network capabilities expose VPN routing to local observers." to
            R.string.detection_exposure_vpn_binding_reason,
        "Review VPN mode, per-app routing, and active VPN ownership before retesting." to
            R.string.detection_exposure_vpn_binding_hint,
        "local proxy" to R.string.detection_exposure_local_proxy_label,
        "A local proxy endpoint is visible to detection probes." to R.string.detection_exposure_local_proxy_reason,
        "Bind proxy listeners narrowly and avoid exposing local ports to checked apps." to
            R.string.detection_exposure_local_proxy_hint,
        "installed or active app artifact" to R.string.detection_exposure_app_artifact_label,
        "Installed package or active service metadata matches VPN or bypass tooling." to
            R.string.detection_exposure_app_artifact_reason,
        "Hide app metadata where possible and disable unrelated active VPN services." to
            R.string.detection_exposure_app_artifact_hint,
        "DNS observability" to R.string.detection_exposure_dns_leak_label,
        "DNS resolver behavior differs from the expected encrypted or routed path." to
            R.string.detection_exposure_dns_leak_reason,
        "Align direct, bootstrap, and encrypted DNS settings with the selected exit path." to
            R.string.detection_exposure_dns_leak_hint,
        "TLS handshake traits differ from the expected browser-like profile." to
            R.string.detection_exposure_tls_fingerprint_reason,
        "Switch to a browser-like TLS profile and retest after reconnecting." to
            R.string.detection_exposure_tls_fingerprint_hint,
        "network observability" to R.string.detection_exposure_network_observability_label,
        "Network interface or routing metadata exposes a tunnel-shaped path." to
            R.string.detection_exposure_network_observability_reason,
        "Review MTU, interface, and routing settings for tunnel-specific markers." to
            R.string.detection_exposure_network_observability_hint,
        "timing anomaly" to R.string.detection_exposure_timing_label,
        "Latency or timing patterns differ from ordinary direct HTTPS traffic." to
            R.string.detection_exposure_timing_reason,
        "Try a closer exit, reduce chained hops, or select a lower-latency profile." to
            R.string.detection_exposure_timing_hint,
        "native runtime signal" to R.string.detection_exposure_native_signs_label,
        "Native checks found hook, root, or hidden-interface artifacts." to
            R.string.detection_exposure_native_signs_reason,
        "Remove unrelated instrumentation and retest on the target device profile." to
            R.string.detection_exposure_native_signs_hint,
        "call transport leak" to R.string.detection_exposure_call_transport_label,
        "Call or STUN transport probes reveal a different path than expected." to
            R.string.detection_exposure_call_transport_reason,
        "Route realtime transports through the same protected path as HTTPS traffic." to
            R.string.detection_exposure_call_transport_hint,
        "ICMP observability" to R.string.detection_exposure_icmp_label,
        "ICMP probes need review for path or spoofing behavior." to R.string.detection_exposure_icmp_reason,
        "Verify raw-path behavior and disable strategies that expose ICMP differences." to
            R.string.detection_exposure_icmp_hint,
        "RTT triangulation" to R.string.detection_settings_rtt_triangulation_title,
        "Round-trip timing suggests a route inconsistent with the claimed location." to
            R.string.detection_exposure_rtt_reason,
        "Use an exit closer to the apparent region or avoid high-latency chains." to
            R.string.detection_exposure_rtt_hint,
        "CDN pull signal" to R.string.detection_exposure_cdn_label,
        "CDN pull probes observed address or TLS traits that need review." to R.string.detection_exposure_cdn_reason,
        "Check CDN-facing DNS and TLS routing before rerunning the probe." to R.string.detection_exposure_cdn_hint,
        "WebRTC leak" to R.string.detection_exposure_web_rtc_label,
        "WebRTC behavior exposes an address or path outside the expected route." to
            R.string.detection_exposure_web_rtc_reason,
        "Enable WebRTC protection or route WebRTC through the protected path." to
            R.string.detection_exposure_web_rtc_hint,
        "direct device signal" to R.string.detection_exposure_direct_signs_label,
        "Local direct checks found proxy or VPN artifacts." to R.string.detection_exposure_direct_signs_reason,
        "Review active VPN ownership, proxy ports, and app-visible network state." to
            R.string.detection_exposure_direct_signs_hint,
        "indirect device signal" to R.string.detection_exposure_indirect_signs_label,
        "Installed-app or service signals make the setup recognizable." to
            R.string.detection_exposure_indirect_signs_reason,
        "Disable unrelated VPN apps and reduce app-visible bypass metadata." to
            R.string.detection_exposure_indirect_signs_hint,
        "location signal" to R.string.detection_exposure_location_signals_label,
        "SIM, roaming, or network-country metadata needs review." to
            R.string.detection_exposure_location_signals_reason,
        "Retest on the target network and align the exit region with local context." to
            R.string.detection_exposure_location_signals_hint,
        "bypass probe" to R.string.detection_exposure_bypass_probe_label,
        "Bypass-specific probes found a visible local or remote path." to
            R.string.detection_exposure_bypass_probe_reason,
        "Review split routing and proxy exposure, then rerun the bypass check." to
            R.string.detection_exposure_bypass_probe_hint,
        "Additional diagnostic signals need review" to R.string.detection_exposure_additional,
        "Detection probes did not expose bypass, VPN, IP, DNS, TLS, timing, or native observability signals." to
            R.string.detection_exposure_clean_reason,
        "Keep the current profile and rerun after changing networks or profiles." to
            R.string.detection_exposure_clean_hint,
        "Keep probes clean and improve the masking profile before relying on stricter networks." to
            R.string.detection_exposure_low_hint,
        "The detection pipeline found an exposure signal but no more specific cause was available." to
            R.string.detection_exposure_generic_reason,
        "Open the detailed categories below and adjust the first detected or review item." to
            R.string.detection_exposure_generic_hint,
        "review signal" to R.string.detection_exposure_generic_review,
        "detection probe" to R.string.detection_exposure_generic_detection,
        "ordinary HTTPS baseline" to R.string.detection_exposure_baseline,
        "Fair" to R.string.detection_stealth_fair,
        "Poor" to R.string.detection_stealth_poor,
        "Critical" to R.string.detection_stealth_critical,
        "VPN transport is visible" to R.string.detection_advice_vpn_title,
        "Android reports VPN routing on the active network. Use a non-VPN mode if this local signal must be absent." to
            R.string.detection_advice_vpn_description,
        "Open localhost proxy detected" to R.string.detection_advice_proxy_title,
        "A proxy responds on localhost. Review the listener and disable it when it is not needed." to
            R.string.detection_advice_proxy_description,
        "Xray gRPC API exposed" to R.string.detection_advice_xray_title,
        (
            "The Xray HandlerService API is accessible on localhost, " +
                "leaking VPN server addresses and credentials. Disable the API in " +
                "your Xray client settings."
        ) to
            R.string.detection_advice_xray_description,
        "DNS points to localhost" to R.string.detection_advice_dns_title,
        "The active network lists a loopback DNS resolver. Check the DNS configuration if this is unexpected." to
            R.string.detection_advice_dns_description,
        "Known bypass app installed" to R.string.detection_advice_app_title,
        (
            "A known targeted bypass application is installed. " +
                "Consider using a work profile or private space to isolate it."
        ) to
            R.string.detection_advice_app_description,
        "No issues detected" to R.string.detection_advice_clean_title,
        "Your current setup does not trigger any detection signals based on the methodology checks." to
            R.string.detection_advice_clean_description,
    )
