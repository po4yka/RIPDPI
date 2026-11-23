mod suffix;

use ripdpi_runtime_platform::capability::RuntimeCapability;

use crate::candidates::StrategyCandidateSpec;
use crate::types::{StrategyEmitterTier, StrategyProbeCandidateSummary};

pub(in crate::engine::runners::strategy) use suffix::capability_suffix;

#[cfg(feature = "privileged-packets")]
const ROOTED_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires rooted production emitter tier";
const LAB_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires lab-only emitter tier";
const GENERIC_EMITTER_ELIGIBILITY_RATIONALE: &str = "Required emitter capability unavailable";

pub(in crate::engine::runners::strategy) fn capability_available(
    capability: RuntimeCapability,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> bool {
    match capability {
        RuntimeCapability::TtlWrite => fake_ttl_available,
        RuntimeCapability::RawTcpFakeSend => ipfrag_caps.raw_ipv4,
        RuntimeCapability::RawUdpFragmentation => crate::candidates::supports_udp_ip_fragmentation_for(ipfrag_caps),
        RuntimeCapability::ReplacementSocket => ipfrag_caps.tcp_repair,
        #[cfg(feature = "privileged-packets")]
        RuntimeCapability::RootHelperAvailable => ipfrag_caps.tcp_repair,
        RuntimeCapability::VpnProtect | RuntimeCapability::VpnProtectCallback | RuntimeCapability::NetworkBinding => {
            true
        }
        RuntimeCapability::VpnMode => true,
        RuntimeCapability::TcpWindowClamp => true,
        _ => false,
    }
}

pub(in crate::engine::runners::strategy) fn annotate_emitter_execution(
    summary: &mut StrategyProbeCandidateSummary,
    spec: &StrategyCandidateSpec,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) {
    #[cfg(not(feature = "privileged-packets"))]
    let _ = (summary, spec, fake_ttl_available, ipfrag_caps);
    #[cfg(feature = "privileged-packets")]
    if spec.requires_external_emitter
        && !ripdpi_runtime_platform::tcp::seqovl_supported()
        && let Some(fallback_family) = spec.approximate_fallback_family
    {
        summary.emitter_downgraded = true;
        summary
            .notes
            .push(format!("Exact rooted emitter unavailable; executed approximate {fallback_family} fallback"));
    }
    #[cfg(feature = "privileged-packets")]
    if summary.requires_external_emitter
        && let Some(capability) = spec
            .requires_capabilities
            .iter()
            .copied()
            .find(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
    {
        summary.notes.push(missing_capability_note(spec, capability));
    }
}

#[cfg(feature = "privileged-packets")]
fn missing_capability_note(spec: &StrategyCandidateSpec, capability: RuntimeCapability) -> String {
    if spec.requires_external_emitter || spec.emitter_tier == StrategyEmitterTier::RootedProduction {
        format!("Requires rooted production emitter tier ({})", capability.as_str())
    } else if spec.emitter_tier == StrategyEmitterTier::LabDiagnosticsOnly {
        format!("Lab-only emitter tier unavailable ({})", capability.as_str())
    } else {
        format!("Required emitter capability unavailable ({})", capability.as_str())
    }
}

pub(in crate::engine::runners::strategy) fn missing_capability_rationale(spec: &StrategyCandidateSpec) -> &'static str {
    #[cfg(feature = "privileged-packets")]
    if spec.requires_external_emitter || spec.emitter_tier == StrategyEmitterTier::RootedProduction {
        return ROOTED_EMITTER_ELIGIBILITY_RATIONALE;
    }
    if spec.emitter_tier == StrategyEmitterTier::LabDiagnosticsOnly {
        LAB_EMITTER_ELIGIBILITY_RATIONALE
    } else {
        GENERIC_EMITTER_ELIGIBILITY_RATIONALE
    }
}

#[cfg(test)]
mod tests {
    use ripdpi_runtime_platform::capability::RuntimeCapability;

    use super::{capability_available, capability_suffix};

    #[test]
    fn zapret2_strategy_capabilities_are_handled_by_probe_gating() {
        let caps = ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default();

        for capability in [RuntimeCapability::VpnProtect, RuntimeCapability::VpnMode, RuntimeCapability::TcpWindowClamp]
        {
            assert!(
                capability_available(capability, false, caps),
                "{capability:?} should not be filtered before strategy execution"
            );
            assert!(capability_suffix(capability).contains(capability.as_str()));
        }
    }
}
