use super::*;

#[test]
fn adaptive_ttl_receipt_requires_a_fake_sender_invocation() {
    let payload = b"ABCDEF";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3)));
    group.matches.activation_filter = Some(ripdpi_config::ActivationFilter {
        round: Some(ripdpi_config::NumericRange::new(2, 3)),
        ..Default::default()
    });
    let (mut client, mut server) = connected_pair();
    let platform = ReceiptTestPlatform::default();
    for (round, selected, expected) in [(1, 8, None), (2, 7, Some(7)), (3, 7, Some(7))] {
        let progress =
            OutboundProgress { round, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            Some(selected),
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
            Some(selected),
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect("runtime send");
        assert_eq!(outcome.execution_receipt.adaptive_fake_ttl, expected);
        let mut received = [0; 6];
        server.read_exact(&mut received).expect("genuine peer bytes");
        assert_eq!(&received, payload);
    }
}

#[test]
fn fake_ttl_failure_receipt_keeps_the_attempted_value() {
    let payload = b"ABCDEF";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3)));
    let (mut client, mut server) = connected_pair();
    let platform = ReceiptTestPlatform::default();
    platform.faults.lock().expect("fault queue").push_back(FaultSpec::FakeSendAfterPrefix(1));
    let progress = OutboundProgress { round: 1, payload_size: 6, stream_start: 0, stream_end: 5 };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(7),
        AdaptivePlannerHints::default(),
    );
    let error = send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        Some(7),
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect_err("injected sender failure");
    assert_eq!(error.execution_receipt().expect("failed receipt").adaptive_fake_ttl, Some(7));
    let mut received = [0; 1];
    server.read_exact(&mut received).expect("committed prefix");
    assert_eq!(&received, b"A");
}
