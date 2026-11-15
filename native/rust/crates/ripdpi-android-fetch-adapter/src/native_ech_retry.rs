use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};

use rustls::client::EchStatus;
use rustls::pki_types::ServerName;
use tokio::time::{Instant as Deadline, timeout_at};
use tokio_rustls::TlsConnector;

pub(crate) struct EchEndpoint<'a> {
    pub server_name: &'a str,
    pub host: &'a str,
    pub port: u16,
    pub require_public_address: bool,
}

/// # Cancel safety:
/// cancel-safe: all DNS, TCP and TLS state is owned by this operation; dropping
/// it closes the active transport and prevents an authenticated retry.
pub(crate) async fn connect(
    endpoint: EchEndpoint<'_>,
    configs: Vec<u8>,
    phase_budget: Duration,
    build_config: impl Fn(Vec<u8>) -> io::Result<rustls::ClientConfig>,
) -> io::Result<u64> {
    let deadline = Deadline::now()
        .checked_add(phase_budget.saturating_mul(2))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "ECH timeout is out of range"))?;
    timeout_at(deadline, attempts(endpoint, configs, phase_budget, deadline, build_config))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "ECH operation timed out"))?
}

/// # Cancel safety:
/// cancel-safe: the stream for a failed attempt drops before another connect.
async fn attempts(
    endpoint: EchEndpoint<'_>,
    mut configs: Vec<u8>,
    phase_budget: Duration,
    deadline: Deadline,
    build_config: impl Fn(Vec<u8>) -> io::Result<rustls::ClientConfig>,
) -> io::Result<u64> {
    let server_name = ServerName::try_from(endpoint.server_name.to_owned())
        .map_err(|error| io::Error::new(io::ErrorKind::PermissionDenied, format!("invalid ECH origin: {error}")))?;
    if !matches!(&server_name, ServerName::DnsName(_)) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "ECH origin needs a DNS name"));
    }
    let mut peer: Option<std::net::SocketAddr> = None;
    let mut tls_latency = Duration::ZERO;
    for attempt in 0..2 {
        let config = build_config(configs)?;
        let remaining = deadline.saturating_duration_since(Deadline::now()).min(phase_budget);
        if remaining.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "ECH operation timed out"));
        }
        let connect_ms = u64::try_from(remaining.as_millis()).unwrap_or(u64::MAX);
        let tcp = match peer {
            Some(address) => {
                crate::socket_protection::connect_transport(&address.ip().to_string(), address.port(), connect_ms).await
            }
            None if endpoint.require_public_address => {
                crate::native_ech_endpoint::connect_public_transport(endpoint.host, endpoint.port, connect_ms).await
            }
            None => crate::socket_protection::connect_transport(endpoint.host, endpoint.port, connect_ms).await,
        }
        .map_err(transport_error)?;
        peer = Some(tcp.peer_addr().map_err(transport_error)?);
        let started = Instant::now();
        let connector = TlsConnector::from(Arc::new(config));
        let phase_end = Deadline::now().checked_add(phase_budget).unwrap_or(deadline).min(deadline);
        let result = timeout_at(phase_end, connector.connect(server_name.clone(), tcp))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "ECH TLS handshake timed out"))?;
        tls_latency += started.elapsed();
        match result {
            Ok(stream) => {
                if !matches!(stream.get_ref().1.ech_status(), EchStatus::Accepted) {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "ECH was not accepted"));
                }
                return Ok(tls_latency.as_millis() as u64);
            }
            Err(error) => {
                if attempt == 0
                    && let Some(retry) = error
                        .get_ref()
                        .and_then(|error| error.downcast_ref::<rustls::Error>())
                        .and_then(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs)
                {
                    configs = retry;
                    continue;
                }
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    format!("ECH TLS handshake failed: {error}"),
                ));
            }
        }
    }
    Err(io::Error::other("ECH retry exhausted"))
}

fn transport_error(error: io::Error) -> io::Error {
    if error.kind() == io::ErrorKind::PermissionDenied {
        error
    } else {
        io::Error::other(format!("ECH connect transport: {error}"))
    }
}
