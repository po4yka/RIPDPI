use super::*;

#[test]
fn oob_later_steps_and_payloads_preserve_peer_bytes_and_report_split_fallback() {
    for later_round in [1, 2] {
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
        let mut group = test_group();
        group.actions.oob_data = Some(b'!');
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Oob, OffsetExpr::absolute(2)),
            TcpChainStep::new(TcpChainStepKind::Oob, OffsetExpr::absolute(4)),
        ];
        let unavailable = default_ttl_unavailable();
        let mut outcomes = Vec::new();
        for (payload, round, stream_start) in [(b"abcdef".as_slice(), 1, 0), (b"ghijkl".as_slice(), later_round, 6)] {
            let progress = OutboundProgress {
                round,
                payload_size: payload.len(),
                stream_start,
                stream_end: stream_start + payload.len() - 1,
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
            outcomes.push(
                send_prepared_with_group(
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
                .expect("send urgent payload"),
            );
        }
        client.shutdown(std::net::Shutdown::Write).expect("finish outbound");
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("read original stream");

        assert_eq!(received, b"abcdefghijkl");
        assert_eq!(outcomes[0].execution_receipt.effective_family, Some(TcpStrategyFamily::OutOfBand));
        assert_ne!(outcomes[1].execution_receipt.effective_family, Some(TcpStrategyFamily::OutOfBand));
    }
}
