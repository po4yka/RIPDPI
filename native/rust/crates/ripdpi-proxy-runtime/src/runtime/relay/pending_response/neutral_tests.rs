use super::*;
use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::session::FirstOutboundSession;
use std::io::Read;
use std::net::TcpListener;
use std::time::Duration;

const HOST: Option<&str> = Some("adaptive.example");

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("owned loopback");
    let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let second = listener.accept().expect("accept").0;
    for stream in [&first, &second] {
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded read");
    }
    (first, second)
}

#[test]
fn dpi38_pending_closure_keeps_rotation_and_late_success_neutral() {
    let mut config = RuntimeConfig::default();
    config.timeouts.partial_timeout_ms = 1_000;
    let state = RuntimeState::test(config);
    let request = crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
    let response = [0x15, 3, 3, 0, 2, 1, 90];
    let (mut upstream, mut origin) = pair();
    let (mut client, mut app) = pair();
    let target = upstream.peer_addr().expect("target");
    origin.write_all(&response[..3]).expect("partial closure header");
    app.write_all(b"x").expect("unread client continuation");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        HOST,
        &mut upstream,
        request,
        Some(&client),
        FirstResponseReader::new(&state, request),
    )
    .expect("pending closure") else {
        panic!("partial closure retains its response owner");
    };
    let pending = PendingFirstResponse {
        reader: *reader,
        target,
        route: RuntimeConnectionRoute { group_index: 0, attempted_mask: 1, route_advances: 0 },
        host: HOST.map(str::to_owned),
        original_request: request.to_vec(),
        success_strategy_family: None,
        primary_strategy_family: None,
        tls_send_start: Some(Instant::now()),
    };
    let session = FirstOutboundSession::new().into_relay_session().into_shared();
    origin.write_all(&response[3..]).expect("closure remainder");
    pending.finish(&state, &mut upstream, &mut client, &session, Arc::default()).expect("finish neutral closure");
    let mut bytes = [0; 7];
    app.read_exact(&mut bytes).expect("forwarded closure");
    assert_eq!(bytes, response);
    assert!(!session.rotation_eligible(), "a canceled handshake cannot activate rotation");
    assert!(!session.snapshot().expect("snapshot").permits_success(), "late completion cannot reward cancellation");
}
