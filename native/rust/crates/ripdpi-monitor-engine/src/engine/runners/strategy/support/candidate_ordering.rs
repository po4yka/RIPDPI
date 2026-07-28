use ripdpi_monitor_adapter::failure::FailureClass;

use crate::candidates::{CandidateEligibility, StrategyCandidateSpec};
use crate::classification::{interleave_candidate_families, reorder_tcp_candidates_for_failure};
use crate::types::ProbeResult;

mod rst_timeout;
mod tls_ech;
mod tls_version_split;

pub(in crate::engine::runners::strategy) use rst_timeout::compute_rst_adaptive_timeout;
#[cfg(test)]
pub(in crate::engine::runners::strategy) use tls_ech::baseline_has_tls_ech_only;
pub(in crate::engine::runners::strategy) use tls_ech::baseline_supports_ech_candidates;
pub(in crate::engine::runners::strategy) use tls_version_split::baseline_has_tls_version_split;
use tls_version_split::promote_tls_version_split_families;

pub(in crate::engine::runners::strategy) const ECH_ELIGIBILITY_RATIONALE: &str =
    "Baseline did not expose an ECH-capable HTTPS target, so ECH extension splitting would be a no-op";

pub(in crate::engine::runners::strategy) const FAKE_TTL_ELIGIBILITY_RATIONALE: &str = "setsockopt(IP_TTL) is unavailable on this platform (Android VPN/tun mode); fake-packet strategies that rely on TTL manipulation are skipped";

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

pub(in crate::engine::runners::strategy) fn ordered_follow_up_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
    baseline_results: &[ProbeResult],
    probe_seed: u64,
    fake_ttl_available: bool,
) -> Vec<StrategyCandidateSpec> {
    let baseline_id = tcp_specs.first().map(|candidate| candidate.id);
    let reordered = reorder_tcp_candidates_for_failure(tcp_specs, failure_class, fake_ttl_available)
        .into_iter()
        .filter(|candidate| Some(candidate.id) != baseline_id)
        .collect::<Vec<_>>();

    // Low-confidence L7 bias from split probing: when the HTTPS baseline saw a
    // `tls_version_split` AND no transport `failure_class` already ordered the
    // candidates, float the SNI-splitting families to the front. A transport
    // failure class (TcpReset / TlsAlert / HttpBlockpage / SilentDrop) already
    // promotes split families itself and is the higher-confidence signal, so
    // this bias applies ONLY when there is none — it never overrides a
    // transport-failure ordering.
    let bias_toward_split = failure_class.is_none() && baseline_has_tls_version_split(baseline_results);

    if !baseline_supports_ech_candidates(baseline_results) {
        let interleaved = interleave_candidate_families(reordered, probe_seed);
        return if bias_toward_split { promote_tls_version_split_families(interleaved) } else { interleaved };
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
    // ECH eligibility takes precedence over the split bias: ECH-eligible specs
    // already lead via `ech_priority`, so the split bias only reorders the
    // non-ECH remainder here.
    let interleaved = interleave_candidate_families(remaining, probe_seed);
    let remainder = if bias_toward_split { promote_tls_version_split_families(interleaved) } else { interleaved };
    ech_priority.extend(remainder);
    ech_priority
}
