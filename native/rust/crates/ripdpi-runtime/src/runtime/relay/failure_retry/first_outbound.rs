use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use ripdpi_session::SessionState;

use crate::runtime::adaptive::note_server_ttl_for_route;
use crate::runtime::desync::send_with_group;
use crate::runtime::relay::failure_retry::retry_logic::{
    classify_first_write_failure, record_stream_relay_success, should_retry_syn_data_without_tfo,
};
use crate::runtime::relay::first_exchange::{needs_first_exchange, read_first_response, FirstResponse};
use crate::runtime::relay::tls_boundary::OutboundTlsClientHelloAssembler;
use crate::runtime::routing::{
    advance_route_for_failure, emit_failure_classified, note_block_signal_for_failure, reconnect_target,
    reconnect_target_without_tfo, should_track_strategy_target,
};
use crate::runtime::state::RuntimeState;
use crate::runtime_policy::{extract_host, is_tls_client_hello_payload, ConnectionRoute};

pub(crate) struct PreparedRelay {
    pub(crate) upstream: TcpStream,
    pub(crate) route: ConnectionRoute,
    pub(crate) session_state: SessionState,
    pub(crate) success_recorded: bool,
    pub(crate) success_host: Option<String>,
    pub(crate) success_payload: Option<Vec<u8>>,
    pub(crate) client_closed: bool,
}

const FIRST_OUTBOUND_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) struct FirstOutboundCoordinator<'a> {
    state: &'a RuntimeState,
    target: SocketAddr,
    route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
}

impl<'a> FirstOutboundCoordinator<'a> {
    fn new(state: &'a RuntimeState, target: SocketAddr, route: ConnectionRoute, seed_request: Option<Vec<u8>>) -> Self {
        Self { state, target, route, seed_request }
    }

    fn run(self, client: &mut TcpStream, mut upstream: TcpStream) -> io::Result<PreparedRelay> {
        let mut session_state = SessionState::default();
        let mut success_recorded = false;
        let first_request = self.seed_request.map_or_else(
            || read_first_client_payload(client, self.state.config.network.buffer_size),
            |seed| Ok(Some(seed)),
        )?;
        let mut success_host = first_request.as_ref().and_then(|payload| extract_host(&self.state.config, payload));
        let mut success_payload = first_request.clone();
        let mut route = self.route;

        let Some(original_request) = first_request else {
            return Ok(PreparedRelay {
                upstream,
                route,
                session_state,
                success_recorded,
                success_host,
                success_payload,
                client_closed: true,
            });
        };

        let host = extract_host(&self.state.config, &original_request);
        success_host = host.clone();
        success_payload = Some(original_request.clone());
        let is_tls = is_tls_client_hello_payload(&original_request);
        let inspect_first_response = needs_first_exchange(self.state)?;
        let mut syn_data_retry_attempted = false;

        loop {
            session_state = SessionState::default();
            let progress = session_state.observe_outbound(&original_request);
            let group = self.state.config.groups[route.group_index].clone();
            let tls_send_start = is_tls.then(Instant::now);
            let send_result = send_with_group(
                &mut upstream,
                self.state,
                route.group_index,
                &group,
                &original_request,
                progress,
                host.as_deref(),
                self.target,
            );
            let send_outcome = match send_result {
                Ok(outcome) => outcome,
                Err(err) => {
                    let failure = classify_first_write_failure(&err);
                    if should_retry_syn_data_without_tfo(
                        self.state,
                        &route,
                        Some(&original_request),
                        &failure,
                        syn_data_retry_attempted,
                    ) {
                        tracing::debug!(
                            group_index = route.group_index,
                            target = %self.target,
                            "retrying first outbound connect without TCP Fast Open for SynData"
                        );
                        syn_data_retry_attempted = true;
                        upstream = reconnect_target_without_tfo(
                            self.target,
                            self.state,
                            route.clone(),
                            host.clone(),
                            Some(&original_request),
                        )?
                        .0;
                        continue;
                    }
                    emit_failure_classified(self.state, self.target, &failure, host.as_deref());
                    let next = advance_route_for_failure(
                        self.state,
                        self.target,
                        &route,
                        host.clone(),
                        Some(&original_request),
                        &failure,
                    )?;
                    let Some(next) = next else {
                        return Err(err.into_io_error());
                    };
                    route = next;
                    upstream = reconnect_target(
                        self.target,
                        self.state,
                        route.clone(),
                        host.clone(),
                        Some(&original_request),
                    )?
                    .0;
                    continue;
                }
            };
            tracing::debug!(
                target = %self.target,
                strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
                bytes_committed = send_outcome.bytes_committed,
                "first outbound payload forwarded"
            );

            if !inspect_first_response {
                break;
            }

            match read_first_response(
                self.state,
                self.target,
                host.as_deref(),
                &mut upstream,
                &self.state.config,
                &original_request,
            )? {
                FirstResponse::Forward(bytes, server_ttl) => {
                    if let (Some(start), Some(telemetry)) = (tls_send_start, &self.state.telemetry) {
                        telemetry.on_tls_handshake_completed(self.target, start.elapsed().as_millis() as u64);
                    }
                    session_state.observe_inbound(&bytes);
                    client.write_all(&bytes)?;
                    if session_state.recv_count > 0 {
                        if should_track_strategy_target(self.target) {
                            if let Some(ttl) = server_ttl {
                                note_server_ttl_for_route(
                                    self.state,
                                    self.target,
                                    route.group_index,
                                    host.as_deref(),
                                    ttl,
                                )?;
                            }
                        }
                        record_stream_relay_success(
                            self.state,
                            self.target,
                            &route,
                            host.as_deref(),
                            Some(&original_request),
                        )?;
                        success_recorded = true;
                    }
                    break;
                }
                FirstResponse::NoData => break,
                FirstResponse::Failure { failure, response_bytes } => {
                    if should_retry_syn_data_without_tfo(
                        self.state,
                        &route,
                        Some(&original_request),
                        &failure,
                        syn_data_retry_attempted,
                    ) {
                        tracing::debug!(
                            group_index = route.group_index,
                            target = %self.target,
                            "retrying first response path without TCP Fast Open for SynData"
                        );
                        syn_data_retry_attempted = true;
                        upstream = reconnect_target_without_tfo(
                            self.target,
                            self.state,
                            route.clone(),
                            host.clone(),
                            Some(&original_request),
                        )?
                        .0;
                        continue;
                    }
                    note_block_signal_for_failure(self.state, host.as_deref(), &failure, None);
                    emit_failure_classified(self.state, self.target, &failure, host.as_deref());
                    let next = advance_route_for_failure(
                        self.state,
                        self.target,
                        &route,
                        host.clone(),
                        Some(&original_request),
                        &failure,
                    )?;
                    if let Some(next) = next {
                        route = next;
                        upstream = reconnect_target(
                            self.target,
                            self.state,
                            route.clone(),
                            host.clone(),
                            Some(&original_request),
                        )?
                        .0;
                        continue;
                    }
                    if failure.action == ripdpi_failure_classifier::FailureAction::ResolverOverrideRecommended {
                        return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                    }
                    if let Some(bytes) = response_bytes {
                        session_state.observe_inbound(&bytes);
                        client.write_all(&bytes)?;
                        break;
                    }
                    if failure.class == ripdpi_failure_classifier::FailureClass::SilentDrop {
                        break;
                    }
                    return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                }
            }
        }

        Ok(PreparedRelay {
            upstream,
            route,
            session_state,
            success_recorded,
            success_host,
            success_payload,
            client_closed: false,
        })
    }
}

#[inline(never)]
pub(crate) fn prepare_relay(
    client: &mut TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<PreparedRelay> {
    FirstOutboundCoordinator::new(state, target, route, seed_request).run(client, upstream)
}

fn read_first_client_payload(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    let mut buffer = vec![0u8; buffer_size.max(16_384)];
    let mut assembler = OutboundTlsClientHelloAssembler::new();
    let result = loop {
        let now = Instant::now();
        let timeout = assembler.timeout(now).unwrap_or(FIRST_OUTBOUND_IDLE_TIMEOUT);
        let _ = client.set_read_timeout(Some(timeout));
        match client.read(&mut buffer) {
            Ok(0) => break Ok(assembler.finish()),
            Ok(n) => {
                if let Some(payload) = assembler.push(&buffer[..n], now) {
                    break Ok(Some(payload));
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if let Some(payload) = assembler.flush_on_timeout(Instant::now()) {
                    break Ok(Some(payload));
                }
                continue;
            }
            Err(err) => break Err(err),
        }
    };
    let _ = client.set_read_timeout(original_timeout);
    result
}

#[cfg(test)]
mod tests {
    use super::read_first_client_payload;
    use std::io::Write;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    mod rust_packet_seeds {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
    }

    #[test]
    fn read_first_client_payload_reassembles_multi_write_client_hello() {
        let (mut client, mut app) = connected_pair();
        let payload = build_multi_record_tls_client_hello();
        let expected = payload.clone();

        let writer = thread::spawn(move || {
            for chunk in expected.chunks(17) {
                app.write_all(chunk).expect("write client hello fragment");
                thread::sleep(Duration::from_millis(2));
            }
        });

        let assembled = read_first_client_payload(&mut client, 64).expect("read payload");
        writer.join().expect("writer thread");

        assert_eq!(assembled, Some(payload));
    }

    fn build_multi_record_tls_client_hello() -> Vec<u8> {
        let client_hello = rust_packet_seeds::tls_client_hello();
        let record_header = &client_hello[..3];
        let handshake = &client_hello[5..];
        let split = 96usize.min(handshake.len().saturating_sub(1));
        let mut payload = Vec::with_capacity(client_hello.len() + 5);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&(split as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[..split]);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&((handshake.len() - split) as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[split..]);
        payload
    }

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }
}
