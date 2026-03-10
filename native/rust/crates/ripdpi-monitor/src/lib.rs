use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DnsTarget {
    pub domain: String,
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

#[derive(Default)]
struct SharedState {
    progress: Option<ScanProgress>,
    report: Option<ScanReport>,
}

pub struct MonitorSession {
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl MonitorSession {
    pub fn new() -> Self {
        Self {
            shared: Arc::new(Mutex::new(SharedState::default())),
            cancel: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
        }
    }

    pub fn start_scan(
        &self,
        session_id: String,
        request: ScanRequest,
    ) -> Result<(), String> {
        let mut worker_guard = self.worker.lock().map_err(|_| "monitor worker poisoned".to_string())?;
        if worker_guard.is_some() {
            return Err("diagnostics scan already running".to_string());
        }
        self.cancel.store(false, Ordering::Relaxed);
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
        shared
            .progress
            .as_ref()
            .map(serde_json::to_string)
            .transpose()
            .map_err(|err| err.to_string())
    }

    pub fn take_report_json(&self) -> Result<Option<String>, String> {
        self.try_join_worker();
        let shared = self.shared.lock().map_err(|_| "monitor shared state poisoned".to_string())?;
        shared
            .report
            .as_ref()
            .map(serde_json::to_string)
            .transpose()
            .map_err(|err| err.to_string())
    }

    pub fn poll_passive_events_json(&self) -> Result<Option<String>, String> {
        Ok(Some("[]".to_string()))
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

fn run_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
) {
    let started_at = now_ms();
    let total_steps = request.dns_targets.len() + request.domain_targets.len() + request.tcp_targets.len();
    let total_steps = total_steps.max(1);
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

    for dns_target in &request.dns_targets {
        if cancel.load(Ordering::Relaxed) {
            persist_cancelled_report(shared, session_id, request, started_at, results);
            return;
        }
        let probe = run_dns_probe(dns_target);
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
        let probe = run_domain_probe(domain_target);
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
        let mut probe = run_tcp_probe(tcp_target);
        if tcp_target.port == 443 && !request.whitelist_sni.is_empty() {
            probe.details.push(ProbeDetail {
                key: "candidateWhitelistSni".to_string(),
                value: request.whitelist_sni[0].clone(),
            });
        }
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

    let success_count = results.iter().filter(|result| result.outcome.contains("ok")).count();
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

fn run_dns_probe(target: &DnsTarget) -> ProbeResult {
    let outcome = resolve_host(&target.domain, 443)
        .map(|addresses| {
            if addresses.is_empty() {
                "dns_empty".to_string()
            } else {
                "dns_ok".to_string()
            }
        })
        .unwrap_or_else(|_| "dns_fail".to_string());

    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: vec![],
    }
}

fn run_domain_probe(target: &DomainTarget) -> ProbeResult {
    let https = try_tcp_connect(&target.host, 443);
    let http = try_tcp_connect(&target.host, 80);
    let outcome = if https.is_ok() {
        "https_ok"
    } else if http.is_ok() {
        "http_only"
    } else {
        "unreachable"
    };

    ProbeResult {
        probe_type: "domain_reachability".to_string(),
        target: target.host.clone(),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail {
                key: "https".to_string(),
                value: https.unwrap_or_else(|err| err),
            },
            ProbeDetail {
                key: "http".to_string(),
                value: http.unwrap_or_else(|err| err),
            },
        ],
    }
}

fn run_tcp_probe(target: &TcpTarget) -> ProbeResult {
    let result = try_direct_socket(&target.ip, target.port);
    let outcome = if result.is_ok() { "tcp_ok" } else { "tcp_timeout" };
    ProbeResult {
        probe_type: "tcp_fat_header".to_string(),
        target: format!("{}:{} ({})", target.ip, target.port, target.provider),
        outcome: outcome.to_string(),
        details: vec![
            ProbeDetail {
                key: "provider".to_string(),
                value: target.provider.clone(),
            },
            ProbeDetail {
                key: "status".to_string(),
                value: result.unwrap_or_else(|err| err),
            },
        ],
    }
}

fn resolve_host(host: &str, port: u16) -> Result<Vec<SocketAddr>, String> {
    (host, port)
        .to_socket_addrs()
        .map(|iter| iter.collect())
        .map_err(|err| err.to_string())
}

fn try_tcp_connect(host: &str, port: u16) -> Result<String, String> {
    let mut addresses = resolve_host(host, port)?;
    let Some(address) = addresses.pop() else {
        return Err("no_addresses".to_string());
    };
    TcpStream::connect_timeout(&address, Duration::from_secs(5))
        .map(|_| "connected".to_string())
        .map_err(|err| err.to_string())
}

fn try_direct_socket(ip: &str, port: u16) -> Result<String, String> {
    let socket_addr: SocketAddr = format!("{ip}:{port}")
        .parse::<SocketAddr>()
        .map_err(|err| err.to_string())?;
    TcpStream::connect_timeout(&socket_addr, Duration::from_secs(5))
        .map(|_| "connected".to_string())
        .map_err(|err| err.to_string())
}

fn set_progress(
    shared: &Arc<Mutex<SharedState>>,
    progress: ScanProgress,
) {
    if let Ok(mut guard) = shared.lock() {
        guard.progress = Some(progress);
    }
}

fn set_report(
    shared: &Arc<Mutex<SharedState>>,
    report: ScanReport,
) {
    if let Ok(mut guard) = shared.lock() {
        guard.report = Some(report);
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
