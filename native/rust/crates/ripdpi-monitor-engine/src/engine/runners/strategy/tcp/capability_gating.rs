use ripdpi_failure_classifier::ClassifiedFailure;

use crate::candidates::{
    probe_fake_ttl_capability, probe_ip_fragmentation_capabilities, probe_tcp_fast_open_capability,
    CandidateEligibility, StrategyCandidateSpec,
};
use crate::types::ProbeResult;

use super::super::support::{
    baseline_supports_ech_candidates, capability_available, capability_suffix, compute_rst_adaptive_timeout,
    missing_capability_rationale, ECH_ELIGIBILITY_RATIONALE, FAKE_TTL_ELIGIBILITY_RATIONALE,
    TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
};

#[derive(Clone, Copy)]
pub(super) struct TcpCapabilities {
    pub(super) baseline_ech_capable: bool,
    pub(super) fake_ttl_available: bool,
    pub(super) tcp_fast_open_available: bool,
    pub(super) ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
}

#[derive(Clone, Copy)]
pub(super) struct NotApplicableReason {
    pub(super) reason: &'static str,
    pub(super) suffix: &'static str,
}

pub(super) fn probe_tcp_capabilities(
    baseline_results: &[ProbeResult],
    baseline_failure: Option<&ClassifiedFailure>,
) -> TcpCapabilities {
    let capabilities = TcpCapabilities {
        baseline_ech_capable: baseline_supports_ech_candidates(baseline_results),
        fake_ttl_available: probe_fake_ttl_capability(),
        tcp_fast_open_available: probe_tcp_fast_open_capability(),
        ipfrag_caps: probe_ip_fragmentation_capabilities(),
    };
    tracing::info!(
        fake_ttl_available = capabilities.fake_ttl_available,
        tcp_fast_open_available = capabilities.tcp_fast_open_available,
        tcp_repair = capabilities.ipfrag_caps.tcp_repair,
        raw_ipv4 = capabilities.ipfrag_caps.raw_ipv4,
        raw_ipv6 = capabilities.ipfrag_caps.raw_ipv6,
        "strategy probe: capabilities probed"
    );
    if let Some(failure) = baseline_failure {
        if let Some(timeout) = compute_rst_adaptive_timeout(failure) {
            tracing::info!(adaptive_timeout_ms = timeout.as_millis(), "strategy probe: adaptive timeout (rst)");
        }
    }
    capabilities
}

pub(super) fn candidate_passes_pilot_without_execution(
    spec: &StrategyCandidateSpec,
    capabilities: TcpCapabilities,
) -> bool {
    spec.id == "baseline_current" || candidate_not_applicable(spec, capabilities).is_some()
}

pub(super) fn candidate_not_applicable(
    spec: &StrategyCandidateSpec,
    capabilities: TcpCapabilities,
) -> Option<NotApplicableReason> {
    if spec.eligibility == CandidateEligibility::RequiresEchCapability && !capabilities.baseline_ech_capable {
        return Some(NotApplicableReason { reason: ECH_ELIGIBILITY_RATIONALE, suffix: "" });
    }
    if spec.requires_fake_ttl && !capabilities.fake_ttl_available {
        return Some(NotApplicableReason {
            reason: FAKE_TTL_ELIGIBILITY_RATIONALE,
            suffix: " — TTL manipulation unavailable",
        });
    }
    if let Some(capability) = spec.requires_capabilities.iter().copied().find(|&capability| {
        !capability_available(capability, capabilities.fake_ttl_available, capabilities.ipfrag_caps)
    }) {
        return Some(NotApplicableReason {
            reason: missing_capability_rationale(spec),
            suffix: capability_suffix(capability),
        });
    }
    if spec.requires_tcp_fast_open && !capabilities.tcp_fast_open_available {
        return Some(NotApplicableReason {
            reason: TCP_FAST_OPEN_ELIGIBILITY_RATIONALE,
            suffix: " — TCP Fast Open unavailable",
        });
    }
    None
}
