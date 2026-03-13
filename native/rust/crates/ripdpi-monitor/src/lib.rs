use std::collections::{BTreeSet, HashMap, VecDeque};
use std::fmt::Debug;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ciadpi_config::RuntimeConfig;
use ciadpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_failure_classifier::{
    classify_quic_probe, confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage,
};
use ripdpi_proxy_config::{
    parse_proxy_config_json, runtime_config_from_ui, ProxyConfigPayload, ProxyEncryptedDnsContext,
    ProxyRuntimeContext, ProxyUiActivationFilter, ProxyUiConfig, ProxyUiNumericRange, ProxyUiTcpChainStep,
    ProxyUiUdpChainStep, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
};
use ripdpi_runtime::{runtime, EmbeddedProxyControl};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    StreamOwned,
};
use serde::{Deserialize, Serialize};

const DEFAULT_DNS_SERVER: &str = "8.8.8.8:53";
const DEFAULT_DOH_URL: &str = "https://dns.google/dns-query";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["8.8.8.8", "8.8.4.4"];
const DEFAULT_DOH_HOST: &str = "dns.google";
const DEFAULT_DOH_PORT: u16 = 443;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const IO_TIMEOUT: Duration = Duration::from_millis(1200);
const MAX_HTTP_BYTES: usize = 64 * 1024;
const FAT_HEADER_REQUESTS: usize = 16;
const FAT_HEADER_THRESHOLD_BYTES: usize = 16 * 1024;
const MAX_PASSIVE_EVENTS: usize = 256;
const STRATEGY_PROBE_SUITE_QUICK_V1: &str = "quick_v1";
const STRATEGY_PROBE_SUITE_FULL_MATRIX_V1: &str = "full_matrix_v1";
const HTTP_FAKE_PROFILE_CLOUDFLARE_GET: &str = "cloudflare_get";
const TLS_FAKE_PROFILE_GOOGLE_CHROME: &str = "google_chrome";
const UDP_FAKE_PROFILE_DNS_QUERY: &str = "dns_query";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanPathMode {
    RawPath,
    InPath,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanKind {
    Connectivity,
    StrategyProbe,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainTarget {
    pub host: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default)]
    pub https_port: Option<u16>,
    #[serde(default)]
    pub http_port: Option<u16>,
    #[serde(default = "default_http_path")]
    pub http_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DnsTarget {
    pub domain: String,
    #[serde(default)]
    pub udp_server: Option<String>,
    #[serde(default)]
    pub encrypted_resolver_id: Option<String>,
    #[serde(default)]
    pub encrypted_protocol: Option<String>,
    #[serde(default)]
    pub encrypted_host: Option<String>,
    #[serde(default)]
    pub encrypted_port: Option<u16>,
    #[serde(default)]
    pub encrypted_tls_server_name: Option<String>,
    #[serde(default)]
    pub encrypted_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub encrypted_doh_url: Option<String>,
    #[serde(default)]
    pub encrypted_dnscrypt_provider_name: Option<String>,
    #[serde(default)]
    pub encrypted_dnscrypt_public_key: Option<String>,
    #[serde(default)]
    pub doh_url: Option<String>,
    #[serde(default)]
    pub doh_bootstrap_ips: Vec<String>,
    #[serde(default)]
    pub expected_ips: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TcpTarget {
    pub id: String,
    pub provider: String,
    pub ip: String,
    pub port: u16,
    pub sni: Option<String>,
    pub asn: Option<String>,
    #[serde(default)]
    pub host_header: Option<String>,
    #[serde(default)]
    pub fat_header_requests: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuicTarget {
    pub host: String,
    #[serde(default)]
    pub connect_ip: Option<String>,
    #[serde(default = "default_quic_port")]
    pub port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRequest {
    #[serde(default = "default_strategy_probe_suite")]
    pub suite_id: String,
    #[serde(default)]
    pub base_proxy_config_json: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanRequest {
    pub profile_id: String,
    pub display_name: String,
    pub path_mode: ScanPathMode,
    #[serde(default = "default_scan_kind")]
    pub kind: ScanKind,
    pub proxy_host: Option<String>,
    pub proxy_port: Option<u16>,
    pub domain_targets: Vec<DomainTarget>,
    pub dns_targets: Vec<DnsTarget>,
    pub tcp_targets: Vec<TcpTarget>,
    #[serde(default)]
    pub quic_targets: Vec<QuicTarget>,
    pub whitelist_sni: Vec<String>,
    #[serde(default)]
    pub strategy_probe: Option<StrategyProbeRequest>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    pub is_finished: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeDetail {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeResult {
    pub probe_type: String,
    pub target: String,
    pub outcome: String,
    pub details: Vec<ProbeDetail>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanReport {
    pub session_id: String,
    pub profile_id: String,
    pub path_mode: ScanPathMode,
    pub started_at: u64,
    pub finished_at: u64,
    pub summary: String,
    pub results: Vec<ProbeResult>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_report: Option<StrategyProbeReport>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeReport {
    pub suite_id: String,
    pub tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub recommendation: StrategyProbeRecommendation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeCandidateSummary {
    pub id: String,
    pub label: String,
    pub family: String,
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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeRecommendation {
    pub tcp_candidate_id: String,
    pub tcp_candidate_label: String,
    pub quic_candidate_id: String,
    pub quic_candidate_label: String,
    pub rationale: String,
    pub recommended_proxy_config_json: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSessionEvent {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
}

#[derive(Default)]
struct SharedState {
    progress: Option<ScanProgress>,
    report: Option<ScanReport>,
    passive_events: VecDeque<NativeSessionEvent>,
}

pub struct MonitorSession {
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
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
        }
    }

    pub fn start_scan(&self, session_id: String, request: ScanRequest) -> Result<(), String> {
        validate_scan_request(&request)?;
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Relaxed);
        {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            shared.progress = None;
            shared.report = None;
            shared.passive_events.clear();
        }
        let shared = self.shared.clone();
        let cancel = self.cancel.clone();
        let handle = thread::spawn(move || run_scan(shared, cancel, session_id, request));
        *worker_guard = Some(handle);
        Ok(())
    }

    pub fn cancel_scan(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    pub fn poll_progress_json(&self) -> Result<Option<String>, String> {
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        shared.progress.as_ref().map(serde_json::to_string).transpose().map_err(|err| err.to_string())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        shared.report.as_ref().map(serde_json::to_string).transpose().map_err(|err| err.to_string())
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        let events = {
            let mut shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
            std::mem::take(&mut shared.passive_events)
        };
        serde_json::to_string(&events).map(Some).map_err(|err| err.to_string())
    }

    pub fn destroy(&self) {
        self.cancel_scan();
        self.try_join_worker();
    }

    fn try_join_worker(&self) {
        let mut worker_guard = match self.worker.lock() {
            Ok(guard) => guard,
            Err(_) => return,
        };
        if let Some(handle) = worker_guard.take() {
            let _ = handle.join();
        }
    }
}

#[derive(Clone, Debug)]
enum TransportConfig {
    Direct,
    Socks5 { host: String, port: u16 },
}

#[derive(Clone, Debug)]
enum TargetAddress {
    Host(String),
    Ip(IpAddr),
}

#[derive(Clone, Debug)]
struct HttpResponse {
    status_code: u16,
    reason: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

#[derive(Clone, Debug)]
struct HttpObservation {
    status: String,
    response: Option<HttpResponse>,
    error: Option<String>,
}

#[derive(Clone, Debug)]
struct TlsObservation {
    status: String,
    version: Option<String>,
    error: Option<String>,
    certificate_anomaly: bool,
}

#[derive(Clone, Copy, Debug)]
enum TlsClientProfile {
    Auto,
    Tls12Only,
    Tls13Only,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum FatHeaderStatus {
    Success,
    ThresholdCutoff,
    Reset,
    Timeout,
    ConnectFailed,
    HandshakeFailed,
}

#[derive(Clone, Debug)]
struct FatHeaderObservation {
    status: FatHeaderStatus,
    bytes_sent: usize,
    responses_seen: usize,
    error: Option<String>,
}

#[derive(Debug)]
enum ConnectionStream {
    Plain(TcpStream),
    Tls(Box<StreamOwned<ClientConnection, TcpStream>>),
}

impl Read for ConnectionStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.read(buf),
            Self::Tls(stream) => stream.read(buf),
        }
    }
}

impl Write for ConnectionStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.write(buf),
            Self::Tls(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(stream) => stream.flush(),
            Self::Tls(stream) => stream.flush(),
        }
    }
}

impl ConnectionStream {
    fn shutdown(&mut self) {
        match self {
            Self::Plain(stream) => {
                let _ = stream.shutdown(Shutdown::Both);
            }
            Self::Tls(stream) => {
                let _ = stream.sock.shutdown(Shutdown::Both);
            }
        }
    }
}

#[derive(Clone)]
struct StrategyCandidateSpec {
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
    preserve_adaptive_fake_ttl: bool,
    warmup: CandidateWarmup,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum CandidateWarmup {
    None,
    AdaptiveFakeTtl,
}

#[derive(Debug)]
struct CandidateExecution {
    summary: StrategyProbeCandidateSummary,
    results: Vec<ProbeResult>,
}

#[derive(Default)]
struct CandidateScore {
    results: Vec<ProbeResult>,
    succeeded_targets: usize,
    total_targets: usize,
    weighted_success_score: usize,
    total_weight: usize,
    quality_score: usize,
    latency_sum_ms: u64,
    latency_count: usize,
}

impl CandidateScore {
    fn add(&mut self, sample: ProbeSample) {
        self.results.push(sample.result);
        self.total_targets += 1;
        self.total_weight += sample.weight;
        self.quality_score += sample.quality * sample.weight;
        if sample.success {
            self.succeeded_targets += 1;
            self.weighted_success_score += sample.weight;
            self.latency_sum_ms += sample.latency_ms;
            self.latency_count += 1;
        }
    }

    fn average_latency_ms(&self) -> Option<u64> {
        (self.latency_count > 0).then_some(self.latency_sum_ms / self.latency_count as u64)
    }

    fn is_full_success(&self) -> bool {
        self.total_targets > 0 && self.succeeded_targets == self.total_targets
    }
}

struct ProbeSample {
    result: ProbeResult,
    success: bool,
    weight: usize,
    quality: usize,
    latency_ms: u64,
}

struct TemporaryProxyRuntime {
    addr: SocketAddr,
    control: Arc<EmbeddedProxyControl>,
    handle: Option<JoinHandle<Result<(), String>>>,
}

impl TemporaryProxyRuntime {
    fn start(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> Result<Self, String> {
        let listener = runtime::create_listener(&config).map_err(|err| err.to_string())?;
        let addr = listener.local_addr().map_err(|err| err.to_string())?;
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, runtime_context));
        let worker_control = control.clone();
        let handle = thread::spawn(move || {
            runtime::run_proxy_with_embedded_control(config, listener, worker_control).map_err(|err| err.to_string())
        });
        wait_for_listener(addr)?;
        Ok(Self { addr, control, handle: Some(handle) })
    }

    fn transport(&self) -> TransportConfig {
        TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: self.addr.port() }
    }
}

impl Drop for TemporaryProxyRuntime {
    fn drop(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn default_http_path() -> String {
    "/".to_string()
}

fn default_quic_port() -> u16 {
    443
}

fn default_strategy_probe_suite() -> String {
    STRATEGY_PROBE_SUITE_QUICK_V1.to_string()
}

fn default_scan_kind() -> ScanKind {
    ScanKind::Connectivity
}

fn run_scan(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>, session_id: String, request: ScanRequest) {
    match request.kind {
        ScanKind::Connectivity => run_connectivity_scan(shared, cancel, session_id, request),
        ScanKind::StrategyProbe => run_strategy_probe_scan(shared, cancel, session_id, request),
    }
}

fn validate_scan_request(request: &ScanRequest) -> Result<(), String> {
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

fn run_connectivity_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
) {
    let started_at = now_ms();
    let total_steps = (request.dns_targets.len() + request.domain_targets.len() + request.tcp_targets.len()).max(1);
    let transport = transport_for_request(&request);
    let mut completed_steps = 0usize;
    let mut results = Vec::new();

    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "starting".to_string(),
            completed_steps,
            total_steps,
            message: format!("Preparing {}", request.display_name),
            is_finished: false,
        },
    );
    push_event(
        &shared,
        "engine",
        "info",
        format!(
            "Starting {} in {:?} transport={}",
            request.display_name,
            request.path_mode,
            describe_transport(&transport),
        ),
    );

    for dns_target in &request.dns_targets {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_dns_probe(dns_target, &transport, &request.path_mode);
        push_event(&shared, "dns_integrity", event_level_for_outcome(&probe.outcome), summarize_probe_event(&probe));
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "dns".to_string(),
                completed_steps,
                total_steps,
                message: format!("DNS probe {}", dns_target.domain),
                is_finished: false,
            },
        );
    }

    for domain_target in &request.domain_targets {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_domain_probe(domain_target, &transport);
        push_event(
            &shared,
            "domain_reachability",
            event_level_for_outcome(&probe.outcome),
            summarize_probe_event(&probe),
        );
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "reachability".to_string(),
                completed_steps,
                total_steps,
                message: format!("Reachability {}", domain_target.host),
                is_finished: false,
            },
        );
    }

    for tcp_target in &request.tcp_targets {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_tcp_probe(tcp_target, &request.whitelist_sni, &transport);
        push_event(&shared, "tcp_fat_header", event_level_for_outcome(&probe.outcome), summarize_probe_event(&probe));
        results.push(probe);
        completed_steps += 1;
        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "tcp".to_string(),
                completed_steps,
                total_steps,
                message: format!("TCP {}", tcp_target.provider),
                is_finished: false,
            },
        );
    }

    let success_count = results.iter().filter(|result| probe_is_success(&result.outcome)).count();
    let summary = format!("{success_count}/{} probes succeeded", results.len());
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary,
        results,
        strategy_probe_report: None,
    };

    set_report(&shared, report);
    push_event(&shared, "engine", "info", "Diagnostics finished".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "finished".to_string(),
            completed_steps: total_steps,
            total_steps,
            message: "Diagnostics finished".to_string(),
            is_finished: true,
        },
    );
}

fn persist_cancelled_report(
    shared: Arc<Mutex<SharedState>>,
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    results: Vec<ProbeResult>,
) {
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary: "Scan cancelled".to_string(),
        results,
        strategy_probe_report: None,
    };
    set_report(&shared, report);
    push_event(&shared, "engine", "warn", "Diagnostics cancelled".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "cancelled".to_string(),
            completed_steps: 0,
            total_steps: 0,
            message: "Diagnostics cancelled".to_string(),
            is_finished: true,
        },
    );
}

fn run_strategy_probe_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
) {
    let started_at = now_ms();
    let strategy_probe = request.strategy_probe.clone().expect("validated strategy_probe request");
    let mut results = Vec::new();

    let Some(base_proxy_config_json) = strategy_probe.base_proxy_config_json.as_deref() else {
        let report = ScanReport {
            session_id,
            profile_id: request.profile_id,
            path_mode: request.path_mode,
            started_at,
            finished_at: now_ms(),
            summary: "Automatic probing could not start".to_string(),
            results,
            strategy_probe_report: None,
        };
        set_report(&shared, report);
        return;
    };

    let (base_payload, runtime_context) = match parse_proxy_config_json(base_proxy_config_json).map_err(|err| err.to_string()) {
        Ok(ProxyConfigPayload::Ui { config, runtime_context }) => (config, runtime_context),
        Ok(ProxyConfigPayload::CommandLine { .. }) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: "Automatic probing requires UI-configured RIPDPI settings".to_string(),
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
        Err(message) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: message,
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
    };

    let suite = match build_strategy_probe_suite(&strategy_probe.suite_id, &base_payload) {
        Ok(suite) => suite,
        Err(message) => {
            let report = ScanReport {
                session_id,
                profile_id: request.profile_id,
                path_mode: request.path_mode,
                started_at,
                finished_at: now_ms(),
                summary: message,
                results,
                strategy_probe_report: None,
            };
            set_report(&shared, report);
            return;
        }
    };
    let total_steps = suite.total_steps();
    let mut completed_steps = 0usize;

    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "starting".to_string(),
            completed_steps,
            total_steps,
            message: format!("Preparing {}", request.display_name),
            is_finished: false,
        },
    );
    push_event(
        &shared,
        "strategy_probe",
        "info",
        format!("Starting {} suite={} in {:?}", request.display_name, strategy_probe.suite_id, request.path_mode),
    );

    let StrategyProbeSuite {
        suite_id,
        tcp_candidates: tcp_specs,
        quic_candidates: preview_quic_specs,
        short_circuit_hostfake,
        short_circuit_quic_burst,
    } = suite;

    if let Some(baseline) = detect_strategy_probe_dns_tampering(&request.domain_targets, runtime_context.as_ref()) {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                baseline.failure.class.as_str(),
                baseline.failure.action.as_str(),
            ),
        );
        results.extend(baseline.results);
        results.push(classified_failure_probe_result("Current strategy", &baseline.failure));

        let tcp_candidates = tcp_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    request.domain_targets.len() * 2,
                    3,
                    "DNS tampering detected before fallback; TCP strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let quic_specs =
            filter_quic_candidates_for_failure(preview_quic_specs.clone(), Some(FailureClass::QuicBreakage));
        let quic_candidates = quic_specs
            .iter()
            .map(|spec| {
                skipped_candidate_summary(
                    spec,
                    request.quic_targets.len(),
                    2,
                    "DNS tampering detected before fallback; QUIC strategy escalation skipped",
                )
            })
            .collect::<Vec<_>>();
        let fallback_quic = quic_specs.first().or_else(|| preview_quic_specs.first()).expect("quic candidate");
        let recommendation = StrategyProbeRecommendation {
            tcp_candidate_id: tcp_specs.first().expect("tcp candidate").id.to_string(),
            tcp_candidate_label: tcp_specs.first().expect("tcp candidate").label.to_string(),
            quic_candidate_id: fallback_quic.id.to_string(),
            quic_candidate_label: fallback_quic.label.to_string(),
            rationale: format!(
                "{} classified before fallback; keep current strategy and prefer resolver override",
                baseline.failure.class.as_str(),
            ),
            recommended_proxy_config_json: strategy_probe_config_json(&base_payload),
        };
        let strategy_probe_report = StrategyProbeReport {
            suite_id: strategy_probe.suite_id,
            tcp_candidates,
            quic_candidates,
            recommendation: recommendation.clone(),
        };
        let report = ScanReport {
            session_id: session_id.clone(),
            profile_id: request.profile_id,
            path_mode: request.path_mode,
            started_at,
            finished_at: now_ms(),
            summary: "DNS tampering classified before fallback; resolver override recommended".to_string(),
            results,
            strategy_probe_report: Some(strategy_probe_report),
        };
        set_report(&shared, report);
        set_progress(
            &shared,
            ScanProgress {
                session_id,
                phase: "finished".to_string(),
                completed_steps: total_steps,
                total_steps,
                message: "Automatic probing finished".to_string(),
                is_finished: true,
            },
        );
        return;
    }

    let mut tcp_candidates = Vec::new();
    let mut hostfake_family_succeeded = false;
    let baseline_spec = tcp_specs.first().expect("tcp candidate");
    set_progress(
        &shared,
        ScanProgress {
            session_id: session_id.clone(),
            phase: "tcp".to_string(),
            completed_steps,
            total_steps,
            message: format!("Testing {}", baseline_spec.label),
            is_finished: false,
        },
    );
    push_event(
        &shared,
        "strategy_probe",
        "info",
        format!("Testing TCP candidate {}", baseline_spec.label),
    );
    let baseline_execution = execute_tcp_candidate(baseline_spec, &request.domain_targets, runtime_context.as_ref());
    let baseline_failure = classify_strategy_probe_baseline_results(&baseline_execution.results);
    results.extend(baseline_execution.results);
    if let Some(failure) = &baseline_failure {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                failure.class.as_str(),
                failure.action.as_str(),
            ),
        );
        results.push(classified_failure_probe_result(baseline_spec.label, failure));
    }
    if baseline_execution.summary.family == "hostfake"
        && baseline_execution.summary.succeeded_targets == baseline_execution.summary.total_targets
    {
        hostfake_family_succeeded = true;
    }
    tcp_candidates.push(baseline_execution.summary);
    completed_steps += 1;

    let ordered_tcp_specs = reorder_tcp_candidates_for_failure(&tcp_specs, baseline_failure.as_ref().map(|value| value.class));
    for spec in ordered_tcp_specs.iter().skip(1) {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }

        if short_circuit_hostfake && spec.family == "hostfake" && hostfake_family_succeeded {
            tcp_candidates.push(skipped_candidate_summary(
                spec,
                request.domain_targets.len() * 2,
                6,
                "Earlier hostfake candidate already achieved full success",
            ));
            completed_steps += 1;
            set_progress(
                &shared,
                ScanProgress {
                    session_id: session_id.clone(),
                    phase: "tcp".to_string(),
                    completed_steps,
                    total_steps,
                    message: format!("Skipping {}", spec.label),
                    is_finished: false,
                },
            );
            continue;
        }

        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "tcp".to_string(),
                completed_steps,
                total_steps,
                message: format!("Testing {}", spec.label),
                is_finished: false,
            },
        );
        push_event(&shared, "strategy_probe", "info", format!("Testing TCP candidate {}", spec.label));
        let execution = execute_tcp_candidate(spec, &request.domain_targets, runtime_context.as_ref());
        results.extend(execution.results);
        if execution.summary.family == "hostfake"
            && execution.summary.succeeded_targets == execution.summary.total_targets
        {
            hostfake_family_succeeded = true;
        }
        tcp_candidates.push(execution.summary);
        completed_steps += 1;
    }

    let winning_tcp = winning_candidate_index(&tcp_candidates).unwrap_or(0);
    let tcp_winner_spec = tcp_specs
        .iter()
        .find(|spec| spec.id == tcp_candidates[winning_tcp].id)
        .unwrap_or_else(|| tcp_specs.first().expect("tcp candidates"));

    let mut quic_candidates = Vec::new();
    let mut quic_family_succeeded = false;
    let quic_specs = filter_quic_candidates_for_failure(
        build_quic_candidates_for_suite(suite_id, &tcp_winner_spec.config).unwrap_or_else(|_| preview_quic_specs.clone()),
        baseline_failure.as_ref().map(|value| value.class),
    );
    for spec in &quic_specs {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }

        if short_circuit_quic_burst && spec.family == "quic_burst" && quic_family_succeeded {
            quic_candidates.push(skipped_candidate_summary(
                spec,
                request.quic_targets.len(),
                2,
                "Earlier QUIC burst candidate already achieved full success",
            ));
            completed_steps += 1;
            set_progress(
                &shared,
                ScanProgress {
                    session_id: session_id.clone(),
                    phase: "quic".to_string(),
                    completed_steps,
                    total_steps,
                    message: format!("Skipping {}", spec.label),
                    is_finished: false,
                },
            );
            continue;
        }

        set_progress(
            &shared,
            ScanProgress {
                session_id: session_id.clone(),
                phase: "quic".to_string(),
                completed_steps,
                total_steps,
                message: format!("Testing {}", spec.label),
                is_finished: false,
            },
        );
        push_event(&shared, "strategy_probe", "info", format!("Testing QUIC candidate {}", spec.label));
        let execution = execute_quic_candidate(spec, &request.quic_targets, runtime_context.as_ref());
        results.extend(execution.results);
        if execution.summary.family == "quic_burst"
            && execution.summary.succeeded_targets == execution.summary.total_targets
            && execution.summary.total_targets > 0
        {
            quic_family_succeeded = true;
        }
        quic_candidates.push(execution.summary);
        completed_steps += 1;
    }

    if let Some(quic_failure) = classify_strategy_probe_baseline_results(
        &results
            .iter()
            .filter(|result| result.probe_type == "strategy_quic")
            .cloned()
            .collect::<Vec<_>>(),
    )
    .filter(|failure| failure.class == FailureClass::QuicBreakage)
    {
        push_event(
            &shared,
            "strategy_probe",
            "warn",
            format!(
                "QUIC classified as {} with {}",
                quic_failure.class.as_str(),
                quic_failure.action.as_str(),
            ),
        );
        results.push(classified_failure_probe_result("QUIC strategy family", &quic_failure));
    }

    let winning_quic = winning_candidate_index(&quic_candidates).unwrap_or(0);
    let quic_winner_spec = quic_specs
        .iter()
        .find(|spec| spec.id == quic_candidates[winning_quic].id)
        .unwrap_or_else(|| quic_specs.first().expect("quic candidates"));
    let recommended_proxy_config_json = strategy_probe_config_json(&quic_winner_spec.config);
    let recommendation = StrategyProbeRecommendation {
        tcp_candidate_id: tcp_candidates[winning_tcp].id.clone(),
        tcp_candidate_label: tcp_candidates[winning_tcp].label.clone(),
        quic_candidate_id: quic_candidates[winning_quic].id.clone(),
        quic_candidate_label: quic_candidates[winning_quic].label.clone(),
        rationale: format!(
            "{} with {} weighted TCP success and {} weighted QUIC success",
            tcp_candidates[winning_tcp].label,
            tcp_candidates[winning_tcp].weighted_success_score,
            quic_candidates[winning_quic].weighted_success_score,
        ),
        recommended_proxy_config_json,
    };
    let summary = build_strategy_probe_summary(suite_id, &tcp_candidates, &quic_candidates, &recommendation);
    let strategy_probe_report = StrategyProbeReport {
        suite_id: strategy_probe.suite_id,
        tcp_candidates,
        quic_candidates,
        recommendation: recommendation.clone(),
    };
    let report = ScanReport {
        session_id: session_id.clone(),
        profile_id: request.profile_id,
        path_mode: request.path_mode,
        started_at,
        finished_at: now_ms(),
        summary,
        results,
        strategy_probe_report: Some(strategy_probe_report),
    };

    set_report(&shared, report);
    push_event(&shared, "strategy_probe", "info", "Automatic probing finished".to_string());
    set_progress(
        &shared,
        ScanProgress {
            session_id,
            phase: "finished".to_string(),
            completed_steps: total_steps,
            total_steps,
            message: "Automatic probing finished".to_string(),
            is_finished: true,
        },
    );
}

struct StrategyProbeSuite {
    suite_id: &'static str,
    tcp_candidates: Vec<StrategyCandidateSpec>,
    quic_candidates: Vec<StrategyCandidateSpec>,
    short_circuit_hostfake: bool,
    short_circuit_quic_burst: bool,
}

impl StrategyProbeSuite {
    fn total_steps(&self) -> usize {
        self.tcp_candidates.len() + self.quic_candidates.len()
    }
}

struct StrategyProbeBaseline {
    failure: ClassifiedFailure,
    results: Vec<ProbeResult>,
}

fn strategy_probe_config_json(config: &ProxyUiConfig) -> String {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        config: config.clone(),
        runtime_context: None,
    })
    .expect("serialize ui proxy config")
}

fn default_runtime_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("google".to_string()),
        protocol: "doh".to_string(),
        host: DEFAULT_DOH_HOST.to_string(),
        port: DEFAULT_DOH_PORT,
        tls_server_name: Some(DEFAULT_DOH_HOST.to_string()),
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

fn strategy_probe_encrypted_dns_context(runtime_context: Option<&ProxyRuntimeContext>) -> ProxyEncryptedDnsContext {
    runtime_context
        .and_then(|value| value.encrypted_dns.clone())
        .unwrap_or_else(default_runtime_encrypted_dns_context)
}

fn strategy_probe_encrypted_dns_endpoint(context: &ProxyEncryptedDnsContext) -> Result<EncryptedDnsEndpoint, String> {
    Ok(EncryptedDnsEndpoint {
        protocol: encrypted_dns_protocol(Some(context.protocol.as_str())),
        resolver_id: context.resolver_id.clone(),
        host: context.host.clone(),
        port: context.port,
        tls_server_name: context.tls_server_name.clone(),
        bootstrap_ips: parse_bootstrap_ips(&context.bootstrap_ips)?,
        doh_url: context.doh_url.clone(),
        dnscrypt_provider_name: context.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: context.dnscrypt_public_key.clone(),
    })
}

fn strategy_probe_encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}

fn detect_strategy_probe_dns_tampering(
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Option<StrategyProbeBaseline> {
    if targets.is_empty() {
        return None;
    }

    let resolver_context = strategy_probe_encrypted_dns_context(runtime_context);
    let resolver_endpoint = strategy_probe_encrypted_dns_endpoint(&resolver_context).ok()?;
    let resolver_label = strategy_probe_encrypted_dns_label(&resolver_context);
    let mut results = Vec::new();
    let mut classified = None;

    for target in targets {
        if target.host.parse::<IpAddr>().is_ok() || target.host.eq_ignore_ascii_case("localhost") {
            continue;
        }
        let system_started = std::time::Instant::now();
        let system_targets = match domain_connect_target(target) {
            TargetAddress::Ip(ip) => vec![SocketAddr::new(ip, target.https_port.unwrap_or(443))],
            TargetAddress::Host(host) => {
                resolve_addresses(&TargetAddress::Host(host), target.https_port.unwrap_or(443)).unwrap_or_default()
            }
        };
        let system_latency_ms = system_started.elapsed().as_millis().to_string();
        if system_targets.is_empty() {
            continue;
        }

        let encrypted_started = std::time::Instant::now();
        let encrypted_result = resolve_via_encrypted_dns(&target.host, resolver_endpoint.clone(), &TransportConfig::Direct);
        let encrypted_latency_ms = encrypted_started.elapsed().as_millis().to_string();
        let encrypted_addresses = encrypted_result
            .as_ref()
            .ok()
            .into_iter()
            .flat_map(|value| value.iter())
            .cloned()
            .collect::<Vec<_>>();
        let encrypted_ips = encrypted_addresses
            .iter()
            .filter_map(|value| value.parse::<IpAddr>().ok())
            .collect::<Vec<_>>();
        let system_ips = system_targets.iter().map(SocketAddr::ip).collect::<Vec<_>>();
        let substitution = system_ips.iter().all(|ip| !encrypted_ips.iter().any(|answer| answer == ip));
        let outcome = if substitution { "dns_substitution" } else { "dns_match" };
        results.push(ProbeResult {
            probe_type: "dns_integrity".to_string(),
            target: target.host.clone(),
            outcome: outcome.to_string(),
            details: vec![
                ProbeDetail {
                    key: "udpAddresses".to_string(),
                    value: system_targets.iter().map(ToString::to_string).collect::<Vec<_>>().join("|"),
                },
                ProbeDetail { key: "udpLatencyMs".to_string(), value: system_latency_ms },
                ProbeDetail {
                    key: "encryptedResolverId".to_string(),
                    value: resolver_context.resolver_id.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedProtocol".to_string(),
                    value: resolver_context.protocol.clone(),
                },
                ProbeDetail {
                    key: "encryptedEndpoint".to_string(),
                    value: resolver_label.clone(),
                },
                ProbeDetail {
                    key: "encryptedHost".to_string(),
                    value: resolver_context.host.clone(),
                },
                ProbeDetail {
                    key: "encryptedPort".to_string(),
                    value: resolver_context.port.to_string(),
                },
                ProbeDetail {
                    key: "encryptedTlsServerName".to_string(),
                    value: resolver_context.tls_server_name.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedBootstrapIps".to_string(),
                    value: resolver_context.bootstrap_ips.join("|"),
                },
                ProbeDetail {
                    key: "encryptedBootstrapValidated".to_string(),
                    value: (!encrypted_addresses.is_empty() && !resolver_context.bootstrap_ips.is_empty()).to_string(),
                },
                ProbeDetail {
                    key: "encryptedDohUrl".to_string(),
                    value: resolver_context.doh_url.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedDnscryptProviderName".to_string(),
                    value: resolver_context.dnscrypt_provider_name.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedDnscryptPublicKey".to_string(),
                    value: resolver_context.dnscrypt_public_key.clone().unwrap_or_default(),
                },
                ProbeDetail {
                    key: "encryptedAddresses".to_string(),
                    value: if encrypted_addresses.is_empty() {
                        encrypted_result.as_ref().err().cloned().unwrap_or_else(|| "[]".to_string())
                    } else {
                        encrypted_addresses.join("|")
                    },
                },
                ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms },
            ],
        });
        if substitution && classified.is_none() {
            for system_ip in &system_ips {
                if let Some(failure) = confirm_dns_tampering(&target.host, *system_ip, &encrypted_ips, &resolver_label) {
                    classified = Some(failure);
                    break;
                }
            }
        }
    }

    classified.map(|failure| StrategyProbeBaseline { failure, results })
}

fn failure_detail_value<'a>(result: &'a ProbeResult, key: &str) -> Option<&'a str> {
    result.details.iter().find_map(|detail| (detail.key == key).then_some(detail.value.as_str()))
}

fn classify_transport_failure_text(text: &str, stage: FailureStage) -> Option<ClassifiedFailure> {
    let normalized = text.trim().to_ascii_lowercase();
    if normalized.is_empty() || normalized == "none" {
        return None;
    }
    if normalized.contains("alert") {
        return Some(ClassifiedFailure::new(
            FailureClass::TlsAlert,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("reset")
        || normalized.contains("broken pipe")
        || normalized.contains("aborted")
        || normalized.contains("unexpected eof")
    {
        return Some(ClassifiedFailure::new(
            FailureClass::TcpReset,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    if normalized.contains("timed out") || normalized.contains("timeout") || normalized.contains("would block") {
        return Some(ClassifiedFailure::new(
            FailureClass::SilentDrop,
            stage,
            FailureAction::RetryWithMatchingGroup,
            text,
        ));
    }
    None
}

fn classify_strategy_probe_result(result: &ProbeResult) -> Option<ClassifiedFailure> {
    match (result.probe_type.as_str(), result.outcome.as_str()) {
        ("strategy_http", "http_blockpage") => Some(ClassifiedFailure::new(
            FailureClass::HttpBlockpage,
            FailureStage::HttpResponse,
            FailureAction::RetryWithMatchingGroup,
            "HTTP blockpage observed during baseline candidate",
        )),
        ("strategy_http", "http_unreachable") => failure_detail_value(result, "error")
            .and_then(|value| classify_transport_failure_text(value, FailureStage::FirstResponse)),
        ("strategy_https", "tls_handshake_failed") => {
            let error = failure_detail_value(result, "tlsError").unwrap_or("tls_handshake_failed");
            Some(
                classify_transport_failure_text(error, FailureStage::TlsHandshake).unwrap_or_else(|| {
                    ClassifiedFailure::new(
                        FailureClass::TlsHandshakeFailure,
                        FailureStage::TlsHandshake,
                        FailureAction::RetryWithMatchingGroup,
                        error,
                    )
                }),
            )
        }
        ("strategy_quic", outcome) => {
            let error = failure_detail_value(result, "error").filter(|value| *value != "none");
            classify_quic_probe(outcome, error)
        }
        _ => None,
    }
}

fn strategy_probe_failure_weight(result: &ProbeResult) -> usize {
    match result.probe_type.as_str() {
        "strategy_https" | "strategy_quic" => 2,
        _ => 1,
    }
}

fn strategy_probe_failure_priority(class: FailureClass) -> usize {
    match class {
        FailureClass::HttpBlockpage => 5,
        FailureClass::TcpReset => 4,
        FailureClass::SilentDrop => 3,
        FailureClass::TlsAlert => 2,
        FailureClass::TlsHandshakeFailure => 1,
        FailureClass::QuicBreakage => 1,
        _ => 0,
    }
}

fn classify_strategy_probe_baseline_results(results: &[ProbeResult]) -> Option<ClassifiedFailure> {
    let mut aggregated = Vec::<(FailureClass, usize, ClassifiedFailure)>::new();
    for result in results {
        let Some(failure) = classify_strategy_probe_result(result) else {
            continue;
        };
        let weight = strategy_probe_failure_weight(result);
        if let Some(entry) = aggregated.iter_mut().find(|entry| entry.0 == failure.class) {
            entry.1 += weight;
        } else {
            aggregated.push((failure.class, weight, failure));
        }
    }
    aggregated
        .into_iter()
        .max_by_key(|(class, weight, _)| (*weight, strategy_probe_failure_priority(*class)))
        .map(|(_, _, failure)| failure)
}

fn classified_failure_probe_result(target: &str, failure: &ClassifiedFailure) -> ProbeResult {
    let evidence =
        std::iter::once(failure.evidence.summary.as_str())
            .chain(failure.evidence.tags.iter().map(String::as_str))
            .collect::<Vec<_>>()
            .join(" | ");
    ProbeResult {
        probe_type: "strategy_failure_classification".to_string(),
        target: target.to_string(),
        outcome: failure.class.as_str().to_string(),
        details: vec![
            ProbeDetail { key: "failureClass".to_string(), value: failure.class.as_str().to_string() },
            ProbeDetail { key: "failureStage".to_string(), value: failure.stage.as_str().to_string() },
            ProbeDetail { key: "failureEvidence".to_string(), value: evidence },
            ProbeDetail { key: "fallbackDecision".to_string(), value: failure.action.as_str().to_string() },
        ],
    }
}

fn reorder_tcp_candidates_for_failure(
    candidates: &[StrategyCandidateSpec],
    failure_class: Option<FailureClass>,
) -> Vec<StrategyCandidateSpec> {
    let preferred_ids: &[&str] = match failure_class {
        Some(FailureClass::HttpBlockpage) => &["baseline_current", "parser_only", "parser_unixeol", "split_host"],
        Some(FailureClass::TcpReset) => &["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake_split"],
        Some(FailureClass::SilentDrop) => &["baseline_current", "tlsrec_fake_rich", "tlsrec_hostfake", "tlsrec_hostfake_split"],
        Some(FailureClass::TlsAlert) => &["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake"],
        _ => &[],
    };
    let mut ordered = Vec::with_capacity(candidates.len());
    for id in preferred_ids {
        if let Some(candidate) = candidates.iter().find(|candidate| candidate.id == *id) {
            ordered.push(candidate.clone());
        }
    }
    for candidate in candidates {
        if !ordered.iter().any(|existing| existing.id == candidate.id) {
            ordered.push(candidate.clone());
        }
    }
    ordered
}

fn filter_quic_candidates_for_failure(
    candidates: Vec<StrategyCandidateSpec>,
    failure_class: Option<FailureClass>,
) -> Vec<StrategyCandidateSpec> {
    if !matches!(failure_class, Some(FailureClass::QuicBreakage)) {
        return candidates;
    }
    let allowed = ["quic_disabled", "quic_compat_burst", "quic_realistic_burst"];
    candidates
        .into_iter()
        .filter(|candidate| allowed.iter().any(|id| candidate.id == *id))
        .collect()
}

fn build_strategy_probe_suite(suite_id: &str, base: &ProxyUiConfig) -> Result<StrategyProbeSuite, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 => Ok(StrategyProbeSuite {
            suite_id: STRATEGY_PROBE_SUITE_QUICK_V1,
            tcp_candidates: build_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: true,
            short_circuit_quic_burst: true,
        }),
        STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(StrategyProbeSuite {
            suite_id: STRATEGY_PROBE_SUITE_FULL_MATRIX_V1,
            tcp_candidates: build_full_matrix_tcp_candidates(base),
            quic_candidates: build_quic_candidates(base),
            short_circuit_hostfake: false,
            short_circuit_quic_burst: false,
        }),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}

fn build_quic_candidates_for_suite(
    suite_id: &str,
    base_tcp: &ProxyUiConfig,
) -> Result<Vec<StrategyCandidateSpec>, String> {
    match suite_id {
        STRATEGY_PROBE_SUITE_QUICK_V1 | STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 => Ok(build_quic_candidates(base_tcp)),
        _ => Err(format!("Unsupported automatic probing suite: {suite_id}")),
    }
}

fn build_strategy_probe_summary(
    suite_id: &str,
    tcp_candidates: &[StrategyProbeCandidateSummary],
    quic_candidates: &[StrategyProbeCandidateSummary],
    recommendation: &StrategyProbeRecommendation,
) -> String {
    if suite_id != STRATEGY_PROBE_SUITE_FULL_MATRIX_V1 {
        return format!(
            "Recommended {} with {}",
            recommendation.tcp_candidate_label, recommendation.quic_candidate_label
        );
    }
    let mut worked = 0usize;
    let mut partial = 0usize;
    let mut failed = 0usize;
    let mut not_applicable = 0usize;
    for candidate in tcp_candidates.iter().chain(quic_candidates.iter()) {
        match candidate.outcome.as_str() {
            "success" => worked += 1,
            "partial" => partial += 1,
            "not_applicable" => not_applicable += 1,
            _ => failed += 1,
        }
    }
    format!(
        "Recommended {} + {}. Worked {} · partial {} · failed {} · not applicable {}",
        recommendation.tcp_candidate_label,
        recommendation.quic_candidate_label,
        worked,
        partial,
        failed,
        not_applicable,
    )
}

fn build_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let baseline = sanitize_current_probe_config(base);
    let parser_only = build_parser_only_candidate(base);
    let parser_unixeol = build_parser_unixeol_candidate(base);
    let parser_methodeol = build_parser_methodeol_candidate(base);
    let split_host = build_split_host_candidate(base);
    let tlsrec_split_host = build_tlsrec_split_host_candidate(base);
    let tlsrec_fake_rich = build_tlsrec_fake_rich_candidate(base);
    let tlsrec_fakedsplit = build_tlsrec_fake_approx_candidate(base, "fakedsplit");
    let tlsrec_fakeddisorder = build_tlsrec_fake_approx_candidate(base, "fakeddisorder");
    let tlsrec_hostfake = build_tlsrec_hostfake_candidate(base, false);
    let tlsrec_hostfake_split = build_tlsrec_hostfake_candidate(base, true);

    vec![
        candidate_spec("baseline_current", "Current strategy", "baseline", baseline),
        candidate_spec("parser_only", "Parser-only", "parser", parser_only),
        candidate_spec("parser_unixeol", "Parser + Unix EOL", "parser_aggressive", parser_unixeol),
        candidate_spec("parser_methodeol", "Parser + Method EOL", "parser_aggressive", parser_methodeol),
        candidate_spec("split_host", "Split Host", "split", split_host),
        candidate_spec("tlsrec_split_host", "TLS record + split host", "tlsrec_split", tlsrec_split_host),
        candidate_spec_with_notes(
            "tlsrec_fake_rich",
            "TLS record + rich fake",
            "tlsrec_fake",
            tlsrec_fake_rich,
            vec!["Randomized fake TLS material with original ClientHello framing"],
        ),
        candidate_spec("tlsrec_fakedsplit", "TLS record + fakedsplit", "fake_approx", tlsrec_fakedsplit),
        candidate_spec("tlsrec_fakeddisorder", "TLS record + fakeddisorder", "fake_approx", tlsrec_fakeddisorder),
        candidate_spec("tlsrec_hostfake", "TLS record + hostfake", "hostfake", tlsrec_hostfake),
        candidate_spec_with_notes(
            "tlsrec_hostfake_split",
            "TLS record + hostfake split",
            "hostfake",
            tlsrec_hostfake_split,
            vec!["Adds a follow-up split after hostfake midhost reconstruction"],
        ),
    ]
}

fn build_full_matrix_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_tcp_candidates(base);
    candidates.extend([
        build_activation_window_split_spec(base),
        build_activation_window_hostfake_spec(base),
        build_adaptive_fake_ttl_spec(base),
        build_fake_payload_library_spec(base),
    ]);
    candidates
}

fn build_quic_candidates(base_tcp: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    vec![
        candidate_spec(
            "quic_disabled",
            "QUIC disabled",
            "quic_disabled",
            build_quic_candidate(base_tcp, false, "disabled"),
        ),
        candidate_spec_with_notes(
            "quic_compat_burst",
            "QUIC compat burst",
            "quic_burst",
            build_quic_candidate(base_tcp, true, "compat_default"),
            vec!["Uses Zapret-style compatibility QUIC fake packets"],
        ),
        candidate_spec_with_notes(
            "quic_realistic_burst",
            "QUIC realistic burst",
            "quic_burst",
            build_quic_candidate(base_tcp, true, "realistic_initial"),
            vec!["Uses realistic QUIC Initial packets with the target SNI"],
        ),
    ]
}

fn candidate_spec(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
) -> StrategyCandidateSpec {
    candidate_spec_with_notes(id, label, family, config, Vec::new())
}

fn candidate_spec_with_notes(
    id: &'static str,
    label: &'static str,
    family: &'static str,
    config: ProxyUiConfig,
    notes: Vec<&'static str>,
) -> StrategyCandidateSpec {
    StrategyCandidateSpec {
        id,
        label,
        family,
        config,
        notes,
        preserve_adaptive_fake_ttl: false,
        warmup: CandidateWarmup::None,
    }
}

fn sanitize_current_probe_config(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = base.clone();
    config.host_autolearn_enabled = false;
    config.host_autolearn_store_path = None;
    config
}

fn strategy_probe_base(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base);
    config.desync_http = true;
    config.desync_https = true;
    config.desync_udp = false;
    config.desync_method = "none".to_string();
    config.split_marker = Some("host+1".to_string());
    config.tcp_chain_steps.clear();
    config.split_position = 0;
    config.split_at_host = false;
    config.fake_ttl = 8;
    config.fake_tls_use_original = false;
    config.fake_tls_randomize = false;
    config.fake_tls_dup_session_id = false;
    config.fake_tls_pad_encap = false;
    config.fake_tls_size = 0;
    config.fake_tls_sni_mode = "fixed".to_string();
    config.host_mixed_case = false;
    config.domain_mixed_case = false;
    config.host_remove_spaces = false;
    config.http_method_eol = false;
    config.http_unix_eol = false;
    config.tls_record_split = false;
    config.tls_record_split_marker = None;
    config.tls_record_split_position = 0;
    config.tls_record_split_at_sni = false;
    config.udp_fake_count = 0;
    config.udp_chain_steps.clear();
    config.drop_sack = false;
    config.fake_offset_marker = Some("0".to_string());
    config.fake_offset = 0;
    config.quic_fake_profile = "disabled".to_string();
    config.quic_fake_host.clear();
    config
}

fn build_parser_only_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.host_mixed_case = true;
    config.domain_mixed_case = true;
    config.host_remove_spaces = true;
    config
}

fn build_parser_unixeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.http_unix_eol = true;
    config
}

fn build_parser_methodeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.http_method_eol = true;
    config
}

fn build_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.tcp_chain_steps = vec![tcp_step("split", "host+2")];
    config
}

fn build_tlsrec_split_host_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.tcp_chain_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("split", "host+2")];
    config
}

fn build_tlsrec_fake_rich_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.tcp_chain_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step("fake", "host+1")];
    config.fake_tls_use_original = true;
    config.fake_tls_randomize = true;
    config.fake_tls_dup_session_id = true;
    config.fake_tls_pad_encap = true;
    config.fake_tls_sni_mode = "randomized".to_string();
    config.fake_offset_marker = Some("endhost-1".to_string());
    config
}

fn build_tlsrec_fake_approx_candidate(base: &ProxyUiConfig, kind: &str) -> ProxyUiConfig {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.tcp_chain_steps = vec![tcp_step("tlsrec", "extlen"), tcp_step(kind, "host+1")];
    config
}

fn build_tlsrec_hostfake_candidate(base: &ProxyUiConfig, with_split: bool) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    let mut steps = vec![
        tcp_step("tlsrec", "extlen"),
        ProxyUiTcpChainStep {
            kind: "hostfake".to_string(),
            marker: "endhost+8".to_string(),
            midhost_marker: Some("midsld".to_string()),
            fake_host_template: Some("googlevideo.com".to_string()),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            activation_filter: ProxyUiActivationFilter::default(),
        },
    ];
    if with_split {
        steps.push(tcp_step("split", "midsld"));
    }
    config.tcp_chain_steps = steps;
    config
}

fn build_quic_candidate(base_tcp: &ProxyUiConfig, enabled: bool, profile: &str) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base_tcp);
    config.desync_udp = enabled;
    config.udp_fake_count = if enabled { 4 } else { 0 };
    config.udp_chain_steps = if enabled {
        vec![ProxyUiUdpChainStep {
            kind: "fake_burst".to_string(),
            count: 4,
            activation_filter: ProxyUiActivationFilter::default(),
        }]
    } else {
        Vec::new()
    };
    config.quic_fake_profile = profile.to_string();
    config.quic_fake_host.clear();
    config
}

fn build_activation_window_split_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_split_host_candidate(base);
    config.group_activation_filter = default_audit_activation_filter();
    candidate_spec_with_notes(
        "activation_window_split",
        "Activation window + split host",
        "activation_window",
        config,
        vec!["Limits split-host attempts to the first packets in a flow"],
    )
}

fn build_activation_window_hostfake_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_hostfake_candidate(base, false);
    config.group_activation_filter = default_audit_activation_filter();
    candidate_spec_with_notes(
        "activation_window_hostfake",
        "Activation window + hostfake",
        "activation_window",
        config,
        vec!["Applies hostfake only inside a narrow activation window"],
    )
}

fn build_adaptive_fake_ttl_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.adaptive_fake_ttl_enabled = true;
    config.adaptive_fake_ttl_delta = ADAPTIVE_FAKE_TTL_DEFAULT_DELTA;
    config.adaptive_fake_ttl_min = ADAPTIVE_FAKE_TTL_DEFAULT_MIN;
    config.adaptive_fake_ttl_max = ADAPTIVE_FAKE_TTL_DEFAULT_MAX;
    config.adaptive_fake_ttl_fallback = ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK;
    StrategyCandidateSpec {
        id: "adaptive_fake_ttl",
        label: "Adaptive fake TTL",
        family: "adaptive_fake_ttl",
        config,
        notes: vec![
            "Runs an unscored warm-up pass before measured probes",
            "Keeps adaptive fake TTL enabled during candidate execution",
        ],
        preserve_adaptive_fake_ttl: true,
        warmup: CandidateWarmup::AdaptiveFakeTtl,
    }
}

fn build_fake_payload_library_spec(base: &ProxyUiConfig) -> StrategyCandidateSpec {
    let mut config = build_tlsrec_fake_rich_candidate(base);
    config.http_fake_profile = HTTP_FAKE_PROFILE_CLOUDFLARE_GET.to_string();
    config.tls_fake_profile = TLS_FAKE_PROFILE_GOOGLE_CHROME.to_string();
    config.udp_fake_profile = UDP_FAKE_PROFILE_DNS_QUERY.to_string();
    candidate_spec_with_notes(
        "library_fake_payloads",
        "Library fake payload presets",
        "fake_payload_library",
        config,
        vec!["Uses bundled Cloudflare GET, Chrome TLS, and DNS query fake payload profiles"],
    )
}

fn default_audit_activation_filter() -> ProxyUiActivationFilter {
    ProxyUiActivationFilter {
        round: Some(ProxyUiNumericRange { start: Some(1), end: Some(2) }),
        payload_size: Some(ProxyUiNumericRange { start: Some(64), end: Some(512) }),
        stream_bytes: Some(ProxyUiNumericRange { start: Some(0), end: Some(2047) }),
    }
}

fn tcp_step(kind: &str, marker: &str) -> ProxyUiTcpChainStep {
    ProxyUiTcpChainStep {
        kind: kind.to_string(),
        marker: marker.to_string(),
        midhost_marker: None,
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
        activation_filter: ProxyUiActivationFilter::default(),
    }
}

fn execute_tcp_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            run_candidate_warmup(spec, &transport, targets);
            let mut score = CandidateScore::default();
            for target in targets {
                score.add(run_http_strategy_probe(&transport, target, spec));
                score.add(run_https_strategy_probe(&transport, target, spec));
            }
            drop(runtime);
            build_candidate_execution(spec, score, 3)
        }
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err),
    }
}

fn execute_quic_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[QuicTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 2, "No QUIC targets configured");
    }
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            let mut score = CandidateScore::default();
            for target in targets {
                score.add(run_quic_strategy_probe(&transport, target, spec));
            }
            drop(runtime);
            build_candidate_execution(spec, score, 2)
        }
        Err(err) => failed_candidate_execution(spec, targets.len(), 2, err),
    }
}

fn probe_runtime_transport(
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<TemporaryProxyRuntime, String> {
    let mut runtime_config = spec.config.clone();
    runtime_config.ip = "127.0.0.1".to_string();
    runtime_config.port = 0;
    runtime_config.host_autolearn_enabled = false;
    runtime_config.host_autolearn_store_path = None;
    if !spec.preserve_adaptive_fake_ttl {
        freeze_adaptive_fake_ttl_for_probe(&mut runtime_config);
    }
    TemporaryProxyRuntime::start(
        runtime_config_from_ui(runtime_config).map_err(|err| err.to_string())?,
        runtime_context.cloned(),
    )
}

fn run_candidate_warmup(spec: &StrategyCandidateSpec, transport: &TransportConfig, targets: &[DomainTarget]) {
    if spec.warmup != CandidateWarmup::AdaptiveFakeTtl {
        return;
    }
    for target in targets {
        let http_port = target.http_port.unwrap_or(80);
        let https_port = target.https_port.unwrap_or(443);
        let _ = try_http_request(
            &domain_connect_target(target),
            http_port,
            transport,
            &target.host,
            &target.http_path,
            false,
        );
        let _ = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
        );
    }
}

fn freeze_adaptive_fake_ttl_for_probe(runtime_config: &mut ProxyUiConfig) {
    if !runtime_config.adaptive_fake_ttl_enabled {
        return;
    }
    let min_ttl = runtime_config.adaptive_fake_ttl_min.clamp(1, 255);
    let max_ttl = runtime_config.adaptive_fake_ttl_max.clamp(min_ttl, 255);
    let fallback = if runtime_config.adaptive_fake_ttl_fallback > 0 {
        runtime_config.adaptive_fake_ttl_fallback
    } else if runtime_config.fake_ttl > 0 {
        runtime_config.fake_ttl
    } else {
        ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
    };
    runtime_config.fake_ttl = fallback.clamp(min_ttl, max_ttl);
    runtime_config.adaptive_fake_ttl_enabled = false;
}

fn build_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let outcome = if score.is_full_success() {
        "success"
    } else if score.succeeded_targets > 0 && score.quality_score >= quality_floor {
        "partial"
    } else {
        "failed"
    };
    let rationale = format!("{} of {} targets succeeded", score.succeeded_targets, score.total_targets);
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: outcome.to_string(),
            rationale,
            succeeded_targets: score.succeeded_targets,
            total_targets: score.total_targets,
            weighted_success_score: score.weighted_success_score,
            total_weight: score.total_weight,
            quality_score: score.quality_score,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: score.average_latency_ms(),
            skipped: false,
        },
        results: score.results,
    }
}

fn failed_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    err: String,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: "failed".to_string(),
            rationale: err,
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: None,
            skipped: false,
        },
        results: Vec::new(),
    }
}

fn not_applicable_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: "not_applicable".to_string(),
            rationale: rationale.to_string(),
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[rationale]),
            average_latency_ms: None,
            skipped: false,
        },
        results: Vec::new(),
    }
}

fn skipped_candidate_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        outcome: "skipped".to_string(),
        rationale: rationale.to_string(),
        succeeded_targets: 0,
        total_targets,
        weighted_success_score: 0,
        total_weight: total_targets * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[rationale]),
        average_latency_ms: None,
        skipped: true,
    }
}

fn candidate_proxy_config_json(spec: &StrategyCandidateSpec) -> Option<String> {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        config: spec.config.clone(),
        runtime_context: None,
    })
    .ok()
}

fn candidate_notes(spec: &StrategyCandidateSpec, extra_notes: &[&str]) -> Vec<String> {
    spec.notes.iter().copied().chain(extra_notes.iter().copied()).map(str::to_string).collect()
}

fn winning_candidate_index(candidates: &[StrategyProbeCandidateSummary]) -> Option<usize> {
    candidates
        .iter()
        .enumerate()
        .filter(|(_, candidate)| !candidate.skipped && candidate.outcome != "not_applicable")
        .max_by_key(|(index, candidate)| {
            (
                candidate.weighted_success_score,
                candidate.quality_score,
                std::cmp::Reverse(candidate.average_latency_ms.unwrap_or(u64::MAX)),
                std::cmp::Reverse(*index),
            )
        })
        .map(|(index, _)| index)
}

fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let observation =
        try_http_request(&domain_connect_target(target), http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = now_ms().saturating_sub(started);
    let outcome = if is_blockpage(&observation) {
        "http_blockpage".to_string()
    } else if observation.status == "http_ok" {
        "http_ok".to_string()
    } else {
        "http_unreachable".to_string()
    };
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details: vec![
                ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
                ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
                ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
                ProbeDetail { key: "protocol".to_string(), value: "HTTP".to_string() },
                ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
                ProbeDetail { key: "status".to_string(), value: observation.status },
                ProbeDetail {
                    key: "error".to_string(),
                    value: observation.error.unwrap_or_else(|| "none".to_string()),
                },
            ],
        },
        success: outcome == "http_ok",
        weight: 1,
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_blockpage" {
            1
        } else {
            0
        },
        latency_ms,
    }
}

fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let tls13 = try_tls_handshake(
        &domain_connect_target(target),
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
    );
    let tls12 = try_tls_handshake(
        &domain_connect_target(target),
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
    );
    let latency_ms = now_ms().saturating_sub(started);
    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else {
        "tls_handshake_failed".to_string()
    };
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details: vec![
                ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
                ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
                ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
                ProbeDetail { key: "protocol".to_string(), value: "HTTPS".to_string() },
                ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
                ProbeDetail { key: "tls13Status".to_string(), value: tls13.status },
                ProbeDetail { key: "tls12Status".to_string(), value: tls12.status },
                ProbeDetail {
                    key: "tlsError".to_string(),
                    value: tls13.error.or(tls12.error).unwrap_or_else(|| "none".to_string()),
                },
            ],
        },
        success: matches!(outcome.as_str(), "tls_ok" | "tls_version_split"),
        weight: 2,
        quality: match outcome.as_str() {
            "tls_ok" => 4,
            "tls_version_split" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let connect_target = quic_connect_target(target);
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload(&connect_target, target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error) = match response {
        Ok(bytes) if parse_quic_initial(&bytes).is_some() => {
            ("quic_initial_response".to_string(), "quic_initial_response".to_string(), "none".to_string())
        }
        Ok(bytes) if !bytes.is_empty() => {
            ("quic_response".to_string(), "quic_response".to_string(), "none".to_string())
        }
        Ok(_) => ("quic_empty".to_string(), "quic_empty".to_string(), "none".to_string()),
        Err(err) => (err.clone(), "quic_error".to_string(), err),
    };
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details: vec![
                ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
                ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
                ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
                ProbeDetail { key: "protocol".to_string(), value: "QUIC".to_string() },
                ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
                ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
                ProbeDetail { key: "status".to_string(), value: status },
                ProbeDetail { key: "error".to_string(), value: error },
            ],
        },
        success: matches!(outcome.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        quality: match outcome.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

fn relay_udp_payload(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let destination =
        resolve_addresses(target, port)?.into_iter().next().ok_or_else(|| "no_socket_addrs".to_string())?;
    match transport {
        TransportConfig::Direct => relay_udp_direct(&destination.to_string(), payload),
        TransportConfig::Socks5 { host, port } => relay_udp_via_socks5(host, *port, destination, payload),
    }
}

fn domain_connect_target(target: &DomainTarget) -> TargetAddress {
    target
        .connect_ip
        .as_ref()
        .and_then(|ip| ip.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

fn quic_connect_target(target: &QuicTarget) -> TargetAddress {
    target
        .connect_ip
        .as_ref()
        .and_then(|ip| ip.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

fn wait_for_listener(addr: SocketAddr) -> Result<(), String> {
    for _ in 0..40 {
        if TcpStream::connect_timeout(&addr, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err(format!("probe runtime listener did not become ready on {addr}"))
}

fn transport_for_request(request: &ScanRequest) -> TransportConfig {
    match (&request.path_mode, request.proxy_host.as_ref(), request.proxy_port) {
        (ScanPathMode::InPath, Some(host), Some(port)) => TransportConfig::Socks5 { host: host.clone(), port },
        _ => TransportConfig::Direct,
    }
}

fn encrypted_dns_protocol(value: Option<&str>) -> EncryptedDnsProtocol {
    match value.unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        _ => EncryptedDnsProtocol::Doh,
    }
}

fn parse_url_host(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let (_, remainder) = trimmed.split_once("://")?;
    let authority = remainder.split('/').next()?;
    if authority.is_empty() {
        return None;
    }
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, suffix)| suffix);
    if host_port.starts_with('[') {
        let end = host_port.find(']')?;
        return Some(host_port[1..end].to_string());
    }
    host_port.split(':').next().map(ToOwned::to_owned)
}

fn encrypted_dns_endpoint_for_target(target: &DnsTarget) -> Result<(EncryptedDnsEndpoint, Vec<String>), String> {
    let protocol = encrypted_dns_protocol(target.encrypted_protocol.as_deref());
    let bootstrap_strings = if target.encrypted_bootstrap_ips.is_empty() {
        if target.doh_bootstrap_ips.is_empty() && target.doh_url.is_none() {
            DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect::<Vec<_>>()
        } else {
            target.doh_bootstrap_ips.clone()
        }
    } else {
        target.encrypted_bootstrap_ips.clone()
    };
    let doh_url = target
        .encrypted_doh_url
        .clone()
        .or_else(|| target.doh_url.clone())
        .or_else(|| (protocol == EncryptedDnsProtocol::Doh).then(|| DEFAULT_DOH_URL.to_string()));
    let host =
        target.encrypted_host.clone().or_else(|| doh_url.as_deref().and_then(parse_url_host)).unwrap_or_else(|| {
            if protocol == EncryptedDnsProtocol::Doh {
                DEFAULT_DOH_HOST.to_string()
            } else {
                String::new()
            }
        });
    let port = target.encrypted_port.unwrap_or(match protocol {
        EncryptedDnsProtocol::Doh => DEFAULT_DOH_PORT,
        EncryptedDnsProtocol::Dot => 853,
        EncryptedDnsProtocol::DnsCrypt => 443,
    });

    Ok((
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: target.encrypted_resolver_id.clone().or_else(|| Some(protocol.as_str().to_string())),
            host,
            port,
            tls_server_name: target.encrypted_tls_server_name.clone(),
            bootstrap_ips: parse_bootstrap_ips(&bootstrap_strings)?,
            doh_url,
            dnscrypt_provider_name: target.encrypted_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: target.encrypted_dnscrypt_public_key.clone(),
        },
        bootstrap_strings,
    ))
}

fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let (encrypted_endpoint, encrypted_bootstrap_ips) = match encrypted_dns_endpoint_for_target(target) {
        Ok(value) => value,
        Err(err) => {
            return ProbeResult {
                probe_type: "dns_integrity".to_string(),
                target: target.domain.clone(),
                outcome: "dns_unavailable".to_string(),
                details: vec![ProbeDetail { key: "encryptedDnsError".to_string(), value: err }],
            };
        }
    };
    let udp_started = std::time::Instant::now();
    let udp_result = resolve_via_udp(&target.domain, &udp_server, transport);
    let udp_latency_ms = udp_started.elapsed().as_millis().to_string();
    let encrypted_started = std::time::Instant::now();
    let encrypted_result = resolve_via_encrypted_dns(&target.domain, encrypted_endpoint.clone(), transport);
    let encrypted_latency_ms = encrypted_started.elapsed().as_millis().to_string();
    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();

    let outcome = match (&udp_result, &encrypted_result) {
        (Ok(udp_ips), Ok(encrypted_ips)) if ip_set(udp_ips) == ip_set(encrypted_ips) => {
            if !expected.is_empty() && ip_set(udp_ips) != expected {
                "dns_expected_mismatch".to_string()
            } else {
                "dns_match".to_string()
            }
        }
        (Ok(_), Ok(_)) => "dns_substitution".to_string(),
        (Ok(_), Err(_)) => "encrypted_dns_blocked".to_string(),
        (Err(_), Ok(_)) => {
            if matches!(path_mode, ScanPathMode::InPath) {
                "udp_skipped_or_blocked".to_string()
            } else {
                "udp_blocked".to_string()
            }
        }
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
    };

    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "udpServer".to_string(), value: udp_server },
            ProbeDetail { key: "udpAddresses".to_string(), value: format_result_set(&udp_result) },
            ProbeDetail { key: "udpLatencyMs".to_string(), value: udp_latency_ms },
            ProbeDetail {
                key: "encryptedResolverId".to_string(),
                value: encrypted_endpoint.resolver_id.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedProtocol".to_string(),
                value: encrypted_endpoint.protocol.as_str().to_string(),
            },
            ProbeDetail {
                key: "encryptedEndpoint".to_string(),
                value: encrypted_endpoint
                    .doh_url
                    .clone()
                    .unwrap_or_else(|| format!("{}:{}", encrypted_endpoint.host, encrypted_endpoint.port)),
            },
            ProbeDetail {
                key: "encryptedHost".to_string(),
                value: encrypted_endpoint.host.clone(),
            },
            ProbeDetail {
                key: "encryptedPort".to_string(),
                value: encrypted_endpoint.port.to_string(),
            },
            ProbeDetail {
                key: "encryptedTlsServerName".to_string(),
                value: encrypted_endpoint.tls_server_name.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedBootstrapIps".to_string(), value: encrypted_bootstrap_ips.join("|") },
            ProbeDetail {
                key: "encryptedBootstrapValidated".to_string(),
                value: (encrypted_result.is_ok() && !encrypted_bootstrap_ips.is_empty()).to_string(),
            },
            ProbeDetail {
                key: "encryptedDohUrl".to_string(),
                value: encrypted_endpoint.doh_url.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptProviderName".to_string(),
                value: encrypted_endpoint.dnscrypt_provider_name.clone().unwrap_or_default(),
            },
            ProbeDetail {
                key: "encryptedDnscryptPublicKey".to_string(),
                value: encrypted_endpoint.dnscrypt_public_key.clone().unwrap_or_default(),
            },
            ProbeDetail { key: "encryptedAddresses".to_string(), value: format_result_set(&encrypted_result) },
            ProbeDetail { key: "encryptedLatencyMs".to_string(), value: encrypted_latency_ms },
            ProbeDetail {
                key: "expected".to_string(),
                value: if expected.is_empty() {
                    "[]".to_string()
                } else {
                    expected.iter().cloned().collect::<Vec<_>>().join("|")
                },
            },
        ],
    }
}

fn run_domain_probe(target: &DomainTarget, transport: &TransportConfig) -> ProbeResult {
    let https_port = target.https_port.unwrap_or(443);
    let http_port = target.http_port.unwrap_or(80);
    let connect_target = domain_connect_target(target);
    let resolved = resolve_addresses(&connect_target, https_port);
    let tls13 =
        try_tls_handshake(&connect_target, https_port, transport, &target.host, true, TlsClientProfile::Tls13Only);
    let tls12 =
        try_tls_handshake(&connect_target, https_port, transport, &target.host, true, TlsClientProfile::Tls12Only);
    let http = try_http_request(&connect_target, http_port, transport, &target.host, &target.http_path, false);
    let tls_signal = classify_tls_signal(&tls13, &tls12);
    let preferred_tls = preferred_tls_observation(&tls13, &tls12);

    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if is_blockpage(&http) {
        "http_blockpage".to_string()
    } else if http.status == "http_ok" {
        "http_ok".to_string()
    } else {
        "unreachable".to_string()
    };

    ProbeResult {
        probe_type: "domain_reachability".to_string(),
        target: target.host.clone(),
        outcome,
        details: vec![
            ProbeDetail { key: "resolved".to_string(), value: format_socket_result(&resolved) },
            ProbeDetail { key: "tlsStatus".to_string(), value: preferred_tls.status.clone() },
            ProbeDetail {
                key: "tlsVersion".to_string(),
                value: preferred_tls.version.clone().unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail {
                key: "tlsError".to_string(),
                value: preferred_tls.error.clone().unwrap_or_else(|| "none".to_string()),
            },
            ProbeDetail { key: "tlsSignal".to_string(), value: tls_signal.to_string() },
            ProbeDetail { key: "tls13Status".to_string(), value: tls13.status },
            ProbeDetail {
                key: "tls13Version".to_string(),
                value: tls13.version.unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail { key: "tls13Error".to_string(), value: tls13.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "tls12Status".to_string(), value: tls12.status },
            ProbeDetail {
                key: "tls12Version".to_string(),
                value: tls12.version.unwrap_or_else(|| "unknown".to_string()),
            },
            ProbeDetail { key: "tls12Error".to_string(), value: tls12.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "httpStatus".to_string(), value: http.status.clone() },
            ProbeDetail { key: "httpResponse".to_string(), value: describe_http_observation(&http) },
        ],
    }
}

fn run_tcp_probe(target: &TcpTarget, whitelist_sni: &[String], transport: &TransportConfig) -> ProbeResult {
    let base_host_header =
        target.host_header.clone().or_else(|| target.sni.clone()).unwrap_or_else(|| target.provider.clone());
    let mut attempted_candidates = Vec::new();

    let initial_candidate = target.sni.clone().unwrap_or_default();
    let initial = run_fat_header_attempt(target, transport, &initial_candidate, &base_host_header);
    attempted_candidates.push(format!(
        "{}:{}",
        if initial_candidate.is_empty() { "<empty>" } else { initial_candidate.as_str() },
        fat_status_label(&initial.status)
    ));

    let mut outcome = classify_fat_header_outcome(&initial.status).to_string();
    let mut winning_sni = None;
    let mut final_observation = initial.clone();

    let tried_whitelist_candidates = initial.status != FatHeaderStatus::Success
        && (matches!(target.port, 443) || target.sni.is_some())
        && !whitelist_sni.is_empty();
    if tried_whitelist_candidates {
        for candidate in whitelist_sni {
            let candidate_result = run_fat_header_attempt(target, transport, candidate, candidate);
            attempted_candidates.push(format!("{}:{}", candidate, fat_status_label(&candidate_result.status)));
            final_observation = candidate_result.clone();
            if candidate_result.status == FatHeaderStatus::Success || candidate_result.responses_seen > 0 {
                outcome = "whitelist_sni_ok".to_string();
                winning_sni = Some(candidate.clone());
                break;
            }
        }
        if winning_sni.is_none() {
            outcome = "whitelist_sni_failed".to_string();
        }
    }

    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome,
        details: vec![
            ProbeDetail { key: "provider".to_string(), value: target.provider.clone() },
            ProbeDetail { key: "attempts".to_string(), value: attempted_candidates.join("|") },
            ProbeDetail {
                key: "selectedSni".to_string(),
                value: winning_sni.unwrap_or_else(|| {
                    if initial_candidate.is_empty() {
                        "<empty>".to_string()
                    } else {
                        initial_candidate
                    }
                }),
            },
            ProbeDetail { key: "asn".to_string(), value: target.asn.clone().unwrap_or_else(|| "unknown".to_string()) },
            ProbeDetail { key: "bytesSent".to_string(), value: final_observation.bytes_sent.to_string() },
            ProbeDetail { key: "responsesSeen".to_string(), value: final_observation.responses_seen.to_string() },
            ProbeDetail {
                key: "lastError".to_string(),
                value: final_observation.error.unwrap_or_else(|| "none".to_string()),
            },
        ],
    }
}

fn run_fat_header_attempt(
    target: &TcpTarget,
    transport: &TransportConfig,
    sni: &str,
    host_header: &str,
) -> FatHeaderObservation {
    let connect_target = match target.ip.parse::<IpAddr>() {
        Ok(ip) => TargetAddress::Ip(ip),
        Err(err) => {
            return FatHeaderObservation {
                status: FatHeaderStatus::ConnectFailed,
                bytes_sent: 0,
                responses_seen: 0,
                error: Some(err.to_string()),
            }
        }
    };

    let uses_tls = target.port == 443;
    let mut stream =
        match open_probe_stream(&connect_target, target.port, transport, Some(sni), false, TlsClientProfile::Auto) {
            Ok(stream) => stream,
            Err(err) => {
                let status = if uses_tls { FatHeaderStatus::HandshakeFailed } else { FatHeaderStatus::ConnectFailed };
                return FatHeaderObservation { status, bytes_sent: 0, responses_seen: 0, error: Some(err) };
            }
        };

    let requests = target.fat_header_requests.unwrap_or(FAT_HEADER_REQUESTS).max(1);
    let mut bytes_sent = 0usize;
    let mut responses_seen = 0usize;
    let host_header = if host_header.is_empty() { "localhost" } else { host_header };

    for index in 0..requests {
        let pad = "A".repeat(8 * 1024 + (index * 128));
        let payload =
            format!("HEAD / HTTP/1.1\r\nHost: {host_header}\r\nConnection: keep-alive\r\nX-Pad: {pad}\r\n\r\n");
        bytes_sent += payload.len();
        if let Err(err) = stream.write_all(payload.as_bytes()).and_then(|_| stream.flush()) {
            let status = classify_fat_io_error(err.kind(), bytes_sent, responses_seen);
            stream.shutdown();
            return FatHeaderObservation { status, bytes_sent, responses_seen, error: Some(err.to_string()) };
        }

        match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
            Ok(_) => {
                responses_seen += 1;
            }
            Err(err) => {
                let status = classify_fat_error_message(&err, bytes_sent, responses_seen);
                stream.shutdown();
                return FatHeaderObservation { status, bytes_sent, responses_seen, error: Some(err) };
            }
        }
    }

    stream.shutdown();
    FatHeaderObservation { status: FatHeaderStatus::Success, bytes_sent, responses_seen, error: None }
}

fn classify_fat_header_outcome(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "tcp_fat_header_ok",
        FatHeaderStatus::ThresholdCutoff => "tcp_16kb_blocked",
        FatHeaderStatus::Reset => "tcp_reset",
        FatHeaderStatus::Timeout => "tcp_timeout",
        FatHeaderStatus::ConnectFailed => "tcp_connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_handshake_failed",
    }
}

fn fat_status_label(status: &FatHeaderStatus) -> &'static str {
    match status {
        FatHeaderStatus::Success => "ok",
        FatHeaderStatus::ThresholdCutoff => "threshold_cutoff",
        FatHeaderStatus::Reset => "reset",
        FatHeaderStatus::Timeout => "timeout",
        FatHeaderStatus::ConnectFailed => "connect_failed",
        FatHeaderStatus::HandshakeFailed => "tls_failed",
    }
}

fn classify_fat_io_error(kind: ErrorKind, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
    match kind {
        ErrorKind::TimedOut | ErrorKind::WouldBlock => FatHeaderStatus::Timeout,
        ErrorKind::ConnectionReset
        | ErrorKind::UnexpectedEof
        | ErrorKind::BrokenPipe
        | ErrorKind::ConnectionAborted => {
            if late_stage_cutoff(bytes_sent, responses_seen) {
                FatHeaderStatus::ThresholdCutoff
            } else {
                FatHeaderStatus::Reset
            }
        }
        _ => FatHeaderStatus::ConnectFailed,
    }
}

fn classify_fat_error_message(message: &str, bytes_sent: usize, responses_seen: usize) -> FatHeaderStatus {
    let lower = message.to_ascii_lowercase();
    if lower.contains("timed out") {
        FatHeaderStatus::Timeout
    } else if lower.contains("connection reset")
        || lower.contains("broken pipe")
        || lower.contains("unexpected eof")
        || lower.contains("connection aborted")
    {
        if late_stage_cutoff(bytes_sent, responses_seen) {
            FatHeaderStatus::ThresholdCutoff
        } else {
            FatHeaderStatus::Reset
        }
    } else {
        FatHeaderStatus::ConnectFailed
    }
}

fn probe_is_success(outcome: &str) -> bool {
    matches!(
        outcome,
        "dns_match" | "dns_expected_mismatch" | "tls_ok" | "http_ok" | "tcp_fat_header_ok" | "whitelist_sni_ok"
    )
}

fn describe_transport(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct => "DIRECT".to_string(),
        TransportConfig::Socks5 { host, port } => format!("SOCKS5({host}:{port})"),
    }
}

fn preferred_tls_observation<'a>(tls13: &'a TlsObservation, tls12: &'a TlsObservation) -> &'a TlsObservation {
    if tls13.certificate_anomaly {
        tls13
    } else if tls12.certificate_anomaly {
        tls12
    } else if tls13.status == "tls_ok" {
        tls13
    } else if tls12.status == "tls_ok" {
        tls12
    } else {
        tls13
    }
}

fn classify_tls_signal(tls13: &TlsObservation, tls12: &TlsObservation) -> &'static str {
    if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid"
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_consistent"
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split_low_confidence"
    } else {
        "tls_unavailable"
    }
}

fn summarize_probe_event(probe: &ProbeResult) -> String {
    match probe.probe_type.as_str() {
        "dns_integrity" => format!(
            "{} -> {} (udp={}, doh={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "udpAddresses"),
            probe_detail_value(probe, "dohAddresses"),
        ),
        "domain_reachability" => format!(
            "{} -> {} (tls13={}, tls12={}, http={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "tls13Status"),
            probe_detail_value(probe, "tls12Status"),
            probe_detail_value(probe, "httpStatus"),
        ),
        "tcp_fat_header" => format!(
            "{} -> {} (sni={}, bytes={}, responses={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "selectedSni"),
            probe_detail_value(probe, "bytesSent"),
            probe_detail_value(probe, "responsesSeen"),
        ),
        _ => format!("{} -> {}", probe.target, probe.outcome),
    }
}

fn probe_detail_value<'a>(probe: &'a ProbeResult, key: &str) -> &'a str {
    probe.details.iter().find(|detail| detail.key == key).map_or("unknown", |detail| detail.value.as_str())
}

fn event_level_for_outcome(outcome: &str) -> &'static str {
    if probe_is_success(outcome) {
        "info"
    } else {
        "warn"
    }
}

fn resolve_via_udp(domain: &str, server: &str, transport: &TransportConfig) -> Result<Vec<String>, String> {
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query(domain, query_id)?;
    let response = match transport {
        TransportConfig::Direct => relay_udp_direct(server, &packet)?,
        TransportConfig::Socks5 { host, port } => {
            let server_addr = resolve_first_socket_addr(server)?;
            relay_udp_via_socks5(host, *port, server_addr, &packet)?
        }
    };
    parse_dns_response(&response, query_id)
}

fn resolve_via_encrypted_dns(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<String>, String> {
    let transport = match transport {
        TransportConfig::Direct => EncryptedDnsTransport::Direct,
        TransportConfig::Socks5 { host, port } => EncryptedDnsTransport::Socks5 { host: host.clone(), port: *port },
    };
    let resolver = EncryptedDnsResolver::new(endpoint, transport).map_err(|err| err.to_string())?;
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query(domain, query_id)?;
    let response = resolver.exchange_blocking(&packet).map_err(|err| err.to_string())?;
    extract_ip_answers(&response).map_err(|err| err.to_string())
}

fn parse_bootstrap_ips(values: &[String]) -> Result<Vec<IpAddr>, String> {
    values.iter().map(|value| value.parse::<IpAddr>().map_err(|err| err.to_string())).collect()
}

fn try_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> HttpObservation {
    match execute_http_request(target, port, transport, host_header, path, secure) {
        Ok(response) => {
            HttpObservation { status: classify_http_response(&response), response: Some(response), error: None }
        }
        Err(err) => HttpObservation { status: "http_unreachable".to_string(), response: None, error: Some(err) },
    }
}

fn execute_http_request(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    host_header: &str,
    path: &str,
    secure: bool,
) -> Result<HttpResponse, String> {
    let mut stream = open_probe_stream(
        target,
        port,
        transport,
        if secure { Some(host_header) } else { None },
        secure,
        TlsClientProfile::Auto,
    )?;
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host_header}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let response = read_http_response(&mut stream, MAX_HTTP_BYTES)?;
    stream.shutdown();
    Ok(response)
}

fn try_tls_handshake(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    server_name: &str,
    verify_certificates: bool,
    profile: TlsClientProfile,
) -> TlsObservation {
    match open_probe_stream(target, port, transport, Some(server_name), verify_certificates, profile) {
        Ok(mut stream) => {
            let version = match &mut stream {
                ConnectionStream::Plain(_) => None,
                ConnectionStream::Tls(stream) => tls_version_label(stream.conn.protocol_version()),
            };
            stream.shutdown();
            TlsObservation { status: "tls_ok".to_string(), version, error: None, certificate_anomaly: false }
        }
        Err(err) => {
            let certificate_anomaly = is_certificate_error(&err);
            TlsObservation {
                status: if certificate_anomaly {
                    "tls_cert_invalid".to_string()
                } else {
                    "tls_handshake_failed".to_string()
                },
                version: None,
                error: Some(err),
                certificate_anomaly,
            }
        }
    }
}

fn open_probe_stream(
    target: &TargetAddress,
    port: u16,
    transport: &TransportConfig,
    tls_name: Option<&str>,
    verify_certificates: bool,
    profile: TlsClientProfile,
) -> Result<ConnectionStream, String> {
    let socket = connect_transport(target, port, transport)?;
    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;

    match tls_name {
        Some(name) if verify_certificates || port == 443 || !matches!(profile, TlsClientProfile::Auto) => {
            let builder = match profile {
                TlsClientProfile::Auto => ClientConfig::builder(),
                TlsClientProfile::Tls12Only => ClientConfig::builder_with_protocol_versions(&[&rustls::version::TLS12]),
                TlsClientProfile::Tls13Only => ClientConfig::builder_with_protocol_versions(&[&rustls::version::TLS13]),
            };
            let config = if verify_certificates {
                Arc::new(builder.with_root_certificates(default_root_store()).with_no_client_auth())
            } else {
                Arc::new(
                    builder
                        .dangerous()
                        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
                        .with_no_client_auth(),
                )
            };
            let server_name = make_server_name(name, target)?;
            let connection = ClientConnection::new(config, server_name).map_err(|err| err.to_string())?;
            let mut tls_stream = StreamOwned::new(connection, socket);
            while tls_stream.conn.is_handshaking() {
                tls_stream.conn.complete_io(&mut tls_stream.sock).map_err(|err| err.to_string())?;
            }
            Ok(ConnectionStream::Tls(Box::new(tls_stream)))
        }
        _ => Ok(ConnectionStream::Plain(socket)),
    }
}

fn connect_transport(target: &TargetAddress, port: u16, transport: &TransportConfig) -> Result<TcpStream, String> {
    match transport {
        TransportConfig::Direct => connect_direct(target, port),
        TransportConfig::Socks5 { host, port: proxy_port } => {
            let proxy = connect_direct(&TargetAddress::Host(host.clone()), *proxy_port)?;
            negotiate_socks5(proxy, target, port)
        }
    }
}

fn relay_udp_direct(server: &str, payload: &[u8]) -> Result<Vec<u8>, String> {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).map_err(|err| err.to_string())?;
    socket.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.send_to(payload, server).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let (size, _) = socket.recv_from(&mut buf).map_err(|err| err.to_string())?;
    Ok(buf[..size].to_vec())
}

fn relay_udp_via_socks5(
    proxy_host: &str,
    proxy_port: u16,
    destination: SocketAddr,
    payload: &[u8],
) -> Result<Vec<u8>, String> {
    let mut control = connect_direct(&TargetAddress::Host(proxy_host.to_string()), proxy_port)?;
    control.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    control.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    socks5_noauth_handshake(&mut control)?;
    let relay_addr = normalize_udp_relay_addr(socks5_udp_associate(&mut control)?, &control)?;

    let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
        "0.0.0.0:0".parse().expect("valid IPv4 UDP bind")
    } else {
        "[::]:0".parse().expect("valid IPv6 UDP bind")
    };
    let udp = UdpSocket::bind(bind_addr).map_err(|err| err.to_string())?;
    udp.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    udp.connect(relay_addr).map_err(|err| err.to_string())?;
    let frame = encode_socks5_udp_frame(destination, payload);
    udp.send(&frame).map_err(|err| err.to_string())?;

    let mut buf = [0u8; 65535];
    let size = udp.recv(&mut buf).map_err(|err| err.to_string())?;
    let (_, payload) = decode_socks5_udp_frame(&buf[..size])?;
    Ok(payload)
}

fn socks5_noauth_handshake(stream: &mut TcpStream) -> Result<(), String> {
    stream.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut reply = [0u8; 2];
    stream.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {reply:?}"));
    }
    Ok(())
}

fn socks5_udp_associate(stream: &mut TcpStream) -> Result<SocketAddr, String> {
    let request = [0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    stream.write_all(&request).map_err(|err| err.to_string())?;
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).map_err(|err| err.to_string())?;
    if header[1] != 0x00 {
        return Err(format!("SOCKS5 UDP ASSOCIATE failed: {:x}", header[1]));
    }
    match header[3] {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut addr).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
        }
        atyp => Err(format!("SOCKS5 UDP ASSOCIATE atyp unsupported: {atyp}")),
    }
}

fn normalize_udp_relay_addr(relay_addr: SocketAddr, control: &TcpStream) -> Result<SocketAddr, String> {
    if relay_addr.ip().is_unspecified() {
        let peer = control.peer_addr().map_err(|err| err.to_string())?;
        Ok(SocketAddr::new(peer.ip(), relay_addr.port()))
    } else {
        Ok(relay_addr)
    }
}

fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match destination {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), String> {
    if frame.len() < 10 {
        return Err("SOCKS5 UDP frame too short".to_string());
    }
    match frame[3] {
        0x01 => {
            let address = SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7])),
                u16::from_be_bytes([frame[8], frame[9]]),
            );
            Ok((address, frame[10..].to_vec()))
        }
        0x04 => {
            if frame.len() < 22 {
                return Err("SOCKS5 UDP IPv6 frame too short".to_string());
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            let address =
                SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(raw)), u16::from_be_bytes([frame[20], frame[21]]));
            Ok((address, frame[22..].to_vec()))
        }
        atyp => Err(format!("SOCKS5 UDP atyp unsupported: {atyp}")),
    }
}

fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, String> {
    value.to_socket_addrs().map_err(|err| err.to_string())?.next().ok_or_else(|| "no_socket_addrs".to_string())
}

fn connect_direct(target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    let addresses = resolve_addresses(target, port)?;
    let mut last_error = None;
    for address in addresses {
        match TcpStream::connect_timeout(&address, CONNECT_TIMEOUT) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err.to_string()),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

fn negotiate_socks5(mut proxy: TcpStream, target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    proxy.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    proxy.write_all(&[0x05, 0x01, 0x00]).map_err(|err| err.to_string())?;
    let mut auth_reply = [0u8; 2];
    proxy.read_exact(&mut auth_reply).map_err(|err| err.to_string())?;
    if auth_reply != [0x05, 0x00] {
        return Err(format!("SOCKS5 auth failed: {auth_reply:?}"));
    }

    let mut request = vec![0x05, 0x01, 0x00];
    match target {
        TargetAddress::Ip(IpAddr::V4(ip)) => {
            request.push(0x01);
            request.extend(ip.octets());
        }
        TargetAddress::Ip(IpAddr::V6(ip)) => {
            request.push(0x04);
            request.extend(ip.octets());
        }
        TargetAddress::Host(host) => {
            let host_bytes = host.as_bytes();
            if host_bytes.len() > u8::MAX as usize {
                return Err("SOCKS5 host too long".to_string());
            }
            request.push(0x03);
            request.push(host_bytes.len() as u8);
            request.extend(host_bytes);
        }
    }
    request.extend(port.to_be_bytes());
    proxy.write_all(&request).map_err(|err| err.to_string())?;

    let mut reply = [0u8; 4];
    proxy.read_exact(&mut reply).map_err(|err| err.to_string())?;
    if reply[1] != 0x00 {
        return Err(format!("SOCKS5 connect failed: {:x}", reply[1]));
    }
    match reply[3] {
        0x01 => {
            let mut tail = [0u8; 6];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x04 => {
            let mut tail = [0u8; 18];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            proxy.read_exact(&mut len).map_err(|err| err.to_string())?;
            let mut tail = vec![0u8; len[0] as usize + 2];
            proxy.read_exact(&mut tail).map_err(|err| err.to_string())?;
        }
        atyp => return Err(format!("SOCKS5 atyp unsupported: {atyp}")),
    }
    Ok(proxy)
}

fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, String> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => {
            (host.as_str(), port).to_socket_addrs().map(std::iter::Iterator::collect).map_err(|err| err.to_string())
        }
    }
}

fn read_http_response(stream: &mut ConnectionStream, max_bytes: usize) -> Result<HttpResponse, String> {
    let buf = read_http_headers(stream, max_bytes)?;
    let header_end = find_headers_end(&buf).ok_or_else(|| "response_missing_headers".to_string())?;
    let header_bytes = buf[..header_end].to_vec();
    let mut body = buf[header_end + 4..].to_vec();
    let content_length = parse_content_length(&header_bytes);
    if let Some(expected_length) = content_length {
        if expected_length > max_bytes {
            return Err("response_too_large".to_string());
        }
        while body.len() < expected_length {
            let remaining = expected_length - body.len();
            let mut chunk = vec![0u8; remaining.min(4096)];
            let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read]);
        }
    } else {
        loop {
            let mut chunk = [0u8; 4096];
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(read) => {
                    body.extend_from_slice(&chunk[..read]);
                    if body.len() > max_bytes {
                        return Err("response_too_large".to_string());
                    }
                }
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    break;
                }
                Err(err) => return Err(err.to_string()),
            }
        }
    }

    parse_http_response(&header_bytes, body)
}

fn read_http_headers(stream: &mut ConnectionStream, max_bytes: usize) -> Result<Vec<u8>, String> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1024];
    loop {
        let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
        if read == 0 {
            if buf.is_empty() {
                return Err("unexpected eof".to_string());
            }
            break;
        }
        buf.extend_from_slice(&chunk[..read]);
        if buf.len() > max_bytes {
            return Err("response_too_large".to_string());
        }
        if find_headers_end(&buf).is_some() {
            break;
        }
    }
    Ok(buf)
}

fn parse_http_response(headers: &[u8], body: Vec<u8>) -> Result<HttpResponse, String> {
    let text = String::from_utf8_lossy(headers);
    let mut lines = text.split("\r\n");
    let status_line = lines.next().ok_or_else(|| "missing_status_line".to_string())?;
    let mut status_parts = status_line.splitn(3, ' ');
    let _http_version = status_parts.next();
    let status_code = status_parts
        .next()
        .ok_or_else(|| "missing_status_code".to_string())?
        .parse::<u16>()
        .map_err(|err| err.to_string())?;
    let reason = status_parts.next().unwrap_or_default().to_string();
    let mut parsed_headers = HashMap::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((name, value)) = line.split_once(':') {
            parsed_headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }
    Ok(HttpResponse { status_code, reason, headers: parsed_headers, body })
}

fn classify_http_response(response: &HttpResponse) -> String {
    if response.status_code == 200 && !body_has_blockpage_keywords(&response.body) {
        "http_ok".to_string()
    } else if response.status_code == 403
        || response.status_code == 451
        || response.status_code == 302
        || body_has_blockpage_keywords(&response.body)
    {
        "http_blockpage".to_string()
    } else {
        format!("http_status_{}", response.status_code)
    }
}

fn describe_http_observation(observation: &HttpObservation) -> String {
    match (&observation.response, &observation.error) {
        (Some(response), _) => format!(
            "{} {} {}",
            response.status_code,
            response.reason,
            response.headers.get("server").cloned().unwrap_or_else(|| "server=unknown".to_string())
        ),
        (None, Some(error)) => error.clone(),
        (None, None) => "none".to_string(),
    }
}

fn is_blockpage(observation: &HttpObservation) -> bool {
    observation.status == "http_blockpage"
}

fn body_has_blockpage_keywords(body: &[u8]) -> bool {
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    ["blocked", "access denied", "forbidden", "restriction", "censorship"].iter().any(|needle| text.contains(needle))
}

fn make_server_name(name: &str, target: &TargetAddress) -> Result<ServerName<'static>, String> {
    if !name.is_empty() {
        if let Ok(server_name) = ServerName::try_from(name.to_string()) {
            return Ok(server_name);
        }
    }
    match target {
        TargetAddress::Ip(ip) => Ok(ServerName::IpAddress((*ip).into())),
        TargetAddress::Host(host) => ServerName::try_from(host.clone()).map_err(|err| err.to_string()),
    }
}

fn default_root_store() -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    roots
}

fn tls_version_label(version: Option<rustls::ProtocolVersion>) -> Option<String> {
    version.map(|value| match value {
        rustls::ProtocolVersion::TLSv1_2 => "TLS1.2".to_string(),
        rustls::ProtocolVersion::TLSv1_3 => "TLS1.3".to_string(),
        other => format!("{other:?}"),
    })
}

fn is_certificate_error(error: &str) -> bool {
    let lower = error.to_ascii_lowercase();
    lower.contains("certificate")
        || lower.contains("unknown issuer")
        || lower.contains("not valid")
        || lower.contains("bad certificate")
}

fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("invalid_dns_name".to_string());
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(1u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

fn parse_dns_response(packet: &[u8], expected_id: u16) -> Result<Vec<String>, String> {
    if packet.len() < 12 {
        return Err("dns_response_too_short".to_string());
    }
    let id = u16::from_be_bytes([packet[0], packet[1]]);
    if id != expected_id {
        return Err("dns_response_id_mismatch".to_string());
    }
    let answer_count = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let question_count = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let mut offset = 12usize;
    for _ in 0..question_count {
        offset = skip_dns_name(packet, offset)?;
        offset += 4;
        if offset > packet.len() {
            return Err("dns_question_truncated".to_string());
        }
    }

    let mut answers = Vec::new();
    for _ in 0..answer_count {
        offset = skip_dns_name(packet, offset)?;
        if offset + 10 > packet.len() {
            return Err("dns_answer_truncated".to_string());
        }
        let record_type = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
        let data_len = u16::from_be_bytes([packet[offset + 8], packet[offset + 9]]) as usize;
        offset += 10;
        if offset + data_len > packet.len() {
            return Err("dns_rdata_truncated".to_string());
        }
        if record_type == 1 && data_len == 4 {
            answers.push(
                Ipv4Addr::new(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3]).to_string(),
            );
        }
        offset += data_len;
    }
    if answers.is_empty() {
        return Err("dns_empty".to_string());
    }
    Ok(answers)
}

fn skip_dns_name(packet: &[u8], mut offset: usize) -> Result<usize, String> {
    loop {
        let Some(length) = packet.get(offset).copied() else {
            return Err("dns_name_truncated".to_string());
        };
        if length & 0b1100_0000 == 0b1100_0000 {
            if offset + 1 >= packet.len() {
                return Err("dns_pointer_truncated".to_string());
            }
            return Ok(offset + 2);
        }
        offset += 1;
        if length == 0 {
            return Ok(offset);
        }
        offset += length as usize;
        if offset > packet.len() {
            return Err("dns_label_truncated".to_string());
        }
    }
}

fn ip_set(values: &[String]) -> BTreeSet<String> {
    values.iter().cloned().collect()
}

fn format_result_set(result: &Result<Vec<String>, String>) -> String {
    match result {
        Ok(values) => values.join("|"),
        Err(err) => format!("error:{err}"),
    }
}

fn format_socket_result(result: &Result<Vec<SocketAddr>, String>) -> String {
    match result {
        Ok(values) => values.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|"),
        Err(err) => format!("error:{err}"),
    }
}

fn fat_threshold_reached(bytes_sent: usize) -> bool {
    bytes_sent >= FAT_HEADER_THRESHOLD_BYTES.saturating_sub(2 * 1024)
}

fn late_stage_cutoff(bytes_sent: usize, responses_seen: usize) -> bool {
    fat_threshold_reached(bytes_sent) || (responses_seen >= 1 && bytes_sent >= 8 * 1024)
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
}

fn parse_content_length(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.split("\r\n") {
        let (name, value) = line.split_once(':')?;
        if name.trim().eq_ignore_ascii_case("content-length") {
            return value.trim().parse::<usize>().ok();
        }
    }
    None
}

fn set_progress(shared: &Arc<Mutex<SharedState>>, progress: ScanProgress) {
    if let Ok(mut guard) = shared.lock() {
        guard.progress = Some(progress);
    }
}

fn set_report(shared: &Arc<Mutex<SharedState>>, report: ScanReport) {
    if let Ok(mut guard) = shared.lock() {
        guard.report = Some(report);
    }
}

fn push_event(shared: &Arc<Mutex<SharedState>>, source: &str, level: &str, message: String) {
    if let Ok(mut guard) = shared.lock() {
        if guard.passive_events.len() >= MAX_PASSIVE_EVENTS {
            guard.passive_events.pop_front();
        }
        guard.passive_events.push_back(NativeSessionEvent {
            source: source.to_string(),
            level: level.to_string(),
            message,
            created_at: now_ms(),
        });
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[derive(Debug)]
struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::PrivateKeyDer;
    use rustls::{ServerConfig, ServerConnection};
    use serde_json::Value;
    use std::net::TcpListener;
    use std::sync::atomic::AtomicUsize;

    fn minimal_ui_config() -> ProxyUiConfig {
        ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: true,
            desync_method: "disorder".to_string(),
            split_marker: Some("host+1".to_string()),
            tcp_chain_steps: Vec::new(),
            group_activation_filter: ProxyUiActivationFilter::default(),
            split_position: 0,
            split_at_host: false,
            fake_ttl: 8,
            adaptive_fake_ttl_enabled: false,
            adaptive_fake_ttl_delta: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_DELTA,
            adaptive_fake_ttl_min: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MIN,
            adaptive_fake_ttl_max: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_MAX,
            adaptive_fake_ttl_fallback: ripdpi_proxy_config::ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
            fake_sni: "www.wikipedia.org".to_string(),
            http_fake_profile: "compat_default".to_string(),
            fake_tls_use_original: false,
            fake_tls_randomize: false,
            fake_tls_dup_session_id: false,
            fake_tls_pad_encap: false,
            fake_tls_size: 0,
            fake_tls_sni_mode: "fixed".to_string(),
            tls_fake_profile: "compat_default".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            http_method_eol: false,
            http_unix_eol: false,
            tls_record_split: false,
            tls_record_split_marker: None,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: "disable".to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            udp_chain_steps: Vec::new(),
            udp_fake_profile: "compat_default".to_string(),
            drop_sack: false,
            fake_offset_marker: Some("0".to_string()),
            fake_offset: 0,
            quic_initial_mode: Some("route_and_cache".to_string()),
            quic_support_v1: true,
            quic_support_v2: true,
            quic_fake_profile: "disabled".to_string(),
            quic_fake_host: String::new(),
            host_autolearn_enabled: false,
            host_autolearn_penalty_ttl_secs: ciadpi_config::HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            host_autolearn_max_hosts: ciadpi_config::HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            host_autolearn_store_path: None,
            network_scope_key: None,
        }
    }

    fn strategy_probe_request(base_ui: ProxyUiConfig) -> ScanRequest {
        ScanRequest {
            profile_id: "automatic-probing".to_string(),
            display_name: "Automatic probing".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::StrategyProbe,
            proxy_host: None,
            proxy_port: None,
            domain_targets: vec![DomainTarget {
                host: "127.0.0.1".to_string(),
                connect_ip: None,
                https_port: Some(9),
                http_port: Some(8080),
                http_path: "/".to_string(),
            }],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            whitelist_sni: vec![],
            strategy_probe: Some(StrategyProbeRequest {
                suite_id: "quick_v1".to_string(),
                base_proxy_config_json: Some(
                    serde_json::to_string(&ProxyConfigPayload::Ui {
                        config: base_ui,
                        runtime_context: None,
                    })
                    .expect("serialize probe ui config"),
                ),
            }),
        }
    }

    #[test]
    fn probe_transport_freezes_adaptive_fake_ttl_to_seed() {
        let mut config = minimal_ui_config();
        config.fake_ttl = 11;
        config.adaptive_fake_ttl_enabled = true;
        config.adaptive_fake_ttl_delta = -1;
        config.adaptive_fake_ttl_min = 3;
        config.adaptive_fake_ttl_max = 9;
        config.adaptive_fake_ttl_fallback = 13;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_ttl, 9);
        assert!(!config.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn probe_transport_uses_fake_ttl_when_adaptive_fallback_is_invalid() {
        let mut config = minimal_ui_config();
        config.fake_ttl = 7;
        config.adaptive_fake_ttl_enabled = true;
        config.adaptive_fake_ttl_min = 3;
        config.adaptive_fake_ttl_max = 12;
        config.adaptive_fake_ttl_fallback = 0;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_ttl, 7);
        assert!(!config.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn dns_probe_reports_substitution_when_udp_and_doh_differ() {
        let udp = UdpDnsServer::start("203.0.113.10");
        let doh = HttpTextServer::start_dns_message("198.51.100.77");
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(udp.addr()),
            encrypted_resolver_id: None,
            encrypted_protocol: None,
            encrypted_host: None,
            encrypted_port: None,
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: Vec::new(),
            encrypted_doh_url: None,
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            doh_bootstrap_ips: vec!["127.0.0.1".to_string()],
            expected_ips: vec![],
        };

        let result = run_dns_probe(&target, &TransportConfig::Direct, &ScanPathMode::RawPath);
        assert_eq!(result.outcome, "dns_substitution");
    }

    #[test]
    fn dns_probe_reports_doh_blocked_when_udp_works_and_doh_fails() {
        let udp = UdpDnsServer::start("203.0.113.10");
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(udp.addr()),
            encrypted_resolver_id: None,
            encrypted_protocol: None,
            encrypted_host: None,
            encrypted_port: None,
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: Vec::new(),
            encrypted_doh_url: None,
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            doh_url: Some("http://127.0.0.1:9/dns-query".to_string()),
            doh_bootstrap_ips: vec!["127.0.0.1".to_string()],
            expected_ips: vec![],
        };

        let result = run_dns_probe(&target, &TransportConfig::Direct, &ScanPathMode::RawPath);
        assert_eq!(result.outcome, "encrypted_dns_blocked");
    }

    #[test]
    fn dns_probe_reports_match_over_socks5_udp_and_doh() {
        let udp = UdpDnsServer::start("203.0.113.10");
        let doh = HttpTextServer::start_dns_message("203.0.113.10");
        let proxy = Socks5RelayServer::start();
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(udp.addr()),
            encrypted_resolver_id: None,
            encrypted_protocol: None,
            encrypted_host: None,
            encrypted_port: None,
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: Vec::new(),
            encrypted_doh_url: None,
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            doh_bootstrap_ips: vec!["127.0.0.1".to_string()],
            expected_ips: vec![],
        };

        let result = run_dns_probe(
            &target,
            &TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: proxy.port() },
            &ScanPathMode::InPath,
        );

        println!("dns socks result: {result:?}");
        assert_eq!(result.outcome, "dns_match");
        assert!(proxy.udp_associate_attempts() >= 1);
    }

    #[test]
    fn domain_probe_reports_tls_certificate_anomaly() {
        let server = TlsHttpServer::start(TlsMode::Single("localhost".to_string()), FatServerMode::AlwaysOk);
        let target = DomainTarget {
            host: "localhost".to_string(),
            connect_ip: None,
            https_port: Some(server.port()),
            http_port: Some(9),
            http_path: "/".to_string(),
        };

        let result = run_domain_probe(&target, &TransportConfig::Direct);
        assert_eq!(result.outcome, "tls_cert_invalid");
    }

    #[test]
    fn tls_signal_reports_version_split_low_confidence() {
        let tls13 = TlsObservation {
            status: "tls_handshake_failed".to_string(),
            version: None,
            error: Some("blocked".to_string()),
            certificate_anomaly: false,
        };
        let tls12 = TlsObservation {
            status: "tls_ok".to_string(),
            version: Some("TLS1.2".to_string()),
            error: None,
            certificate_anomaly: false,
        };

        assert_eq!(classify_tls_signal(&tls13, &tls12), "tls_version_split_low_confidence");
    }

    #[test]
    fn try_tls_handshake_forces_tls_on_non_default_https_port() {
        let server = TlsHttpServer::start(TlsMode::Single("localhost".to_string()), FatServerMode::AlwaysOk);
        let target = TargetAddress::Ip(IpAddr::V4(Ipv4Addr::LOCALHOST));

        let tls = try_tls_handshake(
            &target,
            server.port(),
            &TransportConfig::Direct,
            "localhost",
            false,
            TlsClientProfile::Tls13Only,
        );

        assert_eq!(tls.status, "tls_ok");
        assert!(tls.version.is_some());
    }

    #[test]
    fn domain_probe_reports_http_blockpage() {
        let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
        let target = DomainTarget {
            host: "127.0.0.1".to_string(),
            connect_ip: None,
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
        };

        let result = run_domain_probe(&target, &TransportConfig::Direct);
        assert_eq!(result.outcome, "http_blockpage");
    }

    #[test]
    fn tcp_probe_reports_threshold_cutoff() {
        let server = PlainFatHeaderServer::start(FatServerMode::CutoffAtThreshold);
        let target = TcpTarget {
            id: "test".to_string(),
            provider: "plain-fat".to_string(),
            ip: "127.0.0.1".to_string(),
            port: server.port(),
            sni: None,
            asn: None,
            host_header: Some("plain-fat".to_string()),
            fat_header_requests: Some(16),
        };

        let result = run_tcp_probe(&target, &[], &TransportConfig::Direct);
        assert!(
            matches!(result.outcome.as_str(), "tcp_16kb_blocked" | "tcp_reset"),
            "unexpected cutoff outcome: {}",
            result.outcome
        );
    }

    #[test]
    fn tcp_probe_reports_whitelist_sni_success() {
        let server = PlainFatHeaderServer::start(FatServerMode::AllowHost("allow.example".to_string()));
        let target = TcpTarget {
            id: "test".to_string(),
            provider: "tls-fat".to_string(),
            ip: "127.0.0.1".to_string(),
            port: server.port(),
            sni: Some("deny.example".to_string()),
            asn: Some("AS1337".to_string()),
            host_header: Some("deny.example".to_string()),
            fat_header_requests: Some(8),
        };

        let result = run_tcp_probe(
            &target,
            &["allow.example".to_string(), "other.example".to_string()],
            &TransportConfig::Direct,
        );
        assert_eq!(result.outcome, "whitelist_sni_ok");
    }

    #[test]
    fn tcp_probe_reports_whitelist_sni_failure() {
        let server = PlainFatHeaderServer::start(FatServerMode::AllowHost("allow.example".to_string()));
        let target = TcpTarget {
            id: "test".to_string(),
            provider: "tls-fat".to_string(),
            ip: "127.0.0.1".to_string(),
            port: server.port(),
            sni: Some("deny.example".to_string()),
            asn: Some("AS1337".to_string()),
            host_header: Some("deny.example".to_string()),
            fat_header_requests: Some(8),
        };

        let result = run_tcp_probe(&target, &["missing.example".to_string()], &TransportConfig::Direct);
        assert_eq!(result.outcome, "whitelist_sni_failed");
    }

    #[test]
    fn strategy_probe_request_requires_base_ui_config() {
        let mut request = strategy_probe_request(minimal_ui_config());
        request.strategy_probe.as_mut().expect("strategy probe").base_proxy_config_json = None;

        let err = validate_scan_request(&request).expect_err("missing base config should fail");

        assert_eq!(err, "strategy_probe scan requires baseProxyConfigJson");
    }

    #[test]
    fn strategy_probe_request_rejects_command_line_config_payload() {
        let mut request = strategy_probe_request(minimal_ui_config());
        request.strategy_probe = Some(StrategyProbeRequest {
            suite_id: "quick_v1".to_string(),
            base_proxy_config_json: Some(
                serde_json::to_string(&ProxyConfigPayload::CommandLine {
                    args: vec!["ciadpi".to_string(), "--split".to_string()],
                    runtime_context: None,
                })
                .expect("serialize command line payload"),
            ),
        });

        let err = validate_scan_request(&request).expect_err("command line payload should fail");

        assert_eq!(err, "strategy_probe scans only support UI proxy config");
    }

    #[test]
    fn tcp_candidate_catalog_keeps_current_strategy_first() {
        let candidates = build_tcp_candidates(&minimal_ui_config());

        assert_eq!(candidates.first().map(|candidate| candidate.id), Some("baseline_current"));
        assert_eq!(candidates.len(), 11);
        assert_eq!(candidates.get(1).map(|candidate| candidate.id), Some("parser_only"));
        assert_eq!(candidates.get(2).map(|candidate| candidate.id), Some("parser_unixeol"));
        assert_eq!(candidates.get(3).map(|candidate| candidate.id), Some("parser_methodeol"));
        assert_eq!(candidates.get(7).map(|candidate| candidate.id), Some("tlsrec_fakedsplit"));
        assert_eq!(candidates.get(8).map(|candidate| candidate.id), Some("tlsrec_fakeddisorder"));
    }

    #[test]
    fn http_blockpage_reorders_tcp_candidates_toward_parser_families() {
        let ordered = reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::HttpBlockpage));
        let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

        assert_eq!(ids, vec!["baseline_current", "parser_only", "parser_unixeol", "split_host"]);
    }

    #[test]
    fn tcp_reset_reorders_tcp_candidates_toward_split_families() {
        let ordered = reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::TcpReset));
        let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

        assert_eq!(ids, vec!["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake_split"]);
    }

    #[test]
    fn silent_drop_reorders_tcp_candidates_toward_fake_tls_families() {
        let ordered = reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::SilentDrop));
        let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

        assert_eq!(ids, vec!["baseline_current", "tlsrec_fake_rich", "tlsrec_hostfake", "tlsrec_hostfake_split"]);
    }

    #[test]
    fn tls_alert_reorders_tcp_candidates_away_from_fake_heavy_paths() {
        let ordered = reorder_tcp_candidates_for_failure(&build_tcp_candidates(&minimal_ui_config()), Some(FailureClass::TlsAlert));
        let ids = ordered.iter().take(4).map(|candidate| candidate.id).collect::<Vec<_>>();

        assert_eq!(ids, vec!["baseline_current", "split_host", "tlsrec_split_host", "tlsrec_hostfake"]);
    }

    #[test]
    fn baseline_dns_tampering_uses_runtime_context_before_candidate_trials() {
        let doh = HttpTextServer::start_dns_message("198.51.100.11");
        let runtime_context =
            ProxyRuntimeContext {
                encrypted_dns: Some(ProxyEncryptedDnsContext {
                    resolver_id: Some("doh".to_string()),
                    protocol: "doh".to_string(),
                    host: "127.0.0.1".to_string(),
                    port: doh.port(),
                    tls_server_name: None,
                    bootstrap_ips: vec!["127.0.0.1".to_string()],
                    doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
                    dnscrypt_provider_name: None,
                    dnscrypt_public_key: None,
                }),
            };

        let baseline =
            detect_strategy_probe_dns_tampering(
                &[DomainTarget {
                    host: "blocked.example".to_string(),
                    connect_ip: Some("203.0.113.10".to_string()),
                    https_port: Some(443),
                    http_port: Some(80),
                    http_path: "/".to_string(),
                }],
                Some(&runtime_context),
            )
                .expect("dns tampering");

        assert_eq!(baseline.failure.class, FailureClass::DnsTampering);
        assert_eq!(baseline.failure.action, FailureAction::ResolverOverrideRecommended);
        assert_eq!(baseline.results.first().map(|result| result.outcome.as_str()), Some("dns_substitution"));
    }

    #[test]
    fn quic_probe_failures_are_surfaced_as_quic_breakage() {
        let failure =
            classify_strategy_probe_baseline_results(&[
                ProbeResult {
                    probe_type: "strategy_quic".to_string(),
                    target: "Current QUIC strategy".to_string(),
                    outcome: "quic_empty".to_string(),
                    details: vec![ProbeDetail { key: "error".to_string(), value: "none".to_string() }],
                },
            ])
                .expect("quic failure");

        assert_eq!(failure.class, FailureClass::QuicBreakage);
        assert_eq!(failure.action, FailureAction::DiagnosticsOnly);
    }

    #[test]
    fn aggressive_parser_candidates_enable_only_expected_evasion() {
        let candidates = build_tcp_candidates(&minimal_ui_config());
        let unixeol = candidates.iter().find(|candidate| candidate.id == "parser_unixeol").expect("unixeol candidate");
        let methodeol =
            candidates.iter().find(|candidate| candidate.id == "parser_methodeol").expect("methodeol candidate");

        assert!(unixeol.config.host_mixed_case);
        assert!(unixeol.config.domain_mixed_case);
        assert!(unixeol.config.host_remove_spaces);
        assert!(unixeol.config.http_unix_eol);
        assert!(!unixeol.config.http_method_eol);

        assert!(methodeol.config.host_mixed_case);
        assert!(methodeol.config.domain_mixed_case);
        assert!(methodeol.config.host_remove_spaces);
        assert!(methodeol.config.http_method_eol);
        assert!(!methodeol.config.http_unix_eol);
    }

    #[test]
    fn parser_only_candidate_keeps_aggressive_http_evasions_disabled() {
        let candidates = build_tcp_candidates(&minimal_ui_config());
        let parser_only =
            candidates.iter().find(|candidate| candidate.id == "parser_only").expect("parser_only candidate");

        assert!(parser_only.config.host_mixed_case);
        assert!(parser_only.config.domain_mixed_case);
        assert!(parser_only.config.host_remove_spaces);
        assert!(!parser_only.config.http_method_eol);
        assert!(!parser_only.config.http_unix_eol);
    }

    #[test]
    fn monitor_session_strategy_probe_returns_structured_recommendation() {
        let server = HttpTextServer::start_text("HTTP/1.1 200 OK", "probe");
        let mut request = strategy_probe_request(minimal_ui_config());
        request.domain_targets[0].http_port = Some(server.port());
        let session = MonitorSession::new();

        session.start_scan("session-strategy".to_string(), request).expect("start strategy probe");
        let report = wait_for_report(&session);
        let strategy_probe = report.strategy_probe_report.expect("strategy probe report");

        assert_eq!(report.profile_id, "automatic-probing");
        assert_eq!(
            strategy_probe.tcp_candidates.first().map(|candidate| candidate.id.as_str()),
            Some("baseline_current")
        );
        assert_eq!(
            strategy_probe.recommendation.tcp_candidate_id,
            strategy_probe.tcp_candidates.iter().find(|candidate| !candidate.skipped).expect("tcp winner").id
        );
        assert!(!strategy_probe.recommendation.recommended_proxy_config_json.is_empty());
    }

    #[test]
    fn monitor_session_drains_passive_events_with_probe_details() {
        let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
        let request = ScanRequest {
            profile_id: "default".to_string(),
            display_name: "Passive events".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            proxy_host: None,
            proxy_port: None,
            domain_targets: vec![DomainTarget {
                host: "127.0.0.1".to_string(),
                connect_ip: None,
                https_port: Some(9),
                http_port: Some(server.port()),
                http_path: "/".to_string(),
            }],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            whitelist_sni: vec![],
            strategy_probe: None,
        };
        let session = MonitorSession::new();
        session.start_scan("session-1".to_string(), request).expect("start scan");

        let report = wait_for_report(&session);
        assert_eq!(report.outcome_for("domain_reachability"), Some("http_blockpage"));

        let first = session.poll_passive_events_json().expect("poll passive events").expect("events json");
        let events: Vec<NativeSessionEvent> = serde_json::from_str(&first).expect("decode native events");
        assert!(events.iter().any(|event| event.message.contains("transport=DIRECT")));
        assert!(events.iter().any(|event| event.message.contains("http=http_blockpage")));

        let second = session.poll_passive_events_json().expect("poll passive events again").expect("events json");
        let drained: Vec<NativeSessionEvent> = serde_json::from_str(&second).expect("decode drained events");
        assert!(drained.is_empty());
    }

    #[test]
    fn monitor_json_contracts_match_goldens() {
        let server = HttpTextServer::start(move |_request| {
            thread::sleep(Duration::from_millis(60));
            b"HTTP/1.1 403 Forbidden\r\nContent-Length: 35\r\nConnection: close\r\n\r\nAccess denied by upstream filtering"
                .to_vec()
        });
        let request = ScanRequest {
            profile_id: "default".to_string(),
            display_name: "Passive events golden".to_string(),
            path_mode: ScanPathMode::RawPath,
            kind: ScanKind::Connectivity,
            proxy_host: None,
            proxy_port: None,
            domain_targets: vec![DomainTarget {
                host: "127.0.0.1".to_string(),
                connect_ip: None,
                https_port: Some(9),
                http_port: Some(server.port()),
                http_path: "/".to_string(),
            }],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            whitelist_sni: vec![],
            strategy_probe: None,
        };
        let session = MonitorSession::new();
        session.start_scan("session-golden".to_string(), request).expect("start scan");

        let progress_json = wait_for_progress_json(&session);
        assert_monitor_json_golden("progress_starting", &progress_json, server.port());

        let report_json = wait_for_report_json(&session);
        assert_monitor_json_golden("final_report", &report_json, server.port());

        let passive_events_json =
            session.poll_passive_events_json().expect("poll passive events").expect("events json");
        assert_monitor_json_golden("passive_events_first_poll", &passive_events_json, server.port());

        let drained_json = session.poll_passive_events_json().expect("poll passive events again").expect("events json");
        assert_monitor_json_golden("passive_events_second_poll", &drained_json, server.port());
    }

    struct UdpDnsServer {
        addr: SocketAddr,
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<()>>,
    }

    impl UdpDnsServer {
        fn start(answer_ip: &str) -> Self {
            let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp dns");
            socket.set_read_timeout(Some(Duration::from_millis(100))).expect("set udp timeout");
            let addr = socket.local_addr().expect("udp local addr");
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let answer_ip = answer_ip.parse::<Ipv4Addr>().expect("parse answer ip");
            let handle = thread::spawn(move || {
                let mut buf = [0u8; 512];
                while !stop_flag.load(Ordering::Relaxed) {
                    match socket.recv_from(&mut buf) {
                        Ok((size, peer)) => {
                            if let Ok(response) = build_udp_dns_answer(&buf[..size], answer_ip) {
                                let _ = socket.send_to(&response, peer);
                            }
                        }
                        Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                        Err(_) => break,
                    }
                }
            });
            Self { addr, stop, handle: Some(handle) }
        }

        fn addr(&self) -> String {
            self.addr.to_string()
        }
    }

    impl Drop for UdpDnsServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind wake udp");
            let _ = wake.send_to(b"wake", self.addr);
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join udp dns");
            }
        }
    }

    struct Socks5RelayServer {
        addr: SocketAddr,
        stop: Arc<AtomicBool>,
        udp_associate_attempts: Arc<AtomicUsize>,
        handle: Option<JoinHandle<()>>,
    }

    impl Socks5RelayServer {
        fn start() -> Self {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks5 relay");
            listener.set_nonblocking(true).expect("set socks5 relay nonblocking");
            let addr = listener.local_addr().expect("socks5 relay addr");
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let udp_associate_attempts = Arc::new(AtomicUsize::new(0));
            let udp_attempts_ref = udp_associate_attempts.clone();
            let handle = thread::spawn(move || {
                let udp_relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks udp relay");
                udp_relay.set_read_timeout(Some(Duration::from_millis(100))).expect("set socks udp timeout");
                let udp_local = udp_relay.local_addr().expect("socks udp local addr");
                let udp_socket = udp_relay.try_clone().expect("clone socks udp relay");
                let udp_stop = stop_flag.clone();
                thread::spawn(move || {
                    let mut frame = [0u8; 65535];
                    while !udp_stop.load(Ordering::Relaxed) {
                        match udp_socket.recv_from(&mut frame) {
                            Ok((size, peer)) => {
                                if let Ok((destination, payload)) = decode_socks5_udp_frame(&frame[..size]) {
                                    let forward = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind udp forward");
                                    forward.set_read_timeout(Some(IO_TIMEOUT)).expect("set udp forward timeout");
                                    let _ = forward.send_to(&payload, destination);
                                    let mut response = [0u8; 2048];
                                    if let Ok((read, from)) = forward.recv_from(&mut response) {
                                        let reply = encode_socks5_udp_frame(from, &response[..read]);
                                        let _ = udp_socket.send_to(&reply, peer);
                                    }
                                }
                            }
                            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                            Err(_) => break,
                        }
                    }
                });

                while !stop_flag.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let udp_attempts_ref = udp_attempts_ref.clone();
                            thread::spawn(move || {
                                let _ = stream.set_nonblocking(false);
                                let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
                                if read_socks_greeting(&mut stream).is_err() {
                                    return;
                                }
                                let _ = stream.write_all(&[0x05, 0x00]);

                                let mut header = [0u8; 4];
                                if stream.read_exact(&mut header).is_err() {
                                    return;
                                }
                                match header[1] {
                                    0x03 => {
                                        udp_attempts_ref.fetch_add(1, Ordering::Relaxed);
                                        if consume_socks_addr(&mut stream, header[3]).is_err() {
                                            return;
                                        }
                                        let reply = encode_socks_reply(udp_local);
                                        let _ = stream.write_all(&reply);
                                        let mut drain = [0u8; 16];
                                        loop {
                                            match stream.read(&mut drain) {
                                                Ok(0) => break,
                                                Ok(_) => {}
                                                Err(err)
                                                    if matches!(
                                                        err.kind(),
                                                        ErrorKind::WouldBlock | ErrorKind::TimedOut
                                                    ) => {}
                                                Err(_) => break,
                                            }
                                            thread::sleep(Duration::from_millis(20));
                                        }
                                    }
                                    0x01 => {
                                        let target = match read_socks_target(&mut stream, header[3]) {
                                            Ok(target) => target,
                                            Err(_) => return,
                                        };
                                        let upstream = match TcpStream::connect_timeout(&target, CONNECT_TIMEOUT) {
                                            Ok(stream) => stream,
                                            Err(_) => return,
                                        };
                                        let reply_addr = upstream
                                            .local_addr()
                                            .unwrap_or_else(|_| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
                                        let _ = stream.write_all(&encode_socks_reply(reply_addr));
                                        relay_bidirectional(stream, upstream);
                                    }
                                    _ => {}
                                }
                            });
                        }
                        Err(err) if err.kind() == ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(20));
                        }
                        Err(_) => break,
                    }
                }
            });

            Self { addr, stop, udp_associate_attempts, handle: Some(handle) }
        }

        fn port(&self) -> u16 {
            self.addr.port()
        }

        fn udp_associate_attempts(&self) -> usize {
            self.udp_associate_attempts.load(Ordering::Relaxed)
        }
    }

    impl Drop for Socks5RelayServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let _ = TcpStream::connect(self.addr);
            let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind socks wake udp");
            let _ = wake.send_to(b"wake", self.addr);
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join socks5 relay");
            }
        }
    }

    fn encode_socks_reply(address: SocketAddr) -> Vec<u8> {
        match address {
            SocketAddr::V4(addr) => {
                let mut reply = vec![0x05, 0x00, 0x00, 0x01];
                reply.extend_from_slice(&addr.ip().octets());
                reply.extend_from_slice(&addr.port().to_be_bytes());
                reply
            }
            SocketAddr::V6(addr) => {
                let mut reply = vec![0x05, 0x00, 0x00, 0x04];
                reply.extend_from_slice(&addr.ip().octets());
                reply.extend_from_slice(&addr.port().to_be_bytes());
                reply
            }
        }
    }

    fn read_socks_greeting(stream: &mut TcpStream) -> Result<(), std::io::Error> {
        let mut header = [0u8; 2];
        stream.read_exact(&mut header)?;
        let methods_len = header[1] as usize;
        let mut methods = vec![0u8; methods_len];
        stream.read_exact(&mut methods)?;
        Ok(())
    }

    fn consume_socks_addr(stream: &mut TcpStream, atyp: u8) -> Result<(), std::io::Error> {
        match atyp {
            0x01 => {
                let mut buf = [0u8; 6];
                stream.read_exact(&mut buf)
            }
            0x04 => {
                let mut buf = [0u8; 18];
                stream.read_exact(&mut buf)
            }
            0x03 => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len)?;
                let mut buf = vec![0u8; len[0] as usize + 2];
                stream.read_exact(&mut buf)
            }
            _ => Err(std::io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
        }
    }

    fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> Result<SocketAddr, std::io::Error> {
        match atyp {
            0x01 => {
                let mut addr = [0u8; 4];
                let mut port = [0u8; 2];
                stream.read_exact(&mut addr)?;
                stream.read_exact(&mut port)?;
                Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port)))
            }
            0x03 => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len)?;
                let mut domain = vec![0u8; len[0] as usize];
                let mut port = [0u8; 2];
                stream.read_exact(&mut domain)?;
                stream.read_exact(&mut port)?;
                let domain = String::from_utf8_lossy(&domain).to_string();
                (domain.as_str(), u16::from_be_bytes(port))
                    .to_socket_addrs()?
                    .next()
                    .ok_or_else(|| std::io::Error::new(ErrorKind::AddrNotAvailable, "no target addr"))
            }
            0x04 => {
                let mut addr = [0u8; 16];
                let mut port = [0u8; 2];
                stream.read_exact(&mut addr)?;
                stream.read_exact(&mut port)?;
                Ok(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::from(addr)), u16::from_be_bytes(port)))
            }
            _ => Err(std::io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
        }
    }

    fn relay_bidirectional(client: TcpStream, upstream: TcpStream) {
        let mut client_reader = client.try_clone().expect("clone client reader");
        let mut client_writer = client;
        let mut upstream_reader = upstream.try_clone().expect("clone upstream reader");
        let mut upstream_writer = upstream;
        let to_upstream = thread::spawn(move || {
            let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
        });
        let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
        let _ = to_upstream.join();
    }

    struct HttpTextServer {
        addr: SocketAddr,
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<()>>,
    }

    impl HttpTextServer {
        fn start_text(status_line: &str, body: &str) -> Self {
            let status_line = status_line.to_string();
            let body = body.to_string();
            Self::start(move |_request| {
                format!("{status_line}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
                    .into_bytes()
            })
        }

        fn start_dns_message(answer_ip: &str) -> Self {
            let answer_ip: Ipv4Addr = answer_ip.parse().expect("valid DoH answer IP");
            Self::start(move |mut request| {
                let body = read_http_body(&mut request);
                let response_body = build_udp_dns_answer(&body, answer_ip).expect("build DNS answer");
                let headers = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    response_body.len()
                );
                let mut response = headers.into_bytes();
                response.extend_from_slice(&response_body);
                response
            })
        }

        fn start<F>(handler: F) -> Self
        where
            F: Fn(Vec<u8>) -> Vec<u8> + Send + Sync + 'static,
        {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind http text");
            listener.set_nonblocking(true).expect("set http text nonblocking");
            let addr = listener.local_addr().expect("http text addr");
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let handler = Arc::new(handler);
            let handle = thread::spawn(move || {
                while !stop_flag.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let handler = handler.clone();
                            thread::spawn(move || {
                                let request = read_http_request(&mut stream);
                                let response = handler(request);
                                let _ = stream.write_all(&response);
                                let _ = stream.flush();
                            });
                        }
                        Err(err) if err.kind() == ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(20));
                        }
                        Err(_) => break,
                    }
                }
            });
            Self { addr, stop, handle: Some(handle) }
        }

        fn port(&self) -> u16 {
            self.addr.port()
        }
    }

    impl Drop for HttpTextServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let _ = TcpStream::connect(self.addr);
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join http text");
            }
        }
    }

    fn wait_for_report(session: &MonitorSession) -> ScanReport {
        for _ in 0..50 {
            if let Some(report_json) = session.take_report_json().expect("take report json") {
                return serde_json::from_str(&report_json).expect("decode scan report");
            }
            thread::sleep(Duration::from_millis(50));
        }
        panic!("timed out waiting for scan report");
    }

    fn wait_for_progress_json(session: &MonitorSession) -> String {
        let mut finished_progress = None;
        for _ in 0..50 {
            if let Some(progress_json) = session.poll_progress_json().expect("poll progress json") {
                let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode scan progress");
                if !progress.is_finished {
                    return progress_json;
                }
                finished_progress = Some(progress_json);
            }
            thread::sleep(Duration::from_millis(20));
        }
        finished_progress.unwrap_or_else(|| panic!("timed out waiting for scan progress"))
    }

    fn wait_for_report_json(session: &MonitorSession) -> String {
        for _ in 0..50 {
            if let Some(report_json) = session.take_report_json().expect("take report json") {
                return report_json;
            }
            thread::sleep(Duration::from_millis(50));
        }
        panic!("timed out waiting for scan report");
    }

    fn assert_monitor_json_golden(name: &str, actual_json: &str, http_port: u16) {
        let actual = canonicalize_json_with(actual_json, |value| scrub_monitor_json(value, http_port))
            .expect("canonicalize monitor json");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn scrub_monitor_json(value: &mut Value, http_port: u16) {
        match value {
            Value::Array(items) => {
                for item in items {
                    scrub_monitor_json(item, http_port);
                }
            }
            Value::Object(map) => {
                for (key, item) in map.iter_mut() {
                    if matches!(key.as_str(), "startedAt" | "finishedAt" | "createdAt") {
                        *item = Value::from(0);
                    } else {
                        scrub_monitor_json(item, http_port);
                    }
                }
            }
            Value::String(text) => {
                *text = text.replace(&format!("127.0.0.1:{http_port}"), "127.0.0.1:<port>");
            }
            _ => {}
        }
    }

    trait ScanReportExt {
        fn outcome_for(&self, probe_type: &str) -> Option<&str>;
    }

    impl ScanReportExt for ScanReport {
        fn outcome_for(&self, probe_type: &str) -> Option<&str> {
            self.results.iter().find(|result| result.probe_type == probe_type).map(|result| result.outcome.as_str())
        }
    }

    #[derive(Clone)]
    enum TlsMode {
        Single(String),
    }

    #[derive(Clone)]
    enum FatServerMode {
        AlwaysOk,
        CutoffAtThreshold,
        AllowHost(String),
    }

    struct TlsHttpServer {
        addr: SocketAddr,
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<()>>,
    }

    impl TlsHttpServer {
        fn start(mode: TlsMode, fat_mode: FatServerMode) -> Self {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tls server");
            listener.set_nonblocking(true).expect("set tls server nonblocking");
            let addr = listener.local_addr().expect("tls server addr");
            let config = match mode {
                TlsMode::Single(name) => {
                    let (cert, key) = make_cert(&[name]);
                    Arc::new(
                        ServerConfig::builder()
                            .with_no_client_auth()
                            .with_single_cert(vec![cert], key)
                            .expect("single cert config"),
                    )
                }
            };
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let handle = thread::spawn(move || {
                while !stop_flag.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let config = config.clone();
                            let fat_mode = fat_mode.clone();
                            thread::spawn(move || {
                                handle_tls_client(stream, config, fat_mode);
                            });
                        }
                        Err(err) if err.kind() == ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(20));
                        }
                        Err(_) => break,
                    }
                }
            });
            Self { addr, stop, handle: Some(handle) }
        }

        fn port(&self) -> u16 {
            self.addr.port()
        }
    }

    impl Drop for TlsHttpServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let _ = TcpStream::connect(self.addr);
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join tls server");
            }
        }
    }

    struct PlainFatHeaderServer {
        addr: SocketAddr,
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<()>>,
    }

    impl PlainFatHeaderServer {
        fn start(mode: FatServerMode) -> Self {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind plain fat");
            listener.set_nonblocking(true).expect("set plain fat nonblocking");
            let addr = listener.local_addr().expect("plain fat addr");
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let handle = thread::spawn(move || {
                while !stop_flag.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let mode = mode.clone();
                            thread::spawn(move || {
                                handle_plain_fat_client(&mut stream, mode);
                            });
                        }
                        Err(err) if err.kind() == ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(20));
                        }
                        Err(_) => break,
                    }
                }
            });
            Self { addr, stop, handle: Some(handle) }
        }

        fn port(&self) -> u16 {
            self.addr.port()
        }
    }

    impl Drop for PlainFatHeaderServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let _ = TcpStream::connect(self.addr);
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join plain fat");
            }
        }
    }

    fn handle_tls_client(stream: TcpStream, config: Arc<ServerConfig>, fat_mode: FatServerMode) {
        let connection = match ServerConnection::new(config) {
            Ok(connection) => connection,
            Err(_) => return,
        };
        let mut stream = StreamOwned::new(connection, stream);
        handle_fat_http_stream(&mut stream, fat_mode);
    }

    fn handle_plain_fat_client(stream: &mut TcpStream, fat_mode: FatServerMode) {
        handle_fat_http_stream(stream, fat_mode);
    }

    fn handle_fat_http_stream(stream: &mut impl ReadWrite, fat_mode: FatServerMode) {
        let mut total_read = 0usize;
        loop {
            let request = read_until_marker(stream, b"\r\n\r\n");
            if request.is_empty() {
                return;
            }
            total_read += request.len();
            let request_text = String::from_utf8_lossy(&request).to_ascii_lowercase();
            if let FatServerMode::AllowHost(expected) = &fat_mode {
                if !request_text.contains(&expected.to_ascii_lowercase()) {
                    return;
                }
            }
            if matches!(&fat_mode, FatServerMode::CutoffAtThreshold) && total_read >= FAT_HEADER_THRESHOLD_BYTES {
                return;
            }
            let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
            let _ = stream.write_all(response);
            let _ = stream.flush();
        }
    }

    trait ReadWrite: Read + Write {}

    impl<T: Read + Write> ReadWrite for T {}

    fn read_until_marker(stream: &mut impl Read, marker: &[u8]) -> Vec<u8> {
        let mut buf = Vec::new();
        let mut chunk = [0u8; 1];
        while !buf.windows(marker.len()).any(|window| window == marker) {
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(read) => buf.extend_from_slice(&chunk[..read]),
                Err(_) => break,
            }
        }
        buf
    }

    fn read_http_request(stream: &mut impl Read) -> Vec<u8> {
        let mut request = read_until_marker(stream, b"\r\n\r\n");
        let header_len = request
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .map(|offset| offset + 4)
            .unwrap_or(request.len());
        let header_text = String::from_utf8_lossy(&request[..header_len]).into_owned();
        let content_length = header_text
            .lines()
            .find_map(|line| {
                let (name, value) = line.split_once(':')?;
                name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
            })
            .unwrap_or(0);
        let mut body = vec![0u8; content_length];
        if content_length > 0 {
            stream.read_exact(&mut body).expect("read http body");
            request.extend_from_slice(&body);
        }
        request
    }

    fn read_http_body(request: &mut Vec<u8>) -> Vec<u8> {
        let header_len = request
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .map(|offset| offset + 4)
            .unwrap_or(request.len());
        let header_text = String::from_utf8_lossy(&request[..header_len]).into_owned();
        let content_length = header_text
            .lines()
            .find_map(|line| {
                let (name, value) = line.split_once(':')?;
                name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
            })
            .unwrap_or(0);
        let body = request.split_off(header_len);
        if body.len() != content_length {
            panic!("unexpected DoH request body length: expected {content_length}, got {}", body.len());
        }
        body
    }

    fn build_udp_dns_answer(request: &[u8], answer_ip: Ipv4Addr) -> Result<Vec<u8>, String> {
        if request.len() < 12 {
            return Err("short request".to_string());
        }
        let mut answer = Vec::new();
        answer.extend(&request[0..2]);
        answer.extend(0x8180u16.to_be_bytes());
        answer.extend(1u16.to_be_bytes());
        answer.extend(1u16.to_be_bytes());
        answer.extend(0u16.to_be_bytes());
        answer.extend(0u16.to_be_bytes());
        answer.extend(&request[12..]);
        answer.extend([0xc0, 0x0c]);
        answer.extend(1u16.to_be_bytes());
        answer.extend(1u16.to_be_bytes());
        answer.extend(60u32.to_be_bytes());
        answer.extend(4u16.to_be_bytes());
        answer.extend(answer_ip.octets());
        Ok(answer)
    }

    fn make_cert(names: &[String]) -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
        let certified = generate_simple_self_signed(names.to_vec()).expect("generate cert");
        let cert = certified.cert.der().clone();
        let key = PrivateKeyDer::Pkcs8(certified.key_pair.serialize_der().into());
        (cert, key)
    }

    #[test]
    fn default_runtime_encrypted_dns_context_uses_google() {
        let ctx = default_runtime_encrypted_dns_context();
        assert_eq!(ctx.resolver_id.as_deref(), Some("google"));
        assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.google"));
        assert!(ctx.bootstrap_ips.iter().any(|ip| ip == "8.8.8.8"));
    }
}
