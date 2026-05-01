use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{try_tls_handshake_targets, TlsClientProfile, TlsObservation};
use crate::transport::{domain_connect_targets, TransportConfig};
use crate::types::DomainTarget;
use crate::util::now_ms;

pub(super) struct HttpsObservationCollection {
    pub(super) tls13: TlsObservation,
    pub(super) tls12: TlsObservation,
    pub(super) tls_ech: TlsObservation,
    pub(super) latency_ms: u64,
    pub(super) https_port: u16,
}

pub(super) fn collect_https_observations(
    transport: &TransportConfig,
    target: &DomainTarget,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> HttpsObservationCollection {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let connect_targets = domain_connect_targets(target);
    let tls13 = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
    );
    let tls12 = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
    );
    let tls_ech = try_tls_handshake_targets(
        &connect_targets,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
    );
    let latency_ms = now_ms().saturating_sub(started);

    HttpsObservationCollection { tls13, tls12, tls_ech, latency_ms, https_port }
}
