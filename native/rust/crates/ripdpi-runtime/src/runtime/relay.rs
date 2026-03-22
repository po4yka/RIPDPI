use crate::sync::{Arc, Mutex};
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use crate::platform;
use crate::runtime_policy::{extract_host, ConnectionRoute, TransportProtocol};
use ripdpi_config::{
    RuntimeConfig, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_SILENT_DROP, DETECT_TCP_RESET,
    DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST,
};
use ripdpi_failure_classifier::{
    classify_transport_error, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};
use ripdpi_session::SessionState;

use super::adaptive::{note_adaptive_fake_ttl_success, note_adaptive_tcp_success, note_server_ttl_for_route};
use super::desync::send_with_group;
use super::retry::note_retry_success;
use super::routing::{
    advance_route_for_failure, classify_response_failure, emit_failure_classified, note_route_success,
    reconnect_target, runtime_supports_trigger,
};
use super::state::RuntimeState;

pub(super) fn relay(
    mut client: TcpStream,
    mut upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    mut route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<()> {
    let mut session_state = SessionState::default();
    let mut success_recorded = false;
    let mut success_host = seed_request.as_ref().and_then(|payload| extract_host(&state.config, payload));
    let mut success_payload = seed_request.clone();

    if seed_request.is_some() || needs_first_exchange(state)? {
        let request_timeout = client.read_timeout()?;
        let first_request = if let Some(seed) = seed_request {
            Some(seed)
        } else {
            read_optional_first_request(&mut client, request_timeout)?
        };
        if let Some(first_request) = first_request {
            let original_request = first_request;
            let host = extract_host(&state.config, &original_request);
            success_host = host.clone();
            success_payload = Some(original_request.clone());

            let is_tls = original_request.first().copied() == Some(0x16);
            loop {
                session_state = SessionState::default();
                let progress = session_state.observe_outbound(&original_request);
                let group = state.config.groups[route.group_index].clone();
                let tls_send_start = is_tls.then(Instant::now);
                if let Err(err) = send_with_group(
                    &mut upstream,
                    state,
                    route.group_index,
                    &group,
                    &original_request,
                    progress,
                    host.as_deref(),
                    target,
                ) {
                    let failure = classify_transport_error(FailureStage::FirstWrite, &err);
                    emit_failure_classified(state, target, &failure, host.as_deref());
                    let next = advance_route_for_failure(
                        state,
                        target,
                        &route,
                        host.clone(),
                        Some(&original_request),
                        &failure,
                    )?;
                    let Some(next) = next else {
                        return Err(err);
                    };
                    route = next;
                    upstream = reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?.0;
                    continue;
                }

                match read_first_response(
                    state,
                    target,
                    host.as_deref(),
                    &mut upstream,
                    &state.config,
                    &original_request,
                )? {
                    FirstResponse::Forward(bytes, server_ttl) => {
                        if let (Some(start), Some(telemetry)) = (tls_send_start, &state.telemetry) {
                            telemetry.on_tls_handshake_completed(target, start.elapsed().as_millis() as u64);
                        }
                        session_state.observe_inbound(&bytes);
                        client.write_all(&bytes)?;
                        if session_state.recv_count > 0 {
                            if let Some(ttl) = server_ttl {
                                note_server_ttl_for_route(state, target, route.group_index, host.as_deref(), ttl)?;
                            }
                            note_adaptive_tcp_success(
                                state,
                                target,
                                route.group_index,
                                host.as_deref(),
                                &original_request,
                            )?;
                            note_retry_success(
                                state,
                                target,
                                route.group_index,
                                host.as_deref(),
                                Some(&original_request),
                                TransportProtocol::Tcp,
                            )?;
                            note_adaptive_fake_ttl_success(state, target, route.group_index, host.as_deref())?;
                            note_route_success(state, target, &route, host.as_deref())?;
                            success_recorded = true;
                        }
                        break;
                    }
                    FirstResponse::NoData => break,
                    FirstResponse::Failure { failure, response_bytes } => {
                        emit_failure_classified(state, target, &failure, host.as_deref());
                        let next = advance_route_for_failure(
                            state,
                            target,
                            &route,
                            host.clone(),
                            Some(&original_request),
                            &failure,
                        )?;
                        if let Some(next) = next {
                            route = next;
                            upstream =
                                reconnect_target(target, state, route.clone(), host.clone(), Some(&original_request))?
                                    .0;
                            continue;
                        }
                        if failure.action == FailureAction::ResolverOverrideRecommended {
                            return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                        }
                        if let Some(bytes) = response_bytes {
                            session_state.observe_inbound(&bytes);
                            client.write_all(&bytes)?;
                            break;
                        }
                        if failure.class == FailureClass::SilentDrop {
                            break;
                        }
                        return Err(io::Error::new(io::ErrorKind::ConnectionReset, failure.evidence.summary));
                    }
                }
            }
        }
    }

    let final_state = relay_streams(client, upstream, state, route.group_index, session_state)?;
    if !success_recorded && final_state.recv_count > 0 {
        if let Some(ref request) = success_payload {
            note_adaptive_tcp_success(state, target, route.group_index, success_host.as_deref(), request)?;
            note_retry_success(
                state,
                target,
                route.group_index,
                success_host.as_deref(),
                Some(request),
                TransportProtocol::Tcp,
            )?;
        }
        note_adaptive_fake_ttl_success(state, target, route.group_index, success_host.as_deref())?;
        note_route_success(state, target, &route, success_host.as_deref())?;
    }
    Ok(())
}

fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
) -> io::Result<SessionState> {
    client.set_read_timeout(None)?;
    client.set_write_timeout(None)?;
    upstream.set_read_timeout(None)?;
    upstream.set_write_timeout(None)?;

    let client_reader = client.try_clone()?;
    let client_writer = client.try_clone()?;
    let upstream_reader = upstream.try_clone()?;
    let upstream_writer = upstream.try_clone()?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.drop_sack;

    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || copy_inbound_half(upstream_reader, client_writer, inbound_session))
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;
    let up = thread::Builder::new()
        .name("ripdpi-up".into())
        .spawn(move || {
            copy_outbound_half(client_reader, upstream_writer, outbound_state, group_index, outbound_session)
        })
        .map_err(|err| io::Error::other(format!("failed to spawn outbound relay thread: {err}")))?;

    let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result?;
    down_result?;
    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

fn needs_first_exchange(state: &RuntimeState) -> io::Result<bool> {
    Ok(runtime_supports_trigger(state, DETECT_HTTP_LOCAT)?
        || runtime_supports_trigger(state, DETECT_HTTP_BLOCKPAGE)?
        || runtime_supports_trigger(state, DETECT_TLS_HANDSHAKE_FAILURE)?
        || runtime_supports_trigger(state, DETECT_TLS_ALERT)?
        || runtime_supports_trigger(state, DETECT_TCP_RESET)?
        || runtime_supports_trigger(state, DETECT_SILENT_DROP)?
        || runtime_supports_trigger(state, DETECT_DNS_TAMPER)?
        || state.config.host_autolearn_enabled)
}

fn read_optional_first_request(
    client: &mut TcpStream,
    fallback_timeout: Option<Duration>,
) -> io::Result<Option<Vec<u8>>> {
    client.set_read_timeout(Some(Duration::from_millis(250)))?;
    let mut buffer = vec![0u8; 16_384];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => Ok(None),
        Err(err) => Err(err),
    };
    client.set_read_timeout(fallback_timeout)?;
    result
}

fn read_first_response(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    upstream: &mut TcpStream,
    config: &RuntimeConfig,
    request: &[u8],
) -> io::Result<FirstResponse> {
    let _ = platform::enable_recv_ttl(upstream);
    let mut collected = Vec::new();
    let mut chunk = vec![0u8; config.buffer_size.max(16_384)];
    let mut tls_partial = TlsRecordTracker::new(request, config);
    let mut timeout_count = 0i32;
    let mut observed_server_ttl: Option<u8> = None;

    loop {
        upstream.set_read_timeout(first_response_timeout(config, &tls_partial))?;
        let read_result = if collected.is_empty() {
            platform::read_chunk_with_ttl(upstream, &mut chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut chunk)
        };
        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: ClassifiedFailure::new(
                    FailureClass::SilentDrop,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    "upstream closed before first response",
                ),
                response_bytes: None,
            }),
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &collected, host) {
                    Ok(FirstResponse::Failure { failure, response_bytes: Some(collected) })
                } else {
                    Ok(FirstResponse::Forward(collected, observed_server_ttl))
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if tls_partial.waiting_for_tls_record() {
                    timeout_count += 1;
                    if timeout_count >= timeout_count_limit(config) {
                        Ok(FirstResponse::Failure {
                            failure: ClassifiedFailure::new(
                                FailureClass::SilentDrop,
                                FailureStage::FirstResponse,
                                FailureAction::RetryWithMatchingGroup,
                                "partial TLS response timed out",
                            ),
                            response_bytes: None,
                        })
                    } else {
                        continue;
                    }
                } else if config.timeout_ms != 0 {
                    Ok(FirstResponse::Failure {
                        failure: classify_transport_error(FailureStage::FirstResponse, &err),
                        response_bytes: None,
                    })
                } else {
                    Ok(FirstResponse::NoData)
                }
            }
            Err(err)
                if matches!(
                    err.kind(),
                    io::ErrorKind::ConnectionReset
                        | io::ErrorKind::ConnectionAborted
                        | io::ErrorKind::BrokenPipe
                        | io::ErrorKind::ConnectionRefused
                        | io::ErrorKind::InvalidInput
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::HostUnreachable
                ) =>
            {
                Ok(FirstResponse::Failure {
                    failure: classify_transport_error(FailureStage::FirstResponse, &err),
                    response_bytes: None,
                })
            }
            Err(err) => Err(err),
        };
        let _ = upstream.set_read_timeout(None);
        return result;
    }
}

fn first_response_timeout(config: &RuntimeConfig, tls_partial: &TlsRecordTracker) -> Option<Duration> {
    if tls_partial.active() {
        Some(Duration::from_millis(config.partial_timeout_ms as u64))
    } else if config.timeout_ms != 0 {
        Some(Duration::from_millis(config.timeout_ms as u64))
    } else if config.groups.iter().any(|group| {
        group.detect
            & (DETECT_HTTP_LOCAT
                | DETECT_HTTP_BLOCKPAGE
                | DETECT_TLS_HANDSHAKE_FAILURE
                | DETECT_TLS_ALERT
                | DETECT_TORST)
            != 0
    }) {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

fn timeout_count_limit(config: &RuntimeConfig) -> i32 {
    config.timeout_count_limit.max(1)
}

#[cfg(test)]
fn response_trigger_supported(config: &RuntimeConfig, trigger: ripdpi_session::TriggerEvent) -> bool {
    use ripdpi_config::DETECT_CONNECT;
    let flag = match trigger {
        ripdpi_session::TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        ripdpi_session::TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        ripdpi_session::TriggerEvent::Connect => DETECT_CONNECT,
        ripdpi_session::TriggerEvent::Torst => DETECT_TORST,
    };
    config.groups.iter().any(|group| group.detect & flag != 0)
}

#[derive(Default)]
struct TlsRecordTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

impl TlsRecordTracker {
    fn new(request: &[u8], config: &RuntimeConfig) -> Self {
        Self {
            enabled: ripdpi_packets::is_tls_client_hello(request) && config.partial_timeout_ms != 0,
            disabled: false,
            record_pos: 0,
            record_size: 0,
            header: [0; 5],
            total_bytes: 0,
            bytes_limit: config.timeout_bytes_limit.max(0) as usize,
        }
    }

    fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    fn observe(&mut self, bytes: &[u8]) {
        if !self.active() {
            return;
        }

        self.total_bytes += bytes.len();
        if self.bytes_limit != 0 && self.total_bytes > self.bytes_limit {
            self.disabled = true;
            return;
        }

        let mut pos = 0usize;
        while pos < bytes.len() {
            if self.record_pos < 5 {
                self.header[self.record_pos] = bytes[pos];
                self.record_pos += 1;
                pos += 1;
                if self.record_pos < 5 {
                    continue;
                }
                self.record_size = usize::from(u16::from_be_bytes([self.header[3], self.header[4]])) + 5;
                let rec_type = self.header[0];
                if !(0x14..=0x18).contains(&rec_type) {
                    self.disabled = true;
                    return;
                }
            }

            if self.record_pos == self.record_size {
                self.record_pos = 0;
                self.record_size = 0;
                continue;
            }

            let remaining = self.record_size.saturating_sub(self.record_pos);
            if remaining == 0 {
                self.disabled = true;
                return;
            }
            let take = remaining.min(bytes.len() - pos);
            self.record_pos += take;
            pos += take;
        }
    }
}

enum FirstResponse {
    Forward(Vec<u8>, Option<u8>),
    Failure { failure: ClassifiedFailure, response_bytes: Option<Vec<u8>> },
    NoData,
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        if let Ok(mut state) = session.lock() {
            state.observe_inbound(&buffer[..n]);
        }
        writer.write_all(&buffer[..n])?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    let mut remembered_host = None::<String>;
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        let payload = &buffer[..n];
        let progress = {
            let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
            state.observe_outbound(payload)
        };
        let parsed_host = extract_host(&state.config, payload);
        if parsed_host.is_some() {
            remembered_host = parsed_host.clone();
        }
        let group = state
            .config
            .groups
            .get(group_index)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
        let peer_addr = writer.peer_addr()?;
        send_with_group(
            &mut writer,
            &state,
            group_index,
            &group,
            payload,
            progress,
            parsed_host.as_deref().or(remembered_host.as_deref()),
            peer_addr,
        )?;
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

#[cfg(test)]
mod tests {
    #[cfg(test)]
    use super::super::routing::trigger_flag;
    use super::*;
    use ripdpi_config::{RuntimeConfig, DETECT_CONNECT, DETECT_HTTP_LOCAT};
    use ripdpi_packets::DEFAULT_FAKE_TLS;
    use ripdpi_session::TriggerEvent;

    #[test]
    fn timeout_and_trigger_helpers_follow_runtime_configuration() {
        let mut config = RuntimeConfig { partial_timeout_ms: 75, timeout_ms: 900, ..RuntimeConfig::default() };
        let tls_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &tls_tracker), Some(Duration::from_millis(75)));

        config.partial_timeout_ms = 0;
        let inactive_tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(900)));

        config.timeout_ms = 0;
        config.groups[0].detect = DETECT_HTTP_LOCAT | DETECT_CONNECT;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(250)));
        assert_eq!(timeout_count_limit(&config), 1);
        assert!(response_trigger_supported(&config, TriggerEvent::Redirect));
        assert!(response_trigger_supported(&config, TriggerEvent::Connect));
        assert!(!response_trigger_supported(&config, TriggerEvent::Torst));
        assert_eq!(trigger_flag(TriggerEvent::SslErr), DETECT_TLS_HANDSHAKE_FAILURE);
        assert_eq!(trigger_flag(TriggerEvent::Torst), DETECT_TORST);

        config.groups[0].detect = 0;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), None);
    }

    #[test]
    fn tls_record_tracker_handles_partial_records_and_limits() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };

        let mut tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x05, 0xaa]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xbb, 0xcc, 0xdd, 0xee]);
        assert!(!tracker.waiting_for_tls_record());

        let limited_config =
            RuntimeConfig { partial_timeout_ms: 50, timeout_bytes_limit: 3, ..RuntimeConfig::default() };
        let mut limited = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &limited_config);
        limited.observe(&[0x16, 0x03, 0x03, 0x00]);
        assert!(!limited.active());

        let mut invalid = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        invalid.observe(&[0x13, 0x03, 0x03, 0x00, 0x01, 0x00]);
        assert!(!invalid.active());
    }

    // -- Characterization: TLS record tracker state transitions --

    #[test]
    fn tls_record_tracker_inactive_without_partial_timeout() {
        let config = RuntimeConfig { partial_timeout_ms: 0, ..RuntimeConfig::default() };
        let tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(!tracker.active());
        assert!(!tracker.waiting_for_tls_record());
    }

    #[test]
    fn tls_record_tracker_inactive_for_non_tls_request() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };
        let non_tls = b"GET / HTTP/1.1\r\n";
        let tracker = TlsRecordTracker::new(non_tls, &config);
        assert!(!tracker.active());
    }

    #[test]
    fn tls_record_tracker_multi_record_observation() {
        let config = RuntimeConfig { partial_timeout_ms: 50, ..RuntimeConfig::default() };
        let mut tracker = TlsRecordTracker::new(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());

        // First record: content type 0x16 (handshake), size 3
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x03]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xaa, 0xbb, 0xcc]);
        assert!(!tracker.waiting_for_tls_record());

        // Second record: content type 0x14 (change cipher spec), size 1
        tracker.observe(&[0x14, 0x03, 0x03, 0x00, 0x01, 0xff]);
        assert!(!tracker.waiting_for_tls_record());
        assert!(tracker.active());
    }
}
