use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::ProxyUiConfig;
use ripdpi_runtime_platform::capability::RuntimeCapability;

use crate::types::{ProbeResult, StrategyEmitterTier};

#[derive(Clone, Debug)]
pub struct StrategyCandidateSpec {
    pub id: &'static str,
    pub label: &'static str,
    pub family: &'static str,
    pub emitter_tier: StrategyEmitterTier,
    pub exact_emitter_requires_root: bool,
    pub approximate_fallback_family: Option<&'static str>,
    pub quic_layout_family: Option<&'static str>,
    pub eligibility: CandidateEligibility,
    pub config: ProxyUiConfig,
    pub notes: Vec<&'static str>,
    pub preserve_adaptive_fake_ttl: bool,
    /// Whether candidate preparation preserves the active strategy semantics
    /// closely enough to attribute its result to that immutable snapshot.
    pub active_snapshot_faithful: bool,
    pub warmup: CandidateWarmup,
    /// When `true`, this candidate requires the ability to set a custom IP TTL
    /// on outgoing sockets (via `setsockopt(IP_TTL)`). On Android VPN/tun mode
    /// this syscall fails, so candidates that depend on TTL manipulation (fake,
    /// fakedsplit, fakeddisorder, hostfake without a follow-up split) must be
    /// skipped when TTL capability is unavailable.
    pub requires_fake_ttl: bool,
    pub requires_tcp_fast_open: bool,
    /// Runtime capabilities that must be available for this candidate to emit
    /// packets as designed. An empty slice means the candidate works on every
    /// platform without special privileges.
    ///
    /// Use [`enumerate_capable_candidates`] to filter a pool against a live
    /// capability lookup before promoting a winner.
    pub requires_capabilities: &'static [RuntimeCapability],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CandidateEligibility {
    Always,
    RequiresEchCapability,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CandidateWarmup {
    None,
    AdaptiveFakeTtl,
}

#[derive(Debug)]
pub struct StrategyProbeSuite {
    pub tcp_candidates: Vec<StrategyCandidateSpec>,
    pub quic_candidates: Vec<StrategyCandidateSpec>,
    pub short_circuit_hostfake: bool,
    pub short_circuit_quic_burst: bool,
    pub family_failure_threshold: usize,
}

pub struct StrategyProbeBaseline {
    /// Classified DNS-tampering failure, or `None` when every evaluated
    /// target resolved cleanly through both system and encrypted DNS.
    pub failure: Option<ClassifiedFailure>,
    pub results: Vec<ProbeResult>,
    /// Per-host encrypted-DNS-resolved IPs for targets where DNS tampering was confirmed.
    pub encrypted_ip_overrides: Vec<(String, std::net::IpAddr)>,
}
