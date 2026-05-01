use ripdpi_runtime_platform::RuntimeCapability;

use crate::candidates::StrategyCandidateSpec;
use crate::types::StrategyProbeCandidateSummary;

const ROOTED_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires rooted production emitter tier";
const LAB_EMITTER_ELIGIBILITY_RATIONALE: &str = "Requires lab-only emitter tier";
const GENERIC_EMITTER_ELIGIBILITY_RATIONALE: &str = "Required emitter capability unavailable";

pub(in crate::engine::runners::strategy) fn capability_available(
    capability: RuntimeCapability,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::IpFragmentationCapabilities,
) -> bool {
    match capability {
        RuntimeCapability::TtlWrite => fake_ttl_available,
        RuntimeCapability::RawTcpFakeSend => ipfrag_caps.raw_ipv4,
        RuntimeCapability::RawUdpFragmentation => crate::candidates::supports_udp_ip_fragmentation_for(ipfrag_caps),
        RuntimeCapability::ReplacementSocket | RuntimeCapability::RootHelperAvailable => ipfrag_caps.tcp_repair,
        RuntimeCapability::VpnProtectCallback | RuntimeCapability::NetworkBinding => true,
    }
}

pub(in crate::engine::runners::strategy) fn annotate_emitter_execution(
    summary: &mut StrategyProbeCandidateSummary,
    spec: &StrategyCandidateSpec,
    fake_ttl_available: bool,
    ipfrag_caps: ripdpi_runtime_platform::IpFragmentationCapabilities,
) {
    if spec.exact_emitter_requires_root
        && !ripdpi_runtime_platform::seqovl_supported()
        && spec.approximate_fallback_family.is_some()
    {
        summary.emitter_downgraded = true;
        if let Some(fallback_family) = spec.approximate_fallback_family {
            summary
                .notes
                .push(format!("Exact rooted emitter unavailable; executed approximate {fallback_family} fallback"));
        }
    }
    if summary.exact_emitter_requires_root {
        if let Some(capability) = spec
            .requires_capabilities
            .iter()
            .copied()
            .find(|&capability| !capability_available(capability, fake_ttl_available, ipfrag_caps))
        {
            summary.notes.push(missing_capability_note(spec, capability));
        }
    }
}

fn missing_capability_note(spec: &StrategyCandidateSpec, capability: RuntimeCapability) -> String {
    if spec.exact_emitter_requires_root
        || matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::RootedProduction)
    {
        format!("Requires rooted production emitter tier ({})", capability.as_str())
    } else if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        format!("Lab-only emitter tier unavailable ({})", capability.as_str())
    } else {
        format!("Required emitter capability unavailable ({})", capability.as_str())
    }
}

pub(in crate::engine::runners::strategy) fn missing_capability_rationale(spec: &StrategyCandidateSpec) -> &'static str {
    if spec.exact_emitter_requires_root
        || matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::RootedProduction)
    {
        ROOTED_EMITTER_ELIGIBILITY_RATIONALE
    } else if matches!(spec.emitter_tier, crate::types::StrategyEmitterTier::LabDiagnosticsOnly) {
        LAB_EMITTER_ELIGIBILITY_RATIONALE
    } else {
        GENERIC_EMITTER_ELIGIBILITY_RATIONALE
    }
}

pub(in crate::engine::runners::strategy) fn capability_suffix(capability: RuntimeCapability) -> &'static str {
    match capability {
        RuntimeCapability::TtlWrite => " — ttl_write unavailable",
        RuntimeCapability::RawTcpFakeSend => " — raw_tcp_fake_send unavailable",
        RuntimeCapability::RawUdpFragmentation => " — raw_udp_fragmentation unavailable",
        RuntimeCapability::ReplacementSocket => " — replacement_socket unavailable",
        RuntimeCapability::RootHelperAvailable => " — root_helper_available unavailable",
        RuntimeCapability::VpnProtectCallback => " — vpn_protect_callback unavailable",
        RuntimeCapability::NetworkBinding => " — network_binding unavailable",
    }
}
