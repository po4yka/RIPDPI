use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};
use ripdpi_desync::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, DesyncAction, plan_udp,
};
use ripdpi_packets::{
    QuicInitialBrowserProfile, QuicInitialPacketLayout, build_browser_like_quic_initial_seed,
    build_realistic_quic_initial, packetize_quic_initial, parse_quic_initial,
};

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
fn planner_bounds_fake_version_copies_across_steps() {
    let payload = build_realistic_quic_initial(1, Some("real.example.test")).expect("Initial");
    for counts in [vec![65], vec![i32::MAX], vec![32, 33], vec![64, 1]] {
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain =
            counts.iter().map(|&count| UdpChainStep::new(UdpChainStepKind::QuicFakeVersion, count)).collect();
        let actions = plan_udp(&group, &payload, 64, context(&payload));
        let writes = actions
            .iter()
            .filter_map(|action| match action {
                DesyncAction::Write(bytes) => Some(bytes),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(writes.len(), 65, "64 decoys and one original for {counts:?}");
        assert_eq!(writes.last().copied(), Some(&payload));
        assert!(writes[..64].iter().all(|bytes| bytes.len() <= u16::MAX as usize));
    }
}

#[test]
fn planner_bounds_fake_version_packet_sizes() {
    let seed =
        build_browser_like_quic_initial_seed(1, Some("real.example.test"), QuicInitialBrowserProfile::ChromeAndroid)
            .expect("seed");
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicFakeVersion, 1)];
    for size in [65_535, 65_536] {
        let payload = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(size)).expect("Initial");
        assert_eq!(payload.len(), size);
        assert_eq!(parse_quic_initial(&payload).is_some(), size <= 65_535);

        let actions = plan_udp(&group, &payload, 64, context(&payload));

        if size > 65_535 {
            assert_eq!(actions, vec![DesyncAction::Write(payload)]);
        } else {
            let writes = actions
                .iter()
                .filter_map(|action| match action {
                    DesyncAction::Write(bytes) => Some(bytes),
                    _ => None,
                })
                .collect::<Vec<_>>();
            assert_eq!(writes.len(), 2, "one decoy and one original");
            assert_eq!(writes[0].len(), size);
            assert_eq!(writes[1], &payload);
        }
    }
}
