use std::collections::BTreeMap;

use ripdpi_config::QuicFakeProfile;
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveUdpBurstProfile};
use ripdpi_packets::DEFAULT_FAKE_TLS;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyPreferredEdge, ProxyRuntimeContext};
use ripdpi_runtime_policy::direct_path_learning::direct_path_ip_set_digest;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use crate::retry_stealth::RetryLane;

use super::direct_path_capability::direct_path_capability_for_hostname_targets;
use super::*;

fn capability(authority: &str) -> ProxyDirectPathCapability {
    ProxyDirectPathCapability {
        authority: authority.to_string(),
        quic_usable: None,
        udp_usable: None,
        fallback_required: None,
        repeated_handshake_failure_class: None,
        transport_policy_version: 0,
        ip_set_digest: String::new(),
        dns_classification: None,
        quic_mode: "ALLOW".to_string(),
        preferred_stack: "H3".to_string(),
        dns_mode: "SYSTEM".to_string(),
        tcp_family: "NONE".to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: None,
        reason_code: None,
        cooldown_until: None,
        updated_at: 0,
    }
}

#[test]
fn direct_path_capability_matches_host_and_target_authorities() {
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: None,
        protect_path: None,
        preferred_edges: BTreeMap::default(),
        direct_path_capabilities: vec![capability("example.org:443"), capability("203.0.113.10:443")],
        morph_policy: None,
        connection_concurrency: None,
    };

    let host_match = direct_path_capability_for_route(
        Some(&runtime_context),
        Some("Example.org"),
        "203.0.113.10:443".parse().expect("target"),
    )
    .expect("host capability");
    let target_match =
        direct_path_capability_for_route(Some(&runtime_context), None, "203.0.113.10:443".parse().expect("target"))
            .expect("target capability");

    assert_eq!(host_match.authority, "example.org:443");
    assert_eq!(target_match.authority, "203.0.113.10:443");
}

#[test]
fn udp_hints_are_hardened_when_capability_requires_fallback() {
    let hints = AdaptivePlannerHints {
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..AdaptivePlannerHints::default()
    };
    let capability = ProxyDirectPathCapability {
        authority: "example.org:443".to_string(),
        quic_usable: Some(false),
        udp_usable: Some(false),
        fallback_required: Some(true),
        repeated_handshake_failure_class: Some("tcp_reset".to_string()),
        transport_policy_version: 0,
        ip_set_digest: String::new(),
        dns_classification: None,
        quic_mode: "SOFT_DISABLE".to_string(),
        preferred_stack: "H2".to_string(),
        dns_mode: "SYSTEM".to_string(),
        tcp_family: "NONE".to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: Some("QUIC_BLOCK_SUSPECT".to_string()),
        reason_code: Some("QUIC_BLOCKED".to_string()),
        cooldown_until: None,
        updated_at: 10,
    };

    let merged = merge_udp_hints_with_capability(hints, Some(&capability));

    assert_eq!(merged.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
    assert_eq!(merged.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));
}

#[test]
fn capability_blocks_udp_for_soft_disable_but_respects_no_tcp_fallback() {
    let mut capability = capability("example.org:443");
    capability.quic_mode = "SOFT_DISABLE".to_string();
    assert!(capability_blocks_transport(&capability, TransportProtocol::Udp, 0));
    assert!(capability_requires_desync_fallback(&capability));
    assert!(!capability_udp_clean(&capability));

    capability.reason_code = Some("NO_TCP_FALLBACK".to_string());
    assert!(!capability_blocks_transport(&capability, TransportProtocol::Udp, 0));
    assert!(!capability_requires_desync_fallback(&capability));
    assert!(capability_udp_clean(&capability));
}

#[test]
fn no_tcp_fallback_keeps_udp_hints_intact() {
    let hints = AdaptivePlannerHints {
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
        ..AdaptivePlannerHints::default()
    };
    let mut capability = capability("example.org:443");
    capability.quic_mode = "SOFT_DISABLE".to_string();
    capability.reason_code = Some("NO_TCP_FALLBACK".to_string());

    let merged = merge_udp_hints_with_capability(hints, Some(&capability));

    assert_eq!(merged, hints);
}

#[test]
fn capability_blocks_tcp_for_owned_stack_and_active_no_direct_solution() {
    let mut owned_stack = capability("example.org:443");
    owned_stack.outcome = "OWNED_STACK_ONLY".to_string();
    assert!(capability_blocks_transport(&owned_stack, TransportProtocol::Tcp, 0));

    let mut no_direct = capability("example.org:443");
    no_direct.outcome = "NO_DIRECT_SOLUTION".to_string();
    no_direct.cooldown_until = Some(500);
    assert!(capability_blocks_transport(&no_direct, TransportProtocol::Tcp, 100));
    assert!(!capability_blocks_transport(&no_direct, TransportProtocol::Tcp, 1000));
}

#[test]
fn direct_path_capability_matches_targets_with_ip_set_digest() {
    let targets =
        vec!["203.0.113.10:443".parse().expect("first target"), "203.0.113.11:443".parse().expect("second target")];
    let digest = direct_path_ip_set_digest(&targets);
    assert_eq!(digest, "ae7c89389f929dcb");
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: None,
        protect_path: None,
        preferred_edges: BTreeMap::default(),
        direct_path_capabilities: vec![ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            ip_set_digest: digest,
            ..capability("example.org:443")
        }],
        morph_policy: None,
        connection_concurrency: None,
    };

    let matched =
        direct_path_capability_for_targets(Some(&runtime_context), Some("example.org"), &targets).expect("capability");

    assert_eq!(matched.authority, "example.org:443");
}

#[test]
fn hostname_capability_prefers_exact_digest_over_wildcard_in_any_order() {
    let targets = vec!["203.0.113.10:443".parse().expect("target")];
    let digest = direct_path_ip_set_digest(&targets);
    let wildcard = capability("example.org:443");
    let mut exact = capability("example.org:443");
    exact.ip_set_digest = digest;
    exact.outcome = "OWNED_STACK_ONLY".to_string();

    for capabilities in [vec![wildcard.clone(), exact.clone()], vec![exact.clone(), wildcard.clone()]] {
        let runtime_context =
            ProxyRuntimeContext { direct_path_capabilities: capabilities, ..ProxyRuntimeContext::default() };

        let matched =
            direct_path_capability_for_hostname_targets(Some(&runtime_context), Some("example.org"), &targets)
                .expect("hostname capability");

        assert_eq!(matched.ip_set_digest, exact.ip_set_digest);
        assert_eq!(matched.outcome, "OWNED_STACK_ONLY");
    }
}

#[test]
fn preferred_targets_selects_matching_edges_and_appends_original_target() {
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: None,
        protect_path: None,
        preferred_edges: BTreeMap::from([(
            "example.org".to_string(),
            vec![
                ProxyPreferredEdge { ip: "203.0.113.10".to_string(), transport_kind: "tcp".to_string() },
                ProxyPreferredEdge { ip: "203.0.113.11".to_string(), transport_kind: "throughput".to_string() },
                ProxyPreferredEdge { ip: "203.0.113.12".to_string(), transport_kind: "tcp".to_string() },
                ProxyPreferredEdge { ip: "203.0.113.13".to_string(), transport_kind: "quic".to_string() },
            ],
        )]),
        direct_path_capabilities: Vec::new(),
        morph_policy: None,
        connection_concurrency: None,
    };

    let decision = preferred_targets_for_transport(
        Some(&runtime_context),
        "198.51.100.9:443".parse().expect("target"),
        Some("example.org"),
        TransportProtocol::Tcp,
        0,
    );

    let expected = vec![
        "203.0.113.10:443".parse().expect("first preferred target"),
        "203.0.113.11:443".parse().expect("second preferred target"),
        "198.51.100.9:443".parse().expect("original target"),
    ];
    assert_eq!(decision.targets, expected);
    assert_eq!(decision.suppressed_targets, Vec::new());
    assert!(!decision.suppressed_udp);
}

#[test]
fn preferred_targets_suppresses_udp_when_direct_path_capability_blocks_quic() {
    let original = "198.51.100.9:443".parse().expect("target");
    let runtime_context = ProxyRuntimeContext {
        encrypted_dns: None,
        protect_path: None,
        preferred_edges: BTreeMap::new(),
        direct_path_capabilities: vec![ProxyDirectPathCapability {
            authority: "example.org:443".to_string(),
            quic_mode: "SOFT_DISABLE".to_string(),
            ..capability("example.org:443")
        }],
        morph_policy: None,
        connection_concurrency: None,
    };

    let decision = preferred_targets_for_transport(
        Some(&runtime_context),
        original,
        Some("example.org"),
        TransportProtocol::Udp,
        0,
    );

    assert!(decision.targets.is_empty());
    assert_eq!(decision.suppressed_targets, vec![original]);
    assert!(decision.suppressed_udp);
}

#[test]
fn retry_lane_classification_uses_policy_transport_and_payload_protocol() {
    assert_eq!(retry_lane_for_payload(TransportProtocol::Tcp, Some(DEFAULT_FAKE_TLS)), RetryLane::TcpTls);
    assert_eq!(retry_lane_for_payload(TransportProtocol::Tcp, Some(b"GET / HTTP/1.1\r\n\r\n")), RetryLane::TcpOther);
    assert_eq!(retry_lane_for_payload(TransportProtocol::Udp, None), RetryLane::UdpOther);
}
