mod detail_builder;
mod observation_collection;
mod outcome_classification;
mod retry_policy;
mod sample_builder;

use crate::candidates::StrategyCandidateSpec;
use crate::execution::scoring::ProbeSample;
use crate::tls::ProbeStreamOptions;
use crate::transport::TransportConfig;
use crate::types::DomainTarget;

pub(super) fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    options: &ProbeStreamOptions<'_>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> ProbeSample {
    sample_builder::build_https_probe_sample(transport, target, candidate, options, should_abort)
}

#[cfg(test)]
mod tests;
