use crate::candidates::prelude::*;

pub fn build_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("split", "host+2")];
    config
}

pub fn build_disorder_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("disorder", "host+2")];
    config
}

pub fn build_tlsrec_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("disorder", "host+2")];
    config
}

pub fn build_oob_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("oob", "host+2")];
    config
}

pub fn build_tlsrec_oob_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("oob", "host+2")];
    config
}

pub fn build_disoob_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("disoob", "host+2")];
    config
}

pub fn build_tlsrec_disoob_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("disoob", "host+2")];
    config
}

pub fn build_tlsrandrec_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrandrec", "sniext+4"), tcp_step("split", "host+2")];
    config
}

pub fn build_tlsrandrec_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrandrec", "sniext+4"), tcp_step("disorder", "host+2")];
    config
}

pub fn build_ech_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("split", "echext")];
    config
}

pub fn build_ech_tlsrec_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "echext")];
    config
}

pub fn build_tlsrec_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("split", "host+2")];
    config
}

pub fn build_circular_tlsrec_split_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_split_host_candidate(base);
    config.chains.tcp_rotation = Some(ProxyUiTcpRotationConfig {
        fails: 3,
        retrans: 3,
        seq: 65_536,
        rst: 1,
        time_secs: 60,
        cancel_on_failure: None,
        candidates: vec![
            ProxyUiTcpRotationCandidate { tcp_steps: build_tlsrec_hostfake_candidate(base, true).chains.tcp_steps },
            ProxyUiTcpRotationCandidate { tcp_steps: build_tlsrec_fake_rich_candidate(base).chains.tcp_steps },
            ProxyUiTcpRotationCandidate { tcp_steps: build_split_host_candidate(base).chains.tcp_steps },
        ],
    });
    config
}

pub fn build_tlsrec_fake_rich_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("fake", "host+1")];
    apply_coherent_chrome_fake_profile(&mut config);
    config
}

pub fn build_tlsrec_fake_seqgroup_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.ip_id_mode = "seqgroup".to_string();
    config
}

pub fn build_tlsrec_fake_hrr_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.fake_packets.tls_fake_profile = TLS_FAKE_PROFILE_GOOGLE_CHROME_HRR.to_string();
    config
}

pub fn build_tlsrec_fake_flag_candidate(base: &ProxyUiConfig, flags: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|step| step.kind == "fake") {
        step.tcp_flags_set = flags.to_string();
    }
    config
}

pub fn build_tlsrec_fake_approx_candidate(base: &ProxyUiConfig, kind: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step(kind, "host+1")];
    config
}

pub fn build_tlsrec_fakedsplit_altorder_candidate(base: &ProxyUiConfig, fake_order: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|step| step.kind == "fakedsplit") {
        step.fake_order = fake_order.to_string();
    }
    config
}

pub fn build_tlsrec_hostfake_candidate(base: &ProxyUiConfig, with_split: bool) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    let mut steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: "midsld".to_string(),
            fake_host_template: "googlevideo.com".to_string(),
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: None,
            inter_segment_delay_ms: 0,
            ipv6_extension_profile: "none".to_string(),
            random_fake_host: false,
        },
    ];
    if with_split {
        steps.push(tcp_step("split", "midsld"));
    }
    config.chains.tcp_steps = steps;
    config
}

pub fn build_split_delayed_candidate(base: &ProxyUiConfig, delay_ms: u32) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    let mut step = tcp_step("split", "host+2");
    step.inter_segment_delay_ms = delay_ms;
    config.chains.tcp_steps = vec![tcp_step("tlsrec", "extlen"), step];
    config
}

pub fn build_tlsrec_hostfake_random_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_tlsrec_hostfake_candidate(base, false);
    if let Some(step) = config.chains.tcp_steps.iter_mut().find(|s| s.kind == "hostfake") {
        step.random_fake_host = true;
    }
    config
}

pub fn build_tlsrec_seqovl_candidate(base: &ProxyUiConfig, marker: &str) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "seqovl".to_string(),
            marker: marker.to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 12,
            fake_mode: "profile".to_string(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: Some(ProxyUiActivationFilter {
                round: Some(ProxyUiNumericRange { start: Some(1), end: Some(1) }),
                payload_size: None,
                stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(1500) }),
                tcp_has_timestamp: None,
                tcp_has_ech: None,
                tcp_window_below: None,
                tcp_mss_below: None,
            }),
            inter_segment_delay_ms: 0,
            ipv6_extension_profile: "none".to_string(),
            random_fake_host: false,
        },
    ];
    config
}
pub fn build_ipfrag_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.chains.tcp_steps = vec![tcp_step("ipfrag2", "host+2")];
    config
}

pub fn build_ipfrag_candidate_with_ipv6_ext(base: &ProxyUiConfig, profile: &str) -> ProxyUiConfig {
    let mut config = build_ipfrag_candidate(base);
    if let Some(step) = config.chains.tcp_steps.first_mut() {
        step.ipv6_extension_profile = profile.to_string();
    }
    config
}

pub fn build_fake_rst_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    // FakeRst is a pre-send action, followed by a regular split to deliver the payload.
    config.chains.tcp_steps = vec![tcp_step("fakerst", "host+2"), tcp_step("split", "host+2")];
    apply_coherent_chrome_fake_profile(&mut config);
    config
}

pub fn build_multi_disorder_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    // Multi-disorder splits the ClientHello into 3+ out-of-order segments.
    config.chains.tcp_steps = vec![tcp_step("multidisorder", "host+2"), tcp_step("multidisorder", "midsld")];
    config
}
pub fn build_tfo_variant(config: &ProxyUiConfig) -> ProxyUiConfig {
    let mut candidate = config.clone();
    candidate.listen.tcp_fast_open = true;
    candidate
}
