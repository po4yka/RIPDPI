use super::*;

#[test]
fn late_ordered_failure_does_not_replay_the_host() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let platform = ReceiptTestPlatform::default();
    platform.faults.lock().expect("fault queue").push_back(FaultSpec::OrderedSegmentsAfterPartialGenuine);
    let mut group = test_group();
    group
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)));
    let (mut client, mut server) = connected_pair();
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
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
    .expect_err("late send failure must be terminal");
    assert_eq!(error.kind(), io::ErrorKind::Other);
    assert!(error.payload_commit_uncertain());
    drop(client);
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read committed prefix");
    assert_eq!(received, b"GET /watch HTTP/1.1\r\nHost: you");
}
