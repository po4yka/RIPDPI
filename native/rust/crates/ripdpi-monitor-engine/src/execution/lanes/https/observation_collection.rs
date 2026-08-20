mod tls_attempts;

use std::net::SocketAddr;
use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{TlsKeyLogCallback, TlsObservation};
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::DomainTarget;
use crate::util::now_ms;

use self::tls_attempts::collect_tls_profile_observations;

pub(super) struct HttpsObservationCollection {
    pub(super) tls13: TlsObservation,
    pub(super) tls12: TlsObservation,
    pub(super) tls_ech: TlsObservation,
    pub(super) latency_ms: u64,
    pub(super) https_port: u16,
}

pub(super) struct SelectedTlsFields {
    pub(super) tcp_connect_ms: Option<u64>,
    pub(super) tls_handshake_ms: Option<u64>,
    pub(super) cert_chain_length: Option<usize>,
    pub(super) cert_issuer: Option<String>,
    pub(super) local_socket_ttl: Option<u8>,
    pub(super) ja3_fingerprint: Option<String>,
    pub(super) tls_alert_code: Option<u8>,
    pub(super) tls_alert_description: Option<String>,
    pub(super) tls_server_hello_received: Option<bool>,
    pub(super) tls_dpi_signature: Option<String>,
    pub(super) tls_negotiated_version: Option<String>,
    pub(super) connected_addr: Option<SocketAddr>,
    pub(super) cdn_provider: Option<String>,
}

pub(super) fn collect_https_observations(
    transport: &TransportConfig,
    target: &DomainTarget,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> HttpsObservationCollection {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let connect_targets = domain_connect_targets(target);
    let (tls13, tls12, tls_ech) =
        collect_tls_profile_observations(&connect_targets, https_port, transport, &target.host, tls_verifier, key_log);
    let latency_ms = now_ms().saturating_sub(started);

    HttpsObservationCollection { tls13, tls12, tls_ech, latency_ms, https_port }
}
