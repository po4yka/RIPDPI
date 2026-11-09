use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::capture::{CaptureTlsError, CaptureTlsHandshakeOptions, CapturedTlsHandshake, capture_tls_handshake};
use crate::transport::{TargetAddress, connect_transport_observed};
use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};
use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};

pub(super) fn capture_with_ech_retry(
    socket: TcpStream,
    connected_addr: Option<SocketAddr>,
    options: &CaptureTlsHandshakeOptions<'_>,
) -> Result<CapturedTlsHandshake, CaptureTlsError> {
    if !matches!(options.profile, super::super::types::TlsClientProfile::Tls13WithEch) {
        return capture_tls_handshake(socket, options);
    }
    let budget = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    let started = Instant::now();
    let deadline = active_scan_io_deadline().map_or(started + budget, |deadline| deadline.min(started + budget));
    with_scan_io_deadline(Some(deadline), || {
        let failure = match capture_tls_handshake(socket, options) {
            Ok(handshake) => return Ok(handshake),
            Err(failure) => failure,
        };
        let Some(configs) = failure.ech_retry_configs.as_deref() else {
            return Err(failure);
        };
        // Retry only a previously resolved peer. A fresh DNS result could refer
        // to another ECH deployment and cannot authorize this retry.
        let Some(peer) = connected_addr else {
            return Err(failure);
        };
        if let Some(reason) = (options.should_abort)() {
            return Err(CaptureTlsError {
                message: format!("probe_aborted:{reason}"),
                server_hello_received: failure.server_hello_received,
                ech_retry_configs: None,
            });
        }
        bounded_scan_io_timeout(IO_TIMEOUT).map_err(|reason| CaptureTlsError {
            message: reason.into(),
            server_hello_received: failure.server_hello_received,
            ech_retry_configs: None,
        })?;
        let target = [TargetAddress::Ip(peer.ip())];
        let reconnect =
            connect_transport_observed(&target, peer.port(), options.transport).map_err(|error| CaptureTlsError {
                message: error.message,
                server_hello_received: failure.server_hello_received,
                ech_retry_configs: None,
            })?;
        let retry_options = CaptureTlsHandshakeOptions {
            targets: options.targets,
            ech_retry_configs: Some(configs),
            transport: options.transport,
            tls_name: options.tls_name,
            profile: options.profile,
            tls_verifier: options.tls_verifier,
            key_log: options.key_log,
            application_protocol: options.application_protocol,
            should_abort: options.should_abort,
        };
        let mut handshake = capture_tls_handshake(reconnect.stream, &retry_options).map_err(|mut retry_error| {
            retry_error.server_hello_received |= failure.server_hello_received;
            retry_error
        })?;
        handshake.tls_handshake_ms = started.elapsed().as_millis() as u64;
        Ok(handshake)
    })
}

#[cfg(test)]
mod tests;
