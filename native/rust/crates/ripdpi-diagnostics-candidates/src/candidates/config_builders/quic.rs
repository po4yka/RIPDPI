use crate::candidates::prelude::*;

pub fn build_quic_candidate(base_tcp: &ProxyUiConfig, enabled: bool, profile: &str) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = enabled;
    config.chains.udp_steps = if enabled {
        vec![ProxyUiUdpChainStep {
            kind: "fake_burst".to_string(),
            count: 4,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        }]
    } else {
        Vec::new()
    };
    config.quic.fake_profile = profile.to_string();
    config.quic.fake_host.clear();
    config
}
pub fn build_quic_ipfrag_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "ipfrag2_udp".to_string(),
        count: 0,
        split_bytes: 8,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "disabled".to_string();
    config.quic.fake_host.clear();
    config
}

pub fn build_quic_ipfrag_candidate_with_ipv6_ext(base_tcp: &ProxyUiConfig, profile: &str) -> ProxyUiConfig {
    let mut config = build_quic_ipfrag_candidate(base_tcp);
    if let Some(step) = config.chains.udp_steps.first_mut() {
        step.ipv6_extension_profile = profile.to_string();
    }
    config
}
pub fn build_quic_sni_split_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_sni_split".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

pub fn build_quic_fake_version_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "quic_fake_version".to_string(),
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

pub fn build_quic_dummy_prepend_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: "dummy_prepend".to_string(),
        count: 3,
        split_bytes: 0,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = "compat_default".to_string();
    config.quic.fake_host.clear();
    config
}

pub fn build_quic_crypto_split_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_crypto_split", 1, 0, "realistic_initial")
}

pub fn build_quic_padding_ladder_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_padding_ladder", 4, 0, "compat_default")
}

pub fn build_quic_version_negotiation_decoy_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_version_negotiation_decoy", 1, 0, "compat_default")
}

pub fn build_quic_multi_initial_realistic_candidate(base_tcp: &ProxyUiConfig) -> ProxyUiConfig {
    build_quic_step_candidate(base_tcp, "quic_multi_initial_realistic", 3, 0, "realistic_initial")
}

pub fn build_quic_step_candidate(
    base_tcp: &ProxyUiConfig,
    kind: &str,
    count: i32,
    split_bytes: i32,
    fake_profile: &str,
) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base_tcp);
    config.protocols.desync_udp = true;
    config.chains.udp_steps = vec![ProxyUiUdpChainStep {
        kind: kind.to_string(),
        count,
        split_bytes,
        activation_filter: None,
        ipv6_extension_profile: "none".to_string(),
    }];
    config.quic.fake_profile = fake_profile.to_string();
    config.quic.fake_host.clear();
    config
}
