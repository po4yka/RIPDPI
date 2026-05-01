mod detail_builder;
mod observation_collection;
mod outcome_classification;
mod retry_policy;
mod sample_builder;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::transport::TransportConfig;
use crate::types::DomainTarget;

pub(super) fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeSample {
    let sample = sample_builder::build_https_probe_sample(transport, target, candidate, tls_verifier);
    debug_assert!(matches!(
        sample.result.outcome.as_str(),
        "tls_cert_invalid" | "tls_ok" | "tls_version_split" | "tls_ech_only" | "tls_handshake_failed"
    ));
    sample
}

#[cfg(test)]
mod tests;
