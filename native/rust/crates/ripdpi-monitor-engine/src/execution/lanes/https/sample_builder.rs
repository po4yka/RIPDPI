mod sample_result;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::tls::ProbeStreamOptions;
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
    options: &ProbeStreamOptions<'_>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> ProbeSample {
    let observations = collect_https_observations(transport, target, options, should_abort);
    let outcome = classify_https_outcome(&observations, should_abort().is_some());
    let mut details = build_https_probe_details(candidate, &observations, &outcome, transport);

    let retry = apply_https_retry_policy(
        transport,
        target,
        options,
        should_abort,
        observations.https_port,
        &outcome,
        &mut details,
    );

    sample_result::build_https_sample(candidate, target, retry.final_outcome, details, observations.latency_ms)
}
