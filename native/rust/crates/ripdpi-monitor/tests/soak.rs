use std::io::{self, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use local_network_fixture::{FixtureConfig, FixtureManifest, FixtureStack};
use native_soak_support::{
    acquire_global_lock, assert_growth, write_json_artifact, GrowthThresholds, SoakProfile, SoakSampler, WARMUP_WINDOW,
};
use ripdpi_monitor::{
    DiagnosticProfileFamily, DnsTarget, DomainTarget, MonitorSession, NativeSessionEvent, ScanKind, ScanPathMode,
    ScanProgress, ScanReport, ScanRequest, TcpTarget,
};
use serde_json::json;

const POLL_INTERVAL: Duration = Duration::from_millis(100);
const PROGRESS_TIMEOUT: Duration = Duration::from_secs(30);

#[test]
#[ignore = "requires RIPDPI_RUN_SOAK=1"]
fn diagnostics_session_soak() {
    if !SoakProfile::is_enabled() {
        eprintln!("skipping diagnostics_session_soak because RIPDPI_RUN_SOAK!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let cycles = profile.pick_count(100, 500);

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let server = FatHttpServer::start();
    let artifact_guard =
        FixtureArtifactGuard::new("diagnostics_session_soak", fixture.manifest().clone(), fixture.events());

    let completed = Arc::new(AtomicUsize::new(0));
    let passive_events = Arc::new(AtomicUsize::new(0));
    let sampler = {
        let completed = completed.clone();
        let passive_events = passive_events.clone();
        let events = fixture.events();
        SoakSampler::start("diagnostics_session_soak", move || {
            json!({
                "completedScans": completed.load(Ordering::Relaxed),
                "passiveEvents": passive_events.load(Ordering::Relaxed),
                "fixtureEventCount": events.snapshot().len(),
            })
        })
        .expect("start diagnostics session sampler")
    };

    for cycle in 0..cycles {
        let session = MonitorSession::new();
        session
            .start_scan(format!("diagnostics-session-{cycle}"), scan_request(fixture.manifest(), server.port()).into())
            .expect("start diagnostics scan");
        let report = wait_for_report(&session, &passive_events);
        assert_report_success(&report);
        session.destroy();
        completed.fetch_add(1, Ordering::Relaxed);
    }

    let samples = sampler.finish().expect("finish diagnostics session sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 16 * 1024 * 1024, fd_growth: 8, thread_growth: 4 },
    )
    .expect("diagnostics session growth thresholds");
}

#[test]
#[ignore = "requires RIPDPI_RUN_SOAK=1"]
fn diagnostics_polling_soak() {
    if !SoakProfile::is_enabled() {
        eprintln!("skipping diagnostics_polling_soak because RIPDPI_RUN_SOAK!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let duration = profile.pick_duration(Duration::from_secs(300), Duration::from_secs(600));

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let server = FatHttpServer::start();
    let artifact_guard =
        FixtureArtifactGuard::new("diagnostics_polling_soak", fixture.manifest().clone(), fixture.events());

    let completed = Arc::new(AtomicUsize::new(0));
    let passive_events = Arc::new(AtomicUsize::new(0));
    let progress_polls = Arc::new(AtomicUsize::new(0));
    let sampler = {
        let completed = completed.clone();
        let passive_events = passive_events.clone();
        let progress_polls = progress_polls.clone();
        let events = fixture.events();
        SoakSampler::start("diagnostics_polling_soak", move || {
            json!({
                "completedScans": completed.load(Ordering::Relaxed),
                "passiveEvents": passive_events.load(Ordering::Relaxed),
                "progressPolls": progress_polls.load(Ordering::Relaxed),
                "fixtureEventCount": events.snapshot().len(),
            })
        })
        .expect("start diagnostics polling sampler")
    };

    let deadline = Instant::now() + duration;
    while Instant::now() < deadline {
        let session = MonitorSession::new();
        let session_id = format!("diagnostics-polling-{}", completed.load(Ordering::Relaxed));
        session
            .start_scan(session_id, scan_request(fixture.manifest(), server.port()).into())
            .expect("start diagnostics polling scan");
        let report = wait_for_report_with_progress(&session, &progress_polls, &passive_events);
        assert_report_success(&report);
        session.destroy();
        completed.fetch_add(1, Ordering::Relaxed);
    }

    let samples = sampler.finish().expect("finish diagnostics polling sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 16 * 1024 * 1024, fd_growth: 8, thread_growth: 4 },
    )
    .expect("diagnostics polling growth thresholds");
}

#[derive(Clone)]
struct FixtureArtifactGuard {
    scenario: &'static str,
    manifest: FixtureManifest,
    events: local_network_fixture::EventLog,
}

impl FixtureArtifactGuard {
    fn new(scenario: &'static str, manifest: FixtureManifest, events: local_network_fixture::EventLog) -> Self {
        Self { scenario, manifest, events }
    }

    fn persist(&self) {
        let _ = write_json_artifact(&format!("{}.fixture-manifest.json", self.scenario), &self.manifest);
        let _ = write_json_artifact(&format!("{}.fixture-events.json", self.scenario), &self.events.snapshot());
    }
}

fn wait_for_report(session: &MonitorSession, passive_events: &AtomicUsize) -> ScanReport {
    wait_for_report_with_progress(session, &AtomicUsize::new(0), passive_events)
}

fn wait_for_report_with_progress(
    session: &MonitorSession,
    progress_polls: &AtomicUsize,
    passive_events: &AtomicUsize,
) -> ScanReport {
    let started = Instant::now();
    let mut last_progress_change = Instant::now();
    let mut last_progress: Option<ScanProgress> = None;
    loop {
        if let Some(progress_json) = session.poll_progress_json().expect("poll progress json") {
            progress_polls.fetch_add(1, Ordering::Relaxed);
            let progress: ScanProgress = serde_json::from_str(&progress_json).expect("decode progress");
            if last_progress.as_ref().is_none_or(|previous| {
                previous.completed_steps != progress.completed_steps
                    || previous.phase != progress.phase
                    || previous.message != progress.message
            }) {
                last_progress_change = Instant::now();
                last_progress = Some(progress);
            }
        }

        if let Some(events_json) = session.poll_passive_events_json().expect("poll passive events json") {
            let events: Vec<NativeSessionEvent> = serde_json::from_str(&events_json).expect("decode passive events");
            passive_events.fetch_add(events.len(), Ordering::Relaxed);
        }

        if let Some(report_json) = session.take_report_json().expect("take report json") {
            return serde_json::from_str(&report_json).expect("decode report");
        }

        assert!(
            started.elapsed() <= PROGRESS_TIMEOUT || last_progress_change.elapsed() <= PROGRESS_TIMEOUT,
            "diagnostics made no progress for more than {PROGRESS_TIMEOUT:?}"
        );
        thread::sleep(POLL_INTERVAL);
    }
}

fn assert_report_success(report: &ScanReport) {
    assert_eq!(report.results.len(), 3, "expected dns/domain/tcp probe results");
    assert_eq!(outcome_for(report, "dns_integrity"), Some("dns_match"));
    assert_eq!(outcome_for(report, "domain_reachability"), Some("http_ok"));
    assert!(
        matches!(outcome_for(report, "tcp_fat_header"), Some("tcp_fat_header_ok" | "tcp_16kb_blocked")),
        "unexpected tcp fat-header outcome: {:?}",
        outcome_for(report, "tcp_fat_header")
    );
}

fn outcome_for<'a>(report: &'a ScanReport, probe_type: &str) -> Option<&'a str> {
    report.results.iter().find(|result| result.probe_type == probe_type).map(|result| result.outcome.as_str())
}

fn scan_request(manifest: &FixtureManifest, http_port: u16) -> ScanRequest {
    ScanRequest {
        profile_id: "fixture-soak".to_string(),
        display_name: "Local diagnostics soak".to_string(),
        path_mode: ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: vec![],
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![],
        domain_targets: vec![DomainTarget {
            host: manifest.fixture_domain.clone(),
            connect_ip: Some("127.0.0.1".to_string()),
            https_port: Some(free_port()),
            http_port: Some(http_port),
            http_path: "/".to_string(),
        }],
        dns_targets: vec![DnsTarget {
            domain: manifest.fixture_domain.clone(),
            udp_server: Some(format!("127.0.0.1:{}", manifest.dns_udp_port)),
            encrypted_resolver_id: Some("fixture-doh".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("127.0.0.1".to_string()),
            encrypted_port: Some(manifest.dns_http_port),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec!["127.0.0.1".to_string()],
            encrypted_doh_url: Some(format!("http://127.0.0.1:{}/dns-query", manifest.dns_http_port)),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            doh_url: Some(format!("http://127.0.0.1:{}/dns-query", manifest.dns_http_port)),
            doh_bootstrap_ips: vec!["127.0.0.1".to_string()],
            expected_ips: vec![manifest.dns_answer_ipv4.clone()],
        }],
        tcp_targets: vec![TcpTarget {
            id: "fixture-fat".to_string(),
            provider: "fixture".to_string(),
            ip: "127.0.0.1".to_string(),
            port: http_port,
            sni: None,
            asn: None,
            host_header: Some(manifest.fixture_domain.clone()),
            fat_header_requests: Some(4),
        }],
        quic_targets: vec![],
        service_targets: vec![],
        circumvention_targets: vec![],
        throughput_targets: vec![],
        whitelist_sni: vec![manifest.fixture_domain.clone()],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: None,
    }
}

fn ephemeral_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: free_port(),
        udp_echo_port: free_port(),
        tls_echo_port: free_port(),
        dns_udp_port: free_port(),
        dns_http_port: free_port(),
        socks5_port: free_port(),
        control_port: free_port(),
        ..FixtureConfig::default()
    }
}

fn free_port() -> u16 {
    TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind free port").local_addr().expect("local addr").port()
}

struct FatHttpServer {
    listener_addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl FatHttpServer {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fat http server");
        listener.set_nonblocking(true).expect("set fat http server nonblocking");
        let listener_addr = listener.local_addr().expect("fat http addr");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        thread::spawn(move || handle_http_client(&mut stream));
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(20)),
                    Err(_) => break,
                }
            }
        });

        Self { listener_addr, stop, handle: Some(handle) }
    }

    fn port(&self) -> u16 {
        self.listener_addr.port()
    }
}

impl Drop for FatHttpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(self.listener_addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("join fat http server");
        }
    }
}

fn handle_http_client(stream: &mut TcpStream) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
    loop {
        match read_until_marker(stream, b"\r\n\r\n") {
            Ok(request) if request.is_empty() => return,
            Ok(_) => {
                if stream.write_all(response).is_err() {
                    return;
                }
                if stream.flush().is_err() {
                    return;
                }
            }
            Err(_) => return,
        }
    }
}

fn read_until_marker(stream: &mut TcpStream, marker: &[u8]) -> io::Result<Vec<u8>> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 1024];
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => return Ok(buffer),
            Ok(read) => {
                buffer.extend_from_slice(&chunk[..read]);
                if buffer.windows(marker.len()).any(|window| window == marker) {
                    return Ok(buffer);
                }
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => return Ok(buffer),
            Err(err) => return Err(err),
        }
    }
}
