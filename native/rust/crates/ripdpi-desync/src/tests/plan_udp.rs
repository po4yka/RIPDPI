use super::*;

fn udp_context(payload: &[u8]) -> ActivationContext {
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
        UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 2,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
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
            split_bytes: 0,
            activation_filter: Some(ActivationFilter {
                round: Some(NumericRange::new(2, 4)),
                payload_size: None,
                stream_bytes: None,
                ..Default::default()
            }),
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 2,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
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
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
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
fn plan_udp_realistic_quic_fake_defaults_to_builtin_fake_host() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(7);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected first fake write");
    };
    let parsed = parse_quic_initial(fake_packet).expect("parse generated fake");

    assert_eq!(parsed.version, QUIC_V2_VERSION);
    assert_eq!(parsed.host(), b"www.wikipedia.org", "a decoy must not carry the real authority");
}

#[test]
fn plan_udp_adaptive_profiles_tune_burst_count_and_quic_fake_payload() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.quic_fake_host = Some("video.example.test".to_string());
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
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
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
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
fn plan_udp_dummy_prepend_emits_compact_browser_like_quic_decoys() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(6);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::DummyPrepend,
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("dummy.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    assert_eq!(actions.len(), 6);
    assert_eq!(actions[0], DesyncAction::SetTtl(6));
    let DesyncAction::Write(first) = &actions[1] else {
        panic!("expected first dummy prepend packet");
    };
    let DesyncAction::Write(second) = &actions[2] else {
        panic!("expected second dummy prepend packet");
    };
    let first_parsed = parse_quic_initial(first).expect("parse first decoy");
    let second_parsed = parse_quic_initial(second).expect("parse second decoy");
    assert!(first.len() >= 256);
    assert!(second.len() >= 256);
    assert_eq!(first_parsed.host(), b"www.wikipedia.org");
    assert_eq!(second_parsed.host(), b"www.wikipedia.org");
    assert_ne!(first, second, "dummy prepend decoys should vary by packet number/profile");
    assert_eq!(actions[3], DesyncAction::RestoreDefaultTtl);
    assert_eq!(actions[4], DesyncAction::SetTtl(64));
    assert_eq!(actions[5], DesyncAction::Write(payload));
}

#[test]
fn plan_udp_quic_sni_split_emits_tampered_quic_initials() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicSniSplit,
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("docs.example.test")).expect("input quic");
    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    assert_eq!(actions[0], DesyncAction::SetTtl(8));
    let DesyncAction::Write(first_packet) = &actions[1] else {
        panic!("expected first split packet");
    };
    let DesyncAction::Write(second_packet) = &actions[2] else {
        panic!("expected second split packet");
    };
    let layout = parse_quic_initial_layout(first_packet).expect("parse split layout");
    assert_eq!(layout.info.host(), b"docs.example.test");
    assert_eq!(layout.crypto_frames.len(), 2);
    assert_eq!(
        layout.crypto_frames[0].crypto_offset + layout.crypto_frames[0].data_len,
        layout.info.tls_info.host_start
    );
    assert_eq!(first_packet, second_packet);
    assert_eq!(actions[3], DesyncAction::RestoreDefaultTtl);
    assert_eq!(actions[4], DesyncAction::SetTtl(64));
    assert_eq!(actions[5], DesyncAction::Write(payload));
}

#[test]
fn plan_udp_quic_crypto_split_uses_first_flight_ir_boundary() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicCryptoSplit,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("docs.example.test")).expect("input quic");
    let ir = normalize_quic_initial(&payload).expect("normalize input quic");
    let client_hello_len = ir.tls_client_hello.raw.len();
    let split_at = ir
        .desired
        .crypto_frame_boundaries
        .first()
        .copied()
        .filter(|offset| *offset > 0 && *offset < client_hello_len)
        .unwrap_or_else(|| (client_hello_len / 2).max(1));

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let DesyncAction::Write(packet) = &actions[1] else {
        panic!("expected split packet");
    };
    let layout = parse_quic_initial_layout(packet).expect("parse crypto split layout");
    assert_eq!(layout.info.host(), b"docs.example.test");
    assert_eq!(layout.crypto_frames.len(), 2);
    assert_eq!(layout.crypto_frames[0].crypto_offset + layout.crypto_frames[0].data_len, split_at);
    assert_eq!(layout.crypto_frames[1].crypto_offset, split_at);
    assert_eq!(actions[4], DesyncAction::Write(payload));
}

#[test]
fn quic_crypto_split_keeps_the_original_when_framing_exceeds_its_budget() {
    let mut seed = ripdpi_packets::parse_quic_initial_seed(include_bytes!(
        "../../../ripdpi-packets/tests/fixtures/quic-initial/token-v1.bin"
    ))
    .expect("independent RFC ClientHello");
    seed.token.clear();
    // Add a TLS padding extension so the 1150-byte handshake fills a 1200-byte Initial.
    seed.client_hello.extend_from_slice(&[0, 21, 3, 137]);
    seed.client_hello.extend_from_slice(&[0; 905]);
    seed.client_hello[3..5].copy_from_slice(&1150u16.to_be_bytes());
    seed.client_hello[6..9].copy_from_slice(&[0, 4, 122]);
    seed.client_hello[52..54].copy_from_slice(&1101u16.to_be_bytes());
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicCryptoSplit, 1)];
    for version in [ripdpi_packets::QUIC_V1_VERSION, QUIC_V2_VERSION] {
        seed.version = version;
        let payload =
            ripdpi_packets::packetize_quic_initial(&seed, &ripdpi_packets::QuicInitialPacketLayout::contiguous(1200))
                .expect("packetize tight Initial");
        assert_eq!(payload.len(), 1200);
        assert!(normalize_quic_initial(&payload).is_some());
        assert_eq!(plan_udp(&group, &payload, 64, udp_context(&payload)), vec![DesyncAction::Write(payload)],);
    }
}

#[test]
fn plan_udp_quic_multi_initial_realistic_uses_builtin_host_when_no_override_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicMultiInitialRealistic,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    assert_eq!(actions.len(), 6);
    assert_eq!(actions[0], DesyncAction::SetTtl(8));
    let DesyncAction::Write(first_packet) = &actions[1] else {
        panic!("expected first realistic initial write");
    };
    let DesyncAction::Write(second_packet) = &actions[2] else {
        panic!("expected second realistic initial write");
    };
    let first = parse_quic_initial(first_packet).expect("parse first realistic initial");
    let second = parse_quic_initial(second_packet).expect("parse second realistic initial");
    assert_eq!(first.version, QUIC_V2_VERSION);
    assert_eq!(first.host(), b"www.wikipedia.org");
    assert_eq!(second.host(), b"www.wikipedia.org");
    assert_ne!(first_packet, second_packet, "multi-initial realistic should vary browser profile or packet number");
    assert_eq!(actions[4], DesyncAction::SetTtl(64));
    assert_eq!(actions[5], DesyncAction::Write(payload));
}

#[test]
fn plan_udp_quic_fake_version_emits_tampered_long_headers() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_version = 0x1a2b_3c4d;
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicFakeVersion,
        count: 2,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("docs.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let DesyncAction::Write(first_packet) = &actions[1] else {
        panic!("expected first fake-version packet");
    };
    let DesyncAction::Write(second_packet) = &actions[2] else {
        panic!("expected second fake-version packet");
    };
    assert_eq!(&first_packet[1..5], &group.actions.quic_fake_version.to_be_bytes());
    assert_eq!(first_packet, second_packet);
    assert!(parse_quic_initial(first_packet).is_none());
    assert_eq!(actions[5], DesyncAction::Write(payload));
}

#[test]
fn plan_udp_quic_padding_ladder_uses_builtin_fake_host() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicPaddingLadder,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("padding.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let DesyncAction::Write(packet) = &actions[1] else {
        panic!("expected padding ladder packet");
    };
    let parsed = parse_quic_initial(packet).expect("parse ladder packet");
    assert_eq!(parsed.host(), b"www.wikipedia.org");
    assert!(packet.len() >= payload.len());
}

#[test]
fn plan_udp_quic_padding_ladder_rungs_have_distinct_lengths() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicPaddingLadder,
        count: 4,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("padding.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let lengths = actions[1..5]
        .iter()
        .map(|action| match action {
            DesyncAction::Write(packet) => packet.len(),
            other => panic!("expected ladder packet, got {other:?}"),
        })
        .collect::<Vec<_>>();
    assert!(lengths.windows(2).all(|pair| pair[0] < pair[1]), "ladder rungs must grow: {lengths:?}");
}

#[test]
fn plan_udp_quic_decoys_stay_within_a_1500_byte_path() {
    // Four labels of 63/63/63/61 characters make a 253-character host.
    let long_host = [63, 63, 63, 61].map(|len| "a".repeat(len)).join(".");
    let max = ripdpi_config::MAX_QUIC_PADDING_LADDER_COUNT;
    for kind in [UdpChainStepKind::QuicPaddingLadder, UdpChainStepKind::QuicMultiInitialRealistic] {
        for (count, built) in [(1, 1), (4, 4), (31, 31), (max, max as usize), (1000, max as usize)] {
            // The multi-initial step always builds at least two decoys.
            let built = if kind == UdpChainStepKind::QuicMultiInitialRealistic { built.max(2) } else { built };
            for quic_fake_host in [None, Some(long_host.clone())] {
                let mut group = DesyncGroup::new(0);
                group.actions.quic_fake_host = quic_fake_host.clone();
                group.actions.udp_chain = vec![UdpChainStep {
                    kind,
                    count,
                    split_bytes: 0,
                    activation_filter: None,
                    ip_frag_disorder: false,
                    ipv6_hop_by_hop: false,
                    ipv6_dest_opt: false,
                    ipv6_dest_opt2: false,
                    ipv6_frag_next_override: None,
                }];
                for input_len in [1200, 1250, 1452, 1472] {
                    let mut payload =
                        build_realistic_quic_initial(QUIC_V2_VERSION, Some("mtu.example.test")).expect("input quic");
                    assert!(payload.len() <= input_len);
                    // A client pads its Initial datagram up to the path maximum.
                    payload.resize(input_len, 0);

                    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

                    let lengths = actions[1..=built]
                        .iter()
                        .map(|action| match action {
                            DesyncAction::Write(packet) => packet.len(),
                            other => panic!("{kind:?}: expected decoy packet, got {other:?}"),
                        })
                        .collect::<Vec<_>>();
                    let case = format!("{kind:?} count {count} input {input_len} host {quic_fake_host:?}: {lengths:?}");
                    let writes = actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count();
                    assert_eq!(writes, built + 1, "{case}");
                    let ceiling = input_len.max(1452);
                    assert!(lengths.iter().all(|&len| (1200..=ceiling).contains(&len)), "{case}");
                    assert!(lengths.windows(2).all(|pair| pair[0] < pair[1]), "{case}");
                }
            }
        }
    }
}

#[test]
fn plan_udp_quic_ladder_decoys_vary_per_flow_and_per_rung() {
    use ripdpi_packets::{QuicInitialPacketLayout, packetize_quic_initial, parse_quic_initial_seed};

    // RFC 9001 Appendix A test-vector DCID, the old constant of every decoy.
    const OLD_FIXED_DCID: [u8; 8] = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    // Record header (5), handshake header (4) and legacy version (2) precede the random.
    const RANDOM: std::ops::Range<usize> = 11..43;

    let mut group = DesyncGroup::new(0);
    let mut step = fake_burst_step();
    step.kind = UdpChainStepKind::QuicPaddingLadder;
    step.count = 4;
    group.actions.udp_chain = vec![step];
    let base = build_realistic_quic_initial(QUIC_V2_VERSION, Some("flow.example.test")).expect("input quic");
    let input_with_dcid = |dcid: [u8; 8]| {
        let mut seed = parse_quic_initial_seed(&base).expect("input seed");
        seed.dcid = dcid.to_vec();
        packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("input with dcid")
    };
    let ladder = |input: &[u8]| -> Vec<(Vec<u8>, ripdpi_packets::QuicInitialSeed)> {
        plan_udp(&group, input, 64, udp_context(input))[1..5]
            .iter()
            .map(|action| {
                let DesyncAction::Write(packet) = action else { panic!("expected ladder packet, got {action:?}") };
                (packet.clone(), parse_quic_initial_seed(packet).expect("decrypt decoy"))
            })
            .collect()
    };

    let dcids = [[1_u8, 2, 3, 4, 5, 6, 7, 8], [8_u8, 7, 6, 5, 4, 3, 2, 1]];
    let ladders = dcids.map(|dcid| ladder(&input_with_dcid(dcid)));
    for (dcid, rungs) in dcids.iter().zip(&ladders) {
        for (idx, (packet, seed)) in rungs.iter().enumerate() {
            assert_ne!(seed.dcid, OLD_FIXED_DCID);
            assert_ne!(seed.dcid, dcid.to_vec());
            assert_eq!(seed.dcid, rungs[0].1.dcid, "one flow keeps one decoy DCID");
            // Rung `idx` carries packet number `idx`: rebuilding it from the
            // decrypted seed with that number reproduces the datagram.
            let layout = {
                let mut layout = QuicInitialPacketLayout::contiguous(packet.len());
                layout.packet_number = idx as u32;
                layout
            };
            assert_eq!(packetize_quic_initial(seed, &layout).as_ref(), Some(packet), "rung {idx} packet number");
        }
    }
    assert_ne!(ladders[0][0].1.dcid, ladders[1][0].1.dcid, "different flows get different DCIDs");
    assert_ne!(
        ladders[0][0].1.client_hello[RANDOM], ladders[1][0].1.client_hello[RANDOM],
        "different flows get different ClientHello randoms"
    );

    // Bytes that follow the only occurrence of `marker` in the ClientHello.
    let after_marker = |hello: &[u8], marker: &[u8], len: usize| -> Vec<u8> {
        let starts: Vec<usize> =
            (0..=hello.len() - marker.len()).filter(|&at| &hello[at..at + marker.len()] == marker).collect();
        assert_eq!(starts.len(), 1, "marker {marker:02x?} must occur once");
        hello[starts[0] + marker.len()..starts[0] + marker.len() + len].to_vec()
    };
    for rungs in &ladders {
        let seed = &rungs[0].1;
        assert_ne!(seed.scid, [0x11, 0x22, 0x33, 0x44], "decoy SCID is not the old constant");
        // initial_source_connection_id (0x0f) must repeat the header SCID.
        let mut scid_param = vec![0x0f, seed.scid.len() as u8];
        scid_param.extend_from_slice(&seed.scid);
        after_marker(&seed.client_hello, &scid_param, 0);
    }
    assert_ne!(ladders[0][0].1.scid, ladders[1][0].1.scid, "different flows get different SCIDs");
    // X25519 key_share entry: group 0x001d, key length 32.
    let x25519 = [0x00, 0x1d, 0x00, 0x20];
    assert_ne!(
        after_marker(&ladders[0][0].1.client_hello, &x25519, 32),
        after_marker(&ladders[1][0].1.client_hello, &x25519, 32),
        "different flows get different X25519 key shares"
    );
}

#[test]
fn plan_udp_quic_padding_ladder_needs_only_the_initial_header() {
    let mut group = DesyncGroup::new(0);
    let mut step = fake_burst_step();
    step.kind = UdpChainStepKind::QuicPaddingLadder;
    step.count = 3;
    group.actions.udp_chain = vec![step];

    // The ClientHello does not fit one datagram, so there is no normalized input.
    let payload = rust_packet_seeds::quic_initial_with_crypto_gap(1, "docs.example.test");
    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let writes = actions
        .iter()
        .filter_map(|action| match action {
            DesyncAction::Write(packet) => Some(packet),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(writes.len(), 3 + 1, "three decoys plus the real datagram");
    assert_eq!(writes[3], &payload, "the real datagram goes out once, last");
    // A later datagram of the flow is the ClientHello's second half, an ACK or a
    // retransmission: it gets no new burst.
    assert_eq!(
        plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 2)),
        vec![DesyncAction::Write(payload.clone())]
    );
    for decoy in &writes[..3] {
        let parsed = parse_quic_initial(decoy).expect("decoy is a decryptable Initial");
        assert_eq!(parsed.host(), b"www.wikipedia.org");
        assert!(decoy.len() >= 1200);
    }

    let long_header_prefix = |version: [u8; 4]| {
        let mut bytes = vec![0xc3];
        bytes.extend_from_slice(&version);
        bytes.extend_from_slice(&[8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0]);
        bytes.resize(1200, 0);
        bytes
    };
    let mut truncated_dcid = long_header_prefix([0, 0, 0, 1]);
    truncated_dcid.truncate(10);
    let malformed: [Vec<u8>; 7] = [
        Vec::new(),
        vec![0xc3],
        vec![0x43; 1200],
        long_header_prefix(0x1a2a_3a4a_u32.to_be_bytes()),
        b"not a quic datagram, just some text".repeat(40),
        truncated_dcid,
        vec![0xc3, 0, 0, 0, 1, 8],
    ];
    for payload in malformed {
        let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
        assert_eq!(actions, vec![DesyncAction::Write(payload.clone())], "malformed input {payload:02x?}");
    }
}

#[test]
fn plan_udp_quic_multi_initial_realistic_packets_are_distinct() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicMultiInitialRealistic,
        count: 3,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let DesyncAction::Write(first) = &actions[1] else { panic!("expected first initial") };
    let DesyncAction::Write(third) = &actions[3] else { panic!("expected third initial") };
    assert_ne!(first, third, "same-profile initials must not be byte-identical");
}

#[test]
fn plan_udp_quic_version_negotiation_decoy_uses_ir_seed_authority() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicVersionNegotiationDecoy,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let seed_payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("decoy.example.test")).expect("input quic");
    let payload = seed_payload.clone();

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    let DesyncAction::Write(packet) = &actions[1] else {
        panic!("expected version-negotiation decoy packet");
    };
    assert_eq!(&packet[1..5], &(QUIC_V2_VERSION ^ 0x0f0f_0f0f).to_be_bytes());
    assert!(parse_quic_initial(packet).is_none());
}

#[test]
fn plan_udp_ipfrag2_emits_fragmented_write_for_quic_initial() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::IpFrag2Udp,
        count: 0,
        split_bytes: 8,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("docs.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));

    assert_eq!(
        actions,
        vec![DesyncAction::WriteIpFragmentedUdp {
            bytes: payload,
            split_offset: 8,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default()
        }]
    );
}

#[test]
fn plan_udp_ipfrag2_falls_back_to_normal_write_for_non_quic_payload() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::IpFrag2Udp,
        count: 0,
        split_bytes: 8,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];

    let actions = plan_udp(&group, b"not-quic", 64, udp_context(b"not-quic"));

    assert_eq!(actions, vec![DesyncAction::Write(b"not-quic".to_vec())]);
}

#[test]
fn plan_udp_skips_quic_specific_steps_when_payload_is_not_quic() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![
        UdpChainStep {
            kind: UdpChainStepKind::DummyPrepend,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::QuicSniSplit,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::QuicFakeVersion,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
    ];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(actions, vec![DesyncAction::Write(b"payload".to_vec())]);
}

#[test]
fn plan_udp_uses_selected_udp_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_fake_profile = UdpFakeProfile::DnsQuery;
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected fake packet write");
    };
    assert_eq!(fake_packet.len(), 38);
    assert_eq!(&fake_packet[12..19], b"\x06update");
}

// Fake data that lands inside the GFW popcount detection window (3.4-4.6):
// 0x0F bytes have popcount 4.0/byte, triggering generate_entropy_padding.
const HIGH_POPCOUNT_FAKE: &[u8] = &[0x0F_u8; 32];

fn fake_burst_step() -> UdpChainStep {
    UdpChainStep {
        kind: UdpChainStepKind::FakeBurst,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }
}

#[test]
fn entropy_padding_disabled_leaves_fake_payload_unchanged() {
    let mut group = DesyncGroup::new(0);
    group.actions.entropy_mode = EntropyMode::Disabled;
    group.actions.fake_data = Some(HIGH_POPCOUNT_FAKE.to_vec());
    group.actions.udp_chain = vec![fake_burst_step()];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    // append_ttl_wrapped_packets emits SetTtl first, so the fake Write is at index 1.
    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected fake write at actions[1], got: {:?}", actions[1]);
    };
    assert_eq!(fake_packet.as_slice(), HIGH_POPCOUNT_FAKE);
}

#[test]
fn entropy_padding_popcount_mode_prepends_padding_to_high_popcount_fake() {
    let mut group = DesyncGroup::new(0);
    group.actions.entropy_mode = EntropyMode::Popcount;
    // Target 3.4 (permil 3400): HIGH_POPCOUNT_FAKE has popcount 4.0/byte, inside
    // the GFW detection window, so padding will be prepended.
    group.actions.entropy_padding_target_permil = Some(3400);
    group.actions.entropy_padding_max = 256;
    group.actions.fake_data = Some(HIGH_POPCOUNT_FAKE.to_vec());
    group.actions.udp_chain = vec![fake_burst_step()];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    // append_ttl_wrapped_packets emits SetTtl first, so the fake Write is at index 1.
    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected fake write at actions[1], got: {:?}", actions[1]);
    };
    // Packet must be longer than the original fake data (padding was prepended).
    assert!(fake_packet.len() > HIGH_POPCOUNT_FAKE.len(), "expected padding to grow the fake packet");
    // Combined popcount of the padded packet must be at or below the target.
    assert!(
        popcount_per_byte(fake_packet) <= 3.4_f32,
        "combined popcount {:.3} exceeds target 3.4",
        popcount_per_byte(fake_packet)
    );
    // The original fake data must still appear as the tail of the packet.
    assert_eq!(&fake_packet[fake_packet.len() - HIGH_POPCOUNT_FAKE.len()..], HIGH_POPCOUNT_FAKE);
}

fn fake_burst_with_round(round: Option<NumericRange<i64>>) -> DesyncGroup {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_data = Some(b"udp-fake".to_vec());
    let mut step = fake_burst_step();
    step.activation_filter = round.map(|round| ActivationFilter { round: Some(round), ..Default::default() });
    group.actions.udp_chain = vec![step];
    group
}

fn udp_context_at_round(payload: &[u8], round: i64) -> ActivationContext {
    ActivationContext { round, ..udp_context(payload) }
}

#[test]
fn fake_burst_without_round_skips_later_non_initial_datagrams() {
    let group = fake_burst_with_round(None);
    let payload = [0x40_u8, 1, 2, 3, 4, 5, 6, 7, 8];

    let actions = plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 2));

    assert_eq!(actions, vec![DesyncAction::Write(payload.to_vec())]);
}

#[test]
fn fake_burst_without_round_still_fires_on_later_quic_initial() {
    let group = fake_burst_with_round(None);
    let payload = rust_packet_seeds::quic_initial_with_host(1, "docs.example.test");

    let actions = plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 2));

    assert!(actions.contains(&DesyncAction::Write(b"udp-fake".to_vec())));
}

#[test]
fn fake_burst_explicit_step_round_applies_at_later_rounds() {
    let group = fake_burst_with_round(Some(NumericRange::new(1, 5)));
    let payload = [0x40_u8, 1, 2, 3, 4, 5, 6, 7, 8];

    let actions = plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 2));

    assert!(actions.contains(&DesyncAction::Write(b"udp-fake".to_vec())));
}

#[test]
fn fake_burst_explicit_group_round_applies_at_later_rounds() {
    let mut group = fake_burst_with_round(None);
    group.set_round_activation(Some(NumericRange::new(1, 5)));
    let payload = [0x40_u8, 1, 2, 3, 4, 5, 6, 7, 8];

    let actions = plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 2));

    assert!(actions.contains(&DesyncAction::Write(b"udp-fake".to_vec())));
}

#[test]
fn round_default_does_not_gate_other_udp_steps() {
    let mut group = DesyncGroup::new(0);
    let mut step = fake_burst_step();
    step.kind = UdpChainStepKind::QuicPaddingLadder;
    group.actions.udp_chain = vec![step];
    let payload = rust_packet_seeds::quic_initial_with_host(1, "docs.example.test");

    let first = plan_udp(&group, &payload, 64, udp_context(&payload));
    let later = plan_udp(&group, &payload, 64, udp_context_at_round(&payload, 3));

    assert!(first.len() > 1, "padding ladder emits decoys");
    assert_eq!(later.len(), first.len(), "round 3 keeps the padding ladder");
}

#[test]
fn fake_burst_planner_caps_count_built_outside_parsers() {
    let mut group = DesyncGroup::new(0);
    let mut step = fake_burst_step();
    step.count = 1_000_000;
    group.actions.udp_chain = vec![step];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));
    let writes = actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count();

    assert_eq!(writes, 64 + 1, "64 decoys plus the real datagram");
}

#[test]
fn quic_padding_ladder_planner_caps_count_built_outside_parsers() {
    let mut group = DesyncGroup::new(0);
    let mut step = fake_burst_step();
    step.kind = UdpChainStepKind::QuicPaddingLadder;
    step.count = 1_000_000;
    group.actions.udp_chain = vec![step];
    let payload = rust_packet_seeds::quic_initial_with_host(1, "docs.example.test");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let writes = actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count();

    assert_eq!(writes, ripdpi_config::MAX_QUIC_PADDING_LADDER_COUNT as usize + 1, "MAX decoys plus the real datagram");
}

#[test]
fn quic_fake_profile_applies_to_initial_with_incomplete_client_hello() {
    let payload = rust_packet_seeds::quic_initial_with_crypto_gap(1, "docs.example.test");
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![fake_burst_step()];

    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let DesyncAction::Write(fake) = &actions[1] else {
        panic!("expected fake write, got {:?}", actions[1]);
    };
    assert_eq!(fake[0] & 0x80, 0x80, "decoy must be a QUIC long header");
    assert_eq!(fake.len(), 1200);

    group.actions.quic_fake_profile = QuicFakeProfile::CompatDefault;
    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    assert_eq!(actions[1], DesyncAction::Write(default_fake_quic_compat()));
}

#[test]
fn aggressive_burst_profile_never_lowers_the_configured_count() {
    let payload = [0x40_u8, 1, 2, 3];
    let mut context = udp_context(&payload);
    context.adaptive.udp_burst_profile = Some(AdaptiveUdpBurstProfile::Aggressive);
    for (configured, expected) in [(3, 4), (16, 16), (20, 20)] {
        let mut group = fake_burst_with_round(None);
        group.actions.udp_chain[0].count = configured;
        let writes = plan_udp(&group, &payload, 64, context)
            .iter()
            .filter(|action| **action == DesyncAction::Write(b"udp-fake".to_vec()))
            .count();
        assert_eq!(writes, expected, "configured count {configured}");
    }
}

#[test]
fn udp_fake_ignores_the_tcp_fake_offset() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_fake_profile = UdpFakeProfile::StunBinding;
    group.actions.fake_offset = Some(OffsetExpr::absolute(8));
    group.actions.udp_chain = vec![fake_burst_step()];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions[1],
        DesyncAction::Write(ripdpi_packets::udp_fake_profile_bytes(UdpFakeProfile::StunBinding).to_vec())
    );
}

#[test]
fn dummy_prepend_planner_bounds_direct_model_counts_and_keeps_original() {
    for version in [1, QUIC_V2_VERSION] {
        let payload = build_realistic_quic_initial(version, Some("original.example.test")).unwrap();
        for counts in [vec![65], vec![32, 33], vec![i32::MAX]] {
            let mut group = DesyncGroup::new(0);
            group.actions.udp_chain =
                counts.into_iter().map(|count| UdpChainStep::new(UdpChainStepKind::DummyPrepend, count)).collect();
            let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
            let writes: Vec<_> = actions
                .iter()
                .filter_map(|action| match action {
                    DesyncAction::Write(bytes) => Some(bytes),
                    _ => None,
                })
                .collect();
            assert_eq!(writes.len(), 65, "at most 64 dummy datagrams plus the original");
            assert!(writes[..64].iter().all(|bytes| bytes.len() <= 4096));
            assert_eq!(writes[64], &payload);
        }
    }
}

#[test]
fn dummy_prepend_skips_oversized_hosts_and_packets() {
    let payload = build_realistic_quic_initial(1, Some("original.example.test")).unwrap();
    for host_len in [4096, 4097] {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_fake_host = Some("a".repeat(host_len));
        group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::DummyPrepend, 2)];
        let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
        assert_eq!(actions, vec![DesyncAction::Write(payload.clone())]);
    }
}
