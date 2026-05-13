mod detail_builder;
mod observation_collection;
mod outcome_classification;
mod retry_policy;
mod sample_builder;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::DomainTarget;

pub(super) fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeSample {
    sample_builder::build_https_probe_sample(transport, target, candidate, tls_verifier, key_log)
}

#[cfg(test)]
mod tests;
