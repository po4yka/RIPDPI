#![allow(unused_imports)]

pub(super) use ripdpi_config::{EmitterTier, TcpChainStepKind, UdpChainStepKind};
pub(super) use ripdpi_diagnostics_contracts::types::{
    ProbeResult, StrategyEmitterTier, StrategyProbeAuditAssessment, StrategyProbeAuditConfidenceLevel,
    StrategyProbeCandidateSummary, StrategyProbeRecommendation,
};
pub(super) use ripdpi_dns_resolver::EncryptedDnsEndpoint;
pub(super) use ripdpi_failure_classifier::ClassifiedFailure;
pub(super) use ripdpi_proxy_config::{
    ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
    ADAPTIVE_FAKE_TTL_DEFAULT_MIN, ProxyConfigPayload, ProxyEncryptedDnsContext, ProxyRuntimeContext,
    ProxyUiActivationFilter, ProxyUiConfig, ProxyUiNumericRange, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate,
    ProxyUiTcpRotationConfig, ProxyUiUdpChainStep, parse_udp_chain_step_kind,
};
pub(super) use ripdpi_runtime_platform::capability::RuntimeCapability;

pub(super) use crate::dns::{encrypted_dns_protocol, parse_bootstrap_ips};
pub(super) use crate::util::{
    DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL, HTTP_FAKE_PROFILE_CLOUDFLARE_GET,
    STRATEGY_PROBE_SUITE_FULL_MATRIX_V1, STRATEGY_PROBE_SUITE_QUICK_V1, TLS_FAKE_PROFILE_GOOGLE_CHROME,
    TLS_FAKE_PROFILE_GOOGLE_CHROME_HRR, UDP_FAKE_PROFILE_DNS_QUERY, ranged_probe_delay,
};

pub(super) use super::audit_specs::{
    apply_coherent_chrome_fake_profile, build_activation_window_hostfake_spec, build_activation_window_split_spec,
    build_adaptive_fake_ttl_spec, build_circular_tlsrec_split_spec, build_fake_payload_library_spec,
    default_audit_activation_filter,
};
pub(super) use super::config_builders::*;
pub(super) use super::emitter::{
    candidate_approximate_fallback_family, candidate_emitter_tier, candidate_exact_emitter_requires_root,
    tcp_step_kind, udp_step_kind,
};
pub(super) use super::platform::*;
pub(super) use super::quic_pool::{build_full_matrix_quic_candidates, build_quic_candidates};
pub(super) use super::spec::{
    candidate_spec, candidate_spec_with_notes, candidate_spec_with_notes_and_eligibility, config_requires_capabilities,
    config_requires_fake_ttl, with_quic_layout_family,
};
pub(super) use super::step::tcp_step;
pub(super) use super::tcp_pool::{
    build_full_matrix_tcp_candidates, build_opportunistic_candidates, build_primary_candidates,
    build_rooted_candidates, build_tcp_candidates,
};
pub(super) use super::types::{CandidateEligibility, CandidateWarmup, StrategyCandidateSpec, StrategyProbeSuite};
