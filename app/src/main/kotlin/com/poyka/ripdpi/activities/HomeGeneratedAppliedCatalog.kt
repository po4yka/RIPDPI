package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R

internal val homeGeneratedAppliedLabelResources =
    mapOf(
        "TCP/TLS lane" to R.string.diagnostics_signature_tcp_tls_lane,
        "QUIC lane" to R.string.diagnostics_signature_quic_lane,
        "DNS lane" to R.string.diagnostics_audit_winning_dns_lane,
        "Chain" to R.string.diagnostics_field_chain,
        "Resolver" to R.string.diagnostics_resolver_field_resolver,
        "Protocol" to R.string.diagnostics_resolver_field_protocol,
        "TLS fingerprint" to R.string.home_generated_tls_fingerprint,
        "Traffic morphing" to R.string.detection_resistance_summary_entropy,
        "Morphing budget" to R.string.home_generated_morphing_budget,
        "Strategy evolution" to R.string.detection_resistance_strategy_evolution_title,
        "QUIC resistance" to R.string.home_generated_quic_resistance,
    )

internal val homeGeneratedSettingValueResources =
    mapOf(
        "Off" to R.string.split_tunnel_mode_off,
        "Automatic" to R.string.settings_option_automatic,
        "Disabled" to R.string.adaptive_fallback_badge_disabled,
        "Chrome stable" to R.string.settings_option_chrome_stable,
        "Chrome desktop stable" to R.string.settings_option_chrome_desktop_stable,
        "Firefox stable" to R.string.settings_option_firefox_stable,
        "Firefox stable (ECH)" to R.string.settings_option_firefox_ech_stable,
        "Safari stable" to R.string.settings_option_safari_stable,
        "Edge stable" to R.string.settings_option_edge_stable,
        "System" to R.string.settings_option_theme_system,
    )
