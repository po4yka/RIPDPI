use super::*;
use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::session::FirstOutboundSession;
use ripdpi_proxy_runtime_adapter::desync_platform::EvolutionHintContext;
use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveHintPort;
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
    for client_canceled in [false, true] {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 1_000;
        config.adaptive.strategy_evolution = true;
        config.adaptive.evolution_epsilon_permil = 0;
        let state = RuntimeState::test(config.clone());
        let request = crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
        let response = [0x15, 3, 3, 0, 2, 1, 90];
        let (mut upstream, mut origin) = pair();
        let (client, mut app) = pair();
        let mut client = ClientStream::Tcp(client);
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
            host: HOST.map(str::to_owned),
            original_request: request.to_vec(),
            tls_send_start: Some(Instant::now()),
        };
        let resolve = |attempt| {
            state
                .test_services()
                .resolve_tcp_hints_with_evolver(
                    &config,
                    EvolutionHintContext { runtime_context: None, existing_attempt: attempt },
                    0,
                    target,
                    HOST,
                    &config.groups[0],
                    request,
                )
                .unwrap()
        };
        let selected = resolve(None);
        let expected_hint = selected.hints.split_offset_base;
        assert!(expected_hint.is_some(), "TLS attempt differs from group fallback");
        let neutral_attempt = selected.evolution_attempt.unwrap();
        let other = resolve(None).evolution_attempt.unwrap();
        let mut outbound = FirstOutboundSession::new();
        outbound.defer_first_response(pending);
        let mut seed = outbound.into_relay_session();
        let pending = seed.take_pending_response().expect("actual pending response owner");
        let session = seed.into_shared();
        session.begin_evolution_send().unwrap();
        if client_canceled {
            // Explicitly revoke ownership as the real client-error path does. This unit branch is not an RST oracle.
            session.finish_first_response(FirstResponseOutcome::Neutral).unwrap();
            app.shutdown(std::net::Shutdown::Both).unwrap();
            origin.shutdown(std::net::Shutdown::Write).unwrap();
        } else {
            origin.write_all(&response[3..]).expect("closure remainder");
        }
        pending.finish(&state, &mut upstream, &mut client, &session).expect("finish neutral closure");
        session.finish_evolution_send(&state, Some(neutral_attempt)).unwrap();
        session.finish_first_response(FirstResponseOutcome::Accepted).unwrap();
        session.finish_evolution_response(&state, true).unwrap();
        let expected: &[u8] = if client_canceled { &[] } else { &response };
        let mut bytes = vec![0; expected.len()];
        app.read_exact(&mut bytes).expect("forwarded closure");
        assert_eq!(bytes, expected);
        assert_eq!(resolve(Some(neutral_attempt)).hints.split_offset_base, None);
        assert_eq!(resolve(Some(other)).hints.split_offset_base, expected_hint);
        state.note_evolver_success(Some(neutral_attempt));
        state.note_evolver_success(Some(other));
        assert_eq!(resolve(Some(other)).hints.split_offset_base, None);
        assert!(!session.rotation_eligible(), "a canceled handshake cannot activate rotation");
        assert!(!session.snapshot().expect("snapshot").permits_success(), "late completion cannot reward cancellation");
    }
}

#[derive(Default)]
struct HttpFailureRecorder(std::sync::Mutex<Vec<String>>);

impl ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink for HttpFailureRecorder {
    fn on_listener_started(
        &self,
        _: ripdpi_proxy_runtime_adapter::model::runtime_api::ListenerAddress,
        _: usize,
        _: usize,
    ) {
    }
    fn on_listener_stopped(&self) {}
    fn on_client_accepted(&self) {}
    fn on_client_finished(&self) {}
    fn on_client_error(&self, _: &io::Error) {}
    fn on_route_selected(&self, _: std::net::SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
    fn on_failure_classified(
        &self,
        _: std::net::SocketAddr,
        failure: &ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure,
        _: Option<&str>,
    ) {
        self.0.lock().expect("recorded failures").push(failure.class.as_str().to_owned());
    }
    fn on_route_advanced(&self, _: std::net::SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {}
    fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
    fn on_host_autolearn_event(&self, _: &'static str, _: Option<&str>, _: Option<usize>) {}
}

#[test]
fn dpi38_pending_close_delimited_body_distinguishes_real_client_rst_from_write_fin() {
    use super::super::stream_copy::{RelayStreamSettings, relay_streams};
    use std::net::Shutdown;
    use std::sync::mpsc;
    use std::thread;

    const REQUEST: &[u8] = b"GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
    const RESPONSE: &[u8] = b"HTTP/1.0 200 OK\r\n\r\nThis website has been blocked";
    for reset in [false, true] {
        let mut config = RuntimeConfig::default();
        config.timeouts.timeout_ms = 3_000;
        let recorder = std::sync::Arc::new(HttpFailureRecorder::default());
        let state = RuntimeState::test_with_telemetry(config, Some(recorder.clone()));
        let (mut upstream, mut origin) = pair();
        let (client, mut app) = pair();
        let client = ClientStream::Tcp(client);
        let target = upstream.peer_addr().expect("target");
        origin.write_all(RESPONSE).expect("retained close-delimited body source");
        app.write_all(b"x").expect("owned outbound continuation");
        let FirstResponse::Continuation(reader) = read_first_response(
            &state,
            target,
            HOST,
            &mut upstream,
            REQUEST,
            Some(&client),
            FirstResponseReader::new(&state, REQUEST),
        )
        .expect("pending HTTP response") else {
            panic!("close-delimited body remains incomplete before EOF");
        };
        let pending = PendingFirstResponse {
            reader: *reader,
            target,
            host: HOST.map(str::to_owned),
            original_request: REQUEST.to_vec(),
            tls_send_start: None,
        };
        let mut seed = FirstOutboundSession::new();
        seed.defer_first_response(pending);
        let settings = RelayStreamSettings { group: state.relay_group(0).expect("group"), rotation_seed: None };
        let (continuation_tx, continuation_rx) = mpsc::sync_channel(1);
        let (release_tx, release_rx) = mpsc::sync_channel(1);
        let origin_worker = thread::spawn(move || {
            let mut continuation = [0];
            origin.read_exact(&mut continuation).expect("real outbound continuation arrives");
            assert_eq!(continuation, *b"x");
            continuation_tx.send(()).expect("outbound observation signal");
            let mut next = [0];
            assert_eq!(origin.read(&mut next).expect("upstream write half closes"), 0);
            if !reset {
                origin.shutdown(Shutdown::Write).expect("ordinary origin response EOF");
            }
            // The reset case keeps origin writes open until the relay has ended. Its EOF was local.
            release_rx.recv_timeout(Duration::from_secs(3)).expect("owned origin release");
        });
        let app_worker = thread::spawn(move || {
            continuation_rx.recv_timeout(Duration::from_secs(3)).expect("actual outbound round");
            if reset {
                ripdpi_proxy_runtime_adapter::platform::listener::close_rejected_client(&app);
                drop(app);
                None
            } else {
                app.shutdown(Shutdown::Write).expect("normal HTTP client write FIN");
                let mut forwarded = Vec::new();
                app.read_to_end(&mut forwarded).expect("normal client still receives the response");
                Some(forwarded)
            }
        });
        let result =
            relay_streams(client, upstream, &state, 0, settings, seed.into_relay_session(), HOST.map(str::to_owned));
        release_tx.send(()).expect("release origin after relay completion");
        origin_worker.join().expect("owned origin worker");
        let forwarded = app_worker.join().expect("owned client worker");
        let failures = recorder.0.lock().expect("recorded failures").clone();
        if reset {
            let Err(error) = result else {
                panic!("real client RST must terminate outbound copying");
            };
            assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
            assert!(failures.is_empty(), "locally induced upstream EOF cannot classify retained body: {failures:?}");
            assert!(forwarded.is_none());
        } else {
            let session = result.expect("ordinary write FIN preserves inbound response");
            assert_eq!(failures, ["http_blockpage"]);
            assert_eq!(forwarded.as_deref(), Some(RESPONSE));
            assert!(!session.permits_success(), "a real blockpage is not a positive strategy result");
        }
    }
}

#[test]
fn dpi38_partial_method_timeout_keeps_body_framing_and_learning_neutral() {
    use crate::runtime::relay::execution::{RelayResultContext, record_relay_result};
    use crate::runtime::relay::failure_retry::{RelayFlow, prepare_relay};
    use crate::runtime::relay::stream_copy::{RelayStreamSettings, relay_streams};
    use crate::runtime::routing::select_route;
    use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
    use std::net::Shutdown;
    use std::sync::mpsc;
    use std::thread;

    let mut wrong = Vec::new();
    for (method, response, header_failure) in [
        ("HEAD", b"HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".as_slice(), false),
        ("HEAD", b"HTTP/1.1 200 OK\r\n\r\nHTTP/1.1 200 OK\r\n\r\nThis website has been blocked".as_slice(), false),
        ("CONNECT", b"HTTP/1.1 200 Connection Established\r\n\r\nThis website has been blocked".as_slice(), false),
        ("HEAD", b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".as_slice(), false),
        ("HEAD", b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n".as_slice(), true),
    ] {
        let authority = if method == "CONNECT" { "fixture.test:443" } else { "/" };
        let request = format!("{method} {authority} HTTP/1.1\r\nHost: fixture.test\r\n\r\n").into_bytes();
        let prefix = &request[..1];
        assert!(ripdpi_packets::is_http_request_prefix(prefix));
        assert_eq!(ripdpi_packets::http_request_head_end(&request), Some(request.len()));
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = true;
        config.timeouts.timeout_ms = 3_000;
        config.adaptive.cache_ttl = 60;
        config.groups.push(DesyncGroup::new(1));
        let recorder = std::sync::Arc::new(HttpFailureRecorder::default());
        let state = RuntimeState::test_with_telemetry(config, Some(recorder.clone()));
        assert!(!state.destination_policy_may_need_host(), "no hostname policy forbids the assembly fallback");
        assert!(state.first_response_exchange_required().expect("response policy"));
        let (upstream, mut origin) = pair();
        let (client, mut app) = pair();
        let mut client = ClientStream::Tcp(client);
        let target = upstream.peer_addr().expect("target");
        let (prefix_tx, prefix_rx) = mpsc::sync_channel(1);
        let source_request = request.clone();
        let started = Instant::now();
        app.write_all(prefix).expect("only an incomplete method is available initially");
        let origin_worker = thread::spawn(move || {
            let mut first = [0];
            origin.read_exact(&mut first).expect("real first emitter sends the timeout fallback");
            assert_eq!(first, source_request[..1]);
            assert!(started.elapsed() >= Duration::from_millis(75), "the owning assembler's 75ms window expires");
            prefix_tx.send(()).expect("raw prefix reached upstream before the remaining method");
            let mut rest = vec![0; source_request.len() - 1];
            origin.read_exact(&mut rest).expect("actual outbound continuation");
            let received = [first.as_slice(), rest.as_slice()].concat();
            assert_eq!(received, source_request, "the origin receives a complete HEAD/CONNECT request");
            origin.write_all(response).expect("bodyless response and opaque bytes");
            origin.shutdown(Shutdown::Write).expect("ordinary response EOF");
        });
        let continuation = request[1..].to_vec();
        let app_worker = thread::spawn(move || {
            prefix_rx.recv_timeout(Duration::from_secs(2)).expect("observe actual fallback before continuing");
            app.write_all(&continuation).expect("remaining valid request");
            app.shutdown(Shutdown::Write).expect("normal client FIN");
            let mut bytes = Vec::new();
            app.read_to_end(&mut bytes).expect("exact response bytes and FIN");
            bytes
        });
        let route = RuntimeConnectionRoute { group_index: 1, attempted_mask: 2, route_advances: 0 };
        let prepared = prepare_relay(&mut client, upstream, &state, target, route, None, RelayFlow::default(), None)
            .expect("real first outbound coordinator");
        assert_eq!(prepared.success_payload.as_deref(), Some(prefix), "the pending reader owns the incomplete method");
        assert!(!prepared.session_state.permits_success(), "the first response remains pending");
        let final_route = prepared.route.clone();
        let settings = RelayStreamSettings { group: state.relay_group(1).expect("group"), rotation_seed: None };
        let result = relay_streams(client, prepared.upstream, &state, 1, settings, prepared.session_state, None);
        origin_worker.join().expect("owned origin worker");
        let forwarded = app_worker.join().expect("owned client worker");
        record_relay_result(
            &result,
            &state,
            RelayResultContext {
                relay_timeouts: state.relay_timeouts(1).expect("timeouts"),
                target,
                route: &final_route,
                success_host: prepared.success_host.as_deref(),
                success_payload: prepared.success_payload.as_deref(),
                success_strategy_family: prepared.success_strategy_family,
                primary_strategy_family: None,
            },
        )
        .expect("actual terminal feedback");
        let session = result.expect("normal FIN preserves the response");
        let failures = recorder.0.lock().expect("recorded failures").clone();
        let expected_failures = if header_failure { vec!["http_blockpage".to_owned()] } else { Vec::new() };
        if forwarded != response {
            wrong.push(format!("{method} {:?}: changed wire bytes", &response[..12]));
        }
        if failures != expected_failures {
            wrong.push(format!("{method}: {failures:?}"));
        }
        if session.into_shared().rotation_eligible() {
            wrong.push(format!("{method}: unconfirmed method enabled rotation"));
        }
        if select_route(&state, target, None, None, true).expect("neutral route").group_index != 0 {
            wrong.push(format!("{method}: unconfirmed method trained the destination cache"));
        }
        state
            .note_route_success(target, &final_route, None, RuntimeTransportProtocol::Tcp)
            .expect("cache positive control");
        assert_eq!(select_route(&state, target, None, None, true).expect("explicit success").group_index, 1);
    }
    assert!(wrong.is_empty(), "partial method cannot prove response-body framing or success: {wrong:?}");
}
