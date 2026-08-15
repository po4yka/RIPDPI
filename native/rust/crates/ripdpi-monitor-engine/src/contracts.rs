pub use ripdpi_diagnostics_contracts::{
    CandidateRuntimeCleanupReceipt, CircumventionTarget, ConcurrencyProbeTargetMetadata,
    ConnectionConcurrencyAssessment, ConnectionConcurrencyCellStatus, ConnectionConcurrencyObservationFact,
    ConnectionConcurrencyVerdict, DIAGNOSTICS_ENGINE_SCHEMA_VERSION, Diagnosis, DiagnosticProfileFamily,
    DnsObservationFact, DnsObservationStatus, DnsTarget, DomainObservationFact, DomainTarget, EndpointProbeStatus,
    HttpProbeStatus, NativeSessionEvent, ObservationKind, ProbeDetail, ProbeObservation, ProbeResult, ProbeTask,
    ProbeTaskFamily, QuicObservationFact, QuicProbeStatus, QuicTarget, ScanKind, ScanPathMode, ScanProgress,
    ScanReport, ScanRequest, ServiceObservationFact, ServiceTarget, StrategyObservationFact,
    StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeAuditCoverage, StrategyProbeCandidateSummary, StrategyProbeLiveProgress, StrategyProbeProgressLane,
    StrategyProbeProtocol, StrategyProbeRecommendation, StrategyProbeReport, StrategyProbeRequest, StrategyProbeStatus,
    StrategyProbeTargetSelection, TcpObservationFact, TcpProbeStatus, TcpTarget, TelegramDcEndpoint,
    TelegramObservationFact, TelegramTarget, TelegramTransferStatus, TelegramVerdict, ThroughputObservationFact,
    ThroughputProbeStatus, ThroughputTarget, TlsProbeStatus, TransportFailureKind,
};

#[doc(hidden)]
pub fn connectivity_partial_report_contract_fixture() -> ScanReport {
    crate::engine::connectivity_partial_report_contract_fixture()
}

/// A single recorded step from a connectivity runner's `run_collecting` output,
/// scrubbed of non-deterministic fields.
#[doc(hidden)]
pub use crate::engine::RunnerStepSnapshot;

/// Full behavioral snapshot record for one connectivity runner.
#[doc(hidden)]
pub use crate::engine::RunnerParityRecord;

/// Run all 9 connectivity runners against a deterministic no-network fixture
/// plan and return one [`RunnerParityRecord`] per runner in canonical stage
/// order. Used by the behavioral-parity snapshot integration test.
#[doc(hidden)]
pub fn connectivity_runner_parity_snapshot() -> Vec<RunnerParityRecord> {
    crate::engine::connectivity_runner_parity_snapshot()
}
