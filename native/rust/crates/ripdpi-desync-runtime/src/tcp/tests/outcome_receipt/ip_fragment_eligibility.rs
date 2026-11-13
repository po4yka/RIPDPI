use super::*;

#[test]
fn ipfrag2_ineligible_rounds_and_offsets_use_one_full_plain_write() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for (round, offset) in [(2, 12), (1, 0), (1, payload.len() as i64), (1, payload.len() as i64 + 1)] {
        let mut group = test_group();
        group.set_round_activation(Some(NumericRange::new(1, 2)));
        group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(offset))];
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("read deadline");
        let progress =
            OutboundProgress { round, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
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
            &ReceiptTestPlatform { ipfrag_error_kind: Some(io::ErrorKind::ConnectionReset), ..Default::default() },
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
        .expect("plain send");
        client.shutdown(std::net::Shutdown::Write).expect("finish write");
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("read full payload");
        assert_eq!(received, payload);
        assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
        assert_eq!(outcome.execution_receipt.completed_awaits, 0);
    }
}
