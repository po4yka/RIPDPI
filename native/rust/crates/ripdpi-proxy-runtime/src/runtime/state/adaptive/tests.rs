use std::io::Read;
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use super::*;
use crate::runtime::desync::{DesyncSendRequest, TcpExecutionDisposition};
use ripdpi_proxy_runtime_adapter::model::config::{
    ActivationFilter, AutoTtlConfig, DesyncGroup, NumericRange, OffsetExpr, RuntimeConfig, TcpChainStep,
    TcpChainStepKind,
};
use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveHintPort;
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

#[test]
fn skipped_first_write_does_not_bind_the_ttl_of_later_sender_attempts() {
    let mut group = DesyncGroup::new(0);
    group.actions.auto_ttl = Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 8 });
    group.actions.ttl = Some(8);
    group.actions.fake_data = Some(b"fake".to_vec());
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3)));
    group.matches.activation_filter =
        Some(ActivationFilter { round: Some(NumericRange::new(2, 3)), ..Default::default() });
    let config = RuntimeConfig { groups: vec![group], ..Default::default() };
    let state = RuntimeState::test(config.clone());
    let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
    let target = listener.local_addr().expect("target");
    let mut writer = TcpStream::connect(target).expect("connect");
    let (mut peer, _) = listener.accept().expect("accept");
    peer.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    let host = Some("adaptive.example");
    let mut bound = None;
    let payload = b"ABCDEF";
    let mut send = |round, slot: &mut Option<FakeTtlAttempt>| {
        state.send_tcp_desync_payload(
            &mut writer,
            DesyncSendRequest {
                fake_ttl_attempt: Some(slot),
                lua_flow: None,
                group_index: 0,
                group_override: None,
                payload,
                progress: OutboundProgress { round, payload_size: payload.len(), stream_start: 0, stream_end: 5 },
                host,
                target,
            },
        )
    };
    let skipped = send(1, &mut bound).expect("inactive plain write");
    assert_eq!(skipped.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    assert_eq!(skipped.execution_receipt.adaptive_fake_ttl, None);
    assert_eq!(bound, None);
    let mut received = [0; 6];
    peer.read_exact(&mut received).expect("plain peer bytes");
    assert_eq!(&received, payload);

    let other = AdaptiveHintPort::resolve_fake_ttl(&state.services, None, 0, target, host, &config.groups[0])
        .expect("other selection")
        .expect("adaptive enabled");
    state.note_adaptive_fake_ttl_failure(target, 0, host, other).expect("other failure");
    // The production fake sender is unsupported on this host. Its error still
    // identifies the TTL of the actual invocation, without claiming packet delivery.
    let attempted = send(2, &mut bound).expect_err("host fake sender unsupported");
    let selected = bound.expect("attempted active TTL");
    assert_eq!(selected.ttl, 7);
    assert_eq!(attempted.execution_receipt().expect("attempt receipt").adaptive_fake_ttl, Some(7));

    let other = AdaptiveHintPort::resolve_fake_ttl(&state.services, None, 0, target, host, &config.groups[0])
        .expect("other selection")
        .expect("adaptive enabled");
    state.note_adaptive_fake_ttl_failure(target, 0, host, other).expect("other failure");
    let later = send(3, &mut bound).expect_err("host fake sender unsupported");
    assert_eq!(bound, Some(selected));
    assert_eq!(later.execution_receipt().expect("attempt receipt").adaptive_fake_ttl, Some(7));
}
