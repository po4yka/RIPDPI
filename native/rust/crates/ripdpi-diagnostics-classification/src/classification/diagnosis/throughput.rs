use crate::types::{Diagnosis, ProbeResult};

use super::common::{DiagnosisSink, diagnosis_evidence};
use super::failure_detail_value;

#[derive(PartialEq, Eq)]
struct TransferScope<'a> {
    window_bytes: usize,
    scheme: &'a str,
    address_family: &'a str,
}

fn comparison_sample(result: &ProbeResult) -> Option<(&ProbeResult, bool, u64, TransferScope<'_>)> {
    let detail = |key| failure_detail_value(result, key);
    if result.probe_type != "throughput_window"
        || result.outcome != "throughput_measured"
        || detail("transferNetworkScope") == Some("unverified")
    {
        return None;
    }
    let runs = detail("runs")?.parse::<usize>().ok().filter(|runs| (2..=10).contains(runs))?;
    if detail("acceptedRuns")?.parse::<usize>().ok() != Some(runs)
        || detail("attemptedRuns")?.parse::<usize>().ok() != Some(runs)
    {
        return None;
    }
    let bps = detail("medianBodyGoodputBps")?.parse().ok()?;
    let window_bytes = detail("windowBytes")?.parse().ok().filter(|bytes| *bytes > 0)?;
    let (scheme, _) = detail("url")?.split_once("://")?;
    if !matches!(scheme, "http" | "https") {
        return None;
    }
    let mut families = detail("addressFamilyReadings")?.split('|');
    let address_family = families.next().filter(|value| matches!(*value, "ipv4" | "ipv6"))?;
    if families.clone().count() != runs - 1 || !families.all(|family| family == address_family) {
        return None;
    }
    let is_control = detail("isControl")?.parse().ok()?;
    Some((result, is_control, bps, TransferScope { window_bytes, scheme, address_family }))
}

pub(crate) fn classify_throughput_diagnosis(results: &[ProbeResult], sink: &mut DiagnosisSink) {
    let measured = results.iter().filter_map(comparison_sample).collect::<Vec<_>>();

    for (result, is_control, target_median, scope) in &measured {
        if *is_control {
            continue;
        }
        let mut controls = measured
            .iter()
            .filter_map(|(_, is_control, bps, control_scope)| (*is_control && control_scope == scope).then_some(*bps))
            .collect::<Vec<_>>();
        let control_median = median(&mut controls);
        if control_median >= 5_000_000 && target_median.saturating_mul(4) < control_median {
            let mut evidence =
                diagnosis_evidence(result, &["medianBodyGoodputBps", "bodyGoodputBpsReadings", "windowBytes"]);
            evidence.push(format!("controlMedianBodyGoodputBps={control_median}"));
            sink.push(Diagnosis {
                code: "throughput_degradation".to_string(),
                summary: format!(
                    "{} measured body rate is far below the controls for the same transfer window",
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
