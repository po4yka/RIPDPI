use super::*;

#[test]
fn inactive_fake_group_preserves_payload_before_entropy_padding() {
    for (round_filter, active) in
        [(None, false), (Some(NumericRange::new(1, 1)), false), (Some(NumericRange::new(2, 2)), true)]
    {
        let payload = [0xaa; 100];
        let mut group = test_group();
        group.actions.entropy_mode = if active { EntropyMode::Disabled } else { EntropyMode::Popcount };
        group
            .actions
            .tcp_chain
            .push(TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)));
        group.set_round_activation(round_filter);
        let progress = OutboundProgress { round: 2, payload_size: payload.len(), stream_start: 100, stream_end: 199 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(1))).expect("set read deadline");
        let outcome = send_prepared_with_group(
            &mut client,
            &ReceiptTestPlatform::default(),
            &RuntimeConfig::default(),
            &group,
            &payload,
            progress,
            context,
            None,
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect("inactive fake group sends the original payload");
        drop(client);
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("read the complete original payload");

        assert_eq!(received, payload);
        assert_eq!(outcome.bytes_committed, payload.len());
        assert_eq!(outcome.execution_receipt.payload_bytes_committed, payload.len());
        assert_eq!(
            outcome.execution_receipt.disposition,
            if active { TcpExecutionDisposition::Applied } else { TcpExecutionDisposition::ActivationSkipped }
        );
        assert_eq!(outcome.strategy_family, active.then_some("fake"));
    }
}

#[test]
fn opaque_fake_and_ordered_failures_mark_payload_commit_as_uncertain() {
    let (client, _peer) = connected_pair();
    let platform = ReceiptTestPlatform::default();
    let fake_error = crate::platform::with_tcp_desync_platform(&platform, || {
        crate::transport_io::send_fake_tcp_action_named(
            &client,
            b"real",
            b"fake",
            1,
            false,
            64,
            FakeTcpOptions::default(),
            None,
            (false, Duration::ZERO),
            "send_fake",
            "fake",
            None,
            0,
        )
        .expect_err("opaque fake operation fails")
    });
    assert_eq!(fake_error.bytes_committed(), 0);
    assert!(fake_error.payload_commit_uncertain());

    platform.faults.lock().expect("fault queue").push_back(FaultSpec::OrderedSegments(io::ErrorKind::PermissionDenied));
    let ordered_error = crate::platform::with_tcp_desync_platform(&platform, || {
        crate::transport_io::send_ordered_fake_segments_action_named(
            &client,
            &[],
            4,
            64,
            None,
            false,
            None,
            None,
            (false, Duration::ZERO),
            "send_fake",
            "fake",
            None,
            0,
        )
        .expect_err("opaque ordered operation fails")
    });
    assert_eq!(ordered_error.bytes_committed(), 0);
    assert!(ordered_error.payload_commit_uncertain());
}

#[test]
fn fake_disorder_does_not_repeat_an_uncertain_first_prefix() {
    let platform = ReceiptTestPlatform::default();
    platform.faults.lock().expect("fault queue").push_back(FaultSpec::FakeSendAfterPrefix(3));
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::host(1)));
    let (mut client, mut peer) = connected_pair();
    client.set_ttl(37).expect("set normal TTL");
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(8),
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
        Some(8),
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect_err("partial opaque send");
    assert!(error.payload_commit_uncertain());
    assert_eq!(error.bytes_committed(), 0);
    assert_eq!(client.ttl().expect("TTL after opaque failure"), 37);
    drop(client);
    let mut received = Vec::new();
    peer.read_to_end(&mut received).expect("read committed prefix");
    assert_eq!(received, b"GET");
}
