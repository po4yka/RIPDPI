use std::collections::{BTreeSet, HashMap};
use std::fmt::Debug;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    StreamOwned,
};
use serde::{Deserialize, Serialize};
use url::Url;

const DEFAULT_DNS_SERVER: &str = "1.1.1.1:53";
const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const IO_TIMEOUT: Duration = Duration::from_millis(1200);
const MAX_HTTP_BYTES: usize = 64 * 1024;
const FAT_HEADER_REQUESTS: usize = 16;
const FAT_HEADER_THRESHOLD_BYTES: usize = 16 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanPathMode {
    RawPath,
    InPath,
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
    pub doh_url: Option<String>,
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
pub struct ScanRequest {
    pub profile_id: String,
    pub display_name: String,
    pub path_mode: ScanPathMode,
    pub proxy_host: Option<String>,
    pub proxy_port: Option<u16>,
    pub domain_targets: Vec<DomainTarget>,
    pub dns_targets: Vec<DnsTarget>,
    pub tcp_targets: Vec<TcpTarget>,
    pub whitelist_sni: Vec<String>,
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
    passive_events: Vec<NativeSessionEvent>,
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

fn default_http_path() -> String {
    "/".to_string()
}

fn run_scan(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>, session_id: String, request: ScanRequest) {
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

fn transport_for_request(request: &ScanRequest) -> TransportConfig {
    match (&request.path_mode, request.proxy_host.as_ref(), request.proxy_port) {
        (ScanPathMode::InPath, Some(host), Some(port)) => TransportConfig::Socks5 { host: host.clone(), port },
        _ => TransportConfig::Direct,
    }
}

fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let doh_url = target.doh_url.clone().unwrap_or_else(|| DEFAULT_DOH_URL.to_string());
    let udp_result = resolve_via_udp(&target.domain, &udp_server, transport);
    let doh_result = resolve_via_doh(&target.domain, &doh_url, transport);
    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();

    let outcome = match (&udp_result, &doh_result) {
        (Ok(udp_ips), Ok(doh_ips)) if ip_set(udp_ips) == ip_set(doh_ips) => {
            if !expected.is_empty() && ip_set(udp_ips) != expected {
                "dns_expected_mismatch".to_string()
            } else {
                "dns_match".to_string()
            }
        }
        (Ok(_), Ok(_)) => "dns_substitution".to_string(),
        (Ok(_), Err(_)) => "doh_blocked".to_string(),
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
            ProbeDetail { key: "dohUrl".to_string(), value: doh_url },
            ProbeDetail { key: "dohAddresses".to_string(), value: format_result_set(&doh_result) },
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
    let tls13 = try_tls_handshake(
        &connect_target,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
    );
    let tls12 = try_tls_handshake(
        &connect_target,
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
    );
    let http = try_http_request(
        &connect_target,
        http_port,
        transport,
        &target.host,
        &target.http_path,
        false,
    );
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

fn domain_connect_target(target: &DomainTarget) -> TargetAddress {
    target
        .connect_ip
        .as_ref()
        .and_then(|ip| ip.parse::<IpAddr>().ok())
        .map(TargetAddress::Ip)
        .unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
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

fn resolve_via_doh(domain: &str, doh_url: &str, transport: &TransportConfig) -> Result<Vec<String>, String> {
    let url = Url::parse(doh_url).map_err(|err| err.to_string())?;
    let mut path = url.path().to_string();
    if path.is_empty() {
        path.push('/');
    }
    if let Some(query) = url.query() {
        path.push('?');
        path.push_str(query);
        path.push('&');
    } else {
        path.push('?');
    }
    path.push_str(&format!("name={domain}&type=A"));
    let secure = url.scheme() == "https";
    let host = url.host_str().ok_or_else(|| "DoH URL missing host".to_string())?.to_string();
    let port = url.port_or_known_default().ok_or_else(|| "DoH URL missing port".to_string())?;
    let target = TargetAddress::Host(host.clone());
    let response = execute_http_request(&target, port, transport, &host, &path, secure)?;
    let payload: DnsJsonResponse = serde_json::from_slice(&response.body).map_err(|err| err.to_string())?;
    let mut ips = Vec::new();
    for answer in payload.answers {
        if answer.record_type == 1 && answer.data.parse::<IpAddr>().is_ok() {
            ips.push(answer.data);
        }
    }
    if ips.is_empty() {
        return Err("doh_empty".to_string());
    }
    Ok(ips)
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
        guard.passive_events.push(NativeSessionEvent {
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

#[derive(Debug, Deserialize)]
struct DnsJsonResponse {
    #[serde(rename = "Answer", default)]
    answers: Vec<DnsJsonAnswer>,
}

#[derive(Debug, Deserialize)]
struct DnsJsonAnswer {
    #[serde(rename = "type")]
    record_type: u16,
    data: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::PrivateKeyDer;
    use rustls::{ServerConfig, ServerConnection};
    use std::net::TcpListener;
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn dns_probe_reports_substitution_when_udp_and_doh_differ() {
        let udp = UdpDnsServer::start("203.0.113.10");
        let doh = HttpTextServer::start_json(r#"{"Answer":[{"type":1,"data":"198.51.100.77"}]}"#);
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(udp.addr()),
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
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
            doh_url: Some("http://127.0.0.1:9/dns-query".to_string()),
            expected_ips: vec![],
        };

        let result = run_dns_probe(&target, &TransportConfig::Direct, &ScanPathMode::RawPath);
        assert_eq!(result.outcome, "doh_blocked");
    }

    #[test]
    fn dns_probe_reports_match_over_socks5_udp_and_doh() {
        let udp = UdpDnsServer::start("203.0.113.10");
        let doh = HttpTextServer::start_json(r#"{"Answer":[{"type":1,"data":"203.0.113.10"}]}"#);
        let proxy = Socks5RelayServer::start();
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(udp.addr()),
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
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
        let target = TargetAddress::Host("localhost".to_string());

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
        assert_eq!(result.outcome, "tcp_16kb_blocked");
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
    fn monitor_session_drains_passive_events_with_probe_details() {
        let server = HttpTextServer::start_text("HTTP/1.1 403 Forbidden", "Access denied by upstream filtering");
        let request = ScanRequest {
            profile_id: "default".to_string(),
            display_name: "Passive events".to_string(),
            path_mode: ScanPathMode::RawPath,
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
            whitelist_sni: vec![],
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

        fn start_json(body: &str) -> Self {
            let body = body.to_string();
            Self::start(move |_request| {
                format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/dns-json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                )
                .into_bytes()
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
                                let request = read_until_marker(&mut stream, b"\r\n\r\n");
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
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let handle = thread::spawn(move || {
                let config = match mode.clone() {
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
}
