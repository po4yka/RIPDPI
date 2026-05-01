use std::time::Duration;

use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass};

use crate::candidates::{CandidateEligibility, StrategyCandidateSpec};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::types::ProbeResult;

pub(in crate::engine::runners::strategy) const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

pub(in crate::engine::runners::strategy) const FAKE_TTL_ELIGIBILITY_RATIONALE: &str =
    "setsockopt(IP_TTL) is unavailable on this platform (Android VPN/tun mode); fake-packet strategies that rely on TTL manipulation are skipped";

pub(in crate::engine::runners::strategy) const TCP_FAST_OPEN_ELIGIBILITY_RATIONALE: &str =
    "TCP Fast Open is unavailable on this device/kernel, so TFO probe variants are skipped";

pub(in crate::engine::runners::strategy) struct FamilyFailureTracker<'a> {
    blocked: Option<&'a str>,
    last_failed: Option<&'a str>,
    consecutive: usize,
    threshold: usize,
}

impl<'a> FamilyFailureTracker<'a> {
    pub(in crate::engine::runners::strategy) fn new(threshold: usize) -> Self {
        Self { blocked: None, last_failed: None, consecutive: 0, threshold }
    }

    pub(in crate::engine::runners::strategy) fn record(&mut self, family: &'a str, failed: bool) {
        if failed {
            if self.last_failed == Some(family) {
                self.consecutive += 1;
            } else {
                self.last_failed = Some(family);
                self.consecutive = 1;
            }
            if self.consecutive >= self.threshold {
                self.blocked = Some(family);
                self.consecutive = 0;
            }
        } else {
            self.last_failed = None;
            self.consecutive = 0;
            self.blocked = None;
        }
        if self.blocked.is_some() && family != self.blocked.unwrap_or_default() {
            self.blocked = None;
        }
    }

    pub(in crate::engine::runners::strategy) fn blocked_family(&self) -> Option<&'a str> {
        self.blocked
    }
}

fn probe_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find(|detail| detail.key == key).map(|detail| detail.value.as_str())
}

pub(in crate::engine::runners::strategy) fn compute_rst_adaptive_timeout(
    baseline_failure: &ClassifiedFailure,
) -> Option<Duration> {
    if !matches!(baseline_failure.class, FailureClass::TcpReset) {
        return None;
    }
    Some(Duration::from_millis(1500))
}

#[cfg(test)]
pub(in crate::engine::runners::strategy) fn baseline_has_tls_ech_only(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| result.probe_type == "strategy_https" && result.outcome == "tls_ech_only")
}

pub(in crate::engine::runners::strategy) fn baseline_supports_ech_candidates(results: &[ProbeResult]) -> bool {
    results.iter().any(|result| {
        result.probe_type == "strategy_https"
            && (result.outcome == "tls_ech_only"
                || probe_detail_value(result, "tlsEchResolutionDetail") == Some("ech_config_available")
                || probe_detail_value(result, "cdnProvider").is_some_and(|value| !value.trim().is_empty()))
    })
}

pub(in crate::engine::runners::strategy) fn ordered_follow_up_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    baseline_results: &[ProbeResult],
    probe_seed: u64,
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let reordered = reorder_tcp_candidates_for_failure(tcp_specs, failure_class, fake_ttl_available)
        .into_iter()
        .skip(1)
        .collect::<Vec<_>>();
    if !baseline_supports_ech_candidates(baseline_results) {
        return interleave_candidate_families(reordered, probe_seed);
    }

    let mut ech_priority = Vec::new();
    let mut remaining = Vec::new();
    for spec in reordered {
        if spec.eligibility == CandidateEligibility::RequiresEchCapability {
            ech_priority.push(spec);
        } else {
            remaining.push(spec);
        }
    }
    ech_priority.extend(interleave_candidate_families(remaining, probe_seed));
    ech_priority
}
