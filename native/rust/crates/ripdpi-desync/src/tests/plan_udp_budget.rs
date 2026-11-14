use super::*;

const PAYLOAD: &[u8] =
    include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/fixtures/quic-initial/token-v1.bin"));

fn planned_writes(counts: &[i32]) -> Vec<Vec<u8>> {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain =
        counts.iter().map(|&count| UdpChainStep::new(UdpChainStepKind::QuicCryptoSplit, count)).collect();
    let context = ActivationContext { transport: ActivationTransport::Udp, ..tcp_context(PAYLOAD) };
    plan_udp(&group, PAYLOAD, 64, context)
        .into_iter()
        .filter_map(|action| if let DesyncAction::Write(bytes) = action { Some(bytes) } else { None })
        .collect()
}

#[test]
fn crypto_split_quota_bounds_typed_counts_before_cloning() {
    // The first value must fail before the huge value on an unbounded planner.
    for count in [65, i32::MAX] {
        let writes = planned_writes(&[count]);
        assert_eq!(writes.len(), 65);
        assert_eq!(writes.last().map(Vec::as_slice), Some(PAYLOAD));
        assert_eq!(writes.iter().filter(|bytes| bytes.as_slice() == PAYLOAD).count(), 1);
    }
}

#[test]
fn crypto_split_quota_is_shared_across_steps() {
    let writes = planned_writes(&[32, 33]);
    assert_eq!(writes.len(), 65);
    assert_eq!(writes.last().map(Vec::as_slice), Some(PAYLOAD));
}

#[test]
fn crypto_split_quota_preserves_valid_counts_and_zero() {
    for count in [0, 1, 64] {
        let writes = planned_writes(&[count]);
        assert_eq!(writes.len(), count as usize + 1);
        assert_eq!(writes.last().map(Vec::as_slice), Some(PAYLOAD));
    }
}

#[test]
fn crypto_split_quota_ignores_inactive_steps() {
    let mut group = DesyncGroup::new(0);
    let mut inactive = UdpChainStep::new(UdpChainStepKind::QuicCryptoSplit, 64);
    inactive.activation_filter =
        Some(ActivationFilter { round: Some(NumericRange { start: 2, end: 2 }), ..Default::default() });
    group.actions.udp_chain = vec![inactive, UdpChainStep::new(UdpChainStepKind::QuicCryptoSplit, 1)];
    let context = ActivationContext { transport: ActivationTransport::Udp, ..tcp_context(PAYLOAD) };
    let actions = plan_udp(&group, PAYLOAD, 64, context);
    assert_eq!(actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 2);
    assert_eq!(actions.last(), Some(&DesyncAction::Write(PAYLOAD.to_vec())));
}
