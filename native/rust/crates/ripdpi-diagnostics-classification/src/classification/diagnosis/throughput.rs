use crate::types::{Diagnosis, ProbeResult};

use super::common::{DiagnosisSink, diagnosis_evidence};
use super::failure_detail_value;

pub(crate) fn classify_throughput_diagnosis(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    let throughput_results = results.iter().filter(|result| result.probe_type == "throughput_window");
    let mut control_medians = Vec::<u64>::new();
    let mut suspicious = Vec::<(&ProbeResult, u64)>::new();

    for result in throughput_results {
        let median_bps =
            failure_detail_value(result, "medianBps").and_then(|value| value.parse::<u64>().ok()).unwrap_or(0);
        let is_control = failure_detail_value(result, "isControl").is_some_and(|value| value == "true");
        if is_control {
            if median_bps > 0 {
                control_medians.push(median_bps);
            }
        } else if result.target.to_ascii_lowercase().contains("youtube") && median_bps > 0 {
            suspicious.push((result, median_bps));
        }
    }

    let control_median = median(&mut control_medians);
    if control_median < 5_000_000 {
        return;
    }

    for (result, youtube_median) in suspicious {
        if youtube_median.saturating_mul(4) < control_median {
            sink.push(Diagnosis {
                code: "youtube_throttled".to_string(),
                summary: format!("{} throughput is far below the neutral control", result.target),
                severity: "warning".to_string(),
                target: Some(result.target.clone()),
                evidence: diagnosis_evidence(result, &["medianBps", "bpsReadings", "windowBytes"]),
                recommendation: None,
                control_validated: None,
            });
        }
    }
}

fn median(values: &mut [u64]) -> u64 {
    if values.is_empty() {
        return 0;
    }
    values.sort_unstable();
    values[values.len() / 2]
}
