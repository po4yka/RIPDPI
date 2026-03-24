use super::*;

fn udp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        transport: ActivationTransport::Udp,
        tcp_segment_hint: None,
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

#[test]
fn plan_udp_no_fake_only_drop_sack() {
    let mut group = DesyncGroup::new(0);
    group.actions.drop_sack = true;

    let actions = plan_udp(&group, b"data", 0, udp_context(b"data"));

    assert_eq!(
        actions,
        vec![DesyncAction::AttachDropSack, DesyncAction::Write(b"data".to_vec()), DesyncAction::DetachDropSack,]
    );
}

#[test]
fn plan_udp_wraps_fake_burst_and_drop_sack_actions() {
    let mut group = DesyncGroup::new(0);
    group.actions.drop_sack = true;
    group.actions.udp_chain = vec![
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None },
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
    ];
    group.actions.ttl = Some(7);
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::AttachDropSack,
            DesyncAction::SetTtl(7),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::SetTtl(7),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
            DesyncAction::DetachDropSack,
        ]
    );
}

#[test]
fn plan_udp_step_activation_filter_skips_filtered_fake_bursts() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![
        UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 1,
            activation_filter: Some(ActivationFilter {
                round: Some(NumericRange::new(2, 4)),
                payload_size: None,
                stream_bytes: None,
            }),
        },
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
    ];
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
        ]
    );
}

#[test]
fn plan_udp_uses_generated_quic_fake_initial_when_profile_is_active() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(7);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.quic_fake_host = Some("video.example.test".to_string());
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected first fake write");
    };
    let parsed = parse_quic_initial(fake_packet).expect("parse generated fake");

    assert_eq!(parsed.version, QUIC_V2_VERSION);
    assert_eq!(parsed.host(), b"video.example.test");
    assert_eq!(actions.last(), Some(&DesyncAction::Write(payload)));
}

#[test]
fn plan_udp_adaptive_profiles_tune_burst_count_and_quic_fake_payload() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.quic_fake_host = Some("video.example.test".to_string());
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

    let balanced = plan_udp(&group, &payload, 64, udp_context(&payload));
    assert_eq!(balanced.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 3,);

    let mut conservative = udp_context(&payload);
    conservative.adaptive.udp_burst_profile = Some(AdaptiveUdpBurstProfile::Conservative);
    conservative.adaptive.quic_fake_profile = Some(QuicFakeProfile::CompatDefault);
    let adaptive_actions = plan_udp(&group, &payload, 64, conservative);
    assert_eq!(adaptive_actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 2,);
    let DesyncAction::Write(fake_packet) = &adaptive_actions[1] else {
        panic!("expected fake packet write");
    };
    assert_eq!(fake_packet, &default_fake_quic_compat());
}

#[test]
fn plan_udp_falls_back_to_raw_fake_payload_for_non_quic_input() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::CompatDefault;
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
        ]
    );
}

#[test]
fn plan_udp_uses_selected_udp_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_fake_profile = UdpFakeProfile::DnsQuery;
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected fake packet write");
    };
    assert_eq!(fake_packet.len(), 38);
    assert_eq!(&fake_packet[12..19], b"\x06update");
}
