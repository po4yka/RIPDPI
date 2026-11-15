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
        let session = FirstOutboundSession::new().into_relay_session().into_shared();
        session.begin_evolution_send().unwrap();
        if client_canceled {
            app.shutdown(std::net::Shutdown::Both).unwrap();
            origin.shutdown(std::net::Shutdown::Write).unwrap();
        } else {
            origin.write_all(&response[3..]).expect("closure remainder");
        }
        pending
            .finish(&state, &mut upstream, &mut client, &session, Arc::new(AtomicBool::new(client_canceled)))
            .expect("finish neutral closure");
        session.finish_evolution_send(&state, Some(neutral_attempt)).unwrap();
        session.finish_first_response(FirstResponseOutcome::Accepted { success_recorded: false }).unwrap();
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
