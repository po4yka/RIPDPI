use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::tls::{try_tls_handshake, TlsClientProfile};
use crate::transport::{domain_connect_target, TransportConfig};
use crate::types::{DomainTarget, ProbeDetail};

pub(super) struct HttpsRetryDecision {
    pub(super) retry_count: usize,
    pub(super) final_outcome: String,
}

pub(super) fn apply_https_retry_policy(
    transport: &TransportConfig,
    target: &DomainTarget,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    https_port: u16,
    outcome: &str,
    details: &mut Vec<ProbeDetail>,
) -> HttpsRetryDecision {
    if outcome == "tls_handshake_failed" {
        let retry = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
        let retry_outcome = if retry.status == "tls_ok" { "tls_ok" } else { "tls_handshake_failed" };
        details.push(ProbeDetail { key: "retryOutcome".to_string(), value: retry_outcome.to_string() });
        details.push(ProbeDetail {
            key: "retryError".to_string(),
            value: retry.error.unwrap_or_else(|| "none".to_string()),
        });
        let final_outcome = if retry_outcome == "tls_ok" { "tls_ok".to_string() } else { outcome.to_string() };

        HttpsRetryDecision { retry_count: 1, final_outcome }
    } else {
        HttpsRetryDecision { retry_count: 0, final_outcome: outcome.to_string() }
    }
}
