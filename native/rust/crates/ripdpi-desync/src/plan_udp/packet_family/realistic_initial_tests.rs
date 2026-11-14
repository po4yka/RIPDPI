use super::*;
use crate::plan_udp::quic::normalized_quic_plan_input;
use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial};

#[test]
fn multi_initial_packets_fit_the_unknown_path_budget() {
    let long_host = format!("{}.{}.{}.{}", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        for host in ["decoy.example.test", long_host.as_str()] {
            let mut group = DesyncGroup::new(0);
            group.actions.quic_fake_host = Some(host.to_string());
            for input_len in [1200, 1452, 1472, 65_000] {
                let mut payload = build_realistic_quic_initial(version, Some("private.example.test")).expect("input");
                payload.resize(input_len, 0);
                let normalized = normalized_quic_plan_input(&payload).expect("normalized input");
                let packets = build_quic_multi_initial_realistic_packets(&group, Some(&normalized), 32);
                assert_eq!(packets.len(), 32);
                assert!(packets.iter().all(|packet| packet.len() == 1200));
            }
        }
    }
}

#[test]
fn multi_initial_tail_padding_cannot_exceed_the_encoded_budget() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("private.example.test")).expect("input");
    let normalized = normalized_quic_plan_input(&payload).expect("normalized input");
    // Exercise the packet builder independently of the public count guard.
    let packets = build_quic_multi_initial_realistic_packets(&DesyncGroup::new(0), Some(&normalized), 128);
    assert!(packets.len() >= 2);
    assert!(packets.iter().all(|packet| packet.len() == 1200));
}

#[test]
fn multi_initial_omits_a_seed_that_cannot_fit() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("private.example.test")).expect("input");
    let normalized = normalized_quic_plan_input(&payload).expect("normalized input");
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_host = Some("a".repeat(5000));
    assert!(build_quic_multi_initial_realistic_packets(&group, Some(&normalized), 2).is_empty());
}
