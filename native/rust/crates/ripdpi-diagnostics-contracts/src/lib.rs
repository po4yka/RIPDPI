#![forbid(unsafe_code)]

pub mod types;
pub mod util;
pub mod wire;

pub use types::{
    CandidateRuntimeCleanupReceipt, CircumventionTarget, ConcurrencyProbeTargetMetadata,
    ConnectionConcurrencyAssessment, ConnectionConcurrencyCellStatus, ConnectionConcurrencyObservationFact,
    ConnectionConcurrencyVerdict, Diagnosis, DiagnosticProfileFamily, DnsObservationFact, DnsObservationStatus,
    DnsTarget, DomainObservationFact, DomainTarget, EndpointProbeStatus, Http3Evidence, Http3ProbeConfig,
    HttpProbeStatus, IpFamilyEvidence, IpFamilyProbeConfig, NativeSessionEvent, ObservationKind, PmtuEvidence,
    PmtuProbeConfig, ProbeDetail, ProbeObservation, ProbeResult, ProbeTask, ProbeTaskFamily, QuicObservationFact,
    QuicProbeStatus, QuicTarget, ScanKind, ScanPathMode, ScanProgress, ScanReport, ScanRequest, SelectiveMatrixConfig,
    SelectiveMatrixTarget, ServiceObservationFact, ServiceTarget, StrategyEmitterTier, StrategyObservationFact,
    StrategyProbeAuditAssessment, StrategyProbeAuditConfidence, StrategyProbeAuditConfidenceLevel,
    StrategyProbeAuditCoverage, StrategyProbeCandidateSummary, StrategyProbeLiveProgress, StrategyProbeProgressLane,
    StrategyProbeProtocol, StrategyProbeRecommendation, StrategyProbeReport, StrategyProbeRequest, StrategyProbeStatus,
    StrategyProbeTargetSelection, TcpObservationFact, TcpProbeStatus, TcpTarget, TelegramDcEndpoint,
    TelegramObservationFact, TelegramTarget, TelegramTransferStatus, TelegramVerdict, ThroughputObservationFact,
    ThroughputProbeStatus, ThroughputTarget, TlsProbeStatus, TransferEvidence, TransferMeasurement, TransferProgress,
    TransferSample, TransportFailureKind,
};

pub use wire::{
    DIAGNOSTICS_ENGINE_SCHEMA_VERSION, EngineObservationWire, EngineProbeResultWire, EngineProbeTaskFamily,
    EngineProbeTaskWire, EngineProgressWire, EngineScanReportWire, EngineScanRequestWire, ResolverRecommendationWire,
};
