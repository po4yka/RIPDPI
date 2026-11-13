use super::*;

#[test]
fn seq_overlap_with_flags_preserves_terminal_sender_failure() {
    let payload = b"ABCD";
    for fake_flags in [
        ripdpi_config::TcpFlagOverrides { set: Some(ripdpi_config::TCP_FLAG_ECE), unset: None },
        ripdpi_config::TcpFlagOverrides { set: None, unset: Some(ripdpi_config::TCP_FLAG_PSH) },
    ] {
        let mut group = test_group();
        group.actions.tcp_chain.push(
            TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(2)).with_seq_overlap_payload(
                ripdpi_config::TcpSeqOverlapPayload {
                    overlap_size: 4,
                    fake_mode: ripdpi_config::SeqOverlapFakeMode::Profile,
                    fake_flags,
                },
            ),
        );
        let platform = ReceiptTestPlatform::default();
        platform.faults.lock().expect("fault queue").push_back(FaultSpec::SeqOverlap(io::ErrorKind::ConnectionReset));
        let (mut client, mut peer) = connected_pair();
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
        let progress = OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: 3 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            None,
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
            None,
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect_err("a terminal overlap error must not become a plain write");
        assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
        assert_eq!(error.bytes_committed(), 0);
        assert_eq!(error.execution_receipt().expect("receipt").disposition, TcpExecutionDisposition::ExecutionFailed);
        client.shutdown(std::net::Shutdown::Write).expect("close write side");
        let mut received = Vec::new();
        peer.read_to_end(&mut received).expect("read to EOF");
        assert!(received.is_empty(), "no genuine bytes can leave after a terminal refusal");
    }
}
