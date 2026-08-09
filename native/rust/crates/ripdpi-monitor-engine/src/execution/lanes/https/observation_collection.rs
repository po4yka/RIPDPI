mod tls_attempts;

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
    pub(super) started_at_ms: u64,
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

    HttpsObservationCollection { tls13, tls12, tls_ech, latency_ms, https_port, started_at_ms: started }
}
