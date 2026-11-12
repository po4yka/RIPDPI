use super::*;

use ripdpi_config::TcpFakeOrdering;
use ripdpi_packets::TlsFakeProfile;

#[test]
fn fake_split_custom_orders_keep_secondary_profile_in_both_regions() {
    for (order, seq_mode) in [
        (FakeOrder::BeforeEach, FakeSeqMode::Sequential),
        (FakeOrder::AllFakesFirst, FakeSeqMode::Duplicate),
        (FakeOrder::AllFakesFirst, FakeSeqMode::Sequential),
        (FakeOrder::RealFakeRealFake, FakeSeqMode::Duplicate),
        (FakeOrder::RealFakeRealFake, FakeSeqMode::Sequential),
        (FakeOrder::AllRealsFirst, FakeSeqMode::Duplicate),
        (FakeOrder::AllRealsFirst, FakeSeqMode::Sequential),
    ] {
        let payload = rust_packet_seeds::tls_client_hello();
        let mut group = test_group();
        group.actions.fake_data = Some(b"PRI".to_vec());
        group.actions.fake_tls_secondary_profile = Some(TlsFakeProfile::GoogleChrome);
        let step = TcpChainStep::new(TcpChainStepKind::FakeSplit, OffsetExpr::absolute(8));
        group.actions.tcp_chain.push(step.clone().with_fake_payload(TcpFakePayload {
            ordering: TcpFakeOrdering { order, seq_mode },
            ..step.fake_payload().expect("fake step payload")
        }));
        let (mut client, mut server) = connected_pair();
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            Some(8),
            AdaptivePlannerHints::default(),
        );
        let platform = ReceiptTestPlatform::default();

        let outcome = send_prepared_with_group(
            &mut client,
            &platform,
            &RuntimeConfig::default(),
            &group,
            &payload,
            progress,
            context,
            Some(8),
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect("custom fake split send");

        let fakes = platform.ordered_fake_payloads.lock().expect("ordered fake payloads");
        assert_eq!(fakes.len(), 4, "missing profile for {order:?}/{seq_mode:?}");
        assert_eq!(fakes[0], b"PRIPRIPR");
        assert_eq!(&fakes[1][..3], &[0x16, 0x03, 0x01]);
        assert_eq!(fakes[1].len(), 8);
        assert_eq!(fakes[2].len(), payload.len() - 8);
        assert_eq!(fakes[3].len(), payload.len() - 8);
        assert_ne!(fakes[2], fakes[3], "secondary second region lost its profile");
        assert_eq!(outcome.bytes_committed, payload.len());
        let mut received = vec![0; payload.len()];
        server.read_exact(&mut received).expect("read genuine payload");
        assert_eq!(received, payload);
    }
}

#[test]
fn fake_split_second_region_failure_preserves_step_context_and_progress() {
    let payload = b"ABCDEF";
    let step = TcpChainStep::new(TcpChainStepKind::FakeSplit, OffsetExpr::absolute(3));
    let group = test_group();
    let config = RuntimeConfig::default();
    let plan = ripdpi_desync::DesyncPlan {
        tampered: payload.to_vec(),
        steps: Vec::new(),
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: ripdpi_desync::TlsPreludeApplication::default(),
    };
    let fake_packets = crate::tcp_plan::BuiltFakePackets {
        primary: ripdpi_desync::FakePacketPlan { bytes: b"fake".to_vec(), fake_offset: 0, proto: ProtoInfo::default() },
        secondary: None,
    };
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut lowering = crate::tcp_lowering::TcpLoweringCapabilities::snapshot(&client, 64, &unavailable);
    let platform = ReceiptTestPlatform::default();
    platform
        .faults
        .lock()
        .expect("receipt fault queue")
        .push_back(FaultSpec::FakePayload { bytes: b"DEF".to_vec(), kind: io::ErrorKind::ConnectionReset });

    let error = crate::platform::with_tcp_desync_platform(&platform, || {
        let mut context = crate::tcp_fake_family::TcpFakeFamilyExecContext {
            writer: &mut client,
            config: &config,
            group: &group,
            plan: &plan,
            fake_packets: &fake_packets,
            resolved_fake_ttl: Some(8),
            lowering: &mut lowering,
            md5sig: false,
        };
        crate::tcp_fake_family::execute_tcp_fake_family_step(
            &mut context,
            TcpChainStepKind::FakeSplit,
            &step,
            &payload[..3],
            0,
            3,
            "fakedsplit",
            Some("split"),
            0,
        )
        .expect_err("second region fails")
    });

    let mut received = [0; 3];
    server.read_exact(&mut received).expect("read first region");
    assert_eq!(&received, b"ABC");
    match error {
        OutboundSendError::StrategyExecution { action, strategy_family, fallback, bytes_committed, source, .. } => {
            assert_eq!(action, "send_fake_fakesplit");
            assert_eq!(strategy_family, "fakedsplit");
            assert_eq!(fallback, Some("split"));
            assert_eq!(bytes_committed, 3);
            assert_eq!(source.kind(), io::ErrorKind::ConnectionReset);
        }
        other @ OutboundSendError::Transport { .. } => panic!("unexpected error: {other}"),
    }
}
