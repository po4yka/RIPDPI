use super::super::constants::SEQOVL_FAKE_MODE_PROFILE;
use super::steps::ProxyUiTcpChainStep;

pub(super) fn default_tcp_chain_steps() -> Vec<ProxyUiTcpChainStep> {
    vec![
        ProxyUiTcpChainStep {
            kind: "tlsrec".to_string(),
            marker: "extlen".to_string(),
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
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
            random_fake_host: false,
        },
        ProxyUiTcpChainStep {
            kind: "fake".to_string(),
            marker: "host+1".to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 0,
            fake_mode: default_seqovl_fake_mode(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
            random_fake_host: false,
        },
    ]
}

pub(super) fn default_seqovl_fake_mode() -> String {
    SEQOVL_FAKE_MODE_PROFILE.to_string()
}

pub(super) fn default_ipv6_extension_profile() -> String {
    "none".to_string()
}
