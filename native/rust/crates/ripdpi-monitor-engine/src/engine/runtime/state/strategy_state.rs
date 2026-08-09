use ripdpi_monitor_adapter::failure::ClassifiedFailure;

use crate::types::{
    ConnectionConcurrencyAssessment, DomainTarget, QuicTarget, StrategyProbeAttempt, StrategyProbeCandidateSummary,
    StrategyProbeReport,
};

#[derive(Default)]
pub(in crate::engine) struct StrategyExecutionState {
    pub(in crate::engine) baseline_failure: Option<ClassifiedFailure>,
    pub(in crate::engine) tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(in crate::engine) quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(in crate::engine) attempts: Vec<StrategyProbeAttempt>,
    pub(in crate::engine) summary: Option<String>,
    pub(in crate::engine) strategy_probe_report: Option<StrategyProbeReport>,
    pub(in crate::engine) connection_concurrency_assessment: Option<ConnectionConcurrencyAssessment>,
    /// When DNS tampering is detected, holds domain targets with `connect_ip`
    /// set to encrypted-DNS-resolved addresses, bypassing poisoned system DNS.
    pub(in crate::engine) dns_override_domain_targets: Option<Vec<DomainTarget>>,
    pub(in crate::engine) dns_override_quic_targets: Option<Vec<QuicTarget>>,
}
