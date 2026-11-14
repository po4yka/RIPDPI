use super::*;
use std::io::Read;
use std::net::TcpListener;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use crate::runtime::desync::{
    DesyncSendRequest, FakeTtlAttempt, OutboundSendError, OutboundSendOutcome, TcpExecutionDisposition,
};
use crate::runtime::relay::session::FirstOutboundSession;
use ripdpi_proxy_runtime_adapter::model::config::{
    ActivationFilter, AutoTtlConfig, NumericRange, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind,
};
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

const HOST: Option<&str> = Some("adaptive.example");

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
    let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let second = listener.accept().expect("accept").0;
    for stream in [&first, &second] {
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded read");
    }
    (first, second)
}

fn send(
    state: &RuntimeState,
    upstream: &mut TcpStream,
    round: u32,
    payload: &[u8],
    attempt: &mut Option<FakeTtlAttempt>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    let target = upstream.peer_addr()?;
    state.send_tcp_desync_payload(
        upstream,
        DesyncSendRequest {
            fake_ttl_attempt: Some(attempt),
            lua_flow: None,
            group_index: 0,
            group_override: None,
            payload,
            progress: OutboundProgress {
                round,
                payload_size: payload.len(),
                stream_start: 0,
                stream_end: payload.len() - 1,
            },
            host: HOST,
            target,
        },
    )
}

fn selected_attempt(state: &RuntimeState, upstream: &mut TcpStream) -> FakeTtlAttempt {
    let mut attempt = None;
    send(state, upstream, 2, b"BODY", &mut attempt).expect_err("host fake sender unsupported");
    attempt.expect("actual sender attempt")
}

fn pending_response_feedback(delayed_publication: bool) {
    let mut config = RuntimeConfig::default();
    let group = &mut config.groups[0];
    group.actions.auto_ttl = Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 8 });
    group.actions.ttl = Some(8);
    group.actions.fake_data = Some(b"fake".to_vec());
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3)));
    group.matches.activation_filter =
        Some(ActivationFilter { round: Some(NumericRange::new(2, 3)), ..Default::default() });
    let state = RuntimeState::test(config);
    let (mut upstream, mut origin) = pair();
    let (mut client, mut app) = pair();
    let (mut parallel, _parallel_origin) = pair();
    let target = upstream.peer_addr().expect("target");
    let request = b"POST / HTTP/1.1\r\nHost: adaptive.example\r\nContent-Length: 4\r\n\r\n";
    let mut attempt = None;
    let inactive = send(&state, &mut upstream, 1, request, &mut attempt).expect("inactive request write");
    assert_eq!(inactive.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    assert_eq!(attempt, None);
    let mut received = vec![0; request.len()];
    origin.read_exact(&mut received).expect("origin gets inactive request");
    assert_eq!(received, request);
    app.write_all(b"BODY").expect("client continuation");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        HOST,
        &mut upstream,
        request,
        Some(&client),
        FirstResponseReader::new(&state, request),
    )
    .expect("handoff pending response") else {
        panic!("expected unread continuation handoff");
    };
    let pending = PendingFirstResponse {
        reader: *reader,
        target,
        route: RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 },
        host: HOST.map(str::to_owned),
        original_request: request.to_vec(),
        success_strategy_family: Some("fake"),
        primary_strategy_family: Some("fake"),
        tls_send_start: None,
    };
    let session = FirstOutboundSession::new().into_relay_session().into_shared();
    let other = selected_attempt(&state, &mut parallel);
    assert_eq!(other.ttl, 8);
    state.note_adaptive_fake_ttl_failure(target, 0, HOST, other).expect("parallel failure from TTL8");

    let (attempt_tx, attempt_rx) = mpsc::sync_channel(1);
    let (publish_tx, publish_rx) = mpsc::sync_channel(1);
    let outbound_state = state.clone();
    let outbound_session = session.clone();
    let mut outbound = upstream.try_clone().expect("outbound owner");
    let publisher = thread::spawn(move || {
        let mut attempt = outbound_session.begin_fake_ttl_send().expect("begin send");
        // This host rejects the real fake sender. The receipt proves its attempted
        // TTL, and does not prove that a fake packet reached the wire.
        let error =
            send(&outbound_state, &mut outbound, 2, b"BODY", &mut attempt).expect_err("host fake sender unsupported");
        assert_eq!(error.execution_receipt().expect("failed receipt").adaptive_fake_ttl, Some(7));
        attempt_tx.send(attempt.expect("actual sender attempt")).expect("report attempted send");
        publish_rx.recv_timeout(Duration::from_secs(5)).expect("release receipt publication");
        if let Some(feedback) = outbound_session.finish_fake_ttl_send(attempt).expect("publish receipt") {
            record_stream_fake_ttl_success(&outbound_state, target, 0, HOST, feedback).expect("deferred fake feedback");
            outbound_session.complete_fake_ttl_feedback().expect("complete fake feedback");
        }
    });
    let active = attempt_rx.recv_timeout(Duration::from_secs(5)).expect("sender invoked before response");
    assert_eq!(active.ttl, 7);
    state.note_adaptive_fake_ttl_failure(target, 0, HOST, active).expect("parallel failure from TTL7");
    let before = selected_attempt(&state, &mut parallel);
    assert_eq!(before.ttl, 6);
    let mut publisher = Some(publisher);
    if !delayed_publication {
        publish_tx.send(()).expect("publish before response");
        publisher.take().expect("publisher").join().expect("publication completed");
    }

    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
    origin.write_all(response).expect("response after active attempt");
    pending.finish(&state, &mut upstream, &mut client, &session, Arc::default()).expect("finish response");
    let mut received = vec![0; response.len()];
    app.read_exact(&mut received).expect("response forwarded once");
    assert_eq!(received, response);
    if delayed_publication {
        assert_eq!(selected_attempt(&state, &mut parallel).ttl, 6, "feedback waits for the attempted TTL");
        publish_tx.send(()).expect("publish after accepted response");
        publisher.take().expect("publisher").join().expect("deferred feedback completed");
    }
    let after = selected_attempt(&state, &mut parallel);
    assert_eq!(after.ttl, 7, "response must pin the shared attempted TTL");
    assert_eq!(after.revision, before.revision + 1, "fake success must run exactly once");
    assert!(!session.snapshot().expect("session snapshot").permits_success());
}

#[test]
fn pending_response_pins_the_attempt_bound_after_continuation_handoff() {
    pending_response_feedback(false);
}

#[test]
fn response_before_attempt_publication_still_pins_the_overlapping_send() {
    pending_response_feedback(true);
}
