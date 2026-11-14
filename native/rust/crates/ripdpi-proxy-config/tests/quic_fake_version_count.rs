use ripdpi_proxy_config::{ProxyUiConfig, ProxyUiUdpChainStep, runtime_config_from_ui};

fn config_with_counts(counts: &[i32]) -> ProxyUiConfig {
    let mut config = ProxyUiConfig::default();
    config.protocols.desync_udp = true;
    config.chains.udp_steps = counts
        .iter()
        .map(|&count| ProxyUiUdpChainStep {
            kind: "quic_fake_version".to_string(),
            count,
            split_bytes: 0,
            activation_filter: None,
            ipv6_extension_profile: "none".to_string(),
        })
        .collect();
    config
}

#[test]
fn fake_version_counts_accept_the_budget_boundary() {
    for counts in [vec![0], vec![1], vec![64], vec![32, 32]] {
        let config = runtime_config_from_ui(config_with_counts(&counts)).expect("bounded chain");
        let udp = config.groups.iter().find(|group| group.matches.proto == ripdpi_packets::IS_UDP).expect("UDP group");
        assert_eq!(udp.actions.udp_chain.iter().map(|step| step.count).collect::<Vec<_>>(), counts);
    }
}

#[test]
fn fake_version_counts_reject_unbounded_individual_and_repeated_steps() {
    for counts in [vec![65], vec![i32::MAX], vec![32, 33], vec![64, 1]] {
        assert!(runtime_config_from_ui(config_with_counts(&counts)).is_err(), "accepted {counts:?}");
    }
}
