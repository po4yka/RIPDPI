mod tls;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::{CandidateWarmup, StrategyCandidateSpec};
use crate::http::try_http_request;
use crate::tls::TlsKeyLogCallback;
use crate::transport::{TransportConfig, domain_connect_target};
use crate::types::DomainTarget;

use self::tls::warmup_tls13;

pub fn run_candidate_warmup(
    spec: &StrategyCandidateSpec,
    transport: &TransportConfig,
    targets: &[DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
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
        let _ = warmup_tls13(transport, target, tls_verifier, key_log, https_port);
    }
}
