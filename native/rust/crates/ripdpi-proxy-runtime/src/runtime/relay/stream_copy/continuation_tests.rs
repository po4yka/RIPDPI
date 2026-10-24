use super::*;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::sync::mpsc;
use std::time::Duration;

use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::failure_retry::prepare_relay;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::model::config::{RotationCandidate, RotationPolicy};
use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

struct SuccessBarrier {
    entered: mpsc::SyncSender<()>,
    release: std::sync::Mutex<mpsc::Receiver<()>>,
}

impl RuntimeTelemetrySink for SuccessBarrier {
    fn on_listener_started(&self, _: SocketAddr, _: usize, _: usize) {}
    fn on_listener_stopped(&self) {}
    fn on_client_accepted(&self) {}
    fn on_client_finished(&self) {}
    fn on_client_error(&self, _: &io::Error) {}
    fn on_route_selected(&self, _: SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
    fn on_failure_classified(
        &self,
        _: SocketAddr,
        _: &ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure,
        _: Option<&str>,
    ) {
    }
    fn on_route_advanced(&self, _: SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {}
    fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
    fn on_host_autolearn_event(&self, action: &'static str, _: Option<&str>, _: Option<usize>) {
        if action == "host_promoted" {
            self.entered.send(()).expect("success recording entered");
            self.release
                .lock()
                .expect("release lock")
                .recv_timeout(Duration::from_secs(2))
                .expect("release success recording");
        }
    }
}

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
    let (mut client, mut app) = pair();
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
    let (entered_tx, entered_rx) = mpsc::sync_channel(1);
    let (release_tx, release_rx) = mpsc::sync_channel(1);
    let telemetry =
        std::sync::Arc::new(SuccessBarrier { entered: entered_tx, release: std::sync::Mutex::new(release_rx) });
    let state = RuntimeState::test_with_telemetry(config, Some(telemetry));
    let mut prepared = prepare_relay(
        &mut client,
        upstream,
        &state,
        target,
        RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 },
        None,
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
    entered_rx.recv_timeout(Duration::from_secs(2)).expect("real success event pauses observer after delivery");
    let eligible_when_response_visible = session.rotation_eligible();
    release_tx.send(()).expect("resume success recording");
    observer.join().expect("join response observer").expect("accept first response");
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
    server.join().expect("join owned origin");
    assert!(eligible_when_response_visible, "a client can start its next round as soon as the response is visible");
}
