use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::super::types::{EchInitialAttempt, ProbeStreamFailureStage, TlsClientProfile};
use super::capture::{CaptureTlsError, CaptureTlsHandshakeOptions, CapturedTlsHandshake, capture_tls_handshake};
use crate::transport::{RouteExperimentReport, TargetAddress, connect_transport_observed_with_abort};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};
use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};

pub(super) struct EchRetryEvidence {
    pub initial_attempt: EchInitialAttempt,
    pub transport_open_ms: u64,
    pub connected_addr: Option<SocketAddr>,
    pub transport_failure: Option<ProbeStreamFailureStage>,
    pub local_addr: Option<SocketAddr>,
    pub route_report: Option<RouteExperimentReport>,
    pub started: Instant,
}

pub(super) fn capture_with_ech_retry(
    socket: TcpStream,
    connected_addr: Option<SocketAddr>,
    transport_open_ms: u64,
    options: &CaptureTlsHandshakeOptions<'_>,
) -> (Result<CapturedTlsHandshake, CaptureTlsError>, Option<EchRetryEvidence>) {
    if !matches!(options.profile, TlsClientProfile::Tls13WithEch) {
        return (capture_tls_handshake(socket, connected_addr, options), None);
    }
    let budget = match bounded_scan_io_timeout(IO_TIMEOUT) {
        Ok(budget) => budget,
        Err(error) => return (Err(error.to_string().into()), None),
    };
    let now = Instant::now();
    let deadline = active_scan_io_deadline().map_or(now + budget, |deadline| deadline.min(now + budget));
    with_scan_io_deadline(Some(deadline), || {
        let mut failure = match capture_tls_handshake(socket, connected_addr, options) {
            Ok(handshake) => return (Ok(handshake), None),
            Err(failure) => failure,
        };
        // Retry only the same known peer. A fresh DNS result cannot authorize the retry.
        let (Some(configs), Some(source)) = (failure.ech_retry_configs.take(), failure.ech_config_source) else {
            return (Err(failure), None);
        };
        let Some(peer) = connected_addr else {
            failure.ech_retry_skipped_reason = Some("unknown_peer");
            return (Err(failure), None);
        };
        if let Some(reason) = (options.should_abort)() {
            failure.ech_retry_skipped_reason = Some(reason);
            return (Err(failure), None);
        }
        if let Err(reason) = bounded_scan_io_timeout(IO_TIMEOUT) {
            failure.ech_retry_skipped_reason = Some(reason);
            return (Err(failure), None);
        }
        let client_hello = failure.client_hello.map_or_else(Default::default, |evidence| *evidence);
        let initial_attempt = EchInitialAttempt {
            connected_addr: peer,
            transport_open_ms,
            tls_handshake_ms: failure.handshake_ms,
            ja3_fingerprint: client_hello.ja3_fingerprint,
            client_hello_capture: client_hello.capture,
            ech_config_source: source,
            error: failure.message,
        };
        let started = Instant::now();
        let target = [TargetAddress::Ip(peer.ip())];
        let reconnected =
            connect_transport_observed_with_abort(&target, peer.port(), options.transport, options.should_abort);
        let reconnect_ms = started.elapsed().as_millis() as u64;
        let reconnect = match reconnected {
            Ok(reconnect) => reconnect,
            Err(error) => {
                let evidence = EchRetryEvidence {
                    initial_attempt,
                    transport_open_ms: reconnect_ms,
                    connected_addr: None,
                    transport_failure: Some(error.stage.into()),
                    local_addr: None,
                    route_report: None,
                    started,
                };
                return (Err(error.message.into()), Some(evidence));
            }
        };
        let evidence = EchRetryEvidence {
            initial_attempt,
            transport_open_ms: reconnect_ms,
            connected_addr: reconnect.connected_addr,
            transport_failure: None,
            local_addr: reconnect.local_addr,
            route_report: reconnect.route_report,
            started,
        };
        let retry_options = CaptureTlsHandshakeOptions {
            targets: options.targets,
            ech_retry_configs: Some(&configs),
            transport: options.transport,
            tls_name: options.tls_name,
            profile: options.profile,
            tls_verifier: options.tls_verifier,
            key_log: options.key_log,
            application_protocol: options.application_protocol,
            should_abort: options.should_abort,
        };
        let result = capture_tls_handshake(reconnect.stream, reconnect.connected_addr, &retry_options);
        (result, Some(evidence))
    })
}

#[cfg(test)]
mod tests;
