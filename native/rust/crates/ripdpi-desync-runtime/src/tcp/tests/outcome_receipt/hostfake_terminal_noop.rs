use super::*;

#[test]
fn terminal_empty_fake_disorder_keeps_the_committed_hostfake_family() {
    for raw_refused in [false, true] {
        let platform = ReceiptTestPlatform::default();
        if raw_refused {
            platform
                .faults
                .lock()
                .expect("fault queue")
                .push_back(FaultSpec::OrderedSegments(io::ErrorKind::PermissionDenied));
        }
        let chain = vec![
            TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)),
            TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)),
        ];
        let (received, outcome) = send_http_hostfake_chain(&platform, Some(chain));

        assert_eq!(received, b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n");
        assert_eq!(outcome.bytes_committed, received.len());
        assert_eq!(outcome.execution_receipt.planned_steps, 2);
        let expected = if raw_refused { TcpStrategyFamily::Split } else { TcpStrategyFamily::HostFake };
        assert_eq!(outcome.execution_receipt.effective_family, Some(expected), "raw_refused={raw_refused}");
    }
}
