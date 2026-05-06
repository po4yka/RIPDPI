mod engine;
mod execution;
mod platform;
mod probes;
mod session;

use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

pub(crate) use probes::{
    blockpage_fingerprints, candidates, cdn_ech, classification, connectivity, http, observations, strategy, telegram,
    tls, transport, util,
};
pub(crate) use ripdpi_diagnostics_contracts as types;
#[cfg(test)]
pub(crate) use session::validate_scan_request;
pub mod wire {
    pub use ripdpi_diagnostics_contracts::wire::{
        EngineObservationWire, EngineProbeResultWire, EngineProbeTaskFamily, EngineProbeTaskWire, EngineProgressWire,
        EngineScanReportWire, EngineScanRequestWire, ResolverRecommendationWire, DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
    };
}

#[cfg(test)]
mod test_fixtures;
#[cfg(test)]
mod tests;

pub use execution::{CandidateProbeRuntime, CandidateRuntimeLauncher, PreparedCandidateRuntime};
pub use platform::{MonitorPlatformBridge, ScopedMonitorLogLevel};
pub use ripdpi_diagnostics_contracts::{
    CircumventionTarget, Diagnosis, DiagnosticProfileFamily, DnsObservationFact, DnsObservationStatus, DnsTarget,
    DomainObservationFact, DomainTarget, EndpointProbeStatus, EngineObservationWire, EngineProbeResultWire,
    EngineProbeTaskFamily, EngineProbeTaskWire, EngineProgressWire, EngineScanReportWire, EngineScanRequestWire,
    HttpProbeStatus, NativeSessionEvent, ObservationKind, ProbeDetail, ProbeObservation, ProbeResult, ProbeTask,
    ProbeTaskFamily, QuicObservationFact, QuicProbeStatus, QuicTarget, ResolverRecommendationWire, ScanKind,
    ScanPathMode, ScanProgress, ScanReport, ScanRequest, ServiceObservationFact, ServiceTarget,
    StrategyObservationFact, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence,
    StrategyProbeAuditConfidenceLevel, StrategyProbeAuditCoverage, StrategyProbeCandidateSummary,
    StrategyProbeLiveProgress, StrategyProbeProgressLane, StrategyProbeProtocol, StrategyProbeRecommendation,
    StrategyProbeReport, StrategyProbeRequest, StrategyProbeStatus, StrategyProbeTargetSelection, TcpObservationFact,
    TcpProbeStatus, TcpTarget, TelegramDcEndpoint, TelegramObservationFact, TelegramTarget, TelegramTransferStatus,
    TelegramVerdict, ThroughputObservationFact, ThroughputProbeStatus, ThroughputTarget, TlsProbeStatus,
    TransportFailureKind, DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
};
pub use ripdpi_diagnostics_runner::transport::TransportConfig;
pub use session::MonitorSession;

pub fn parse_proxy_config_payload_json(json: &str) -> Result<ProxyConfigPayload, String> {
    parse_proxy_config_json(json).map_err(|err| err.to_string())
}
