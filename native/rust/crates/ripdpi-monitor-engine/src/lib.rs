mod engine;
mod execution;
mod platform;
mod probes;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use log::LevelFilter;
use rustls::client::danger::ServerCertVerifier;

use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

use connectivity::set_progress;
use engine::*;
use execution::UnavailableCandidateRuntimeLauncher;
use platform::NoopMonitorPlatformBridge;
use types::SharedState;

pub(crate) use probes::{
    blockpage_fingerprints, candidates, cdn_ech, classification, connectivity, http, observations, strategy, telegram,
    tls, transport, util,
};
pub mod types {
    pub use ripdpi_diagnostics_contracts::*;
}
pub mod wire {
    pub use ripdpi_diagnostics_contracts::wire::*;
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
pub use ripdpi_diagnostics_transport::transport::TransportConfig;

pub struct MonitorSession {
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    platform_bridge: Arc<dyn MonitorPlatformBridge>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
}

impl Default for MonitorSession {
    fn default() -> Self {
        Self::new()
    }
}

impl MonitorSession {
    pub fn new() -> Self {
        Self::with_parts(None, Arc::new(NoopMonitorPlatformBridge), Arc::new(UnavailableCandidateRuntimeLauncher))
    }

    pub fn with_platform_bridge(platform_bridge: Arc<dyn MonitorPlatformBridge>) -> Self {
        Self::with_parts(None, platform_bridge, Arc::new(UnavailableCandidateRuntimeLauncher))
    }

    pub fn with_tls_verifier(tls_verifier: Option<Arc<dyn ServerCertVerifier>>) -> Self {
        Self::with_parts(
            tls_verifier,
            Arc::new(NoopMonitorPlatformBridge),
            Arc::new(UnavailableCandidateRuntimeLauncher),
        )
    }

    pub fn with_candidate_runtime_launcher(candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>) -> Self {
        Self::with_parts(None, Arc::new(NoopMonitorPlatformBridge), candidate_runtime_launcher)
    }

    pub fn with_platform_bridge_and_candidate_runtime_launcher(
        platform_bridge: Arc<dyn MonitorPlatformBridge>,
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    ) -> Self {
        Self::with_parts(None, platform_bridge, candidate_runtime_launcher)
    }

    fn with_parts(
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
        platform_bridge: Arc<dyn MonitorPlatformBridge>,
        candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    ) -> Self {
        Self {
            shared: Arc::new(Mutex::new(SharedState::default())),
            cancel: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            tls_verifier,
            platform_bridge,
            candidate_runtime_launcher,
        }
    }

    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        validate_scan_request(&request)?;
        let native_log_level = request
            .native_log_level
            .as_deref()
            .map(|value| {
                native_log_level_from_str(value)
                    .ok_or_else(|| format!("Unsupported diagnostics nativeLogLevel: {value}"))
            })
            .transpose()?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        Self::join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Release);
        self.platform_bridge.clear_passive_events();
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.log_context = request.log_context.clone();
        }
        let shared = self.shared.clone();
        let cancel = self.cancel.clone();
        let tls_verifier = self.tls_verifier.clone();
        let platform_bridge = self.platform_bridge.clone();
        let candidate_runtime_launcher = self.candidate_runtime_launcher.clone();
        let domain_request: ScanRequest = request.into();
        let shared_panic = shared.clone();
        let session_id_panic = session_id.clone();
        let handle = thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run_scan(
                    shared,
                    cancel,
                    session_id,
                    domain_request,
                    tls_verifier,
                    platform_bridge,
                    candidate_runtime_launcher,
                    native_log_level,
                );
            }));
            if let Err(panic_payload) = result {
                let msg = panic_payload
                    .downcast_ref::<String>()
                    .map(String::as_str)
                    .or_else(|| panic_payload.downcast_ref::<&str>().copied())
                    .unwrap_or("unknown panic");
                set_progress(
                    &shared_panic,
                    ScanProgress {
                        session_id: session_id_panic,
                        phase: "error".to_string(),
                        completed_steps: 1,
                        total_steps: 1,
                        message: format!("Internal error: {msg}"),
                        is_finished: true,
                        latest_probe_target: None,
                        latest_probe_outcome: None,
                        strategy_probe_progress: None,
                    },
                );
            }
        });
        *worker_guard = Some(handle);
        Ok(())
    }

    pub fn cancel_scan(&self) {
        self.cancel.store(true, Ordering::Release);
    }

    pub fn poll_progress_json(&self) -> Result<Option<String>, String> {
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        shared
            .progress
            .as_ref()
            .map(|progress| serde_json::to_string(&EngineProgressWire::from(progress.clone())))
            .transpose()
            .map_err(|err| err.to_string())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        shared
            .report
            .as_ref()
            .map(|report| serde_json::to_string(&EngineScanReportWire::from(report.clone())))
            .transpose()
            .map_err(|err| err.to_string())
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        let events = self.platform_bridge.drain_passive_events();
        serde_json::to_string(&events).map(Some).map_err(|err| err.to_string())
    }

    pub fn destroy(&self) {
        self.cancel_scan();
        self.try_join_worker();
    }

    fn try_join_worker(&self) {
        let Ok(mut worker_guard) = self.worker.lock() else {
            return;
        };
        Self::join_finished_worker_locked(&mut worker_guard);
        if let Some(handle) = worker_guard.take() {
            let _ = handle.join();
        }
    }

    fn join_finished_worker_locked(worker_guard: &mut Option<JoinHandle<()>>) {
        let finished = worker_guard.as_ref().is_some_and(JoinHandle::is_finished);
        if finished {
            let handle = worker_guard.take().expect("finished worker handle must exist");
            let _ = handle.join();
        }
    }
}

fn run_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    platform_bridge: Arc<dyn MonitorPlatformBridge>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
    native_log_level: Option<LevelFilter>,
) {
    let _log_scope =
        native_log_level.map(|level| platform_bridge.scoped_log_level("diagnostics_native".to_string(), level));
    run_engine_scan(shared, cancel, session_id, request, tls_verifier, candidate_runtime_launcher);
}

fn validate_scan_request(request: &EngineScanRequestWire) -> Result<(), String> {
    if request.schema_version != DIAGNOSTICS_ENGINE_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported diagnostics schema {} (expected {})",
            request.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION
        ));
    }
    if request.kind == ScanKind::StrategyProbe {
        let strategy_probe = request
            .strategy_probe
            .as_ref()
            .ok_or_else(|| "strategy_probe scan requires strategyProbe request".to_string())?;
        let Some(config_json) = strategy_probe.base_proxy_config_json.as_deref() else {
            return Err("strategy_probe scan requires baseProxyConfigJson".to_string());
        };
        match parse_proxy_config_json(config_json).map_err(|err| err.to_string())? {
            ProxyConfigPayload::Ui { .. } => {}
            ProxyConfigPayload::CommandLine { .. } => {
                return Err("strategy_probe scans only support UI proxy config".to_string());
            }
        }
    }

    match (request.proxy_host.as_deref(), request.proxy_port) {
        (Some(host), Some(port)) if !host.trim().is_empty() && port > 0 => Ok(()),
        (None, None) if request.path_mode == ScanPathMode::RawPath => Ok(()),
        _ => Err("IN_PATH diagnostics require proxyHost/proxyPort".to_string()),
    }
}

fn native_log_level_from_str(value: &str) -> Option<LevelFilter> {
    match value.trim().to_ascii_lowercase().as_str() {
        "off" => Some(LevelFilter::Off),
        "error" => Some(LevelFilter::Error),
        "warn" | "warning" => Some(LevelFilter::Warn),
        "info" => Some(LevelFilter::Info),
        "debug" => Some(LevelFilter::Debug),
        "trace" => Some(LevelFilter::Trace),
        _ => None,
    }
}

pub fn parse_proxy_config_payload_json(json: &str) -> Result<ProxyConfigPayload, String> {
    parse_proxy_config_json(json).map_err(|err| err.to_string())
}
