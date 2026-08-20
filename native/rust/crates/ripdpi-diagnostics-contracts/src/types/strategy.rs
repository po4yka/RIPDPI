use serde::{Deserialize, Serialize};

use crate::types::TransportPivotRecommendation;

pub const STRATEGY_PROBE_METHODOLOGY_VERSION: &str = "strategy_learning_v3";

pub const CONNECTION_CONCURRENCY_CLASSIFIER_VERSION: &str = "connection_concurrency_v1";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnectionConcurrencyVerdict {
    NoInteraction,
    FingerprintOnly,
    ConcurrencyOnly,
    ConjunctionSuspected,
    ConjunctionConfirmed,
    Inconclusive,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConcurrencyAssessment {
    #[serde(default = "default_connection_concurrency_classifier_version")]
    pub classifier_version: String,
    pub verdict: ConnectionConcurrencyVerdict,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_profile_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub safe_cap: Option<u16>,
    pub planned_cells: usize,
    pub clean_cells: usize,
    pub affected_targets: usize,
    #[serde(default)]
    pub healthy_caps_by_profile: std::collections::BTreeMap<String, u16>,
    #[serde(default)]
    pub warnings: Vec<String>,
}

fn default_connection_concurrency_classifier_version() -> String {
    CONNECTION_CONCURRENCY_CLASSIFIER_VERSION.to_string()
}

fn default_strategy_probe_methodology_version() -> String {
    STRATEGY_PROBE_METHODOLOGY_VERSION.to_string()
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyEmitterTier {
    #[default]
    NonRootProduction,
    RootedProduction,
    LabDiagnosticsOnly,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeTargetSelection {
    pub cohort_id: String,
    pub cohort_label: String,
    #[serde(default)]
    pub domain_hosts: Vec<String>,
    #[serde(default)]
    pub quic_hosts: Vec<String>,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeCompletionKind {
    #[default]
    Normal,
    DnsShortCircuited,
    DnsTamperingWithFallback,
    PartialResults,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeReport {
    pub suite_id: String,
    #[serde(default = "default_strategy_probe_methodology_version")]
    pub methodology_version: String,
    pub tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub quic_candidates: Vec<StrategyProbeCandidateSummary>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recommendation: Option<StrategyProbeRecommendation>,
    #[serde(default)]
    pub completion_kind: StrategyProbeCompletionKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audit_assessment: Option<StrategyProbeAuditAssessment>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_concurrency_assessment: Option<ConnectionConcurrencyAssessment>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_selection: Option<StrategyProbeTargetSelection>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub pilot_bucket_labels: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_path_observation: Option<StrategyActivePathObservation>,
    /// Per-domain strategy seeds derived from probe results.
    /// Each entry records the best candidate for a domain that did NOT succeed
    /// with the baseline candidate. Consumers can pass these to the autolearn
    /// seeding API so each domain starts with its optimal strategy override.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub domain_strategy_seeds: Vec<StrategyDomainSeed>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyActivePathObservation {
    pub role: StrategyProbeObservationRole,
    pub response_stage: StrategyProbeResponseStage,
    pub active_path_authority: StrategyActivePathAuthority,
    pub attempted_targets: usize,
    #[serde(default)]
    pub route_reached_targets: usize,
    pub response_observed_targets: usize,
    #[serde(default)]
    pub successful_targets: usize,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyActivePathAuthority {
    #[default]
    Unverified,
    OwnedRouteLeaseAtScan,
}

/// Records the best strategy probe candidate for a single domain.
/// Used to seed the autolearn table so each domain starts with its
/// optimal strategy override rather than discovering it at runtime.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyDomainSeed {
    pub domain: String,
    pub candidate_id: String,
    pub candidate_label: String,
    pub family: String,
}

/// Per-domain outcome within a single candidate's execution.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeDomainOutcome {
    pub domain: String,
    pub succeeded: bool,
    #[serde(default)]
    pub is_control: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeCandidateSummary {
    pub id: String,
    pub label: String,
    pub family: String,
    #[serde(default)]
    pub emitter_tier: StrategyEmitterTier,
    #[serde(default)]
    pub exact_emitter_requires_root: bool,
    #[serde(default)]
    pub emitter_downgraded: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quic_layout_family: Option<String>,
    pub outcome: String,
    pub rationale: String,
    pub succeeded_targets: usize,
    pub total_targets: usize,
    pub weighted_success_score: usize,
    pub total_weight: usize,
    pub quality_score: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proxy_config_json: Option<String>,
    #[serde(default)]
    pub notes: Vec<String>,
    pub average_latency_ms: Option<u64>,
    pub skipped: bool,
    /// Per-domain outcomes for this candidate. Populated during strategy probe
    /// execution so the recommendation phase can derive per-domain seeds.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub domain_outcomes: Vec<StrategyProbeDomainOutcome>,
    #[serde(default)]
    pub observation_role: StrategyProbeObservationRole,
    /// False when candidate preparation intentionally changed active behavior
    /// (for example by disabling host autolearn to keep the probe read-only).
    #[serde(default)]
    pub active_snapshot_faithful: bool,
    #[serde(default = "default_true")]
    pub desync_execution_required: bool,
    #[serde(default)]
    pub runtime_terminal_status: StrategyProbeRuntimeTerminalStatus,
    #[serde(default)]
    pub execution_evidence_complete: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub execution_attempts: Vec<StrategyProbeAttemptExecutionEvidence>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub route_features: Vec<StrategyProbeRouteFeature>,
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeRouteFeature {
    UpstreamRelay,
    Warp,
    WebSocketTunnel,
    DestinationRouting,
    TcpRotation,
    AdaptiveFallback,
    GroupActivationFilter,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeObservationRole {
    #[default]
    EphemeralCandidateRawPath,
    ActiveServiceInPath,
    Unavailable,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeRuntimeTerminalStatus {
    #[default]
    Unavailable,
    CleanShutdown,
    ForcedAbort,
    RuntimeFailed,
    RuntimePanicked,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAttemptExecutionEvidence {
    pub probe_succeeded: bool,
    pub complete: bool,
    #[serde(default)]
    pub response_stage: StrategyProbeResponseStage,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub receipts: Vec<StrategyDesyncExecutionReceipt>,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeResponseStage {
    #[default]
    Unavailable,
    ResponseObserved,
    ResponseNotObserved,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyDesyncExecutionReceipt {
    #[serde(default)]
    pub connection_ordinal: u16,
    #[serde(default)]
    pub transport: StrategyExecutionTransport,
    pub disposition: StrategyExecutionDisposition,
    pub configured_family: Option<StrategyExecutionFamily>,
    pub effective_family: Option<StrategyExecutionFamily>,
    pub marker_base: Option<StrategyOffsetMarkerBase>,
    pub marker_delta: Option<i16>,
    pub resolved_offset: Option<u16>,
    pub planned_steps: u16,
    pub attempted_actions: u16,
    pub completed_actions: u16,
    pub real_writes_committed: u16,
    pub completed_awaits: u16,
    pub payload_bytes_committed: u16,
    #[serde(default)]
    pub tls_record_prelude_applied: bool,
    #[serde(default)]
    pub tls_prelude_configured_count: u16,
    #[serde(default)]
    pub tls_prelude_applied_count: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_prelude_kind: Option<StrategyTlsPreludeKind>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_prelude_marker_base: Option<StrategyOffsetMarkerBase>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_prelude_marker_delta: Option<i16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls_prelude_resolved_offset: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_ipv6_extension_profile: Option<StrategyUdpIpv6ExtensionProfile>,
    pub fallback_reason: Option<StrategyFallbackReason>,
    pub terminal_reason: Option<StrategyTerminalReason>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyTlsPreludeKind {
    TlsRec,
    TlsRandRec,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyUdpIpv6ExtensionProfile {
    HopByHop,
    HopByHop2,
    DestinationOptions,
    HopByHopDestinationOptions,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyExecutionTransport {
    #[default]
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyExecutionDisposition {
    Applied,
    ActivationSkipped,
    PlanFailedPlainFallback,
    ExecutionFailed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyExecutionFamily {
    Split,
    TlsRecordSplit,
    SeqOverlap,
    TlsRecordSeqOverlap,
    MultiDisorder,
    TlsRecordMultiDisorder,
    Disorder,
    OutOfBand,
    DisorderedOutOfBand,
    Fake,
    FakeSplit,
    FakeDisorder,
    HostFake,
    IpFragment2,
    FakeRst,
    TlsRecord,
    QuicBurst,
    QuicSniSplit,
    QuicFakeVersion,
    QuicCryptoSplit,
    QuicPaddingLadder,
    QuicVersionNegotiationDecoy,
    QuicMultiInitialRealistic,
    QuicDummyPrepend,
    QuicIpFragment2,
    Unknown,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyOffsetMarkerBase {
    Absolute,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    Adaptive,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyFallbackReason {
    AndroidTtlUnavailable,
    StrategyFamilyFallback,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyTerminalReason {
    Transport,
    StrategyExecution,
    Planning,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRecommendation {
    pub tcp_candidate_id: String,
    pub tcp_candidate_label: String,
    pub quic_candidate_id: String,
    pub quic_candidate_label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quic_candidate_layout_family: Option<String>,
    pub rationale: String,
    pub recommended_proxy_config_json: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transport_pivot: Option<TransportPivotRecommendation>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeAuditConfidenceLevel {
    High,
    Medium,
    Low,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditCoverage {
    pub tcp_candidates_planned: usize,
    pub tcp_candidates_executed: usize,
    pub tcp_candidates_skipped: usize,
    pub tcp_candidates_not_applicable: usize,
    pub quic_candidates_planned: usize,
    pub quic_candidates_executed: usize,
    pub quic_candidates_skipped: usize,
    pub quic_candidates_not_applicable: usize,
    pub tcp_winner_succeeded_targets: usize,
    pub tcp_winner_total_targets: usize,
    pub quic_winner_succeeded_targets: usize,
    pub quic_winner_total_targets: usize,
    pub matrix_coverage_percent: usize,
    pub winner_coverage_percent: usize,
    pub tcp_winner_coverage_percent: usize,
    pub quic_winner_coverage_percent: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditConfidence {
    pub level: StrategyProbeAuditConfidenceLevel,
    pub score: usize,
    pub rationale: String,
    #[serde(default)]
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeAuditAssessment {
    pub dns_short_circuited: bool,
    pub coverage: StrategyProbeAuditCoverage,
    pub confidence: StrategyProbeAuditConfidence,
}
