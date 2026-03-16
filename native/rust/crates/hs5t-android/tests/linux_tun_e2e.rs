#![cfg(target_os = "linux")]

use std::io::{self, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::process::Command;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use hs5t_config::{Config, MapDnsConfig, MiscConfig, Socks5Config, TunnelConfig};
use hs5t_core::{run_tunnel, Stats};
use hs5t_tunnel::{LinuxTunnel, TunnelDriver};
use local_network_fixture::{FixtureConfig, FixtureStack};
use tokio_util::sync::CancellationToken;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const E2E_ROUTE_CIDR: &str = "198.18.0.0/15";
const TUN_IPV4: &str = "10.77.0.1/24";
const MAPDNS_ADDRESS: &str = "198.18.0.53";
const MAPDNS_NETWORK: &str = "198.18.0.0";
const MAPDNS_NETMASK: &str = "255.254.0.0";

#[test]
#[ignore = "requires Linux CAP_NET_ADMIN and RIPDPI_RUN_TUN_E2E=1"]
fn linux_tun_tcp_udp_and_mapdns_round_trip() {
    if std::env::var("RIPDPI_RUN_TUN_E2E").ok().as_deref() != Some("1") {
        eprintln!("skipping linux_tun_tcp_udp_and_mapdns_round_trip because RIPDPI_RUN_TUN_E2E!=1");
        return;
    }

    let _guard = test_guard();
    let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("start fixture");
    let tun = TunHarness::start().expect("start tun harness");

    let stats = Arc::new(Stats::new());
    let _worker = TunnelWorker::start(
        tun.dup_fd().expect("dup tun fd"),
        Arc::new(build_tunnel_config(&fixture, tun.name())),
        stats.clone(),
    );

    wait_for_tunnel_path(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);

    tcp_round_trip(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().tcp_echo_port);
    udp_round_trip(fixture.manifest().fixture_ipv4.as_str(), fixture.manifest().udp_echo_port);
    mapdns_round_trip(MAPDNS_ADDRESS, fixture.manifest().tcp_echo_port);

    let snapshot = stats.snapshot();
    assert!(snapshot.0 > 0, "expected tx packets > 0, got {snapshot:?}");
    assert!(snapshot.2 > 0, "expected rx packets > 0, got {snapshot:?}");
}

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().expect("lock tun e2e")
}

struct TunnelWorker {
    cancel: CancellationToken,
    handle: Option<thread::JoinHandle<io::Result<()>>>,
}

impl TunnelWorker {
    fn start(tun_fd: i32, config: Arc<Config>, stats: Arc<Stats>) -> Self {
        let cancel = CancellationToken::new();
        let cancel_for_worker = cancel.clone();
        let handle = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_current_thread().enable_all().build().expect("build tokio runtime");
            runtime.block_on(run_tunnel(config, tun_fd, cancel_for_worker, stats))
        });
        Self { cancel, handle: Some(handle) }
    }
}

impl Drop for TunnelWorker {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(handle) = self.handle.take() {
            let worker_result = handle.join().expect("join tunnel worker");
            worker_result.expect("tunnel worker exited cleanly");
        }
    }
}

fn build_tunnel_config(fixture: &FixtureStack, tun_name: &str) -> Config {
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
            port: fixture.manifest().socks5_port,
            address: "127.0.0.1".to_string(),
            udp: Some("udp".to_string()),
            udp_address: None,
            pipeline: Some(false),
            username: None,
            password: None,
            mark: None,
        },
        mapdns: Some(MapDnsConfig {
            address: MAPDNS_ADDRESS.to_string(),
            port: 53,
            network: Some(MAPDNS_NETWORK.to_string()),
            netmask: Some(MAPDNS_NETMASK.to_string()),
            cache_size: 128,
            resolver_id: Some("fixture".to_string()),
            doh_url: Some(format!("http://fixture.test:{}/dns-query", fixture.manifest().dns_http_port)),
            doh_bootstrap_ips: vec!["127.0.0.1".to_string()],
            dns_query_timeout_ms: 2_000,
        }),
        misc: MiscConfig { max_session_count: 128, ..MiscConfig::default() },
    }
}

fn tcp_round_trip(host: &str, port: u16) {
    let mut stream = TcpStream::connect((host, port)).expect("connect through tunnel");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set tcp read timeout");
    stream.write_all(b"tun tcp").expect("write tcp payload");
    let mut buf = [0u8; 7];
    stream.read_exact(&mut buf).expect("read tcp echo");
    assert_eq!(&buf, b"tun tcp");
}

fn udp_round_trip(host: &str, port: u16) {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind udp client");
    socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set udp timeout");
    socket.connect((host, port)).expect("connect udp through tunnel");
    socket.send(b"tun udp").expect("send udp payload");
    let mut buf = [0u8; 64];
    let read = socket.recv(&mut buf).expect("receive udp echo");
    assert_eq!(&buf[..read], b"tun udp");
}

fn mapdns_round_trip(host: &str, tcp_port: u16) {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind dns client");
    socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set dns timeout");
    socket.connect((host, 53)).expect("connect mapdns");
    socket.send(&build_dns_query("fixture.test")).expect("send dns query");
    let mut buf = [0u8; 512];
    let read = socket.recv(&mut buf).expect("receive dns response");
    let answers = parse_a_answers(&buf[..read]);
    let synthetic_ip = answers
        .into_iter()
        .find(|ip| ip.octets()[0] == 198 && ip.octets()[1] == 18)
        .expect("expected mapdns response in 198.18.0.0/15");

    let mut stream = TcpStream::connect((synthetic_ip, tcp_port)).expect("connect to synthetic target through tunnel");
    stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set synthetic tcp timeout");
    stream.write_all(b"mapdns").expect("write synthetic tcp payload");
    let mut echoed = [0u8; 6];
    stream.read_exact(&mut echoed).expect("read synthetic tcp echo");
    assert_eq!(&echoed, b"mapdns");
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

fn parse_a_answers(packet: &[u8]) -> Vec<Ipv4Addr> {
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
            answers.push(Ipv4Addr::new(packet[cursor], packet[cursor + 1], packet[cursor + 2], packet[cursor + 3]));
        }
        cursor += rdlen;
    }
    answers
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

fn other_io<E>(error: E) -> io::Error
where
    E: ToString,
{
    io::Error::new(ErrorKind::Other, error.to_string())
}
