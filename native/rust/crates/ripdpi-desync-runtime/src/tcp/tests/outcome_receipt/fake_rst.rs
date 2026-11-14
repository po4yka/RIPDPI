//! Fake RST executor and receipt tests (DPI-15).

use super::*;

#[test]
fn flagged_fake_rst_lowers_the_socket_ttl_around_the_reset() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.ttl = Some(5);
    let plain = TcpChainStep::new(TcpChainStepKind::FakeRst, OffsetExpr::host(1));
    // Fake flag overrides route the step to the plan executor.
    group.actions.tcp_chain.push(TcpChainStep::from_typed_step(ripdpi_config::TcpTypedChainStep::FakeRst {
        common: plain.typed_step().common(),
        fake_flags: ripdpi_config::TcpFlagOverrides { set: Some(0x08), unset: None },
    }));
    let (mut client, mut server) = connected_pair();
    let default_ttl = client.ttl().expect("read default ttl");
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let platform = ReceiptTestPlatform::default();

    send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("fakerst step writes the payload");

    assert_eq!(*platform.fake_rst_ttls.lock().expect("fake rst ttl log"), vec![5]);
    assert_eq!(client.ttl().expect("read restored ttl"), default_ttl);
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

fn send_plain_fakerst(
    platform: &ReceiptTestPlatform,
    config: &RuntimeConfig,
    resolved_fake_ttl: Option<u8>,
) -> (Vec<u8>, OutboundSendOutcome) {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.ttl = Some(5);
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeRst, OffsetExpr::host(1)));
    let (mut client, mut server) = connected_pair();
    let default_ttl = client.ttl().expect("read default ttl");
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        resolved_fake_ttl,
        AdaptivePlannerHints::default(),
    );
    let outcome = send_prepared_with_group(
        &mut client,
        platform,
        config,
        &group,
        payload,
        progress,
        context,
        resolved_fake_ttl,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("fakerst step writes the payload");
    assert_eq!(client.ttl().expect("read restored ttl"), default_ttl);
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
    (buf, outcome)
}

#[test]
fn plain_fakerst_runs_in_the_plan_executor_with_the_protect_path() {
    let platform = ReceiptTestPlatform { fake_rst_succeeds: true, ..Default::default() };
    let mut config = RuntimeConfig::default();
    config.process.protect_path = Some("/protect.sock".to_owned());

    let (_, outcome) = send_plain_fakerst(&platform, &config, None);

    assert_eq!(*platform.fake_rst_ttls.lock().expect("fake rst ttl log"), vec![5]);
    assert_eq!(
        *platform.fake_rst_protect_paths.lock().expect("fake rst protect log"),
        vec![Some("/protect.sock".to_owned())]
    );
    assert_eq!(outcome.execution_receipt.adaptive_fake_ttl, None);
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::FakeRst));
    assert_eq!(outcome.execution_receipt.fallback_reason, None);
}

#[test]
fn unsent_fakerst_reports_the_plain_split_it_ran() {
    let platform = ReceiptTestPlatform::default();

    let (_, outcome) = send_plain_fakerst(&platform, &RuntimeConfig::default(), None);

    assert_eq!(platform.fake_rst_ttls.lock().expect("fake rst ttl log").len(), 1);
    assert_eq!(outcome.execution_receipt.adaptive_fake_ttl, None);
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
}

#[test]
fn adaptive_fakerst_receipt_requires_a_successful_reset() {
    for (succeeds, expected) in [(true, Some(5)), (false, None)] {
        let platform = ReceiptTestPlatform { fake_rst_succeeds: succeeds, ..Default::default() };
        let (_, outcome) = send_plain_fakerst(&platform, &RuntimeConfig::default(), Some(5));
        assert_eq!(outcome.execution_receipt.adaptive_fake_ttl, expected);
    }
}

#[test]
fn fakerst_without_ttl_lowering_reports_the_plain_split_it_ran() {
    let platform = ReceiptTestPlatform { fake_rst_succeeds: true, ..Default::default() };
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeRst, OffsetExpr::host(1)));
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(5),
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        Some(5),
        None,
        &AtomicBool::new(true),
        None,
    )
    .expect("TTL-less fakerst still sends the payload");

    assert!(platform.fake_rst_ttls.lock().expect("fake rst ttl log").is_empty());
    assert_eq!(outcome.execution_receipt.adaptive_fake_ttl, None);
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn fakerst_uncertain_failure_stops_both_executors_before_original_bytes() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for plan_executor in [true, false] {
        for phase in [Some(true), None] {
            let platform = ReceiptTestPlatform::default();
            platform.faults.lock().expect("fault queue").push_back(FaultSpec::FakeRstFailure(phase));
            let (mut client, mut server) = connected_pair();
            let default_ttl = client.ttl().expect("default TTL");
            let error = if plan_executor {
                let mut group = test_group();
                group.actions.ttl = Some(5);
                group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeRst, OffsetExpr::host(1)));
                let progress = OutboundProgress {
                    round: 1,
                    payload_size: payload.len(),
                    stream_start: 0,
                    stream_end: payload.len() - 1,
                };
                let context = activation_context_from_progress(
                    progress,
                    ActivationTransport::Tcp,
                    Some(payload),
                    None,
                    None,
                    None,
                    AdaptivePlannerHints::default(),
                );
                send_prepared_with_group(
                    &mut client,
                    &platform,
                    &RuntimeConfig::default(),
                    &group,
                    payload,
                    progress,
                    context,
                    None,
                    None,
                    &default_ttl_unavailable(),
                    None,
                )
                .expect_err("uncertain RST state must stop the plan")
            } else {
                crate::platform::with_tcp_desync_platform(&platform, || {
                    execute_tcp_actions(
                        &mut client,
                        &[DesyncAction::SendFakeRst, DesyncAction::Write(payload.to_vec())],
                        64,
                        false,
                        Duration::from_millis(10),
                        Some("fakerst"),
                        &default_ttl_unavailable(),
                        false,
                        None,
                        None,
                    )
                })
                .expect_err("uncertain RST state must stop the action list")
            };
            assert!(error.payload_commit_uncertain());
            assert_eq!(error.bytes_committed(), 0);
            assert_eq!(client.ttl().expect("restored TTL"), default_ttl);
            client.shutdown(std::net::Shutdown::Write).expect("finish write");
            let mut received = Vec::new();
            server.read_to_end(&mut received).expect("read peer bytes");
            assert!(received.is_empty());
        }
    }
}
