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

#[test]
fn seq_overlap_safe_refusal_reports_split_and_exact_payload() {
    let payload = b"ABCD";
    for kind in [
        io::ErrorKind::Unsupported,
        io::ErrorKind::PermissionDenied,
        io::ErrorKind::WouldBlock,
        io::ErrorKind::InvalidInput,
    ] {
        for split in [2, 4] {
            let mut group = test_group();
            group.actions.tcp_chain.push(
                TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(split))
                    .with_seq_overlap_payload(ripdpi_config::TcpSeqOverlapPayload::profile(4)),
            );
            let platform = ReceiptTestPlatform::default();
            platform.faults.lock().expect("fault queue").push_back(FaultSpec::SeqOverlap(kind));
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
            let outcome = send_prepared_with_group(
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
            .expect("a safe refusal sends the original payload");
            assert_eq!(outcome.strategy_family, Some("split"));
            let receipt = &outcome.execution_receipt;
            assert_eq!(receipt.disposition, TcpExecutionDisposition::Applied);
            assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::SeqOverlap));
            assert_eq!(receipt.effective_family, Some(TcpStrategyFamily::Split));
            assert_eq!(receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
            assert_eq!(receipt.real_writes_committed, if split == 4 { 1 } else { 2 });
            assert_eq!(receipt.payload_bytes_committed, payload.len());
            client.shutdown(std::net::Shutdown::Write).expect("close write side");
            let mut received = Vec::new();
            peer.read_to_end(&mut received).expect("read to EOF");
            assert_eq!(received, payload);
        }
    }
}

#[test]
fn seq_overlap_fallback_failure_keeps_family_and_partial_progress() {
    for direct_action in [true, false] {
        let mut payload = b"AB".to_vec();
        payload.extend(std::iter::repeat_n(b'x', 2 * 1024 * 1024));
        let mut group = test_group();
        group.actions.tcp_chain.push(
            TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(2))
                .with_seq_overlap_payload(ripdpi_config::TcpSeqOverlapPayload::profile(4)),
        );
        let (mut client, mut peer) = connected_pair();
        socket2::SockRef::from(&client).set_send_buffer_size(1024).expect("send buffer");
        client.set_nonblocking(true).expect("nonblocking");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let platform = ReceiptTestPlatform::default();
        let error = if direct_action {
            let actions = [DesyncAction::WriteSeqOverlap {
                real_chunk: payload[..2].to_vec(),
                fake_prefix: b"XXXX".to_vec(),
                remainder: payload[2..].to_vec(),
                flags: ripdpi_config::TcpFlagOverrides::disabled(),
            }];
            crate::platform::with_tcp_desync_platform(&platform, || {
                execute_tcp_actions(
                    &mut client,
                    &actions,
                    64,
                    false,
                    Duration::from_millis(10),
                    Some("seqovl"),
                    &default_ttl_unavailable(),
                    false,
                    None,
                    None,
                )
            })
            .expect_err("the action cannot complete a blocked remainder")
        } else {
            send_prepared_with_group(
                &mut client,
                &platform,
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
            .expect_err("a blocked remainder cannot be reported as a complete overlap")
        };
        assert_eq!(error.kind(), io::ErrorKind::WouldBlock);
        assert!(error.bytes_committed() >= 2 && error.bytes_committed() < payload.len());
        let receipt = error.execution_receipt().expect("failure receipt");
        assert_eq!(receipt.effective_family, Some(TcpStrategyFamily::Split));
        assert_eq!(receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
        assert_eq!(receipt.real_writes_committed, 1, "the completed prefix must remain recorded");
        assert_eq!(receipt.payload_bytes_committed, error.bytes_committed());
        client.shutdown(std::net::Shutdown::Write).expect("close write side");
        let mut received = Vec::new();
        peer.read_to_end(&mut received).expect("read to EOF");
        assert_eq!(received, payload[..error.bytes_committed()]);
    }
}

#[test]
fn seq_overlap_fallback_without_applied_tls_prelude_reports_split() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::absolute(2)),
        TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(2))
            .with_seq_overlap_payload(ripdpi_config::TcpSeqOverlapPayload::profile(4)),
    ];
    let (mut client, mut peer) = connected_pair();
    peer.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
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
    .expect("safe fallback");
    assert_eq!(outcome.strategy_family, Some("split"));
    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::TlsRecordSeqOverlap));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert!(!outcome.execution_receipt.tls_record_prelude_applied);
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    client.shutdown(std::net::Shutdown::Write).expect("close write side");
    let mut received = Vec::new();
    peer.read_to_end(&mut received).expect("read to EOF");
    assert_eq!(received, payload);
}
