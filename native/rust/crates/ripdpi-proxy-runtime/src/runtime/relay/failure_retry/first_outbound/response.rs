use ripdpi_ws_transport_port::ClientStream;
use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::relay::first_exchange::{FirstResponse, FirstResponseReader, read_first_response};
use crate::runtime::relay::pending_response::PendingFirstResponse;
use crate::runtime::relay::session::FirstOutboundSession;
use crate::runtime::state::RuntimeState;

pub(super) enum FirstResponseDecision {
    Complete,
    Retry { failure: RuntimeClassifiedFailure, response_bytes: Option<Vec<u8>> },
    Continue(Box<PendingFirstResponse>),
}

pub(super) struct FirstResponseContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) host: Option<&'a str>,
    pub(super) original_request: &'a [u8],
    pub(super) tls_send_start: Option<Instant>,
}

pub(super) fn handle_first_response(
    client: &mut ClientStream,
    upstream: &mut TcpStream,
    session_state: &mut FirstOutboundSession,
    context: FirstResponseContext<'_>,
) -> io::Result<FirstResponseDecision> {
    let reader = FirstResponseReader::new(context.state, context.original_request);
    match read_first_response(
        context.state,
        context.target,
        context.host,
        upstream,
        context.original_request,
        Some(client),
        reader,
    )? {
        FirstResponse::Continuation(reader) => Ok(FirstResponseDecision::Continue(Box::new(PendingFirstResponse {
            reader: *reader,
            target: context.target,
            host: context.host.map(str::to_owned),
            original_request: context.original_request.to_vec(),
            tls_send_start: context.tls_send_start,
        }))),
        FirstResponse::Forward(bytes, server_ttl) => {
            if let Some(start) = context.tls_send_start {
                context.state.note_tls_handshake_completed(context.target, start.elapsed().as_millis() as u64);
            }
            let has_inbound_payload = session_state.observe_first_response_payload(&bytes);
            client.write_all(&bytes)?;
            context.state.note_application_traffic(0, bytes.len() as u64);
            if has_inbound_payload {
                session_state.accept_first_response(server_ttl);
                context.state.note_evolver_answered(session_state.evolution_attempt);
            }
            Ok(FirstResponseDecision::Complete)
        }
        FirstResponse::Neutral(bytes) => {
            session_state.neutral_first_response();
            context.state.discard_evolver_attempt(session_state.evolution_attempt);
            session_state.observe_retry_response_payload(&bytes);
            client.write_all(&bytes)?;
            context.state.note_application_traffic(0, bytes.len() as u64);
            Ok(FirstResponseDecision::Complete)
        }
        FirstResponse::NoData => {
            session_state.neutral_first_response();
            context.state.discard_evolver_attempt(session_state.evolution_attempt);
            Ok(FirstResponseDecision::Complete)
        }
        FirstResponse::Failure { failure, response_bytes } => {
            Ok(FirstResponseDecision::Retry { failure, response_bytes })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
    use crate::runtime::relay::execution::{RelayResultContext, record_relay_result};
    use crate::runtime::routing::select_route;
    use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};
    use ripdpi_proxy_runtime_adapter::desync_platform::EvolutionHintContext;
    use ripdpi_proxy_runtime_adapter::model::config::{DETECT_TLS_ALERT, DesyncGroup};
    use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveHintPort;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener};

    #[derive(Default)]
    struct HandshakeCount(std::sync::Mutex<usize>);

    impl RuntimeTelemetrySink for HandshakeCount {
        fn on_listener_started(&self, _: SocketAddr, _: usize, _: usize) {}
        fn on_listener_stopped(&self) {}
        fn on_client_accepted(&self) {}
        fn on_client_finished(&self) {}
        fn on_client_error(&self, _: &io::Error) {}
        fn on_route_selected(&self, _: SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
        fn on_failure_classified(&self, _: SocketAddr, _: &RuntimeClassifiedFailure, _: Option<&str>) {}
        fn on_route_advanced(&self, _: SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {}
        fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
        fn on_host_autolearn_event(&self, _: &'static str, _: Option<&str>, _: Option<usize>) {}
        fn on_tls_handshake_completed(&self, _: SocketAddr, _: u64) {
            *self.0.lock().expect("handshake count") += 1;
        }
    }

    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("owned listener");
        let client = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
        let server = listener.accept().expect("accept").0;
        for stream in [&client, &server] {
            stream.set_read_timeout(Some(std::time::Duration::from_secs(2))).expect("bounded fixture read");
        }
        (client, server)
    }

    #[test]
    fn dpi38_closure_alerts_do_not_reward_or_complete_handshake() {
        for code in [Some(0), Some(90), None] {
            let telemetry = std::sync::Arc::new(HandshakeCount::default());
            let mut config = RuntimeConfig::default();
            config.adaptive.strategy_evolution = true;
            config.adaptive.evolution_epsilon_permil = 0;
            config.groups[0].matches.detect = DETECT_TLS_ALERT;
            let state = RuntimeState::test_with_telemetry(config.clone(), Some(telemetry.clone()));
            let (mut upstream, mut origin) = pair();
            let (client, mut app) = pair();
            let mut client = ClientStream::Tcp(client);
            let target = upstream.peer_addr().expect("target");
            let request = RUNTIME_DEFAULT_FAKE_TLS;
            let response = code.map_or_default(|code| vec![0x15, 3, 3, 0, 2, 1, code]);
            if code.is_some() {
                origin.write_all(&response).expect("closure record");
            }
            // Without a response, the bounded fallback deadline produces NoData,
            // because timeout failure reporting is disabled in this configuration.
            let mut session = FirstOutboundSession::new();
            session.observe_first_outbound_payload(request);
            let resolve = |attempt| {
                state
                    .test_services()
                    .resolve_tcp_hints_with_evolver(
                        &config,
                        EvolutionHintContext { runtime_context: None, existing_attempt: attempt },
                        0,
                        target,
                        Some("example.test"),
                        &config.groups[0],
                        request,
                    )
                    .unwrap()
            };
            let selected = resolve(None);
            let expected_hint = selected.hints.split_offset_base;
            assert!(expected_hint.is_some(), "TLS attempt differs from group fallback");
            let canceled = selected.evolution_attempt.unwrap();
            let other = resolve(None).evolution_attempt.unwrap();
            session.evolution_attempt = Some(canceled);
            let result = handle_first_response(
                &mut client,
                &mut upstream,
                &mut session,
                FirstResponseContext {
                    state: &state,
                    target,
                    host: Some("example.test"),
                    original_request: request,
                    tls_send_start: Some(Instant::now()),
                },
            )
            .expect("closure is forwarded without a failure");
            assert_eq!(*telemetry.0.lock().expect("handshake count"), 0, "closure is not a ServerHello");
            assert!(matches!(result, FirstResponseDecision::Complete), "code {code:?} is neutral");
            let mut bytes = vec![0; response.len()];
            app.read_exact(&mut bytes).expect("forwarded closure");
            assert_eq!(bytes, response);
            assert_eq!(resolve(Some(canceled)).hints.split_offset_base, None);
            state.note_evolver_success(Some(canceled));
            state.note_evolver_success(Some(canceled));
            assert_eq!(resolve(Some(other)).hints.split_offset_base, expected_hint);
            state.note_evolver_success(Some(other));
            assert_eq!(resolve(Some(other)).hints.split_offset_base, None);
            assert!(!session.into_relay_session().permits_success(), "late relay completion must stay neutral");
        }
    }

    #[test]
    fn forwarded_response_stays_provisional_until_the_relay_completes() {
        for frozen in [false, true] {
            let mut config = RuntimeConfig::default();
            config.groups.push(DesyncGroup::new(1));
            config.adaptive.cache_ttl = 60;
            let state = RuntimeState::test(config);
            let (mut upstream, mut origin) = pair();
            let (client, mut app) = pair();
            let mut client = ClientStream::Tcp(client);
            let target = upstream.peer_addr().expect("target");
            let request = b"GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
            let response = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
            let route = RuntimeConnectionRoute { group_index: 1, attempted_mask: 3, route_advances: 1 };
            let mut session = FirstOutboundSession::new();
            session.observe_first_outbound_payload(request);
            origin.write_all(response).expect("short response");
            handle_first_response(
                &mut client,
                &mut upstream,
                &mut session,
                FirstResponseContext {
                    state: &state,
                    target,
                    host: None,
                    original_request: request,
                    tls_send_start: None,
                },
            )
            .expect("forward response");
            let mut forwarded = vec![0; response.len()];
            app.read_exact(&mut forwarded).expect("already visible to client");
            assert_eq!(forwarded.as_slice(), response);
            let selected = || select_route(&state, target, None, None, true).expect("next route").group_index;
            assert_eq!(selected(), 0, "an open relay cannot commit a strategy");
            let context = || RelayResultContext {
                relay_timeouts: state.relay_timeouts(1).expect("timeouts"),
                target,
                route: &route,
                success_host: None,
                success_payload: Some(request),
                success_strategy_family: None,
                primary_strategy_family: None,
            };
            let result = if frozen {
                Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    crate::runtime::relay::stream_copy::CONNECTION_FREEZE_MARKER,
                ))
            } else {
                Ok(session.into_relay_session())
            };
            record_relay_result(&result, &state, context()).expect("terminal feedback");
            assert_eq!(selected(), usize::from(!frozen), "only clean completion can commit the strategy");
        }
    }

    #[test]
    fn dpi38_closure_cannot_train_terminal_route_cache() {
        for code in [0, 90] {
            let mut config = RuntimeConfig::default();
            config.groups.push(DesyncGroup::new(1));
            config.adaptive.cache_ttl = 60;
            let state = RuntimeState::test(config);
            let (mut upstream, mut origin) = pair();
            let (client, mut app) = pair();
            let mut client = ClientStream::Tcp(client);
            let target = upstream.peer_addr().expect("target");
            let request = RUNTIME_DEFAULT_FAKE_TLS;
            let response = [0x15, 3, 3, 0, 2, 1, code];
            origin.write_all(&response).expect("closure record");
            let route = RuntimeConnectionRoute { group_index: 1, attempted_mask: 1, route_advances: 0 };
            let mut session = FirstOutboundSession::new();
            session.observe_first_outbound_payload(request);
            handle_first_response(
                &mut client,
                &mut upstream,
                &mut session,
                FirstResponseContext {
                    state: &state,
                    target,
                    host: None,
                    original_request: request,
                    tls_send_start: None,
                },
            )
            .expect("observe actual closure");
            let mut forwarded = [0; 7];
            app.read_exact(&mut forwarded).expect("forwarded closure");
            assert_eq!(forwarded, response);
            let relay_session = session.into_relay_session();
            assert!(relay_session.has_inbound_payload(), "neutrality must retain inbound accounting");
            record_relay_result(
                &Ok(relay_session),
                &state,
                RelayResultContext {
                    relay_timeouts: state.relay_timeouts(1).expect("timeouts"),
                    target,
                    route: &route,
                    success_host: None,
                    success_payload: Some(request),
                    success_strategy_family: None,
                    primary_strategy_family: None,
                },
            )
            .expect("terminal accounting remains neutral");
            assert_eq!(
                select_route(&state, target, None, None, true).expect("initial route").group_index,
                0,
                "closure cannot pin a successful destination route"
            );
            state
                .note_route_success(target, &route, None, RuntimeTransportProtocol::Tcp)
                .expect("oracle positive control");
            assert_eq!(
                select_route(&state, target, None, None, true).expect("learned route").group_index,
                1,
                "the real policy cache does retain an explicit success"
            );
        }
    }
}
