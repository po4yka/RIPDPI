//! Privileged physical-kernel proof for the `SO_BINDTODEVICE=tun0` UID guard.
#![cfg(target_os = "linux")]

mod support;

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::os::fd::{FromRawFd, OwnedFd};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Output};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use local_network_fixture::{FixtureConfig, FixtureStack};
use ripdpi_tun_driver::{LinuxTunnel, TunnelDriver};
use ripdpi_tunnel_core::{PacketObserver, Stats, run_tunnel};
use socket2::{Domain, Protocol, Socket, Type};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config_with_misc;

const TUN_NAME: &str = "tun0";
const ALLOWED_UID: u32 = 65_534;
const DENIED_UID: u32 = 65_533;
const HELPER_GID: u32 = 65_534;
const TCP_ECHO_PORT: u16 = 47_401;
const UDP_ECHO_PORT: u16 = 47_402;
const IPV4_TARGET: Ipv4Addr = Ipv4Addr::new(192, 0, 2, 2);
const IPV6_TARGET: Ipv6Addr = Ipv6Addr::new(0x2001, 0x0db8, 0, 0, 0, 0, 0, 2);

const PHASES: [PhaseSpec; 12] = [
    PhaseSpec::new("ipv4_direct_tcp", 48_001, Family::Ipv4, ProtocolKind::Tcp, false, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv4_direct_udp", 48_002, Family::Ipv4, ProtocolKind::Udp, false, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv4_allowed_tcp", 48_003, Family::Ipv4, ProtocolKind::Tcp, true, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv4_allowed_udp", 48_004, Family::Ipv4, ProtocolKind::Udp, true, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv4_denied_tcp", 48_005, Family::Ipv4, ProtocolKind::Tcp, true, DENIED_UID, Expected::Reset),
    PhaseSpec::new("ipv4_denied_udp", 48_006, Family::Ipv4, ProtocolKind::Udp, true, DENIED_UID, Expected::Drop),
    PhaseSpec::new("ipv6_direct_tcp", 49_001, Family::Ipv6, ProtocolKind::Tcp, false, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv6_direct_udp", 49_002, Family::Ipv6, ProtocolKind::Udp, false, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv6_allowed_tcp", 49_003, Family::Ipv6, ProtocolKind::Tcp, true, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv6_allowed_udp", 49_004, Family::Ipv6, ProtocolKind::Udp, true, ALLOWED_UID, Expected::Echo),
    PhaseSpec::new("ipv6_denied_tcp", 49_005, Family::Ipv6, ProtocolKind::Tcp, true, DENIED_UID, Expected::Reset),
    PhaseSpec::new("ipv6_denied_udp", 49_006, Family::Ipv6, ProtocolKind::Udp, true, DENIED_UID, Expected::Drop),
];

#[derive(Clone, Copy, PartialEq, Eq)]
enum Family {
    Ipv4,
    Ipv6,
}

impl Family {
    const fn label(self) -> &'static str {
        match self {
            Self::Ipv4 => "ipv4",
            Self::Ipv6 => "ipv6",
        }
    }

    const fn target(self, port: u16) -> SocketAddr {
        match self {
            Self::Ipv4 => SocketAddr::new(IpAddr::V4(IPV4_TARGET), port),
            Self::Ipv6 => SocketAddr::new(IpAddr::V6(IPV6_TARGET), port),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ProtocolKind {
    Tcp,
    Udp,
}

impl ProtocolKind {
    const fn label(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Expected {
    Echo,
    Reset,
    Drop,
}

#[derive(Clone, Copy)]
struct PhaseSpec {
    id: &'static str,
    source_port: u16,
    family: Family,
    protocol: ProtocolKind,
    bind_to_tun: bool,
    uid: u32,
    expected: Expected,
}

impl PhaseSpec {
    const fn new(
        id: &'static str,
        source_port: u16,
        family: Family,
        protocol: ProtocolKind,
        bind_to_tun: bool,
        uid: u32,
        expected: Expected,
    ) -> Self {
        Self { id, source_port, family, protocol, bind_to_tun, uid, expected }
    }
}

#[derive(Clone, Copy, Default)]
struct PacketCounts {
    inbound: u64,
    outbound: u64,
    outbound_resets: u64,
}

#[derive(Default)]
struct PhasePacketObserver {
    counts: Mutex<HashMap<u16, PacketCounts>>,
}

impl PhasePacketObserver {
    fn snapshot(&self, source_port: u16) -> PacketCounts {
        self.counts.lock().expect("packet count lock").get(&source_port).copied().unwrap_or_default()
    }

    fn observe(&self, packet: &[u8], inbound: bool) {
        let Some((protocol, source_port, destination_port, reset)) = packet_tuple(packet) else {
            return;
        };
        if protocol != 6 && protocol != 17 {
            return;
        }
        let phase_port = if inbound { source_port } else { destination_port };
        if !PHASES.iter().any(|phase| phase.source_port == phase_port) {
            return;
        }
        let mut counts = self.counts.lock().expect("packet count lock");
        let entry = counts.entry(phase_port).or_default();
        if inbound {
            entry.inbound = entry.inbound.saturating_add(1);
        } else {
            entry.outbound = entry.outbound.saturating_add(1);
            if reset {
                entry.outbound_resets = entry.outbound_resets.saturating_add(1);
            }
        }
    }
}

impl PacketObserver for PhasePacketObserver {
    fn on_inbound(&self, packet: &[u8]) {
        self.observe(packet, true);
    }

    fn on_outbound(&self, packet: &[u8]) {
        self.observe(packet, false);
    }
}

fn packet_tuple(packet: &[u8]) -> Option<(u8, u16, u16, bool)> {
    let (protocol, offset) = match packet.first()? >> 4 {
        4 if packet.len() >= 20 => (packet[9], usize::from(packet[0] & 0x0f) * 4),
        6 if packet.len() >= 40 => (packet[6], 40),
        _ => return None,
    };
    if packet.len() < offset.saturating_add(8) {
        return None;
    }
    let source = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
    let destination = u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]);
    let reset = protocol == 6 && packet.len() > offset + 13 && packet[offset + 13] & 0x04 != 0;
    Some((protocol, source, destination, reset))
}

struct TunnelRuntime {
    cancel: CancellationToken,
    thread: Option<JoinHandle<std::io::Result<()>>>,
}

impl TunnelRuntime {
    fn stop(&mut self) {
        self.cancel.cancel();
        if let Some(thread) = self.thread.take() {
            let result = thread.join().expect("tunnel thread panicked");
            assert!(result.is_ok(), "tunnel runtime must stop cleanly: {result:?}");
        }
    }
}

impl Drop for TunnelRuntime {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

struct AttributionWorker {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl AttributionWorker {
    fn start() -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let thread = std::thread::spawn(move || {
            while !worker_stop.load(Ordering::Acquire) {
                let Some(job) = ripdpi_flow_app_attribution::pop_pending_request(Duration::from_millis(50)) else {
                    continue;
                };
                let uid =
                    PHASES.iter().find(|phase| phase.source_port == job.request().local.port()).map(|phase| phase.uid);
                ripdpi_flow_app_attribution::store_uid_resolution(&job, uid);
            }
        });
        Self { stop, thread: Some(thread) }
    }
}

impl Drop for AttributionWorker {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

struct Topology {
    namespace: String,
    host_veth: String,
    peer_veth: String,
    stop_path: PathBuf,
    peer: Option<Child>,
    table: String,
    rule_priority: String,
    ipv4_rule_installed: bool,
    ipv6_rule_installed: bool,
    cleanup_commands_succeeded: bool,
    cleaned: bool,
}

impl Topology {
    fn start(root: &Path, ownership_path: &Path) -> Self {
        let suffix = std::process::id() % 10_000;
        let namespace = format!("ripdpi-uid-{suffix}");
        let host_veth = format!("rduh{suffix}");
        let peer_veth = format!("rdup{suffix}");
        let table = (30_000 + suffix).to_string();
        let rule_priority = (20_000 + suffix).to_string();
        let stop_path = root.join("peer.stop");
        let ready_path = root.join("peer.ready");
        let event_path = root.join("peer.events");
        let mut topology = Self {
            namespace,
            host_veth,
            peer_veth,
            stop_path: stop_path.clone(),
            peer: None,
            table,
            rule_priority,
            ipv4_rule_installed: false,
            ipv6_rule_installed: false,
            cleanup_commands_succeeded: true,
            cleaned: false,
        };

        assert!(netns_is_absent(&topology.namespace), "test namespace is unavailable or already exists");
        assert!(link_is_absent(&topology.host_veth), "test host veth is unavailable or already exists");
        assert!(link_is_absent(&topology.peer_veth), "test peer veth is unavailable or already exists");
        assert!(policy_priority_is_clear(false, &topology.rule_priority), "IPv4 rule priority is unavailable");
        assert!(policy_priority_is_clear(true, &topology.rule_priority), "IPv6 rule priority is unavailable");
        fs::write(
            ownership_path,
            format!(
                "namespace={}\nhost_veth={}\npeer_veth={}\nrule_priority={}\ntable={}\n",
                topology.namespace, topology.host_veth, topology.peer_veth, topology.rule_priority, topology.table,
            ),
        )
        .expect("publish exact topology ownership before mutation");
        run("ip", &["netns", "add", &topology.namespace]);
        run("ip", &["link", "add", &topology.host_veth, "type", "veth", "peer", "name", &topology.peer_veth]);
        run("ip", &["link", "set", &topology.peer_veth, "netns", &topology.namespace]);
        run("ip", &["addr", "add", "192.0.2.1/24", "dev", &topology.host_veth]);
        run("ip", &["-6", "addr", "add", "2001:db8::1/64", "dev", &topology.host_veth, "nodad"]);
        run("ip", &["link", "set", &topology.host_veth, "up"]);
        run("ip", &["-n", &topology.namespace, "link", "set", "lo", "up"]);
        run("ip", &["-n", &topology.namespace, "addr", "add", "192.0.2.2/24", "dev", &topology.peer_veth]);
        run(
            "ip",
            &["-n", &topology.namespace, "-6", "addr", "add", "2001:db8::2/64", "dev", &topology.peer_veth, "nodad"],
        );
        run("ip", &["-n", &topology.namespace, "link", "set", &topology.peer_veth, "up"]);

        let current_exe = std::env::current_exe().expect("current test executable");
        let peer = Command::new("ip")
            .args(["netns", "exec", &topology.namespace])
            .arg(current_exe)
            .args(["--exact", "so_bindtodevice_peer_helper", "--ignored", "--nocapture"])
            .env("RIPDPI_SO_BIND_HELPER", "peer")
            .env("RIPDPI_SO_BIND_PEER_READY", &ready_path)
            .env("RIPDPI_SO_BIND_PEER_STOP", &stop_path)
            .env("RIPDPI_SO_BIND_PEER_EVENTS", &event_path)
            .spawn()
            .expect("start peer helper in network namespace");
        topology.peer = Some(peer);

        let deadline = Instant::now() + Duration::from_secs(10);
        while !ready_path.exists() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        assert!(ready_path.exists(), "peer echo helper did not become ready");
        topology
    }

    fn install_tun_policy(&mut self) {
        assert!(policy_priority_is_clear(false, &self.rule_priority), "IPv4 rule priority changed before install");
        assert!(policy_priority_is_clear(true, &self.rule_priority), "IPv6 rule priority changed before install");
        run("ip", &["rule", "add", "pref", &self.rule_priority, "oif", TUN_NAME, "lookup", &self.table]);
        self.ipv4_rule_installed = true;
        run("ip", &["-6", "rule", "add", "pref", &self.rule_priority, "oif", TUN_NAME, "lookup", &self.table]);
        self.ipv6_rule_installed = true;
        run("ip", &["route", "add", "table", &self.table, "192.0.2.2/32", "dev", TUN_NAME]);
        run("ip", &["-6", "route", "add", "table", &self.table, "2001:db8::2/128", "dev", TUN_NAME]);
    }

    fn cleanup(&mut self) {
        if self.cleaned {
            return;
        }
        let _ = fs::write(&self.stop_path, b"stop\n");
        if let Some(mut peer) = self.peer.take() {
            let deadline = Instant::now() + Duration::from_secs(3);
            while peer.try_wait().ok().flatten().is_none() && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(20));
            }
            if peer.try_wait().ok().flatten().is_none() {
                let _ = peer.kill();
            }
            let _ = peer.wait();
        }
        if self.ipv4_rule_installed {
            let removed = command_succeeds(
                "ip",
                &["rule", "del", "pref", &self.rule_priority, "oif", TUN_NAME, "lookup", &self.table],
            );
            self.cleanup_commands_succeeded &= removed;
            self.ipv4_rule_installed = false;
        }
        if self.ipv6_rule_installed {
            let removed = command_succeeds(
                "ip",
                &["-6", "rule", "del", "pref", &self.rule_priority, "oif", TUN_NAME, "lookup", &self.table],
            );
            self.cleanup_commands_succeeded &= removed;
            self.ipv6_rule_installed = false;
        }
        if link_exists(&self.host_veth) {
            self.cleanup_commands_succeeded &= command_succeeds("ip", &["link", "del", "dev", &self.host_veth]);
        }
        if link_exists(&self.peer_veth) {
            self.cleanup_commands_succeeded &= command_succeeds("ip", &["link", "del", "dev", &self.peer_veth]);
        }
        if netns_exists(&self.namespace) {
            self.cleanup_commands_succeeded &= command_succeeds("ip", &["netns", "del", &self.namespace]);
        }
        self.cleaned = true;
    }

    fn cleanup_verified(&self) -> bool {
        self.cleaned
            && self.cleanup_commands_succeeded
            && netns_is_absent(&self.namespace)
            && link_is_absent(&self.host_veth)
            && link_is_absent(&self.peer_veth)
            && link_is_absent(TUN_NAME)
            && policy_priority_is_clear(false, &self.rule_priority)
            && policy_priority_is_clear(true, &self.rule_priority)
    }
}

impl Drop for Topology {
    fn drop(&mut self) {
        self.cleanup();
    }
}

#[derive(Clone, Copy)]
struct PhaseEvidence {
    spec: PhaseSpec,
    packets: PacketCounts,
    socks_events: u64,
    peer_events: u64,
}

#[test]
#[ignore = "requires root, CAP_NET_ADMIN, an unprivileged SO_BINDTODEVICE-capable Linux kernel, and RIPDPI_RUN_SO_BINDTODEVICE_E2E=1"]
fn e2e_so_bindtodevice_tun_uid_guard() {
    assert_eq!(std::env::var("RIPDPI_RUN_SO_BINDTODEVICE_E2E").as_deref(), Ok("1"));
    assert_eq!(unsafe { libc::geteuid() }, 0, "physical lane must run as root");
    let evidence_path = required_path("RIPDPI_SO_BIND_EVIDENCE_PATH");
    let source_sha = required_env("RIPDPI_EVIDENCE_SOURCE_SHA");
    let workflow_run_id = required_env("RIPDPI_EVIDENCE_RUN_ID");
    let workflow_run_attempt = required_env("RIPDPI_EVIDENCE_RUN_ATTEMPT");
    assert!(
        source_sha.len() == 40 && source_sha.bytes().all(|byte| byte.is_ascii_hexdigit()),
        "source SHA must be 40 hex"
    );

    let root = std::env::temp_dir().join(format!("ripdpi-so-bind-{}", std::process::id()));
    fs::create_dir(&root).expect("create private test directory");
    let event_path = root.join("peer.events");
    let ownership_path = required_path("RIPDPI_SO_BIND_OWNERSHIP_PATH");
    let mut topology = Topology::start(&root, &ownership_path);

    let fixture = FixtureStack::start(FixtureConfig {
        tcp_echo_port: 0,
        udp_echo_port: 0,
        tls_echo_port: 0,
        dns_udp_port: 0,
        dns_http_port: 0,
        dns_dot_port: 0,
        dns_dnscrypt_port: 0,
        dns_doq_port: 0,
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
        socks5_port: 0,
        control_port: 0,
        ..FixtureConfig::default()
    })
    .expect("start local SOCKS fixture");
    let proxy = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.manifest().socks5_port);

    let tun = LinuxTunnel::open(Some(TUN_NAME), false).expect("open deterministic tun0");
    tun.set_mtu(1500).expect("set tun0 MTU");
    tun.set_ipv4(Ipv4Addr::new(10, 0, 0, 1), 24).expect("set tun0 IPv4");
    tun.set_ipv6(Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, 1), 64).expect("set tun0 IPv6");
    tun.set_up().expect("bring tun0 up");
    topology.install_tun_policy();

    let raw_fd = tun.fd();
    std::mem::forget(tun);
    // SAFETY: `tun` was forgotten, so this is the sole owner of its fd.
    let owned_fd = unsafe { OwnedFd::from_raw_fd(raw_fd) };
    let mut config = Arc::try_unwrap(test_tunnel_config_with_misc(proxy, |misc| {
        misc.uid_policy_mode = "allowlist".to_owned();
        misc.uid_policy_uids = vec![ALLOWED_UID];
    }))
    .unwrap_or_else(|_| panic!("test config Arc must be uniquely owned"));
    config.tunnel.ipv6 = Some("fd00::1/64".to_owned());
    let config = Arc::new(config);

    ripdpi_flow_app_attribution::clear();
    let _attribution_worker = AttributionWorker::start();
    let stats = Arc::new(Stats::new());
    let packet_observer = Arc::new(PhasePacketObserver::default());
    stats.set_packet_observer(Arc::clone(&packet_observer) as Arc<dyn PacketObserver>);
    let cancel = CancellationToken::new();
    let thread_cancel = cancel.clone();
    let thread_stats = Arc::clone(&stats);
    let mut tunnel = TunnelRuntime {
        cancel,
        thread: Some(std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime");
            runtime.block_on(run_tunnel(config, owned_fd, thread_cancel, thread_stats))
        })),
    };
    std::thread::sleep(Duration::from_millis(250));

    let mut evidence = Vec::with_capacity(PHASES.len());
    for phase in PHASES {
        fixture.events().clear();
        let peer_before = peer_event_count(&event_path, phase.id);
        let output = run_client_helper(phase);
        assert!(
            output.status.success(),
            "{} helper failed:\n{}\n{}",
            phase.id,
            text(&output.stdout),
            text(&output.stderr)
        );
        std::thread::sleep(Duration::from_millis(80));
        let packets = packet_observer.snapshot(phase.source_port);
        let events = fixture.events().snapshot();
        let socks_events = events
            .iter()
            .filter(|event| event.service == "socks5_relay" && event.protocol == phase.protocol.label())
            .count() as u64;
        let peer_events = peer_event_count(&event_path, phase.id).saturating_sub(peer_before);
        assert_phase(phase, packets, socks_events, peer_events);
        evidence.push(PhaseEvidence { spec: phase, packets, socks_events, peer_events });
    }

    stats.clear_packet_observer();
    tunnel.stop();
    ripdpi_flow_app_attribution::clear();
    topology.cleanup();
    assert!(topology.cleanup_verified(), "network namespace, veth, or tun0 survived cleanup");
    write_manifest(&evidence_path, &source_sha, &workflow_run_id, &workflow_run_attempt, &evidence, true);
    let _ = fs::remove_dir_all(root);
}

fn assert_phase(phase: PhaseSpec, packets: PacketCounts, socks_events: u64, peer_events: u64) {
    match (phase.bind_to_tun, phase.expected) {
        (false, Expected::Echo) => {
            assert_eq!(packets.inbound, 0, "{} direct control entered tun0", phase.id);
            assert_eq!(packets.outbound, 0, "{} direct control received a tun0 packet", phase.id);
            assert_eq!(socks_events, 0, "{} direct control reached SOCKS", phase.id);
            assert_eq!(peer_events, 1, "{} direct control must reach the peer exactly once", phase.id);
        }
        (true, Expected::Echo) => {
            assert!(packets.inbound > 0, "{} must enter tun0", phase.id);
            assert!(packets.outbound > 0, "{} must receive a tun0 response", phase.id);
            assert!(socks_events > 0, "{} must cross the SOCKS relay", phase.id);
            assert_eq!(peer_events, 1, "{} must reach the peer exactly once", phase.id);
        }
        (true, Expected::Reset) => {
            assert!(packets.inbound > 0, "{} denied TCP must enter tun0", phase.id);
            assert_eq!(packets.outbound_resets, 1, "{} must receive exactly one physical TCP RST", phase.id);
            assert_eq!(socks_events, 0, "{} denied TCP must not reach SOCKS", phase.id);
            assert_eq!(peer_events, 0, "{} denied TCP must not reach the peer", phase.id);
        }
        (true, Expected::Drop) => {
            assert!(packets.inbound > 0, "{} denied UDP must enter tun0", phase.id);
            assert_eq!(packets.outbound, 0, "{} denied UDP must not receive a TUN response", phase.id);
            assert_eq!(socks_events, 0, "{} denied UDP must not reach SOCKS", phase.id);
            assert_eq!(peer_events, 0, "{} denied UDP must not reach the peer", phase.id);
        }
        _ => panic!("invalid phase contract: {}", phase.id),
    }
}

fn run_client_helper(phase: PhaseSpec) -> Output {
    let current_exe = std::env::current_exe().expect("current test executable");
    let uid = phase.uid.to_string();
    let gid = HELPER_GID.to_string();
    let mut command = Command::new("setpriv");
    command
        .args([
            "--reuid",
            &uid,
            "--regid",
            &gid,
            "--clear-groups",
            "--inh-caps=-all",
            "--ambient-caps=-all",
            "--bounding-set=-all",
            "--no-new-privs",
            "--",
        ])
        .arg(current_exe)
        .args(["--exact", "so_bindtodevice_client_helper", "--ignored", "--nocapture"])
        .env("RIPDPI_SO_BIND_HELPER", "client")
        .env("RIPDPI_SO_BIND_PHASE", phase.id)
        .env("RIPDPI_SO_BIND_FAMILY", phase.family.label())
        .env("RIPDPI_SO_BIND_PROTOCOL", phase.protocol.label())
        .env("RIPDPI_SO_BIND_SOURCE_PORT", phase.source_port.to_string())
        .env("RIPDPI_SO_BIND_EXPECTED_UID", phase.uid.to_string())
        .env("RIPDPI_SO_BIND_DEVICE", if phase.bind_to_tun { TUN_NAME } else { "" })
        .env(
            "RIPDPI_SO_BIND_EXPECTED",
            match phase.expected {
                Expected::Echo => "echo",
                Expected::Reset => "reset",
                Expected::Drop => "drop",
            },
        );
    command.output().expect("run unprivileged client helper")
}

#[test]
#[ignore = "internal subprocess helper"]
fn so_bindtodevice_client_helper() {
    assert_eq!(required_env("RIPDPI_SO_BIND_HELPER"), "client");
    let phase = required_env("RIPDPI_SO_BIND_PHASE");
    let family = match required_env("RIPDPI_SO_BIND_FAMILY").as_str() {
        "ipv4" => Family::Ipv4,
        "ipv6" => Family::Ipv6,
        other => panic!("unsupported family: {other}"),
    };
    let protocol = required_env("RIPDPI_SO_BIND_PROTOCOL");
    let source_port = required_env("RIPDPI_SO_BIND_SOURCE_PORT").parse::<u16>().expect("source port");
    let expected_uid = required_env("RIPDPI_SO_BIND_EXPECTED_UID").parse::<u32>().expect("expected UID");
    // SAFETY: getuid has no arguments or caller preconditions and only queries this process's real UID.
    assert_eq!(unsafe { libc::getuid() }, expected_uid, "helper real UID must match the phase UID");
    // SAFETY: geteuid has no arguments or caller preconditions and only queries this process's effective UID.
    assert_eq!(unsafe { libc::geteuid() }, expected_uid, "helper process must run under the phase UID");
    // SAFETY: getgid has no arguments or caller preconditions and only queries this process's real GID.
    assert_eq!(unsafe { libc::getgid() }, HELPER_GID, "helper real GID must be unprivileged");
    // SAFETY: getegid has no arguments or caller preconditions and only queries this process's effective GID.
    assert_eq!(unsafe { libc::getegid() }, HELPER_GID, "helper effective GID must be unprivileged");
    assert_unprivileged_process();
    let device = required_env("RIPDPI_SO_BIND_DEVICE");
    let expected = required_env("RIPDPI_SO_BIND_EXPECTED");
    let target_port = if protocol == "tcp" { TCP_ECHO_PORT } else { UDP_ECHO_PORT };
    let target = family.target(target_port);
    let payload = phase.as_bytes();
    let domain = if family == Family::Ipv4 { Domain::IPV4 } else { Domain::IPV6 };
    let socket_type = if protocol == "tcp" { Type::STREAM } else { Type::DGRAM };
    let socket_protocol = if protocol == "tcp" { Protocol::TCP } else { Protocol::UDP };
    let socket = Socket::new(domain, socket_type, Some(socket_protocol)).expect("create client socket");
    if !device.is_empty() {
        socket.bind_device(Some(device.as_bytes())).expect("unprivileged SO_BINDTODEVICE=tun0");
    }
    let source = match family {
        Family::Ipv4 => SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), source_port),
        Family::Ipv6 => SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), source_port),
    };
    socket.bind(&source.into()).expect("bind fixed source port");
    socket.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    socket.set_write_timeout(Some(Duration::from_secs(2))).expect("write timeout");
    let connect_result = socket.connect(&target.into());

    if protocol == "tcp" && expected == "reset" {
        match connect_result {
            Ok(()) => {
                let mut stream = TcpStream::from(socket);
                let result = stream.write_all(payload).and_then(|()| {
                    let mut byte = [0_u8; 1];
                    stream.read_exact(&mut byte)
                });
                let error = result.expect_err("denied TCP must be reset");
                assert_eq!(error.raw_os_error(), Some(libc::ECONNRESET), "denied TCP must fail with ECONNRESET");
            }
            Err(error) => {
                assert_eq!(
                    error.raw_os_error(),
                    Some(libc::ECONNREFUSED),
                    "denied TCP SYN reset must fail connect with ECONNREFUSED"
                );
            }
        }
        return;
    }

    connect_result.expect("connect client socket");

    match (protocol.as_str(), expected.as_str()) {
        ("tcp", "echo") => {
            let mut stream = TcpStream::from(socket);
            stream.write_all(payload).expect("send TCP payload");
            let mut echoed = vec![0_u8; payload.len()];
            stream.read_exact(&mut echoed).expect("receive TCP echo");
            assert_eq!(echoed, payload);
        }
        ("udp", "echo") => {
            let udp = UdpSocket::from(socket);
            udp.send(payload).expect("send UDP payload");
            let mut echoed = vec![0_u8; payload.len()];
            let read = udp.recv(&mut echoed).expect("receive UDP echo");
            assert_eq!(&echoed[..read], payload);
        }
        ("udp", "drop") => {
            let udp = UdpSocket::from(socket);
            udp.send(payload).expect("send denied UDP payload");
            let mut byte = [0_u8; 1];
            let error = udp.recv(&mut byte).expect_err("denied UDP must time out");
            assert!(
                matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut),
                "unexpected UDP error: {error}"
            );
        }
        _ => panic!("unsupported helper contract: protocol={protocol} expected={expected}"),
    }
}

#[test]
#[ignore = "internal subprocess helper"]
fn so_bindtodevice_peer_helper() {
    assert_eq!(required_env("RIPDPI_SO_BIND_HELPER"), "peer");
    let ready = required_path("RIPDPI_SO_BIND_PEER_READY");
    let stop = required_path("RIPDPI_SO_BIND_PEER_STOP");
    let events = required_path("RIPDPI_SO_BIND_PEER_EVENTS");
    let event_lock = Arc::new(Mutex::new(()));
    let listeners = [
        TcpListener::bind((IPV4_TARGET, TCP_ECHO_PORT)).expect("bind IPv4 TCP peer"),
        TcpListener::bind((IPV6_TARGET, TCP_ECHO_PORT)).expect("bind IPv6 TCP peer"),
    ];
    let sockets = [
        UdpSocket::bind((IPV4_TARGET, UDP_ECHO_PORT)).expect("bind IPv4 UDP peer"),
        UdpSocket::bind((IPV6_TARGET, UDP_ECHO_PORT)).expect("bind IPv6 UDP peer"),
    ];
    let mut workers = Vec::new();
    for listener in listeners {
        listener.set_nonblocking(true).expect("nonblocking TCP peer");
        let stop = stop.clone();
        let events = events.clone();
        let event_lock = Arc::clone(&event_lock);
        workers.push(std::thread::spawn(move || tcp_peer_loop(listener, &stop, &events, &event_lock)));
    }
    for socket in sockets {
        socket.set_nonblocking(true).expect("nonblocking UDP peer");
        let stop = stop.clone();
        let events = events.clone();
        let event_lock = Arc::clone(&event_lock);
        workers.push(std::thread::spawn(move || udp_peer_loop(socket, &stop, &events, &event_lock)));
    }
    fs::write(&ready, b"ready\n").expect("publish peer readiness");
    while !stop.exists() {
        std::thread::sleep(Duration::from_millis(20));
    }
    for worker in workers {
        worker.join().expect("peer worker");
    }
}

fn tcp_peer_loop(listener: TcpListener, stop: &Path, events: &Path, event_lock: &Mutex<()>) {
    while !stop.exists() {
        match listener.accept() {
            Ok((mut stream, _)) => {
                let mut payload = Vec::new();
                let mut chunk = [0_u8; 64];
                loop {
                    let read = stream.read(&mut chunk).expect("read TCP peer payload");
                    assert!(read > 0, "TCP peer closed before a complete phase payload");
                    payload.extend_from_slice(&chunk[..read]);
                    if PHASES.iter().any(|phase| phase.id.as_bytes() == payload) {
                        break;
                    }
                    assert!(
                        PHASES.iter().any(|phase| phase.id.as_bytes().starts_with(&payload)),
                        "TCP peer received an unknown phase payload",
                    );
                }
                stream.write_all(&payload).expect("send TCP peer echo");
                append_peer_event(events, event_lock, &payload);
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(10)),
            Err(error) => panic!("TCP peer failed: {error}"),
        }
    }
}

fn udp_peer_loop(socket: UdpSocket, stop: &Path, events: &Path, event_lock: &Mutex<()>) {
    while !stop.exists() {
        let mut payload = [0_u8; 256];
        match socket.recv_from(&mut payload) {
            Ok((read, peer)) => {
                let payload = &payload[..read];
                socket.send_to(payload, peer).expect("send UDP peer echo");
                append_peer_event(events, event_lock, payload);
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(10)),
            Err(error) => panic!("UDP peer failed: {error}"),
        }
    }
}

fn append_peer_event(path: &Path, lock: &Mutex<()>, payload: &[u8]) {
    let _guard = lock.lock().expect("peer event lock");
    let mut file = OpenOptions::new().create(true).append(true).open(path).expect("open peer event log");
    file.write_all(payload).expect("write peer event");
    file.write_all(b"\n").expect("terminate peer event");
}

fn peer_event_count(path: &Path, phase: &str) -> u64 {
    fs::read_to_string(path).unwrap_or_default().lines().filter(|line| *line == phase).count() as u64
}

fn write_manifest(
    path: &Path,
    source_sha: &str,
    run_id: &str,
    run_attempt: &str,
    phases: &[PhaseEvidence],
    cleanup_verified: bool,
) {
    let mut phase_json = String::new();
    for (index, phase) in phases.iter().enumerate() {
        if index > 0 {
            phase_json.push(',');
        }
        let _ = std::fmt::Write::write_fmt(
            &mut phase_json,
            format_args!(
                "{{\"family\":\"{}\",\"id\":\"{}\",\"outcome\":\"PASS\",\"peerEvents\":{},\"protocol\":\"{}\",\"socksEvents\":{},\"tunInboundPackets\":{},\"tunOutboundPackets\":{},\"tunOutboundResets\":{}}}",
                phase.spec.family.label(),
                phase.spec.id,
                phase.peer_events,
                phase.spec.protocol.label(),
                phase.socks_events,
                phase.packets.inbound,
                phase.packets.outbound,
                phase.packets.outbound_resets,
            ),
        );
    }
    let manifest = format!(
        "{{\"capabilities\":{{\"ipv4\":true,\"ipv6\":true,\"realTun\":true,\"unprivilegedSoBindToDevice\":true}},\"cleanupVerified\":{cleanup_verified},\"phases\":[{phase_json}],\"provenance\":{{\"sourceSha\":\"{source_sha}\",\"testTarget\":\"so_bindtodevice_e2e\",\"workflowRunAttempt\":\"{run_attempt}\",\"workflowRunId\":\"{run_id}\"}},\"result\":\"PASS\",\"version\":\"so_bindtodevice_tun_evidence_v1\"}}\n",
    );
    fs::write(path, manifest).expect("write canonical SO_BINDTODEVICE evidence manifest");
}

fn required_env(name: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| panic!("{name} is required"))
}

fn required_path(name: &str) -> PathBuf {
    PathBuf::from(required_env(name))
}

fn run(program: &str, args: &[&str]) {
    let output = Command::new(program).args(args).output().unwrap_or_else(|error| panic!("run {program}: {error}"));
    assert!(
        output.status.success(),
        "{program} {} failed:\n{}\n{}",
        args.join(" "),
        text(&output.stdout),
        text(&output.stderr)
    );
}

fn command_succeeds(program: &str, args: &[&str]) -> bool {
    Command::new(program).args(args).status().is_ok_and(|status| status.success())
}

fn netns_exists(namespace: &str) -> bool {
    Command::new("ip").args(["netns", "list"]).output().is_ok_and(|output| {
        output.status.success()
            && text(&output.stdout).lines().any(|line| line.split_whitespace().next() == Some(namespace))
    })
}

fn netns_is_absent(namespace: &str) -> bool {
    Command::new("ip").args(["netns", "list"]).output().is_ok_and(|output| {
        output.status.success()
            && text(&output.stdout).lines().all(|line| line.split_whitespace().next() != Some(namespace))
    })
}

fn policy_priority_is_clear(ipv6: bool, priority: &str) -> bool {
    let mut args = Vec::new();
    if ipv6 {
        args.push("-6");
    }
    args.extend(["-o", "rule", "show", "pref", priority]);
    Command::new("ip")
        .args(args)
        .output()
        .is_ok_and(|output| output.status.success() && output.stdout.iter().all(u8::is_ascii_whitespace))
}

fn link_exists(name: &str) -> bool {
    Command::new("ip").args(["link", "show", "dev", name]).output().is_ok_and(|output| output.status.success())
}

fn link_is_absent(name: &str) -> bool {
    Command::new("ip").args(["-o", "link", "show"]).output().is_ok_and(|output| {
        output.status.success()
            && text(&output.stdout).lines().all(|line| {
                line.split_once(": ")
                    .and_then(|(_, remainder)| remainder.split(':').next())
                    .and_then(|interface| interface.split('@').next())
                    != Some(name)
            })
    })
}

fn assert_unprivileged_process() {
    let status = fs::read_to_string("/proc/self/status").expect("read Linux process privilege state");
    for field in ["CapInh:", "CapPrm:", "CapEff:", "CapBnd:", "CapAmb:"] {
        let value = status
            .lines()
            .find_map(|line| line.strip_prefix(field))
            .unwrap_or_else(|| panic!("{field} missing from /proc/self/status"))
            .trim();
        assert_eq!(u64::from_str_radix(value, 16).expect("hex capability mask"), 0, "{field} must be empty");
    }
    let no_new_privileges = status
        .lines()
        .find_map(|line| line.strip_prefix("NoNewPrivs:"))
        .expect("NoNewPrivs missing from /proc/self/status")
        .trim();
    assert_eq!(no_new_privileges, "1", "helper must prohibit privilege escalation");
    let groups = status
        .lines()
        .find_map(|line| line.strip_prefix("Groups:"))
        .expect("Groups missing from /proc/self/status")
        .split_whitespace()
        .map(|group| group.parse::<u32>().expect("numeric supplementary group"))
        .collect::<Vec<_>>();
    assert!(groups.is_empty(), "helper must not retain supplementary groups: {groups:?}");
}

fn text(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}
