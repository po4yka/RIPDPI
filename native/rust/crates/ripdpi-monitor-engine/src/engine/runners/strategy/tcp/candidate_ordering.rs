use ripdpi_monitor_adapter::failure::ClassifiedFailure;

use crate::candidates::StrategyCandidateSpec;
use crate::types::ProbeResult;

use super::super::support::ordered_follow_up_tcp_candidates;
use super::capability_gating::TcpCapabilities;

pub(super) fn ordered_pending_tcp_candidates(
    tcp_specs: &[StrategyCandidateSpec],
    baseline_results: &[ProbeResult],
    baseline_failure: Option<&ClassifiedFailure>,
    probe_seed: u64,
    max_candidates: Option<usize>,
    capabilities: TcpCapabilities,
) -> Vec<StrategyCandidateSpec> {
    let mut pending_tcp_specs = ordered_follow_up_tcp_candidates(
        tcp_specs,
        baseline_failure.map(|value| value.class),
        baseline_results,
        probe_seed,
        capabilities.fake_ttl_available,
    );
    // Quick scan: truncate candidate list when max_candidates is set.
    if let Some(max) = max_candidates {
        let remaining = max.saturating_sub(1); // baseline already counted
        if pending_tcp_specs.len() > remaining {
            pending_tcp_specs.truncate(remaining);
        }
    }
    pending_tcp_specs
}
