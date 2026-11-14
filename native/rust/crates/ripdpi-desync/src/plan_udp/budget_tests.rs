use super::*;
use crate::{ActivationTcpState, ActivationTransport, AdaptivePlannerHints};
use ripdpi_config::{NumericRange, UdpChainStep};
use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial};

fn context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Udp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

#[test]
fn cid_churn_planner_bounds_internal_counts_and_chain_total() {
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let payload = build_realistic_quic_initial(version, Some("input.example.test")).expect("Initial");
        for (counts, expected) in [
            (vec![0], 0),
            (vec![1], 1),
            (vec![64], 64),
            (vec![65], 64),
            (vec![i32::MAX], 64),
            (vec![32, 33], 64),
            (vec![64, i32::MAX], 64),
        ] {
            let mut group = DesyncGroup::new(0);
            group.actions.udp_chain =
                counts.into_iter().map(|count| UdpChainStep::new(UdpChainStepKind::QuicCidChurn, count)).collect();
            let actions = plan_udp(&group, &payload, 64, context(&payload));
            let writes = actions
                .iter()
                .filter_map(|action| match action {
                    DesyncAction::Write(bytes) => Some(bytes),
                    _ => None,
                })
                .collect::<Vec<_>>();
            assert_eq!(writes.len(), expected + 1);
            assert_eq!(writes.last().copied(), Some(&payload));
        }
    }
}

#[test]
fn inactive_cid_churn_step_does_not_consume_the_budget() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("input.example.test")).expect("Initial");
    let mut inactive = UdpChainStep::new(UdpChainStepKind::QuicCidChurn, 64);
    inactive.activation_filter = Some(ActivationFilter { round: Some(NumericRange::new(2, 2)), ..Default::default() });
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![inactive, UdpChainStep::new(UdpChainStepKind::QuicCidChurn, 1)];
    let actions = plan_udp(&group, &payload, 64, context(&payload));
    assert_eq!(actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 2);
    assert_eq!(actions.last(), Some(&DesyncAction::Write(payload)));
}
