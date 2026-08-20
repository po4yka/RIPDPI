use super::prelude::*;

pub fn build_circular_tlsrec_split_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    candidate_spec_with_notes(
        "circular_tlsrec_split",
        "Circular TLS record split",
        "circular_tlsrec_split",
        build_circular_tlsrec_split_candidate(base),
        vec![
            "Rotates TLS record strategies between outbound rounds on the same TCP socket",
            "Fallback order: hostfake split -> rich fake -> split host",
        ],
    )
}

pub fn build_activation_window_split_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_split_host_candidate(base);
    config.chains.group_activation_filter = Some(default_audit_activation_filter());
    candidate_spec_with_notes(
        "activation_window_split",
        "Activation window + split host",
        "activation_window",
        config,
        vec!["Limits split-host attempts to the first packets in a flow"],
    )
}

pub fn build_activation_window_hostfake_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_hostfake_candidate(base, false);
    config.chains.group_activation_filter = Some(default_audit_activation_filter());
    candidate_spec_with_notes(
        "activation_window_hostfake",
        "Activation window + hostfake",
        "activation_window",
        config,
        vec!["Applies hostfake only inside a narrow activation window"],
    )
}

pub fn build_adaptive_fake_ttl_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.adaptive_fake_ttl_enabled = true;
    config.fake_packets.adaptive_fake_ttl_delta = ADAPTIVE_FAKE_TTL_DEFAULT_DELTA;
    config.fake_packets.adaptive_fake_ttl_min = ADAPTIVE_FAKE_TTL_DEFAULT_MIN;
    config.fake_packets.adaptive_fake_ttl_max = ADAPTIVE_FAKE_TTL_DEFAULT_MAX;
    config.fake_packets.adaptive_fake_ttl_fallback = ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK;
    let requires_fake_ttl = config_requires_fake_ttl(&config);
    let requires_capabilities = config_requires_capabilities(&config);
    StrategyCandidateSpec {
        id: "adaptive_fake_ttl",
        label: "Adaptive fake TTL",
        family: "adaptive_fake_ttl",
        emitter_tier: StrategyEmitterTier::LabDiagnosticsOnly,
        exact_emitter_requires_root: false,
        approximate_fallback_family: None,
        quic_layout_family: None,
        eligibility: CandidateEligibility::Always,
        config,
        notes: vec![
            "Runs an unscored warm-up pass before measured probes",
            "Keeps adaptive fake TTL enabled during candidate execution",
            "Uses a coherent Chrome-family fake ClientHello instead of randomized or original-byte mutation",
        ],
        preserve_adaptive_fake_ttl: true,
        active_snapshot_faithful: true,
        warmup: CandidateWarmup::AdaptiveFakeTtl,
        requires_fake_ttl,
        requires_tcp_fast_open: false,
        requires_capabilities,
    }
}

pub fn build_fake_payload_library_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.http_fake_profile = HTTP_FAKE_PROFILE_CLOUDFLARE_GET.to_string();
    config.fake_packets.tls_fake_profile = TLS_FAKE_PROFILE_GOOGLE_CHROME.to_string();
    config.fake_packets.udp_fake_profile = UDP_FAKE_PROFILE_DNS_QUERY.to_string();
    candidate_spec_with_notes(
        "library_fake_payloads",
        "Library fake payload presets",
        "fake_payload_library",
        config,
        vec!["Uses bundled Cloudflare GET, Chrome TLS, and DNS query fake payload profiles"],
    )
}

pub(super) fn apply_coherent_chrome_fake_profile(config: &mut ProxyUiConfig) {
    config.fake_packets.fake_sni = "www.google.com".to_string();
    config.fake_packets.tls_fake_profile = TLS_FAKE_PROFILE_GOOGLE_CHROME.to_string();
    config.fake_packets.fake_tls_use_original = false;
    config.fake_packets.fake_tls_randomize = false;
    config.fake_packets.fake_tls_dup_session_id = false;
    config.fake_packets.fake_tls_pad_encap = true;
    config.fake_packets.fake_tls_sni_mode = "fixed".to_string();
    config.fake_packets.fake_offset_marker = "endhost-1".to_string();
}

pub fn default_audit_activation_filter() -> ProxyUiActivationFilter {
    ProxyUiActivationFilter {
        round: Some(ProxyUiNumericRange { start: Some(1), end: Some(2) }),
        payload_size: Some(ProxyUiNumericRange { start: Some(64), end: Some(512) }),
        stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(2047) }),
        tcp_has_timestamp: None,
        tcp_has_ech: None,
        tcp_window_below: None,
        tcp_mss_below: None,
    }
}
