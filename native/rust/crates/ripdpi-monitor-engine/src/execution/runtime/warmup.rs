use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{CandidateWarmup, StrategyCandidateSpec};
use crate::http::try_http_request;
use crate::tls::{try_tls_handshake, TlsClientProfile};
use crate::transport::{domain_connect_target, TransportConfig};
use crate::types::DomainTarget;

pub fn run_candidate_warmup(
    spec: &StrategyCandidateSpec,
    transport: &TransportConfig,
    targets: &[DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) {
    if spec.warmup != CandidateWarmup::AdaptiveFakeTtl {
        return;
    }
    for target in targets {
        let http_port = target.http_port.unwrap_or(80);
        let https_port = target.https_port.unwrap_or(443);
        let _ = try_http_request(
            &domain_connect_target(target),
            http_port,
            transport,
            &target.host,
            &target.http_path,
            false,
        );
        let _ = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
    }
}
