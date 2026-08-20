use super::prelude::*;

pub fn candidate_spec(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes_and_eligibility(id, label, family, CandidateEligibility::Always, config, Vec::new())
}

pub fn candidate_spec_with_notes(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes_and_eligibility(id, label, family, CandidateEligibility::Always, config, notes)
}

pub fn candidate_spec_with_notes_and_eligibility(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    eligibility: CandidateEligibility,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    let requires_fake_ttl = config_requires_fake_ttl(&config);
    let requires_tcp_fast_open = config.listen.tcp_fast_open;
    let requires_capabilities = config_requires_capabilities(&config);
    StrategyCandidateSpec {
        id,
        label,
        family,
        emitter_tier: candidate_emitter_tier(id, &config),
        exact_emitter_requires_root: candidate_exact_emitter_requires_root(id),
        approximate_fallback_family: candidate_approximate_fallback_family(id, family),
        quic_layout_family: None,
        eligibility,
        config,
        notes,
        preserve_adaptive_fake_ttl: false,
        active_snapshot_faithful: true,
        warmup: CandidateWarmup::None,
        requires_fake_ttl,
        requires_tcp_fast_open,
        requires_capabilities,
    }
}

pub(super) fn with_quic_layout_family(
    mut spec: StrategyCandidateSpec,
    quic_layout_family: &'static str,
) -> StrategyCandidateSpec {
    spec.quic_layout_family = Some(quic_layout_family);
    spec
}

/// Returns `true` when the config contains at least one TCP step that relies on
/// TTL manipulation to suppress the fake packet at the DPI device. Steps in
/// this set send a segment with a short TTL that expires before it reaches the
/// target server, so it is essential that `setsockopt(IP_TTL)` succeeds.
pub(super) fn config_requires_fake_ttl(config: &ProxyUiConfig) -> bool {
    let step_requires_fake_ttl = |step: &ProxyUiTcpChainStep| {
        matches!(step.kind.as_str(), "fake" | "fakedsplit" | "fakeddisorder" | "hostfake" | "disorder" | "disoob")
    };
    config.chains.tcp_steps.iter().any(step_requires_fake_ttl)
        || config.chains.tcp_rotation.as_ref().is_some_and(|rotation| {
            rotation.candidates.iter().flat_map(|candidate| candidate.tcp_steps.iter()).any(step_requires_fake_ttl)
        })
}

/// Maps a candidate config to the set of [`RuntimeCapability`] entries that
/// must be available for the candidate to emit packets as designed.
///
/// Step-kind → capability mapping:
/// - `fake`, `fakedsplit`, `fakeddisorder`, `hostfake`, `disorder`, `disoob`
///   → [`RuntimeCapability::TtlWrite`] (TTL manipulation to expire fakes before target).
/// - `fakerst` → [`RuntimeCapability::RawTcpFakeSend`] (raw-socket fake RST path).
/// - `seqovl` → [`RuntimeCapability::ReplacementSocket`] (TCP_REPAIR replacement socket path).
/// - `ipfrag2_udp` → [`RuntimeCapability::RawUdpFragmentation`] (raw IP UDP fragmentation path).
/// - `multidisorder` → [`RuntimeCapability::RootHelperAvailable`] (TCP_REPAIR / root).
///
/// Returns a `'static` slice so it can be stored in [`StrategyCandidateSpec`]
/// without allocation.
pub(super) fn config_requires_capabilities(config: &ProxyUiConfig) -> &'static [RuntimeCapability] {
    static TTL_WRITE: &[RuntimeCapability] = &[RuntimeCapability::TtlWrite];
    static RAW_TCP: &[RuntimeCapability] = &[RuntimeCapability::RawTcpFakeSend];
    static RAW_UDP: &[RuntimeCapability] = &[RuntimeCapability::RawUdpFragmentation];
    static REPLACEMENT_SOCKET: &[RuntimeCapability] = &[RuntimeCapability::ReplacementSocket];
    static ROOT_HELPER: &[RuntimeCapability] = &[RuntimeCapability::RootHelperAvailable];

    let all_steps = config.chains.tcp_steps.iter().chain(
        config
            .chains
            .tcp_rotation
            .as_ref()
            .into_iter()
            .flat_map(|r| r.candidates.iter())
            .flat_map(|c| c.tcp_steps.iter()),
    );

    let mut needs_ttl = false;
    let mut needs_raw_tcp = false;
    let mut needs_raw_udp = false;
    let mut needs_replacement_socket = false;
    let mut needs_root = false;

    for step in all_steps {
        match step.kind.as_str() {
            "fake" | "fakedsplit" | "fakeddisorder" | "hostfake" | "disorder" | "disoob" => {
                needs_ttl = true;
            }
            "fakerst" => {
                needs_raw_tcp = true;
            }
            "seqovl" => {
                needs_replacement_socket = true;
            }
            "multidisorder" => {
                needs_root = true;
            }
            _ => {}
        }
    }

    for step in &config.chains.udp_steps {
        if matches!(parse_udp_chain_step_kind(&step.kind), Ok(UdpChainStepKind::IpFrag2Udp)) || step.kind == "ipfrag2" {
            needs_raw_udp = true;
        }
    }

    // Return the most specific static slice. When multiple capabilities are
    // required we conservatively return the highest-privilege one; in practice
    // no single candidate currently needs more than one capability class.
    if needs_root {
        ROOT_HELPER
    } else if needs_replacement_socket {
        REPLACEMENT_SOCKET
    } else if needs_raw_udp {
        RAW_UDP
    } else if needs_raw_tcp {
        RAW_TCP
    } else if needs_ttl {
        TTL_WRITE
    } else {
        &[]
    }
}

/// Filters `candidates` to those whose required capabilities are all available
/// according to `lookup`.
///
/// `lookup` receives a [`RuntimeCapability`] and returns `true` when that
/// capability is confirmed available. Candidates with an empty
/// `requires_capabilities` slice always pass through.
///
/// This function is intentionally a pure filter: it does not perform its own
/// platform probes and does not consult any global cache. The caller supplies
/// the lookup so that real execution (slice 2.4) and tests can each provide the
/// appropriate source.
#[cfg(test)]
pub fn enumerate_capable_candidates(
    candidates: Vec<StrategyCandidateSpec>,
    lookup: &dyn Fn(RuntimeCapability) -> bool,
) -> Vec<StrategyCandidateSpec> {
    candidates.into_iter().filter(|c| c.requires_capabilities.iter().all(|&cap| lookup(cap))).collect()
}
