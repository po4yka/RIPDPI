use ripdpi_proxy_config::ProxyUiTcpChainStep;

pub fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: String::new(),
        fake_host_template: String::new(),
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
    }
}
