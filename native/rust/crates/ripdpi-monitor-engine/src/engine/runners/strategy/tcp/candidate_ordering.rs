use ripdpi_failure_classifier::ClassifiedFailure;

use crate::candidates::StrategyCandidateSpec;
use crate::types::ProbeResult;

use super::super::support::ordered_follow_up_tcp_candidates;
use super::capability_gating::{TcpCapabilities, candidate_not_applicable};

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
        // The batch gate skips these, so they must not take a slot from a runnable candidate.
        pending_tcp_specs.sort_by_key(|spec| candidate_not_applicable(spec, capabilities).is_some());
        pending_tcp_specs.truncate(remaining);
    }
    pending_tcp_specs
}

#[cfg(test)]
mod tests {
    use ripdpi_proxy_config::ProxyUiConfig;
    use ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities;

    use super::*;
    use crate::candidates::build_tcp_candidates;

    #[test]
    fn quick_scan_does_not_spend_its_cap_on_candidates_the_gate_skips() {
        let specs = build_tcp_candidates(&ProxyUiConfig::default());
        let without_root = TcpCapabilities {
            baseline_ech_capable: false,
            fake_ttl_available: true,
            tcp_fast_open_available: false,
            ipfrag_caps: IpFragmentationCapabilities::default(),
        };

        for probe_seed in 0..16 {
            let pending = ordered_pending_tcp_candidates(&specs, &[], None, probe_seed, Some(5), without_root);

            assert_eq!(pending.len(), 4);
            let skipped: Vec<_> = pending
                .iter()
                .filter(|spec| candidate_not_applicable(spec, without_root).is_some())
                .map(|spec| spec.id)
                .collect();
            assert!(skipped.is_empty(), "seed {probe_seed}: {skipped:?}");
        }
    }
}
