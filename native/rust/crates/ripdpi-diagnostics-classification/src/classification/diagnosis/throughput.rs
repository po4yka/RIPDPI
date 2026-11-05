use crate::types::{Diagnosis, ProbeResult};

use super::common::{DiagnosisSink, diagnosis_evidence};
use super::failure_detail_value;

pub(crate) fn classify_throughput_diagnosis(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    let throughput_results = results
        .iter()
        .filter(|result| result.probe_type == "throughput_window" && result.outcome == "throughput_measured");
    let measured = throughput_results
        .filter_map(|result| {
            let median_bps = failure_detail_value(result, "medianBps")?.parse::<u64>().ok()?;
            let window_bytes = failure_detail_value(result, "windowBytes")?.parse::<usize>().ok()?;
            (median_bps > 0 && window_bytes > 0).then_some((result, median_bps, window_bytes))
        })
        .collect::<Vec<_>>();

    for (result, target_median, window_bytes) in &measured {
        if failure_detail_value(result, "isControl") == Some("true") {
            continue;
        }
        let mut controls = measured
            .iter()
            .filter_map(|(control, bps, control_window)| {
                (control_window == window_bytes && failure_detail_value(control, "isControl") == Some("true"))
                    .then_some(*bps)
            })
            .collect::<Vec<_>>();
        let control_median = median(&mut controls);
        if control_median >= 5_000_000 && target_median.saturating_mul(4) < control_median {
            let mut evidence = diagnosis_evidence(result, &["medianBps", "bpsReadings", "windowBytes"]);
            evidence.push(format!("controlMedianBps={control_median}"));
            sink.push(Diagnosis {
                code: "throughput_degradation".to_string(),
                summary: format!(
                    "{} measured throughput is far below the controls for the same transfer window",
                    result.target
                ),
                severity: "warning".to_string(),
                target: Some(result.target.clone()),
                evidence,
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
    let middle = values.len() / 2;
    if values.len() % 2 == 1 {
        values[middle]
    } else {
        // The mean cannot exceed either input's u64 range.
        ((u128::from(values[middle - 1]) + u128::from(values[middle])) / 2) as u64
    }
}
