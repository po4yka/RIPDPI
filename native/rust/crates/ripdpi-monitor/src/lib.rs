mod candidates;
mod classification;
mod connectivity;
mod dns;
mod engine;
mod execution;
mod fat_header;
mod http;
mod observations;
mod strategy;
mod telegram;
mod tls;
mod transport;
mod types;
mod util;
mod wire;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use android_support::{
    android_log_level_from_str, clear_android_log_scope_level, clear_diagnostics_events, drain_diagnostics_events,
    set_android_log_scope_level, NativeEventRecord,
};
use log::LevelFilter;
use rustls::client::danger::ServerCertVerifier;

use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

use connectivity::set_progress;
use engine::*;
use types::SharedState;

#[cfg(test)]
mod test_fixtures;
#[cfg(test)]
mod tests;

pub use types::{
    CircumventionTarget, Diagnosis, DiagnosticProfileFamily, DnsObservationFact, DnsObservationStatus, DnsTarget,
    DomainObservationFact, DomainTarget, EndpointProbeStatus, HttpProbeStatus, NativeSessionEvent, ObservationKind,
    ProbeDetail, ProbeObservation, ProbeResult, ProbeTask, ProbeTaskFamily, QuicObservationFact, QuicProbeStatus,
    QuicTarget, ScanKind, ScanPathMode, ScanProgress, ScanReport, ScanRequest, ServiceObservationFact, ServiceTarget,
    StrategyObservationFact, StrategyProbeAuditAssessment, StrategyProbeAuditConfidence,
    StrategyProbeAuditConfidenceLevel, StrategyProbeAuditCoverage, StrategyProbeCandidateSummary,
    StrategyProbeLiveProgress, StrategyProbeProgressLane, StrategyProbeProtocol, StrategyProbeRecommendation,
    StrategyProbeReport, StrategyProbeRequest, StrategyProbeStatus, TcpObservationFact, TcpProbeStatus, TcpTarget,
    TelegramDcEndpoint, TelegramObservationFact, TelegramTarget, TelegramTransferStatus, TelegramVerdict,
    ThroughputObservationFact, ThroughputProbeStatus, ThroughputTarget, TlsProbeStatus, TransportFailureKind,
};
pub use wire::{
    EngineObservationWire, EngineProbeResultWire, EngineProbeTaskFamily, EngineProbeTaskWire, EngineProgressWire,
    EngineScanReportWire, EngineScanRequestWire, ResolverRecommendationWire, DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
};

impl From<NativeEventRecord> for NativeSessionEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            subsystem: value.subsystem,
        }
    }
}

pub struct MonitorSession {
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
}

impl Default for MonitorSession {
    fn default() -> Self {
        Self::new()
    }
}

impl MonitorSession {
    pub fn new() -> Self {
        Self {
            shared: Arc::new(Mutex::new(SharedState::default())),
            cancel: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            tls_verifier: None,
        }
    }

    pub fn with_tls_verifier(tls_verifier: Option<Arc<dyn ServerCertVerifier>>) -> Self {
        Self {
            shared: Arc::new(Mutex::new(SharedState::default())),
            cancel: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            tls_verifier,
        }
    }

    pub fn start_scan(&self, session_id: String, request: EngineScanRequestWire) -> Result<(), String> {
        validate_scan_request(&request)?;
        let native_log_level = request
            .native_log_level
            .as_deref()
            .map(|value| {
                android_log_level_from_str(value)
                    .ok_or_else(|| format!("Unsupported diagnostics nativeLogLevel: {value}"))
            })
            .transpose()?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        Self::join_finished_worker_locked(&mut worker_guard);
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Release);
        clear_diagnostics_events();
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.log_context = request.log_context.clone();
        }
        let shared = self.shared.clone();
        let cancel = self.cancel.clone();
        let tls_verifier = self.tls_verifier.clone();
        let domain_request: ScanRequest = request.into();
        let shared_panic = shared.clone();
        let session_id_panic = session_id.clone();
        let handle = thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run_scan(shared, cancel, session_id, domain_request, tls_verifier, native_log_level)
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
        let events: Vec<NativeSessionEvent> = drain_diagnostics_events().into_iter().map(Into::into).collect();
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
    native_log_level: Option<LevelFilter>,
) {
    let _log_scope =
        native_log_level.map(|level| ScopedAndroidLogLevel::new(diagnostics_log_scope(&session_id), level));
    run_engine_scan(shared, cancel, session_id, request, tls_verifier);
}

struct ScopedAndroidLogLevel {
    scope: String,
}

impl ScopedAndroidLogLevel {
    fn new(scope: String, level: LevelFilter) -> Self {
        set_android_log_scope_level(scope.clone(), level);
        Self { scope }
    }
}

impl Drop for ScopedAndroidLogLevel {
    fn drop(&mut self) {
        clear_android_log_scope_level(&self.scope);
    }
}

fn diagnostics_log_scope(session_id: &str) -> String {
    format!("diagnostics:{session_id}")
}

fn validate_scan_request(request: &EngineScanRequestWire) -> Result<(), String> {
    match request.kind {
        ScanKind::Connectivity => Ok(()),
        ScanKind::StrategyProbe => {
            let strategy_probe = request
                .strategy_probe
                .as_ref()
                .ok_or_else(|| "strategy_probe scan requires strategyProbe settings".to_string())?;
            if request.path_mode != ScanPathMode::RawPath {
                return Err("strategy_probe scans require RAW_PATH".to_string());
            }
            let base_config_json = strategy_probe
                .base_proxy_config_json
                .as_deref()
                .filter(|value| !value.trim().is_empty())
                .ok_or_else(|| "strategy_probe scan requires baseProxyConfigJson".to_string())?;
            let payload = parse_proxy_config_json(base_config_json).map_err(|err| err.to_string())?;
            match payload {
                ProxyConfigPayload::Ui { .. } => Ok(()),
                ProxyConfigPayload::CommandLine { .. } => {
                    Err("strategy_probe scans only support UI proxy config".to_string())
                }
            }
        }
    }
}
