#![cfg(target_os = "linux")]

use std::io::{self, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use hs5t_config::{Config, MapDnsConfig, MiscConfig, Socks5Config, TunnelConfig};
use hs5t_core::{run_tunnel, Stats};
use hs5t_tunnel::{LinuxTunnel, TunnelDriver};
use local_network_fixture::{
    FixtureConfig, FixtureFaultOutcome, FixtureFaultScope, FixtureFaultSpec, FixtureFaultTarget, FixtureManifest,
    FixtureStack,
};
use native_soak_support::{
    acquire_global_lock, assert_growth, monotonic_u64_samples, write_json_artifact, GrowthThresholds, SoakProfile,
    SoakSampler, WARMUP_WINDOW,
};
use serde_json::json;
use tokio_util::sync::CancellationToken;

const E2E_ROUTE_CIDR: &str = "198.18.0.0/15";
const TUN_IPV4: &str = "10.77.0.1/24";
const MAPDNS_IP: &str = "198.18.0.1";
const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
const PROGRESS_TIMEOUT: Duration = Duration::from_secs(30);

#[test]
#[ignore = "requires Linux CAP_NET_ADMIN, RIPDPI_RUN_TUN_E2E=1, and RIPDPI_RUN_SOAK=1"]
fn tun_sustained_path_soak() {
    if !tun_soak_enabled() {
        eprintln!("skipping tun_sustained_path_soak because soak env is not enabled");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let duration = profile.pick_duration(Duration::from_secs(300), Duration::from_secs(900));

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard =
        FixtureArtifactGuard::new("tun_sustained_path_soak", fixture.manifest().clone(), fixture.events());
    let mut tun = Some(TunHarness::start().expect("start tun harness"));
    let tun_name = tun.as_ref().expect("tun harness").name().to_string();
    let stats = Arc::new(Stats::new());
    let cancel = CancellationToken::new();
    let worker = start_tunnel_worker(
        tun.as_ref().expect("tun harness").dup_fd().expect("dup tun fd"),
        Arc::new(build_tunnel_config(fixture.manifest(), &tun_name)),
        cancel.clone(),
        stats.clone(),
    );

    wait_for_tunnel_path(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);
    let sampler = start_tun_sampler("tun_sustained_path_soak", fixture.events(), stats.clone());

    let stop = Arc::new(AtomicBool::new(false));
    let last_progress_ms = Arc::new(AtomicU64::new(now_ms()));
    let tcp_ok = Arc::new(AtomicUsize::new(0));
    let udp_ok = Arc::new(AtomicUsize::new(0));
    let mapdns_ok = Arc::new(AtomicUsize::new(0));
    let deadline = Instant::now() + duration;

    let handles = vec![
        spawn_tun_worker(stop.clone(), last_progress_ms.clone(), tcp_ok.clone(), deadline, {
            let manifest = fixture.manifest().clone();
            move || tcp_round_trip(manifest.fixture_ipv4.as_str(), manifest.tcp_echo_port)
        }),
        spawn_tun_worker(stop.clone(), last_progress_ms.clone(), udp_ok.clone(), deadline, {
            let manifest = fixture.manifest().clone();
            move || udp_round_trip(manifest.fixture_ipv4.as_str(), manifest.udp_echo_port)
        }),
        spawn_tun_worker(stop.clone(), last_progress_ms.clone(), mapdns_ok.clone(), deadline, || {
            mapdns_round_trip(MAPDNS_IP)
        }),
    ];

    while Instant::now() < deadline {
        thread::sleep(Duration::from_secs(5));
        let idle_for = now_ms().saturating_sub(last_progress_ms.load(Ordering::Relaxed));
        assert!(
            idle_for <= PROGRESS_TIMEOUT.as_millis() as u64,
            "tunnel sustained traffic made no progress for {idle_for}ms"
        );
    }
    stop.store(true, Ordering::Relaxed);
    for handle in handles {
        handle.join().expect("join sustained worker");
    }

    cancel.cancel();
    worker.join().expect("join tunnel worker").expect("tunnel worker exit");

    let samples = sampler.finish().expect("finish tunnel sustained sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 20 * 1024 * 1024, fd_growth: 6, thread_growth: 4 },
    )
    .expect("tunnel sustained growth thresholds");
    assert!(monotonic_u64_samples(&samples, "txPackets"));
    assert!(monotonic_u64_samples(&samples, "rxPackets"));
    assert!(tcp_ok.load(Ordering::Relaxed) > 0);
    assert!(udp_ok.load(Ordering::Relaxed) > 0);
    assert!(mapdns_ok.load(Ordering::Relaxed) > 0);

    let tun_name = tun_name.clone();
    drop(tun.take());
    assert_route_cleanup(&tun_name);
}

#[test]
#[ignore = "requires Linux CAP_NET_ADMIN, RIPDPI_RUN_TUN_E2E=1, and RIPDPI_RUN_SOAK=1"]
fn tun_restart_soak() {
    if !tun_soak_enabled() {
        eprintln!("skipping tun_restart_soak because soak env is not enabled");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let cycles = SoakProfile::from_env().pick_count(100, 100);

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard = FixtureArtifactGuard::new("tun_restart_soak", fixture.manifest().clone(), fixture.events());
    let mut tun = Some(TunHarness::start().expect("start tun harness"));
    let tun_name = tun.as_ref().expect("tun harness").name().to_string();
    let stats = Arc::new(Stats::new());
    let completed_cycles = Arc::new(AtomicUsize::new(0));
    let sampler = {
        let completed_cycles = completed_cycles.clone();
        let stats = stats.clone();
        let events = fixture.events();
        SoakSampler::start("tun_restart_soak", move || {
            let snapshot = stats.snapshot();
            json!({
                "completedCycles": completed_cycles.load(Ordering::Relaxed),
                "txPackets": snapshot.0,
                "txBytes": snapshot.1,
                "rxPackets": snapshot.2,
                "rxBytes": snapshot.3,
                "fixtureEventCount": events.snapshot().len(),
            })
        })
        .expect("start tun restart sampler")
    };

    for _ in 0..cycles {
        let cancel = CancellationToken::new();
        let worker = start_tunnel_worker(
            tun.as_ref().expect("tun harness").dup_fd().expect("dup tun fd"),
            Arc::new(build_tunnel_config(fixture.manifest(), &tun_name)),
            cancel.clone(),
            stats.clone(),
        );
        wait_for_tunnel_path(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);
        tcp_round_trip(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);
        udp_round_trip(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().udp_echo_port);
        mapdns_round_trip(MAPDNS_IP);
        cancel.cancel();
        worker.join().expect("join restart worker").expect("restart worker exit");
        completed_cycles.fetch_add(1, Ordering::Relaxed);
    }

    let samples = sampler.finish().expect("finish tun restart sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 20 * 1024 * 1024, fd_growth: 6, thread_growth: 4 },
    )
    .expect("tunnel restart growth thresholds");

    drop(tun.take());
    assert_route_cleanup(&tun_name);
}

#[test]
#[ignore = "requires Linux CAP_NET_ADMIN, RIPDPI_RUN_TUN_E2E=1, and RIPDPI_RUN_SOAK=1"]
fn tun_fault_recovery_soak() {
    if !tun_soak_enabled() {
        eprintln!("skipping tun_fault_recovery_soak because soak env is not enabled");
        return;
    }

    let _lock = acquire_global_lock().expect("acquire soak lock");
    let profile = SoakProfile::from_env();
    let duration = profile.pick_duration(Duration::from_secs(300), Duration::from_secs(600));

    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let artifact_guard =
        FixtureArtifactGuard::new("tun_fault_recovery_soak", fixture.manifest().clone(), fixture.events());
    let mut tun = Some(TunHarness::start().expect("start tun harness"));
    let tun_name = tun.as_ref().expect("tun harness").name().to_string();
    let stats = Arc::new(Stats::new());
    let cancel = CancellationToken::new();
    let worker = start_tunnel_worker(
        tun.as_ref().expect("tun harness").dup_fd().expect("dup tun fd"),
        Arc::new(build_tunnel_config(fixture.manifest(), &tun_name)),
        cancel.clone(),
        stats.clone(),
    );
    wait_for_tunnel_path(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);

    let sampler = start_tun_sampler("tun_fault_recovery_soak", fixture.events(), stats.clone());
    let faults = [
        FixtureFaultSpec {
            target: FixtureFaultTarget::UdpEcho,
            outcome: FixtureFaultOutcome::UdpDrop,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        },
        FixtureFaultSpec {
            target: FixtureFaultTarget::DnsUdp,
            outcome: FixtureFaultOutcome::DnsTimeout,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        },
        FixtureFaultSpec {
            target: FixtureFaultTarget::DnsUdp,
            outcome: FixtureFaultOutcome::DnsServFail,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        },
        FixtureFaultSpec {
            target: FixtureFaultTarget::Socks5Relay,
            outcome: FixtureFaultOutcome::SocksRejectConnect,
            scope: FixtureFaultScope::OneShot,
            delay_ms: None,
        },
    ];

    let started = Instant::now();
    let mut index = 0usize;
    while started.elapsed() < duration {
        let fault = faults[index % faults.len()].clone();
        fixture.faults().set(fault.clone());
        assert_fault_observed(&fault, fixture.manifest());
        recover_from_fault(&fault, fixture.manifest(), Duration::from_secs(5));
        index += 1;
        thread::sleep(Duration::from_secs(60).min(duration.saturating_sub(started.elapsed())));
    }

    cancel.cancel();
    worker.join().expect("join tunnel worker").expect("tunnel worker exit");

    let samples = sampler.finish().expect("finish tunnel fault sampler");
    artifact_guard.persist();

    assert_growth(
        &samples,
        WARMUP_WINDOW,
        GrowthThresholds { rss_growth_bytes: 20 * 1024 * 1024, fd_growth: 6, thread_growth: 4 },
    )
    .expect("tunnel fault growth thresholds");

    drop(tun.take());
    assert_route_cleanup(&tun_name);
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

fn tun_soak_enabled() -> bool {
    SoakProfile::is_enabled() && std::env::var("RIPDPI_RUN_TUN_E2E").ok().as_deref() == Some("1")
}

fn start_tun_sampler(scenario: &str, events: local_network_fixture::EventLog, stats: Arc<Stats>) -> SoakSampler {
    SoakSampler::start(scenario, move || {
        let snapshot = stats.snapshot();
        json!({
            "txPackets": snapshot.0,
            "txBytes": snapshot.1,
            "rxPackets": snapshot.2,
            "rxBytes": snapshot.3,
            "fixtureEventCount": events.snapshot().len(),
        })
    })
    .expect("start tunnel soak sampler")
}

fn spawn_tun_worker<F>(
    stop: Arc<AtomicBool>,
    last_progress_ms: Arc<AtomicU64>,
    counter: Arc<AtomicUsize>,
    deadline: Instant,
    op: F,
) -> JoinHandle<()>
where
    F: Fn() + Send + 'static,
{
    thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) && Instant::now() < deadline {
            op();
            counter.fetch_add(1, Ordering::Relaxed);
            last_progress_ms.store(now_ms(), Ordering::Relaxed);
        }
    })
}

fn assert_fault_observed(fault: &FixtureFaultSpec, manifest: &FixtureManifest) {
    let result = std::panic::catch_unwind(|| match fault.target {
        FixtureFaultTarget::UdpEcho => {
            udp_round_trip(manifest.fixture_ipv4.as_str(), manifest.udp_echo_port);
        }
        FixtureFaultTarget::DnsUdp => {
            dns_fixture_round_trip(manifest.fixture_ipv4.as_str(), manifest.dns_udp_port)
                .expect("dns fixture response");
        }
        FixtureFaultTarget::Socks5Relay => {
            tcp_round_trip(manifest.fixture_ipv4.as_str(), manifest.tcp_echo_port);
        }
        _ => {}
    });
    assert!(result.is_err(), "expected {fault:?} to break the tunnel probe");
}

fn recover_from_fault(fault: &FixtureFaultSpec, manifest: &FixtureManifest, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let recovered = match fault.target {
            FixtureFaultTarget::UdpEcho => std::panic::catch_unwind(|| {
                udp_round_trip(manifest.fixture_ipv4.as_str(), manifest.udp_echo_port);
            })
            .is_ok(),
            FixtureFaultTarget::DnsUdp => dns_fixture_round_trip(manifest.fixture_ipv4.as_str(), manifest.dns_udp_port)
                .map(|answers| answers.contains(&manifest.dns_answer_ipv4))
                .unwrap_or(false),
            FixtureFaultTarget::Socks5Relay => std::panic::catch_unwind(|| {
                tcp_round_trip(manifest.fixture_ipv4.as_str(), manifest.tcp_echo_port);
            })
            .is_ok(),
            _ => true,
        };
        if recovered {
            return;
        }
        thread::sleep(Duration::from_millis(200));
    }
    panic!("clean tunnel traffic did not recover within {timeout:?}");
}

fn start_tunnel_worker(
    tun_fd: i32,
    config: Arc<Config>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
) -> JoinHandle<io::Result<()>> {
    thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
        runtime.block_on(run_tunnel(config, tun_fd, cancel, stats))
    })
}

fn build_tunnel_config(manifest: &FixtureManifest, tun_name: &str) -> Config {
    Config {
        tunnel: TunnelConfig {
            name: tun_name.to_string(),
            mtu: 1500,
            multi_queue: false,
            ipv4: Some(TUN_IPV4.to_string()),
            ipv6: None,
            post_up_script: None,
            pre_down_script: None,
        },
        socks5: Socks5Config {
            port: manifest.socks5_port,
            address: "127.0.0.1".to_string(),
            udp: Some("udp".to_string()),
            udp_address: None,
            pipeline: Some(false),
            username: None,
            password: None,
            mark: None,
        },
        mapdns: Some(MapDnsConfig {
            address: MAPDNS_IP.to_string(),
            port: 53,
            network: Some(MAPDNS_IP.to_string()),
            netmask: Some("255.255.255.255".to_string()),
            cache_size: 128,
        }),
        misc: MiscConfig { max_session_count: 128, ..MiscConfig::default() },
    }
}

fn tcp_round_trip(host: &str, port: u16) {
    let mut stream = TcpStream::connect((host, port)).expect("connect through tunnel");
    stream.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set tcp read timeout");
    stream.write_all(b"tun tcp").expect("write tcp payload");
    let mut buf = [0u8; 7];
    stream.read_exact(&mut buf).expect("read tcp echo");
    assert_eq!(&buf, b"tun tcp");
}

fn udp_round_trip(host: &str, port: u16) {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind udp client");
    socket.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set udp timeout");
    socket.connect((host, port)).expect("connect udp through tunnel");
    socket.send(b"tun udp").expect("send udp payload");
    let mut buf = [0u8; 64];
    let read = socket.recv(&mut buf).expect("receive udp echo");
    assert_eq!(&buf[..read], b"tun udp");
}

fn mapdns_round_trip(host: &str) {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind dns client");
    socket.set_read_timeout(Some(SOCKET_TIMEOUT)).expect("set dns timeout");
    socket.connect((host, 53)).expect("connect mapdns");
    socket.send(&build_dns_query("fixture.test")).expect("send dns query");
    let mut buf = [0u8; 512];
    let read = socket.recv(&mut buf).expect("receive dns response");
    let answers = parse_a_answers(&buf[..read]);
    assert!(
        answers.iter().any(|ip| ip.starts_with("198.18.")),
        "expected mapdns response in 198.18.0.0/15, got {answers:?}"
    );
}

fn dns_fixture_round_trip(host: &str, port: u16) -> Result<Vec<String>, String> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(|err| err.to_string())?;
    socket.set_read_timeout(Some(SOCKET_TIMEOUT)).map_err(|err| err.to_string())?;
    socket.connect((host, port)).map_err(|err| err.to_string())?;
    socket.send(&build_dns_query("fixture.test")).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 512];
    let read = socket.recv(&mut buf).map_err(|err| err.to_string())?;
    let answers = parse_a_answers(&buf[..read]);
    if answers.is_empty() {
        Err("dns_fixture_empty".to_string())
    } else {
        Ok(answers)
    }
}

fn wait_for_tunnel_path(host: &str, port: u16) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        if TcpStream::connect((host, port)).is_ok() {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
    panic!("tunnel path to {host}:{port} did not become reachable");
}

struct TunHarness {
    tunnel: LinuxTunnel,
}

impl TunHarness {
    fn start() -> io::Result<Self> {
        let name = format!("ripdpi-tun-{}", std::process::id());
        let tunnel = LinuxTunnel::open(Some(&name), false).map_err(other_io)?;
        tunnel.set_mtu(1500).map_err(other_io)?;
        tunnel.set_ipv4(Ipv4Addr::new(10, 77, 0, 1), 24).map_err(other_io)?;
        tunnel.set_up().map_err(other_io)?;
        run_ip(&["route", "replace", E2E_ROUTE_CIDR, "dev", tunnel.name()])?;
        Ok(Self { tunnel })
    }

    fn name(&self) -> &str {
        self.tunnel.name()
    }

    fn dup_fd(&self) -> io::Result<i32> {
        let fd = unsafe { libc::dup(self.tunnel.fd()) };
        if fd < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(fd)
        }
    }
}

impl Drop for TunHarness {
    fn drop(&mut self) {
        let _ = run_ip(&["route", "del", E2E_ROUTE_CIDR, "dev", self.tunnel.name()]);
        let _ = self.tunnel.set_down();
    }
}

fn assert_route_cleanup(tun_name: &str) {
    let route_output = Command::new("ip").args(["route", "show", E2E_ROUTE_CIDR]).output().expect("ip route show");
    let route_text = String::from_utf8_lossy(&route_output.stdout);
    assert!(route_text.trim().is_empty(), "expected route {E2E_ROUTE_CIDR} to be cleaned up: {route_text}");

    let link_status = Command::new("ip").args(["link", "show", tun_name]).output().expect("ip link show");
    assert!(!link_status.status.success(), "expected tunnel device {tun_name} to be removed after teardown");
}

fn run_ip(args: &[&str]) -> io::Result<()> {
    let output = Command::new("ip").args(args).output()?;
    if output.status.success() {
        Ok(())
    } else {
        Err(io::Error::new(
            ErrorKind::Other,
            format!("ip {:?} failed: {}", args, String::from_utf8_lossy(&output.stderr).trim()),
        ))
    }
}

fn build_dns_query(domain: &str) -> Vec<u8> {
    let mut packet = Vec::with_capacity(64);
    packet.extend_from_slice(&0x1234u16.to_be_bytes());
    packet.extend_from_slice(&0x0100u16.to_be_bytes());
    packet.extend_from_slice(&1u16.to_be_bytes());
    packet.extend_from_slice(&0u16.to_be_bytes());
    packet.extend_from_slice(&0u16.to_be_bytes());
    packet.extend_from_slice(&0u16.to_be_bytes());
    for label in domain.split('.') {
        packet.push(label.len() as u8);
        packet.extend_from_slice(label.as_bytes());
    }
    packet.push(0);
    packet.extend_from_slice(&1u16.to_be_bytes());
    packet.extend_from_slice(&1u16.to_be_bytes());
    packet
}

fn parse_a_answers(packet: &[u8]) -> Vec<String> {
    if packet.len() < 12 {
        return Vec::new();
    }
    let qdcount = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let ancount = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let mut cursor = 12usize;
    for _ in 0..qdcount {
        while cursor < packet.len() {
            let len = packet[cursor] as usize;
            cursor += 1;
            if len == 0 {
                break;
            }
            cursor += len;
        }
        cursor += 4;
    }

    let mut answers = Vec::new();
    for _ in 0..ancount {
        if cursor + 12 > packet.len() {
            break;
        }
        cursor += 2;
        let record_type = u16::from_be_bytes([packet[cursor], packet[cursor + 1]]);
        cursor += 2;
        cursor += 2;
        cursor += 4;
        let rdlen = u16::from_be_bytes([packet[cursor], packet[cursor + 1]]) as usize;
        cursor += 2;
        if record_type == 1 && rdlen == 4 && cursor + 4 <= packet.len() {
            answers.push(format!(
                "{}.{}.{}.{}",
                packet[cursor],
                packet[cursor + 1],
                packet[cursor + 2],
                packet[cursor + 3]
            ));
        }
        cursor += rdlen;
    }
    answers
}

fn other_io<E: ToString>(err: E) -> io::Error {
    io::Error::new(ErrorKind::Other, err.to_string())
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
    std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind free port")
        .local_addr()
        .expect("local addr")
        .port()
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_else(|_| Duration::from_secs(0))
        .as_millis() as u64
}
