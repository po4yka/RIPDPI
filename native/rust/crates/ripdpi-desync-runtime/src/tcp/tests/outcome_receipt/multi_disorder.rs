use super::*;

#[test]
fn multidisorder_failure_after_delivery_keeps_progress_and_marks_commit_uncertain() {
    for report_progress in [false, true] {
        let platform = ReceiptTestPlatform::default();
        platform
            .faults
            .lock()
            .expect("fault queue")
            .push_back(FaultSpec::MultiDisorderAfterPrefix { written: 3, report_progress });
        let payload = b"abcdef";
        let mut group = test_group();
        group.actions.tcp_chain = multidisorder_chain();
        let (mut client, mut peer) = connected_pair();
        peer.set_read_timeout(Some(Duration::from_secs(1))).expect("read deadline");
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() };
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
        .expect_err("send fails after genuine bytes reached the peer");
        drop(client);
        let mut received = Vec::new();
        peer.read_to_end(&mut received).expect("read delivered bytes");

        assert_eq!(received, b"abc");
        assert_eq!(error.bytes_committed(), if report_progress { 3 } else { 0 });
        assert!(error.payload_commit_uncertain());
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert_eq!(
            error.execution_receipt().expect("failed receipt").disposition,
            TcpExecutionDisposition::ExecutionFailed
        );
    }
}
