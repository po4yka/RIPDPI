//! Disorder and disoob TTL, round-scope, and receipt tests (DPI-03).

use super::*;

#[test]
fn unfiltered_disorder_skips_later_rounds() {
    let payload = b"GET /2 HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::absolute(3)));
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 2,
        payload_size: payload.len(),
        stream_start: 64,
        stream_end: 64 + payload.len() - 1,
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

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
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
    .expect("later round is sent plain");

    assert_eq!(outcome.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn plan_executor_keeps_disorder_ttl_until_the_send_queue_drains() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    for kind in [TcpChainStepKind::Disorder, TcpChainStepKind::Disoob] {
        let mut group = test_group();
        // The fake step routes the plan through the plan executor.
        group.actions.tcp_chain = vec![
            TcpChainStep::new(kind, OffsetExpr::absolute(3)),
            TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(10)),
        ];
        let (mut client, mut server) = connected_pair();
        let default_ttl = client.ttl().expect("default TTL");
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
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
        .expect("plan executes");

        let stage_ttls = platform.stage_ttls.lock().expect("stage ttl log").clone();
        assert_eq!(stage_ttls.first(), Some(&u32::from(ripdpi_desync::DISORDER_TTL)), "{kind:?}");
        assert_eq!(client.ttl().expect("restored TTL"), default_ttl, "{kind:?}");
        let mut buf = vec![0u8; payload.len()];
        server.read_exact(&mut buf).expect("read relayed payload");
        assert_eq!(&buf, payload);
    }
}

#[test]
fn plan_executor_restores_ttl_when_a_disorder_step_fails() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::absolute(3)),
        TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(10)),
    ];
    let (mut client, _server) = connected_pair();
    let default_ttl = client.ttl().expect("default TTL");
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
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
    platform.faults.lock().expect("receipt fault queue").push_back(FaultSpec::WaitStage(io::ErrorKind::TimedOut));

    let result = send_prepared_with_group(
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
    );

    assert!(result.is_err(), "the injected wait failure must fail the send");
    assert_eq!(
        platform.stage_ttls.lock().expect("stage ttl log").first(),
        Some(&u32::from(ripdpi_desync::DISORDER_TTL))
    );
    assert_eq!(client.ttl().expect("TTL after failure"), default_ttl);
}

#[test]
fn ttl_less_disorder_reports_the_degraded_family() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let disorder = TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::absolute(3));
    let disoob = TcpChainStep::new(TcpChainStepKind::Disoob, OffsetExpr::absolute(3));
    let fake = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(10));
    let cases = [
        (vec![disorder.clone()], TcpStrategyFamily::Split),
        (vec![disoob], TcpStrategyFamily::OutOfBand),
        // The fake step routes the plan through the plan executor.
        (vec![disorder.clone(), fake], TcpStrategyFamily::Split),
        // A disorder step after another primary step is degraded as well.
        (vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(1)), disorder], TcpStrategyFamily::Split),
    ];
    for (chain, expected) in cases {
        let mut group = test_group();
        group.actions.tcp_chain = chain;
        let (mut client, mut server) = connected_pair();
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        // The session already knows that this platform cannot change the TTL.
        let unavailable = AtomicBool::new(true);

        let outcome = send_prepared_with_group(
            &mut client,
            &ReceiptTestPlatform::default(),
            &RuntimeConfig::default(),
            &group,
            payload,
            progress,
            context,
            None,
            None,
            &unavailable,
            None,
        )
        .expect("TTL-less disorder still sends the payload");

        assert_eq!(outcome.execution_receipt.effective_family, Some(expected), "{expected:?}");
        assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
        let mut buf = vec![0u8; payload.len()];
        server.read_exact(&mut buf).expect("read relayed payload");
        assert_eq!(&buf, payload);
    }
}
