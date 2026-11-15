use std::io::{self, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use crate::runtime::adaptive::note_server_ttl_for_route;
use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::relay::failure_retry::retry_logic::record_stream_relay_success;
use crate::runtime::relay::first_exchange::{FirstResponse, FirstResponseReader, read_first_response};
use crate::runtime::relay::pending_response::PendingFirstResponse;
use crate::runtime::relay::session::FirstOutboundSession;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::RuntimeConnectionRoute;

pub(super) enum FirstResponseDecision {
    Complete { recorded_success: bool },
    Retry { failure: RuntimeClassifiedFailure, response_bytes: Option<Vec<u8>> },
    Continue(Box<PendingFirstResponse>),
}

pub(super) struct FirstResponseContext<'a> {
    pub(super) state: &'a RuntimeState,
    pub(super) target: SocketAddr,
    pub(super) route: &'a RuntimeConnectionRoute,
    pub(super) host: Option<&'a str>,
    pub(super) original_request: &'a [u8],
    pub(super) success_strategy_family: Option<&'static str>,
    pub(super) primary_strategy_family: Option<&'static str>,
    pub(super) tls_send_start: Option<Instant>,
}

pub(super) fn handle_first_response(
    client: &mut TcpStream,
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
            route: context.route.clone(),
            host: context.host.map(str::to_owned),
            original_request: context.original_request.to_vec(),
            success_strategy_family: context.success_strategy_family,
            primary_strategy_family: context.primary_strategy_family,
            tls_send_start: context.tls_send_start,
        }))),
        FirstResponse::Forward(bytes, server_ttl) => {
            if let Some(start) = context.tls_send_start {
                context.state.note_tls_handshake_completed(context.target, start.elapsed().as_millis() as u64);
            }
            let has_inbound_payload = session_state.observe_first_response_payload(&bytes);
            client.write_all(&bytes)?;
            context.state.note_application_traffic(0, bytes.len() as u64);
            if !has_inbound_payload {
                return Ok(FirstResponseDecision::Complete { recorded_success: false });
            }
            if RuntimeState::should_track_strategy_target(context.target)
                && let Some(ttl) = server_ttl
            {
                note_server_ttl_for_route(
                    context.state,
                    context.target,
                    context.route.group_index,
                    context.host,
                    ttl,
                    session_state.fake_ttl_attempt,
                )?;
            }
            record_stream_relay_success(
                context.state,
                context.target,
                context.route,
                context.host,
                Some(context.original_request),
                context.success_strategy_family,
                context.primary_strategy_family,
                session_state.fake_ttl_attempt,
            )?;
            Ok(FirstResponseDecision::Complete { recorded_success: true })
        }
        FirstResponse::Neutral(bytes) => {
            session_state.neutral_first_response();
            session_state.observe_retry_response_payload(&bytes);
            client.write_all(&bytes)?;
            context.state.note_application_traffic(0, bytes.len() as u64);
            Ok(FirstResponseDecision::Complete { recorded_success: false })
        }
        FirstResponse::NoData => {
            session_state.neutral_first_response();
            Ok(FirstResponseDecision::Complete { recorded_success: false })
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
    use crate::runtime::types::RuntimeTransportProtocol;
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
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
        for code in [0, 90] {
            let telemetry = std::sync::Arc::new(HandshakeCount::default());
            let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(telemetry.clone()));
            let (mut upstream, mut origin) = pair();
            let (mut client, mut app) = pair();
            let target = upstream.peer_addr().expect("target");
            let request = RUNTIME_DEFAULT_FAKE_TLS;
            let response = [0x15, 3, 3, 0, 2, 1, code];
            origin.write_all(&response).expect("closure record");
            let mut session = FirstOutboundSession::new();
            session.observe_first_outbound_payload(request);
            let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1, route_advances: 0 };
            let result = handle_first_response(
                &mut client,
                &mut upstream,
                &mut session,
                FirstResponseContext {
                    state: &state,
                    target,
                    route: &route,
                    host: Some("example.test"),
                    original_request: request,
                    success_strategy_family: None,
                    primary_strategy_family: None,
                    tls_send_start: Some(Instant::now()),
                },
            )
            .expect("closure is forwarded without a failure");
            assert_eq!(*telemetry.0.lock().expect("handshake count"), 0, "closure is not a ServerHello");
            assert!(
                matches!(result, FirstResponseDecision::Complete { recorded_success: false }),
                "code {code} is neutral"
            );
            let mut bytes = [0; 7];
            app.read_exact(&mut bytes).expect("forwarded closure");
            assert_eq!(bytes, response);
            assert!(!session.into_relay_session().permits_success(), "late relay completion must stay neutral");
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
            let (mut client, mut app) = pair();
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
                    route: &route,
                    host: None,
                    original_request: request,
                    success_strategy_family: None,
                    primary_strategy_family: None,
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
                    success_recorded: false,
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
