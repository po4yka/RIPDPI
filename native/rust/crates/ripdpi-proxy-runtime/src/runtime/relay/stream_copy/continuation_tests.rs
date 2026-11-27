use super::*;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::failure_retry::prepare_relay;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::config::{RotationCandidate, RotationPolicy};

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind owned pair");
    let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let second = listener.accept().expect("accept").0;
    for stream in [&first, &second] {
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("bound read");
    }
    (first, second)
}

#[test]
fn accepted_continuation_enables_rotation_for_the_next_real_request() {
    let (client, mut app) = pair();
    let mut client = ClientStream::Tcp(client);
    let (upstream, mut origin) = pair();
    let target = upstream.peer_addr().expect("target");
    let request = b"POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 4\r\n\r\n";
    let next = b"GET /next HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
    app.write_all(request).expect("send initial headers");
    let mut continued_app = app.try_clone().expect("continue only after headers reach origin");
    let server = thread::spawn(move || {
        let mut header = vec![0; request.len()];
        origin.read_exact(&mut header).expect("first request headers");
        assert_eq!(header, request);
        continued_app.write_all(b"BODY").expect("send continued body");
        let mut body = [0; 4];
        origin.read_exact(&mut body).expect("continued body forwarded");
        assert_eq!(&body, b"BODY");
        origin.write_all(response).expect("first accepted response");
        let mut later = vec![0; next.len()];
        origin.read_exact(&mut later).expect("later request forwarded");
        assert_eq!(later, next);
        origin.write_all(response).expect("later accepted response");
    });
    let mut config = RuntimeConfig::default();
    config.host_autolearn.enabled = true;
    config.groups[0].actions.rotation_policy =
        Some(RotationPolicy { candidates: vec![RotationCandidate::default()], ..RotationPolicy::default() });
    let state = RuntimeState::test(config);
    let mut prepared = prepare_relay(
        &mut client,
        upstream,
        &state,
        target,
        RuntimeConnectionRoute { group_index: 0, attempted_mask: 1, route_advances: 0 },
        None,
        crate::runtime::relay::RelayFlow::default(),
        None,
    )
    .expect("prepare must hand off unread continuation");
    let rotation = RelaySession::rotation_controller(state.relay_rotation_seed(0).expect("seed"))
        .expect("controller exists before the pending response is accepted");
    let pending = prepared.session_state.take_pending_response().expect("pending response");
    let session = prepared.session_state.into_shared();
    let host = Arc::new(Mutex::new(Some("fixture.test".to_owned())));
    let mut body = [0; 4];
    client.read_exact(&mut body).expect("continuation retained for outbound owner");
    copy_halves::flush_outbound_payload(&mut prepared.upstream, &state, 0, &session, &host, Some(&rotation), &body)
        .expect("forward body while observation is pending");
    assert!(rotation.lock().expect("rotation").observed_round().is_none());
    let pending_state = state.clone();
    let pending_session = session.clone();
    let mut pending_upstream = prepared.upstream.try_clone().expect("response reader");
    let mut pending_client = client.try_clone().expect("response writer");
    let observer = thread::spawn(move || {
        pending.finish(&pending_state, &mut pending_upstream, &mut pending_client, &pending_session)
    });
    let mut received = vec![0; response.len()];
    app.read_exact(&mut received).expect("first response delivered");
    assert_eq!(received, response);
    assert!(session.rotation_eligible(), "rotation is eligible as soon as the response is visible");
    app.write_all(next).expect("send later request");
    let mut later = vec![0; next.len()];
    client.read_exact(&mut later).expect("read later request");
    copy_halves::flush_outbound_payload(&mut prepared.upstream, &state, 0, &session, &host, Some(&rotation), &later)
        .expect("forward later request with rotation");
    assert!(rotation.lock().expect("rotation").observed_round().is_some(), "later round lost rotation observation");
    prepared.upstream.read_exact(&mut received).expect("later response");
    observers::observe_rotation_inbound_chunk(
        &state,
        Some(target),
        &host,
        Some(&rotation),
        &prepared.upstream,
        &received,
    );
    assert!(
        rotation.lock().expect("rotation").observed_round().is_none(),
        "accepted later response must finish its observation"
    );
    assert_eq!(received, response);
    observer.join().expect("join response observer").expect("accept first response");
    server.join().expect("join owned origin");
}

#[test]
fn outbound_body_cannot_replace_the_session_hostname() {
    let (mut upstream, mut origin) = pair();
    let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), None);
    let session = crate::runtime::relay::session::FirstOutboundSession::new().into_relay_session().into_shared();
    let host = Arc::new(Mutex::new(None));
    let body = b"GET / HTTP/1.1\r\nHost: body.example\r\n\r\n";
    let first = format!("POST / HTTP/1.1\r\nHost: outer.example\r\nContent-Length: {}\r\n\r\n", body.len());
    for payload in [first.as_bytes(), body.as_slice()] {
        copy_halves::flush_outbound_payload(&mut upstream, &state, 0, &session, &host, None, payload).unwrap();
        let mut received = vec![0; payload.len()];
        origin.read_exact(&mut received).unwrap();
        assert_eq!(received, payload);
    }
    assert_eq!(host.lock().unwrap().as_deref(), Some("outer.example"));
}
