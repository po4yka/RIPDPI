mod sample_result;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;
use crate::types::DomainTarget;

use super::detail_builder::build_https_probe_details;
use super::observation_collection::collect_https_observations;
use super::outcome_classification::classify_https_outcome;
use super::retry_policy::apply_https_retry_policy;

pub(super) fn build_https_probe_sample(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
) -> ProbeSample {
    let observations = collect_https_observations(transport, target, tls_verifier, key_log);
    let outcome = classify_https_outcome(&observations);
    let mut details = build_https_probe_details(candidate, &observations, &outcome);

    let retry = apply_https_retry_policy(
        transport,
        target,
        tls_verifier,
        key_log,
        observations.https_port,
        &outcome,
        &mut details,
    );
    sample_result::build_https_sample(
        candidate,
        target,
        retry.final_outcome,
        details,
        observations.started_at_ms,
        retry.retry_count,
    )
}
