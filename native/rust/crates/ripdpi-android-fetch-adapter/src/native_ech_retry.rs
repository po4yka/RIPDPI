use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use rustls::client::EchStatus;
use rustls::pki_types::ServerName;
use serde::Serialize;
use tokio::time::{Instant as Deadline, timeout_at};
use tokio_rustls::TlsConnector;

pub(crate) struct EchEndpoint<'a> {
    pub server_name: &'a str,
    pub host: &'a str,
    pub port: u16,
    pub require_public_address: bool,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum EchOutcome {
    Accepted,
    Rejected,
    CertificateError,
    TlsError,
    TransportError,
    Timeout,
    UnusableConfig,
    EndpointDenied,
    Unavailable,
    Cancelled,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum EchStage {
    Setup,
    Connect,
    Tls,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct EchHandshakeReport {
    pub(crate) outcome: EchOutcome,
    pub(crate) stage: EchStage,
    pub(crate) peer_address: Option<SocketAddr>,
    pub(crate) latency_ms: Option<u64>,
    pub(crate) retry_attempted: bool,
}

impl EchHandshakeReport {
    pub(crate) fn setup(outcome: EchOutcome) -> Self {
        Self { outcome, stage: EchStage::Setup, peer_address: None, latency_ms: None, retry_attempted: false }
    }
}

/// # Cancel safety:
/// cancel-safe: all DNS, TCP and TLS state is owned by this operation; dropping
/// it closes the active transport and prevents an authenticated retry.
pub(crate) async fn connect(
    endpoint: EchEndpoint<'_>,
    configs: Vec<u8>,
    phase_budget: Duration,
    build_config: impl Fn(Vec<u8>) -> Result<rustls::ClientConfig, EchOutcome>,
) -> EchHandshakeReport {
    let mut report = EchHandshakeReport::setup(EchOutcome::Unavailable);
    let Some(deadline) = Deadline::now().checked_add(phase_budget.saturating_mul(2)) else {
        return report;
    };
    report.outcome =
        timeout_at(deadline, attempts(endpoint, configs, phase_budget, deadline, build_config, &mut report))
            .await
            .unwrap_or(EchOutcome::Timeout);
    report
}

/// # Cancel safety:
/// cancel-safe: the stream for a failed attempt drops before another connect.
async fn attempts(
    endpoint: EchEndpoint<'_>,
    mut configs: Vec<u8>,
    phase_budget: Duration,
    deadline: Deadline,
    build_config: impl Fn(Vec<u8>) -> Result<rustls::ClientConfig, EchOutcome>,
    report: &mut EchHandshakeReport,
) -> EchOutcome {
    let Ok(server_name @ ServerName::DnsName(_)) = ServerName::try_from(endpoint.server_name.to_owned()) else {
        return EchOutcome::EndpointDenied;
    };
    let mut tls_latency = Duration::ZERO;
    for attempt in 0..2 {
        report.stage = EchStage::Setup;
        let config = match build_config(configs) {
            Ok(config) => config,
            Err(outcome) => return outcome,
        };
        let remaining = deadline.saturating_duration_since(Deadline::now()).min(phase_budget);
        if remaining.is_zero() {
            return EchOutcome::Timeout;
        }
        report.stage = EchStage::Connect;
        report.retry_attempted = attempt > 0;
        let connect_ms = u64::try_from(remaining.as_millis()).unwrap_or(u64::MAX);
        let tcp = match report.peer_address {
            Some(address) => {
                crate::socket_protection::connect_transport(&address.ip().to_string(), address.port(), connect_ms).await
            }
            None if endpoint.require_public_address => {
                crate::native_ech_endpoint::connect_public_transport(endpoint.host, endpoint.port, connect_ms).await
            }
            None => crate::socket_protection::connect_transport(endpoint.host, endpoint.port, connect_ms).await,
        };
        let tcp = match tcp {
            Ok(tcp) => tcp,
            Err(error) => return connect_error(&error),
        };
        report.peer_address = match tcp.peer_addr() {
            Ok(address) => Some(address),
            Err(_) => return EchOutcome::TransportError,
        };
        report.stage = EchStage::Tls;
        let started = Instant::now();
        let connector = TlsConnector::from(Arc::new(config));
        let phase_end = Deadline::now().checked_add(phase_budget).unwrap_or(deadline).min(deadline);
        let result = timeout_at(phase_end, connector.connect(server_name.clone(), tcp)).await;
        tls_latency += started.elapsed();
        report.latency_ms = Some(u64::try_from(tls_latency.as_millis()).unwrap_or(u64::MAX));
        match result {
            Err(_) => return EchOutcome::Timeout,
            Ok(Ok(stream)) => {
                return if matches!(stream.get_ref().1.ech_status(), EchStatus::Accepted) {
                    EchOutcome::Accepted
                } else {
                    EchOutcome::TlsError
                };
            }
            Ok(Err(error)) => {
                let tls_error = error.get_ref().and_then(|error| error.downcast_ref::<rustls::Error>());
                if attempt == 0
                    && let Some(retry) = tls_error.and_then(ripdpi_tls_profiles::ech::retry::rustls_ech_retry_configs)
                {
                    configs = retry;
                    continue;
                }
                return tls_outcome(tls_error, error.kind());
            }
        }
    }
    EchOutcome::TlsError
}

fn connect_error(error: &io::Error) -> EchOutcome {
    match error.kind() {
        io::ErrorKind::PermissionDenied => EchOutcome::EndpointDenied,
        io::ErrorKind::TimedOut => EchOutcome::Timeout,
        _ => EchOutcome::TransportError,
    }
}

fn tls_outcome(error: Option<&rustls::Error>, kind: io::ErrorKind) -> EchOutcome {
    match error {
        // rustls reports this only after authenticating the public name and verifying the outer Finished.
        Some(rustls::Error::PeerIncompatible(rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(_))) => {
            EchOutcome::Rejected
        }
        Some(rustls::Error::InvalidCertificate(_) | rustls::Error::NoCertificatesPresented) => {
            EchOutcome::CertificateError
        }
        Some(_) => EchOutcome::TlsError,
        None if kind == io::ErrorKind::TimedOut => EchOutcome::Timeout,
        None => EchOutcome::TransportError,
    }
}
