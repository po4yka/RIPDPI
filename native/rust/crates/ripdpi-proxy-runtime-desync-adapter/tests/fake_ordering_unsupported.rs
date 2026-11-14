#![cfg(all(not(feature = "loom"), not(any(target_os = "linux", target_os = "android"))))]

use std::io::{self, Read};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use ripdpi_config::{
    DesyncGroup, FakeOrder, FakeSeqMode, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, TcpFakeOrdering,
    TcpFakePayload,
};
use ripdpi_desync::{ActivationTransport, AdaptivePlannerHints};
use ripdpi_desync_runtime::activation_context_from_progress;
use ripdpi_proxy_runtime_desync_adapter::desync_platform::{OutboundSendError, send_prepared_with_runtime_platform};
use ripdpi_session::OutboundProgress;

#[test]
fn unsupported_custom_fake_ordering_rejects_before_sending_real_bytes() {
    let payload = b"GET / HTTP/1.1\r\nHost: audit.invalid\r\n\r\n";
    let mut config = RuntimeConfig::default();
    config.timeouts.wait_send = false;
    let mut checked = 0;

    for (kind, expected_action) in [
        (TcpChainStepKind::FakeSplit, "send_fake_fakesplit"),
        (TcpChainStepKind::FakeDisorder, "send_fake_fakeddisorder"),
    ] {
        for order in
            [FakeOrder::BeforeEach, FakeOrder::AllFakesFirst, FakeOrder::RealFakeRealFake, FakeOrder::AllRealsFirst]
        {
            for seq_mode in [FakeSeqMode::Duplicate, FakeSeqMode::Sequential] {
                if (order, seq_mode) == (FakeOrder::BeforeEach, FakeSeqMode::Duplicate) {
                    continue;
                }
                let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
                let mut sender = TcpStream::connect(listener.local_addr().unwrap()).expect("loopback sender");
                let (mut peer, _) = listener.accept().expect("loopback peer");
                sender.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
                peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();

                let mut group = DesyncGroup::new(0);
                group.actions.fake_data = Some(b"FAKE".to_vec());
                let step = TcpChainStep::new(kind, OffsetExpr::absolute(8));
                group.actions.tcp_chain.push(step.clone().with_fake_payload(TcpFakePayload {
                    ordering: TcpFakeOrdering { order, seq_mode },
                    ..step.fake_payload().unwrap()
                }));
                let progress = OutboundProgress {
                    round: 1,
                    payload_size: payload.len(),
                    stream_start: 0,
                    stream_end: payload.len() - 1,
                };
                let context = activation_context_from_progress(
                    progress,
                    ActivationTransport::Tcp,
                    Some(payload),
                    None,
                    None,
                    Some(1),
                    AdaptivePlannerHints::default(),
                );
                let error = send_prepared_with_runtime_platform(
                    &mut sender,
                    &config,
                    &group,
                    payload,
                    progress,
                    context,
                    Some(1),
                    None,
                    &AtomicBool::new(false),
                    None,
                )
                .expect_err("ordered sender is unsupported");
                sender.shutdown(Shutdown::Write).unwrap();
                let mut received = Vec::new();
                peer.read_to_end(&mut received).expect("read real peer bytes");
                assert!(received.is_empty(), "{kind:?}/{order:?}/{seq_mode:?}: {received:?}");
                assert_eq!(error.kind(), io::ErrorKind::Unsupported);
                assert_eq!(error.bytes_committed(), 0, "{kind:?}/{order:?}/{seq_mode:?}");
                let OutboundSendError::StrategyExecution { action, .. } = error else {
                    panic!("expected an ordered strategy failure");
                };
                assert_eq!(action, expected_action);
                checked += 1;
            }
        }
    }
    assert_eq!(checked, 14);
}
