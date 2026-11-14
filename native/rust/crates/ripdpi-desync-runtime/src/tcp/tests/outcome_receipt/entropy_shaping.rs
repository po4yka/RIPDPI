use super::*;

fn receive_with_entropy(
    group: &DesyncGroup,
    payload: &[u8],
    adaptive: AdaptivePlannerHints,
    raw_unavailable: bool,
) -> (Vec<u8>, Vec<Vec<u8>>, OutboundSendOutcome) {
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
    let context =
        activation_context_from_progress(progress, ActivationTransport::Tcp, Some(payload), None, None, None, adaptive);
    let (mut client, mut peer) = connected_pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer deadline");
    let platform = ReceiptTestPlatform { raw_unavailable, require_disorder_raw_original: true, ..Default::default() };
    let outcome = send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("send");
    drop(client);
    let mut received = Vec::new();
    peer.read_to_end(&mut received).expect("read genuine stream");
    assert_eq!(outcome.bytes_committed, payload.len());
    let fakes = platform.ordered_fake_payloads.into_inner().expect("fake bytes");
    (received, fakes, outcome)
}

#[test]
fn entropy_modes_preserve_genuine_stream_and_plain_fallback() {
    let payload: Vec<u8> = (0..=255).cycle().take(512).collect();
    for mode in [EntropyMode::Disabled, EntropyMode::Popcount, EntropyMode::Shannon, EntropyMode::Combined] {
        for kind in [
            TcpChainStepKind::Split,
            TcpChainStepKind::Fake,
            TcpChainStepKind::FakeSplit,
            TcpChainStepKind::FakeDisorder,
        ] {
            for adaptive in [false, true] {
                let mut group = test_group();
                group.actions.entropy_mode = if adaptive { EntropyMode::Disabled } else { mode };
                group.actions.fake_data = Some(payload.clone());
                group.actions.entropy_padding_max = 4096;
                group.actions.shannon_entropy_target_permil = Some(6000);
                group.actions.tcp_chain = vec![TcpChainStep::new(kind, OffsetExpr::absolute(128))];
                let hints = AdaptivePlannerHints { entropy_mode: adaptive.then_some(mode), ..Default::default() };
                let (wire, _, _) = receive_with_entropy(&group, &payload, hints, false);
                assert_eq!(wire, payload, "{mode:?} {kind:?} adaptive={adaptive}");
            }
        }
        let mut group = test_group();
        group.actions.entropy_mode = mode;
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(1)),
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::absolute(1)),
        ];
        let (wire, _, _) = receive_with_entropy(&group, &payload, AdaptivePlannerHints::default(), false);
        assert_eq!(wire, payload, "plain fallback {mode:?}");
    }
}

#[test]
fn entropy_shapes_final_ordered_fake_segments() {
    let payload = vec![0xaa; 100];
    for kind in [TcpChainStepKind::Fake, TcpChainStepKind::FakeSplit, TcpChainStepKind::FakeDisorder] {
        for adaptive in [false, true] {
            let mut group = test_group();
            group.actions.entropy_mode = if adaptive { EntropyMode::Disabled } else { EntropyMode::Popcount };
            group.actions.fake_data = Some(payload.clone());
            group.actions.tcp_chain = vec![TcpChainStep::new(kind, OffsetExpr::absolute(50))];
            let mut options = group.actions.tcp_chain[0].fake_payload().expect("fake payload");
            options.ordering.order = FakeOrder::AllFakesFirst;
            group.actions.tcp_chain[0] = group.actions.tcp_chain[0].clone().with_fake_payload(options);
            let hints =
                AdaptivePlannerHints { entropy_mode: adaptive.then_some(EntropyMode::Popcount), ..Default::default() };
            let (wire, fakes, _) = receive_with_entropy(&group, &payload, hints, false);
            assert_eq!(wire, payload);
            assert!(!fakes.is_empty());
            for fake in fakes {
                assert!(fake.len() > 50, "padding must survive region slicing");
                assert!(fake.ends_with(&payload[..50]));
                let set_bits: u32 = fake.iter().map(|b| b.count_ones()).sum();
                assert!(f64::from(set_bits) / fake.len() as f64 <= 3.4);
            }
        }
    }
}

#[test]
fn entropy_hostfake_preserves_real_host_and_shapes_each_fake_fragment() {
    let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    for custom in [false, true] {
        let mut group = test_group();
        group.actions.entropy_mode = EntropyMode::Popcount;
        let mut step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0))
            .with_fake_host_template(Some("fffffff.fffffff".to_string()));
        if custom {
            step = step.with_midhost_offset(Some(OffsetExpr::marker(OffsetBase::MidSld, 0)));
            if let TcpTypedChainStep::HostFake { common, mut payload } = step.typed_step() {
                payload.ordering.order = FakeOrder::AllFakesFirst;
                payload.ordering.seq_mode = FakeSeqMode::Sequential;
                step = TcpChainStep::from_typed_step(TcpTypedChainStep::HostFake { common, payload });
            }
        }
        group.actions.tcp_chain.push(step);
        let (wire, fakes, _) = receive_with_entropy(&group, payload, AdaptivePlannerHints::default(), false);
        assert_eq!(wire, payload);
        assert!(!fakes.is_empty());
        for fake in fakes {
            let set_bits: u32 = fake.iter().map(|b| b.count_ones()).sum();
            assert!(f64::from(set_bits) / fake.len() as f64 <= 3.4, "unshaped fake: {fake:?}");
        }
    }
}

#[test]
fn enlarged_entropy_fake_without_raw_transport_is_an_explicit_real_only_split() {
    for mode in [EntropyMode::Popcount, EntropyMode::Shannon, EntropyMode::Combined] {
        let fake = if mode == EntropyMode::Popcount {
            [vec![0xff; 35], vec![0x0f; 30], vec![0; 35]].concat()
        } else {
            (0..=255).collect()
        };
        let payload = vec![0x55; fake.len() * 2];
        for kind in [TcpChainStepKind::Fake, TcpChainStepKind::FakeSplit, TcpChainStepKind::FakeDisorder] {
            for adaptive in [false, true] {
                let mut group = test_group();
                group.actions.fake_data = Some(fake.clone());
                group.actions.entropy_mode = if adaptive { EntropyMode::Disabled } else { mode };
                group.actions.shannon_entropy_target_permil = Some(6000);
                group.actions.entropy_padding_max = 4096;
                group.actions.tcp_chain = vec![TcpChainStep::new(kind, OffsetExpr::absolute(fake.len() as i64))];
                let hints = AdaptivePlannerHints { entropy_mode: adaptive.then_some(mode), ..Default::default() };
                let (wire, fakes, outcome) = receive_with_entropy(&group, &payload, hints, true);
                assert_eq!(wire, payload);
                assert!(fakes.is_empty());
                assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
                assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
            }
        }
    }
}
