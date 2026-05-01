use crate::types::{ObservationKind, ProbeObservation, ProbeResult, ThroughputObservationFact};

use super::common::{base_observation, detail_list, detail_value, throughput_status};

pub(crate) fn build_throughput_observation(result: &ProbeResult) -> ProbeObservation {
    let mut observation = base_observation(result, ObservationKind::Throughput);
    observation.throughput = Some(ThroughputObservationFact {
        label: result.target.clone(),
        status: throughput_status(&result.outcome),
        is_control: detail_value(result, "isControl").is_some_and(|value| value == "true"),
        median_bps: detail_value(result, "medianBps").and_then(|value| value.parse::<u64>().ok()).unwrap_or(0),
        sample_bps: detail_list(result, "bpsReadings")
            .into_iter()
            .filter_map(|value| value.parse::<u64>().ok())
            .collect(),
        window_bytes: detail_value(result, "windowBytes")
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or_default(),
    });
    observation
}
