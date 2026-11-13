use super::*;

#[test]
fn ipfrag2_opaque_partial_failure_never_falls_back_or_reports_safe_zero() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for kind in [
        io::ErrorKind::ConnectionReset,
        io::ErrorKind::WouldBlock,
        io::ErrorKind::InvalidInput,
        io::ErrorKind::Unsupported,
    ] {
        let mut group = test_group();
        group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(12))];
        let platform = ReceiptTestPlatform::default();
        platform.faults.lock().expect("fault queue").push_back(FaultSpec::FragmentAfterPrefix(kind));
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("read deadline");
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
        .expect_err("partial failure is terminal");
        client.shutdown(std::net::Shutdown::Write).expect("finish write");
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("received prefix");
        assert_eq!(received, &payload[..5]);
        assert!(error.payload_commit_uncertain(), "partial send must prohibit replay: {error:?}");
    }
}

impl ReceiptTestPlatform {
    pub(super) fn fragment_fault(&self, stream: &TcpStream, payload: &[u8]) -> Option<io::Result<()>> {
        let kind = {
            let mut faults = self.faults.lock().expect("fragment fault queue");
            if !matches!(faults.front(), Some(FaultSpec::FragmentAfterPrefix(_))) {
                return None;
            }
            match faults.pop_front() {
                Some(FaultSpec::FragmentAfterPrefix(kind)) => kind,
                _ => unreachable!(),
            }
        };
        Some(
            (&*stream)
                .write_all(&payload[..5])
                .and_then(|()| Err(io::Error::new(kind, "fragment send failed after prefix"))),
        )
    }
}

#[test]
fn ipfrag2_action_partial_failure_is_uncertain_with_or_without_family() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for family in [None, Some("ipfrag2")] {
        let platform = ReceiptTestPlatform::default();
        platform
            .faults
            .lock()
            .expect("fault queue")
            .push_back(FaultSpec::FragmentAfterPrefix(io::ErrorKind::WouldBlock));
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("read deadline");
        let actions = [DesyncAction::WriteIpFragmentedTcp {
            bytes: payload.to_vec(),
            split_offset: 12,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        }];
        let error = crate::platform::with_tcp_desync_platform(&platform, || {
            execute_tcp_actions(
                &mut client,
                &actions,
                64,
                false,
                Duration::ZERO,
                family,
                &default_ttl_unavailable(),
                false,
                None,
                None,
            )
        })
        .expect_err("partial action send is terminal");
        client.shutdown(std::net::Shutdown::Write).expect("finish write");
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("received prefix");
        assert_eq!(received, &payload[..5]);
        assert!(error.payload_commit_uncertain());
    }
}

impl ReceiptTestPlatform {
    pub(super) fn flagged_fault(&self, stream: &TcpStream, payload: &[u8]) -> Option<io::Result<()>> {
        let kind = {
            let mut faults = self.faults.lock().expect("flagged fault queue");
            if !matches!(faults.front(), Some(FaultSpec::FlaggedAfterPrefix(_))) {
                return None;
            }
            match faults.pop_front() {
                Some(FaultSpec::FlaggedAfterPrefix(kind)) => kind,
                _ => unreachable!(),
            }
        };
        Some(
            (&*stream)
                .write_all(&payload[..5])
                .and_then(|()| Err(io::Error::new(kind, "flagged send failed after prefix"))),
        )
    }
}

#[test]
fn ipfrag2_masked_original_failure_is_uncertain_in_all_rounds() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for round in [1, 2] {
        let mut group = test_group();
        group.set_round_activation(Some(NumericRange::new(1, 2)));
        group.actions.tcp_chain = vec![with_original_flag_overrides(
            &TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(12)),
            ripdpi_config::TcpFlagOverrides { set: Some(ripdpi_config::TCP_FLAG_PSH), unset: None },
        )];
        let platform = ReceiptTestPlatform::default();
        platform
            .faults
            .lock()
            .expect("fault queue")
            .push_back(FaultSpec::FlaggedAfterPrefix(io::ErrorKind::ConnectionReset));
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("deadline");
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
        .expect_err("partial masked write is terminal");
        client.shutdown(std::net::Shutdown::Write).expect("finish write");
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("received prefix");
        assert_eq!(received, &payload[..5]);
        assert!(error.payload_commit_uncertain());
    }
}
