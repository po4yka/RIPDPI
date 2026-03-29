// Load/stress tests for the proxy runtime. These exercise high-concurrency
// ramp-up profiles, burst spikes, and saturation behavior -- complementing the
// soak suite which covers endurance over time.
#![cfg(not(feature = "loom"))]

mod support;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Barrier, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use local_network_fixture::{FixtureConfig, FixtureStack};
use native_soak_support::{acquire_global_lock, write_json_artifact, LatencyRecorder, SoakProfile, SoakSampler};
use ripdpi_runtime::RuntimeTelemetrySink;
use serde_json::json;

use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::SOCKET_TIMEOUT;

const PROGRESS_TIMEOUT: Duration = Duration::from_secs(30);

// ── Tests ──

#[test]
#[ignore = "requires RIPDPI_RUN_LOAD=1"]
fn proxy_ramp_load() {
    if !SoakProfile::load_tests_enabled() {
        eprintln!("skipping proxy_ramp_load because RIPDPI_RUN_LOAD!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let max_conn: i32 = profile.pick_count(128, 256) as i32;
    let hold_secs = profile.pick_duration(Duration::from_secs(10), Duration::from_secs(30));

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(LoadTestTelemetry::default());

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.network.max_open = max_conn;
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    let sampler = start_load_sampler("proxy_ramp_load", &telemetry);

    let steps: Vec<usize> = vec![8, 16, 32, 64, 96, max_conn as usize];
    let mut step_results = Vec::new();

    for concurrency in &steps {
        let concurrency = *concurrency;
        telemetry.connect_latency.reset();
        let step_start_accepted = telemetry.accepted.load(Ordering::Relaxed);
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let last_progress = Arc::new(std::sync::atomic::AtomicU64::new(now_ms()));

        let handles: Vec<_> = (0..concurrency)
            .map(|worker| {
                let stop = stop.clone();
                let last_progress = last_progress.clone();
                let echo_port = fixture.manifest().tcp_echo_port;
                let proxy_port = proxy.port;
                thread::spawn(move || {
                    let mut counter = 0usize;
                    while !stop.load(Ordering::Relaxed) {
                        let payload = format!("ramp-{worker}-{counter}");
                        if socks_tcp_echo(proxy_port, echo_port, payload.as_bytes()).is_ok() {
                            last_progress.store(now_ms(), Ordering::Relaxed);
                        }
                        counter += 1;
                    }
                    counter
                })
            })
            .collect();

        thread::sleep(hold_secs);
        stop.store(true, Ordering::Relaxed);

        let mut total_ops = 0usize;
        for handle in handles {
            total_ops += handle.join().expect("join ramp worker");
        }

        let step_accepted = telemetry.accepted.load(Ordering::Relaxed) - step_start_accepted;
        let latency = telemetry.connect_latency.report();
        let slot_exhaustions = telemetry.slot_exhaustions.load(Ordering::Relaxed);

        let result = json!({
            "concurrency": concurrency,
            "holdSeconds": hold_secs.as_secs(),
            "totalOps": total_ops,
            "acceptedConnections": step_accepted,
            "slotExhaustions": slot_exhaustions,
            "latency": latency,
        });
        eprintln!(
            "ramp step concurrency={concurrency}: ops={total_ops} accepted={step_accepted} exhaustions={slot_exhaustions}"
        );
        step_results.push(result);
    }

    drop(proxy);

    let _samples = sampler.finish().expect("finish ramp sampler");
    let _ = write_json_artifact("proxy_ramp_load.results.json", &step_results);

    // Resource growth is expected under load -- soak tests cover leak detection.
    // Load tests record samples for observability but do not assert growth thresholds.
}

#[test]
#[ignore = "requires RIPDPI_RUN_LOAD=1"]
fn proxy_burst_load() {
    if !SoakProfile::load_tests_enabled() {
        eprintln!("skipping proxy_burst_load because RIPDPI_RUN_LOAD!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let max_conn: i32 = 64;
    let burst_size: usize = 128;

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(LoadTestTelemetry::default());

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.network.max_open = max_conn;
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    // Warm up: 4 steady connections
    let warmup_ok = Arc::new(AtomicUsize::new(0));
    let warmup_stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let warmup_handles: Vec<_> = (0..4)
        .map(|i| {
            let stop = warmup_stop.clone();
            let ok = warmup_ok.clone();
            let echo_port = fixture.manifest().tcp_echo_port;
            let proxy_port = proxy.port;
            thread::spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    let payload = format!("warmup-{i}");
                    if socks_tcp_echo(proxy_port, echo_port, payload.as_bytes()).is_ok() {
                        ok.fetch_add(1, Ordering::Relaxed);
                    }
                }
            })
        })
        .collect();
    thread::sleep(Duration::from_secs(5));
    warmup_stop.store(true, Ordering::Relaxed);
    for handle in warmup_handles {
        handle.join().expect("join warmup worker");
    }
    assert!(warmup_ok.load(Ordering::Relaxed) > 0, "warmup produced no successful echoes");

    // Reset counters for burst measurement
    telemetry.accepted.store(0, Ordering::Relaxed);
    telemetry.slot_exhaustions.store(0, Ordering::Relaxed);
    telemetry.errors.store(0, Ordering::Relaxed);

    // Burst: coordinate simultaneous connection attempts
    let barrier = Arc::new(Barrier::new(burst_size));
    let burst_accepted = Arc::new(AtomicUsize::new(0));
    let burst_rejected = Arc::new(AtomicUsize::new(0));

    let burst_start = Instant::now();
    let burst_handles: Vec<_> = (0..burst_size)
        .map(|i| {
            let barrier = barrier.clone();
            let accepted = burst_accepted.clone();
            let rejected = burst_rejected.clone();
            let echo_port = fixture.manifest().tcp_echo_port;
            let proxy_port = proxy.port;
            thread::spawn(move || {
                barrier.wait();
                let payload = format!("burst-{i}");
                match socks_tcp_echo(proxy_port, echo_port, payload.as_bytes()) {
                    Ok(_) => {
                        accepted.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(_) => {
                        rejected.fetch_add(1, Ordering::Relaxed);
                    }
                }
            })
        })
        .collect();

    for handle in burst_handles {
        handle.join().expect("join burst worker");
    }
    let burst_duration = burst_start.elapsed();

    let accepted = burst_accepted.load(Ordering::Relaxed);
    let rejected = burst_rejected.load(Ordering::Relaxed);

    eprintln!(
        "burst: accepted={accepted} rejected={rejected} total={} duration={:?}",
        accepted + rejected,
        burst_duration
    );

    let burst_result = json!({
        "burstSize": burst_size,
        "maxConn": max_conn,
        "accepted": accepted,
        "rejected": rejected,
        "burstDurationMs": burst_duration.as_millis() as u64,
    });
    let _ = write_json_artifact("proxy_burst_load.results.json", &burst_result);

    assert!(
        accepted <= max_conn as usize,
        "accepted {accepted} exceeds max_conn {max_conn} -- capacity enforcement failed"
    );
    assert_eq!(accepted + rejected, burst_size, "accepted + rejected should equal burst size");

    // Recovery: verify proxy still works after the burst
    thread::sleep(Duration::from_secs(2));
    let payload = b"post-burst-recovery";
    let echoed =
        socks_tcp_echo(proxy.port, fixture.manifest().tcp_echo_port, payload).expect("post-burst recovery echo failed");
    assert_eq!(echoed, payload);

    drop(proxy);
}

#[test]
#[ignore = "requires RIPDPI_RUN_LOAD=1"]
fn proxy_saturation_load() {
    if !SoakProfile::load_tests_enabled() {
        eprintln!("skipping proxy_saturation_load because RIPDPI_RUN_LOAD!=1");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let max_conn: i32 = 64;
    let hold = profile.pick_duration(Duration::from_secs(30), Duration::from_secs(120));

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let telemetry = Arc::new(LoadTestTelemetry::default());

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.network.max_open = max_conn;
    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));

    let sampler = start_load_sampler("proxy_saturation_load", &telemetry);

    // Fill to capacity: open max_conn long-lived connections
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let echo_ok = Arc::new(AtomicUsize::new(0));
    let echo_fail = Arc::new(AtomicUsize::new(0));
    let last_progress = Arc::new(std::sync::atomic::AtomicU64::new(now_ms()));
    let established_count = Arc::new(AtomicUsize::new(0));

    let saturated_handles: Vec<_> = (0..max_conn as usize)
        .map(|i| {
            let stop = stop.clone();
            let echo_ok = echo_ok.clone();
            let echo_fail = echo_fail.clone();
            let last_progress = last_progress.clone();
            let established = established_count.clone();
            let echo_port = fixture.manifest().tcp_echo_port;
            let proxy_port = proxy.port;
            thread::spawn(move || {
                // Open a SOCKS5 connection and keep it alive with periodic pings
                let mut stream = match try_socks_connect(proxy_port, echo_port) {
                    Ok(stream) => {
                        established.fetch_add(1, Ordering::Relaxed);
                        stream
                    }
                    Err(_) => return,
                };
                stream.set_read_timeout(Some(Duration::from_secs(5))).ok();
                stream.set_write_timeout(Some(Duration::from_secs(5))).ok();
                while !stop.load(Ordering::Relaxed) {
                    let payload = format!("sat-{i}");
                    let payload_bytes = payload.as_bytes();
                    match stream.write_all(payload_bytes).and_then(|()| {
                        let mut buf = vec![0u8; payload_bytes.len()];
                        stream.read_exact(&mut buf)?;
                        Ok(buf)
                    }) {
                        Ok(buf) if buf == payload_bytes => {
                            echo_ok.fetch_add(1, Ordering::Relaxed);
                            last_progress.store(now_ms(), Ordering::Relaxed);
                        }
                        _ => {
                            echo_fail.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    thread::sleep(Duration::from_secs(1));
                }
                let _ = stream.shutdown(Shutdown::Both);
            })
        })
        .collect();

    // Wait for connections to establish
    let deadline = Instant::now() + Duration::from_secs(10);
    while established_count.load(Ordering::Relaxed) < max_conn as usize && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(100));
    }
    let actual_established = established_count.load(Ordering::Relaxed);
    eprintln!("saturation: established {actual_established}/{max_conn} connections");

    // Attempt overflow connections during saturation
    let overflow_rejected = Arc::new(AtomicUsize::new(0));
    let overflow_accepted = Arc::new(AtomicUsize::new(0));
    let overflow_stop = Arc::new(std::sync::atomic::AtomicBool::new(false));

    let overflow_handles: Vec<_> = (0..16)
        .map(|_| {
            let stop = overflow_stop.clone();
            let rejected = overflow_rejected.clone();
            let accepted = overflow_accepted.clone();
            let echo_port = fixture.manifest().tcp_echo_port;
            let proxy_port = proxy.port;
            thread::spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    match socks_tcp_echo(proxy_port, echo_port, b"overflow") {
                        Ok(_) => {
                            accepted.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(_) => {
                            rejected.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    thread::sleep(Duration::from_millis(200));
                }
            })
        })
        .collect();

    // Hold at saturation
    let hold_start = Instant::now();
    while hold_start.elapsed() < hold {
        thread::sleep(Duration::from_secs(5));
        let idle_for = now_ms().saturating_sub(last_progress.load(Ordering::Relaxed));
        assert!(
            idle_for <= PROGRESS_TIMEOUT.as_millis() as u64,
            "saturated connections made no progress for {idle_for}ms"
        );
    }

    overflow_stop.store(true, Ordering::Relaxed);
    for handle in overflow_handles {
        handle.join().expect("join overflow worker");
    }

    let total_ok = echo_ok.load(Ordering::Relaxed);
    let total_fail = echo_fail.load(Ordering::Relaxed);
    let overflow_rej = overflow_rejected.load(Ordering::Relaxed);
    let overflow_acc = overflow_accepted.load(Ordering::Relaxed);
    eprintln!("saturation hold: echo_ok={total_ok} echo_fail={total_fail} overflow_rejected={overflow_rej} overflow_accepted={overflow_acc}");

    // Stop half the saturated connections, verify new ones can be accepted
    stop.store(true, Ordering::Relaxed);
    for handle in saturated_handles {
        handle.join().expect("join saturated worker");
    }

    // Wait for slots to drain
    thread::sleep(Duration::from_secs(2));

    // Verify recovery: new connections should succeed
    let recovery_ok =
        (0..4).filter(|_| socks_tcp_echo(proxy.port, fixture.manifest().tcp_echo_port, b"recovery").is_ok()).count();
    assert!(recovery_ok >= 3, "expected at least 3/4 recovery connections to succeed, got {recovery_ok}");

    let _samples = sampler.finish().expect("finish saturation sampler");

    let saturation_result = json!({
        "maxConn": max_conn,
        "established": actual_established,
        "echoOk": total_ok,
        "echoFail": total_fail,
        "overflowRejected": overflow_rej,
        "overflowAccepted": overflow_acc,
        "holdSeconds": hold.as_secs(),
        "recoveryOk": recovery_ok,
    });
    let _ = write_json_artifact("proxy_saturation_load.results.json", &saturation_result);

    assert!(total_ok > 0, "no successful echoes during saturation");
    if actual_established >= max_conn as usize {
        assert!(overflow_rej > 0, "expected overflow rejections at full capacity");
    }

    // Resource growth is expected under load -- soak tests cover leak detection.
    // Load tests record samples for observability but do not assert growth thresholds.
}

// ── Load-test helpers ──

fn socks_tcp_echo(proxy_port: u16, dst_port: u16, payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut stream = try_socks_connect(proxy_port, dst_port)?;
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).map_err(|e| e.to_string())?;
    stream.write_all(payload).map_err(|e| format!("write: {e}"))?;
    let mut buf = vec![0u8; payload.len()];
    stream.read_exact(&mut buf).map_err(|e| format!("read: {e}"))?;
    if buf != payload {
        return Err(format!("echo mismatch: expected {} bytes, got different content", payload.len()));
    }
    Ok(buf)
}

fn try_socks_connect(proxy_port: u16, dst_port: u16) -> Result<TcpStream, String> {
    let mut stream = TcpStream::connect_timeout(
        &std::net::SocketAddr::from((Ipv4Addr::LOCALHOST, proxy_port)),
        Duration::from_secs(5),
    )
    .map_err(|e| format!("connect: {e}"))?;
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(SOCKET_TIMEOUT)).map_err(|e| e.to_string())?;
    // SOCKS5 auth: no auth
    stream.write_all(b"\x05\x01\x00").map_err(|e| format!("socks auth write: {e}"))?;
    let mut auth_reply = [0u8; 2];
    stream.read_exact(&mut auth_reply).map_err(|e| format!("socks auth read: {e}"))?;
    if auth_reply != [0x05, 0x00] {
        return Err(format!("socks auth failed: {auth_reply:?}"));
    }
    // SOCKS5 connect
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).map_err(|e| format!("socks connect write: {e}"))?;
    // Read reply header (4 bytes) + IPv4 addr (4 bytes) + port (2 bytes)
    let mut reply = [0u8; 10];
    stream.read_exact(&mut reply).map_err(|e| format!("socks connect read: {e}"))?;
    if reply[1] != 0x00 {
        return Err(format!("socks connect rejected: status={}", reply[1]));
    }
    Ok(stream)
}

fn start_load_sampler(scenario: &str, telemetry: &Arc<LoadTestTelemetry>) -> SoakSampler {
    let telemetry = telemetry.clone();
    SoakSampler::start(scenario, move || {
        json!({
            "accepted": telemetry.accepted.load(Ordering::Relaxed),
            "finished": telemetry.finished.load(Ordering::Relaxed),
            "slotExhaustions": telemetry.slot_exhaustions.load(Ordering::Relaxed),
            "errors": telemetry.errors.load(Ordering::Relaxed),
            "latency": telemetry.connect_latency.report(),
        })
    })
    .expect("start load sampler")
}

fn ephemeral_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: free_port(),
        udp_echo_port: free_port(),
        tls_echo_port: free_port(),
        dns_udp_port: free_port(),
        dns_http_port: free_port(),
        dns_dot_port: free_port(),
        dns_dnscrypt_port: free_port(),
        dns_doq_port: free_port(),
        socks5_port: free_port(),
        control_port: free_port(),
        ..FixtureConfig::default()
    }
}

fn free_port() -> u16 {
    std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind free port")
        .local_addr()
        .expect("local addr")
        .port()
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_else(|_| Duration::from_secs(0)).as_millis() as u64
}

// ── Load test telemetry ──

#[derive(Default)]
struct LoadTestTelemetry {
    accepted: AtomicUsize,
    finished: AtomicUsize,
    slot_exhaustions: AtomicUsize,
    errors: AtomicUsize,
    connect_latency: LatencyRecorder,
    last_error: Mutex<Option<String>>,
}

impl RuntimeTelemetrySink for LoadTestTelemetry {
    fn on_listener_started(&self, _bind_addr: std::net::SocketAddr, _max_clients: usize, _group_count: usize) {}

    fn on_listener_stopped(&self) {}

    fn on_client_accepted(&self) {
        self.accepted.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_finished(&self) {
        self.finished.fetch_add(1, Ordering::Relaxed);
    }

    fn on_client_error(&self, error: &std::io::Error) {
        self.errors.fetch_add(1, Ordering::Relaxed);
        *self.last_error.lock().expect("lock last error") = Some(error.to_string());
    }

    fn on_failure_classified(
        &self,
        _target: std::net::SocketAddr,
        _failure: &ripdpi_failure_classifier::ClassifiedFailure,
        _host: Option<&str>,
    ) {
    }

    fn on_route_selected(
        &self,
        _target: std::net::SocketAddr,
        _group_index: usize,
        _host: Option<&str>,
        _phase: &'static str,
    ) {
    }

    fn on_upstream_connected(&self, _upstream_addr: std::net::SocketAddr, rtt_ms: Option<u64>) {
        if let Some(rtt) = rtt_ms {
            self.connect_latency.record(rtt * 1000);
        }
    }

    fn on_client_slot_exhausted(&self) {
        self.slot_exhaustions.fetch_add(1, Ordering::Relaxed);
    }

    fn on_tls_handshake_completed(&self, _target: std::net::SocketAddr, _latency_ms: u64) {}

    fn on_route_advanced(
        &self,
        _target: std::net::SocketAddr,
        _from_group: usize,
        _to_group: usize,
        _trigger: u32,
        _host: Option<&str>,
    ) {
    }

    fn on_host_autolearn_state(
        &self,
        _enabled: bool,
        _learned_host_count: usize,
        _penalized_host_count: usize,
        _blocked_host_count: usize,
        _last_block_signal: Option<&str>,
        _last_block_provider: Option<&str>,
    ) {
    }

    fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}
}
