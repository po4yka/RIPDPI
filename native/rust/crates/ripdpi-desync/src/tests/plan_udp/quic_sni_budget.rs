use super::*;
use ripdpi_packets::QUIC_V1_VERSION;

#[test]
fn plan_udp_limits_quic_sni_split_copies_from_direct_models() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.test")).expect("QUIC Initial");
    for counts in [vec![65], vec![32, 33]] {
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain =
            counts.into_iter().map(|count| UdpChainStep::new(UdpChainStepKind::QuicSniSplit, count)).collect();
        let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
        let writes: Vec<_> = actions
            .iter()
            .filter_map(|action| match action {
                DesyncAction::Write(bytes) => Some(bytes),
                _ => None,
            })
            .collect();
        assert_eq!(writes.len(), 65);
        assert_eq!(writes.last(), Some(&&payload));
        assert!(actions.len() <= 64 * 4 + 1);
    }
}

#[test]
fn inactive_quic_sni_split_steps_do_not_consume_the_copy_budget() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.test")).expect("QUIC Initial");
    let mut inactive = UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 64);
    inactive.activation_filter = Some(ActivationFilter { round: Some(NumericRange::new(2, 2)), ..Default::default() });
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![inactive, UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 64)];
    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    assert_eq!(actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 65);
    assert_eq!(actions.last(), Some(&DesyncAction::Write(payload)));
}

#[test]
fn zero_quic_sni_split_count_preserves_the_original_without_a_prelude() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.test")).expect("QUIC Initial");
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 0)];
    assert_eq!(plan_udp(&group, &payload, 64, udp_context(&payload)), vec![DesyncAction::Write(payload)]);
}
