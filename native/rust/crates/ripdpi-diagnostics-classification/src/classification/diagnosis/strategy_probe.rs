use ripdpi_failure_classifier::ClassifiedFailure;

use crate::observations::observation_for_probe;
use crate::types::ProbeResult;

use super::super::strategy::{classify_strategy_probe_baseline_observations, strategy_probe_observation_weight};

pub fn strategy_probe_failure_weight(result: &ProbeResult) -> usize {
    observation_for_probe(result).as_ref().map_or_else(
        || match result.probe_type.as_str() {
            "strategy_https" | "strategy_quic" => 2,
            _ => 1,
        },
        strategy_probe_observation_weight,
    )
}

pub fn classify_strategy_probe_baseline_results(results: &[ProbeResult]) -> Option<ClassifiedFailure> {
    classify_strategy_probe_baseline_observations(&results.iter().filter_map(observation_for_probe).collect::<Vec<_>>())
}
