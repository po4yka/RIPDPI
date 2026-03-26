#[path = "../../ripdpi-runtime/tests/support/mod.rs"]
mod support;

#[allow(dead_code)]
#[path = "../../ripdpi-packets/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

use std::env;
use std::fs::{self, File};
use std::io;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::panic::{catch_unwind, resume_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use local_network_fixture::{FixtureConfig, FixtureEvent, FixtureManifest, FixtureStack};
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use serde_json::Value;

use support::socks5::{attempt_socks_connect_domain_round_trip, socks_udp_associate, udp_proxy_client, udp_proxy_roundtrip_with_socket};
use support::tls::attempt_socks5_tls_round_trip;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const ENABLE_ENV: &str = "RIPDPI_RUN_PACKET_SMOKE";
const ARTIFACT_DIR_ENV: &str = "RIPDPI_PACKET_SMOKE_ARTIFACT_DIR";
const INTERFACE_ENV: &str = "RIPDPI_PACKET_SMOKE_IFACE";
const TCPDUMP_BIN_ENV: &str = "RIPDPI_PACKET_SMOKE_TCPDUMP_BIN";
const TSHARK_BIN_ENV: &str = "RIPDPI_PACKET_SMOKE_TSHARK_BIN";

#[test]
fn cli_packet_smoke_tcp_split_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_split_family",
        |_paths| vec!["-K", "t", "-s", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_http_echo_strict(proxy_port, fixture, "split"),
        |run| {
            assert_tcp_payload_split(run, run.manifest.tcp_echo_port)?;
            assert_fixture_event(run, "tcp_echo")?;
            Ok(())
        },
    );
}

#[test]
fn cli_packet_smoke_tcp_disorder_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_disorder_family",
        |_paths| vec!["-K", "t", "-t", "2", "-d", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_http_echo_best_effort(proxy_port, fixture, "disorder"),
        |run| assert_outbound_ttl(run, run.manifest.tcp_echo_port, 2),
    );
}

#[test]
fn cli_packet_smoke_tcp_fake_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_fake_family",
        |_paths| {
            vec!["-K", "t", "-t", "5", "-f", "host+1", "--fake-tls-profile", "google_chrome"]
                .into_iter()
                .map(str::to_string)
                .collect()
        },
        |manifest| format!("tcp and port {}", manifest.tls_echo_port),
        |proxy_port, fixture| drive_tls_probe_best_effort(proxy_port, fixture),
        |run| assert_outbound_ttl(run, run.manifest.tls_echo_port, 5),
    );
}

#[test]
fn cli_packet_smoke_tcp_oob_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_oob_family",
        |_paths| vec!["-K", "t", "-o", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_http_echo_best_effort(proxy_port, fixture, "oob"),
        |run| assert_outbound_urgent(run, run.manifest.tcp_echo_port),
    );
}

#[test]
fn cli_packet_smoke_tcp_disoob_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_disoob_family",
        |_paths| vec!["-K", "t", "-t", "3", "-q", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_http_echo_best_effort(proxy_port, fixture, "disoob"),
        |run| {
            assert_outbound_urgent(run, run.manifest.tcp_echo_port)?;
            assert_outbound_ttl(run, run.manifest.tcp_echo_port, 3)
        },
    );
}

#[test]
fn cli_packet_smoke_tcp_transport_knobs_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_transport_knobs_family",
        |_paths| {
            vec![
                "-K",
                "t",
                "-t",
                "6",
                "-f",
                "host+1",
                "--fake-tls-profile",
                "google_chrome",
                "-S",
                "--window-clamp",
                "2",
                "--strip-timestamps",
                "--entropy-target",
                "0.65",
                "--entropy-max-pad",
                "48",
                "--entropy-mode",
                "combined",
            ]
            .into_iter()
            .map(str::to_string)
            .collect()
        },
        |manifest| format!("tcp and port {}", manifest.tls_echo_port),
        |proxy_port, fixture| drive_tls_probe_best_effort(proxy_port, fixture),
        |run| assert_outbound_ttl(run, run.manifest.tls_echo_port, 6),
    );
}

#[test]
fn cli_packet_smoke_udp_quic_family() {
    run_capture_scenario(
        "cli_packet_smoke_udp_quic_family",
        |_paths| {
            vec![
                "-K",
                "u",
                "-H",
                ":docs.example.test",
                "-a",
                "2",
                "--quic-dummy-prepend",
                "--quic-sni-split",
                "--quic-fake-version",
                "0x1a2b3c4d",
                "--quic-low-port",
            ]
            .into_iter()
            .map(str::to_string)
            .collect()
        },
        |manifest| format!("udp and port {}", manifest.udp_echo_port),
        |proxy_port, fixture| drive_udp_quic_round_trip(proxy_port, fixture),
        |run| {
            assert_udp_outbound_count_at_least(run, run.manifest.udp_echo_port, 3)?;
            assert_quic_version_present(run, 0x1a2b3c4d)?;
            if cfg!(any(target_os = "linux", target_os = "android")) {
                assert_udp_low_source_port(run, run.manifest.udp_echo_port, 4_096)?;
            }
            assert_stderr_contains(run, "docs.example.test")?;
            assert_stderr_contains(run, "other.example.test")?;
            Ok(())
        },
    );
}

#[test]
fn cli_packet_smoke_adaptive_family() {
    run_capture_scenario(
        "cli_packet_smoke_adaptive_family",
        |paths| {
            let mut args = vec![
                "-K",
                "t",
                "-s",
                "auto(host)",
                "--strategy-evolution",
                "--evolution-epsilon",
                "1.0",
                "--host-autolearn",
                "--host-autolearn-file",
            ]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
            args.push(paths.host_autolearn_store.to_string_lossy().into_owned());
            args
        },
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_adaptive_round_trip(proxy_port, fixture),
        |run| {
            assert_fixture_event(run, "tcp_echo")?;
            assert_stderr_contains(run, "strategy evolution selected combo")?;
            assert_stderr_contains(run, "strategy evolution recorded success")?;
            assert_stderr_contains(run, "autolearn event")?;
            let autolearn_store = fs::read_to_string(&run.paths.host_autolearn_store)
                .map_err(|err| format!("failed to read host autolearn store: {err}"))?;
            if !autolearn_store.contains(&run.manifest.fixture_domain) {
                return Err(format!(
                    "expected host autolearn store to contain {}, got: {autolearn_store}",
                    run.manifest.fixture_domain
                ));
            }
            Ok(())
        },
    );
}

fn run_capture_scenario<Args, Filter, Drive, Assert>(
    id: &str,
    build_args: Args,
    capture_filter: Filter,
    drive: Drive,
    assert: Assert,
) where
    Args: Fn(&ScenarioPaths) -> Vec<String>,
    Filter: Fn(&FixtureManifest) -> String,
    Drive: Fn(u16, &FixtureStack) -> Result<(), String>,
    Assert: Fn(&ScenarioRun) -> Result<(), String>,
{
    if !packet_smoke_enabled() {
        eprintln!("skipping {id} because {ENABLE_ENV} is not enabled");
        return;
    }

    let _guard = test_guard();
    ensure_capture_tooling();

    let paths = ScenarioPaths::new(id).expect("create scenario artifact directory");
    let fixture = FixtureStack::start(dynamic_fixture_config()).expect("start local packet smoke fixture");
    write_json_pretty(&paths.fixture_manifest, fixture.manifest()).expect("write fixture manifest artifact");

    let listen_port = reserve_listen_port();
    let cli_args = build_cli_args(listen_port, build_args(&paths));
    let mut cli = start_cli_process(&cli_args, &paths.cli_stderr).expect("start ripdpi cli");
    wait_for_proxy_listener(listen_port, &mut cli).expect("wait for CLI listener");

    let mut capture = start_capture(&capture_filter(fixture.manifest()), &paths.capture_pcap).expect("start tcpdump capture");

    let drive_result = catch_unwind(AssertUnwindSafe(|| drive(listen_port, &fixture)));

    let capture_stop_result = stop_capture(&mut capture);
    let cli_stop_result = stop_cli_process(&mut cli);

    let events = fixture.events().snapshot();
    write_json_pretty(&paths.fixture_events, &events).expect("write fixture events artifact");

    capture_stop_result.expect("stop tcpdump capture");
    cli_stop_result.expect("stop ripdpi cli");

    let packets = decode_capture_json(&paths.capture_pcap, &paths.capture_json).expect("decode pcap via tshark");
    let stderr = fs::read_to_string(&paths.cli_stderr).unwrap_or_default();

    match drive_result {
        Ok(Ok(())) => {}
        Ok(Err(err)) => panic!("{id} traffic probe failed: {err}"),
        Err(payload) => resume_unwind(payload),
    }

    let run = ScenarioRun { manifest: fixture.manifest().clone(), events, stderr, packets, paths };
    assert(&run).unwrap_or_else(|err| panic!("{id} assertion failed: {err}"));
}

fn drive_http_echo_strict(proxy_port: u16, fixture: &FixtureStack, path_token: &str) -> Result<(), String> {
    let payload = http_echo_payload(fixture, path_token);
    let body =
        attempt_socks_connect_domain_round_trip(proxy_port, "127.0.0.1", fixture.manifest().tcp_echo_port, &payload)?;
    if body != payload {
        return Err(format!("expected echoed HTTP payload to round-trip unchanged, got {} bytes", body.len()));
    }
    Ok(())
}

fn drive_http_echo_best_effort(proxy_port: u16, fixture: &FixtureStack, path_token: &str) -> Result<(), String> {
    let payload = http_echo_payload(fixture, path_token);
    let _ = attempt_socks_connect_domain_round_trip(proxy_port, "127.0.0.1", fixture.manifest().tcp_echo_port, &payload);
    thread::sleep(Duration::from_millis(250));
    Ok(())
}

fn drive_tls_probe_best_effort(proxy_port: u16, fixture: &FixtureStack) -> Result<(), String> {
    let _ = attempt_socks5_tls_round_trip(proxy_port, fixture, None);
    thread::sleep(Duration::from_millis(250));
    Ok(())
}

fn drive_udp_quic_round_trip(proxy_port: u16, fixture: &FixtureStack) -> Result<(), String> {
    let (_control, relay) = socks_udp_associate(proxy_port);
    let udp = udp_proxy_client();

    let matching = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "docs.example.test");
    let fallback = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "other.example.test");

    let matching_body = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &matching);
    if matching_body != matching {
        return Err("matching QUIC initial did not round-trip through the UDP relay".to_string());
    }

    let fallback_body = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &fallback);
    if fallback_body != fallback {
        return Err("fallback QUIC initial did not round-trip through the UDP relay".to_string());
    }

    Ok(())
}

fn drive_adaptive_round_trip(proxy_port: u16, fixture: &FixtureStack) -> Result<(), String> {
    for round in 0..2 {
        drive_http_echo_strict(proxy_port, fixture, &format!("adaptive-{round}"))?;
    }
    Ok(())
}

fn build_cli_args(listen_port: u16, scenario_args: Vec<String>) -> Vec<String> {
    let mut args = vec![
        "--ip".to_string(),
        Ipv4Addr::LOCALHOST.to_string(),
        "--port".to_string(),
        listen_port.to_string(),
        "--debug".to_string(),
        "2".to_string(),
    ];
    args.extend(scenario_args);
    args
}

fn start_cli_process(args: &[String], stderr_path: &Path) -> io::Result<Child> {
    let stderr_file = File::create(stderr_path)?;
    Command::new(env!("CARGO_BIN_EXE_ripdpi"))
        .args(args)
        .env("RUST_LOG", "debug")
        .stdout(Stdio::null())
        .stderr(Stdio::from(stderr_file))
        .spawn()
}

fn wait_for_proxy_listener(port: u16, child: &mut Child) -> io::Result<()> {
    let deadline = Instant::now() + Duration::from_secs(5);
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    while Instant::now() < deadline {
        if let Some(status) = child.try_wait()? {
            return Err(io::Error::other(format!("ripdpi cli exited before it started listening: {status}")));
        }
        match TcpStream::connect_timeout(&addr, Duration::from_millis(100)) {
            Ok(stream) => {
                drop(stream);
                return Ok(());
            }
            Err(err)
                if matches!(
                    err.kind(),
                    io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut | io::ErrorKind::AddrNotAvailable
                ) => {}
            Err(err) => return Err(err),
        }
        thread::sleep(Duration::from_millis(50));
    }
    Err(io::Error::new(io::ErrorKind::TimedOut, "timed out waiting for proxy listener"))
}

fn start_capture(filter: &str, capture_path: &Path) -> io::Result<Child> {
    let mut child = Command::new(tcpdump_bin())
        .args([
            "-i",
            &capture_interface(),
            "-U",
            "-n",
            "-s",
            "0",
            "-w",
        ])
        .arg(capture_path)
        .arg(filter)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;

    thread::sleep(Duration::from_millis(300));
    if let Some(status) = child.try_wait()? {
        return Err(io::Error::other(format!(
            "tcpdump exited before capture traffic began; status={status}"
        )));
    }
    Ok(child)
}

fn stop_capture(child: &mut Child) -> io::Result<()> {
    stop_child_with_signal(child, Signal::SIGINT)
}

fn stop_cli_process(child: &mut Child) -> io::Result<()> {
    stop_child_with_signal(child, Signal::SIGINT)
}

fn stop_child_with_signal(child: &mut Child, signal: Signal) -> io::Result<()> {
    if child.try_wait()?.is_some() {
        return Ok(());
    }
    kill(Pid::from_raw(child.id() as i32), signal).map_err(io::Error::other)?;
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        if child.try_wait()?.is_some() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(50));
    }
    child.kill()?;
    let _ = child.wait()?;
    Ok(())
}

fn decode_capture_json(capture_path: &Path, output_path: &Path) -> Result<Vec<Value>, String> {
    let output = Command::new(tshark_bin())
        .args(["-r"])
        .arg(capture_path)
        .args(["-T", "json"])
        .output()
        .map_err(|err| format!("failed to run tshark: {err}"))?;
    if !output.status.success() {
        return Err(format!(
            "tshark failed with status {}: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    fs::write(output_path, &output.stdout).map_err(|err| format!("failed to write tshark JSON artifact: {err}"))?;
    serde_json::from_slice::<Vec<Value>>(&output.stdout)
        .map_err(|err| format!("failed to parse tshark JSON output: {err}"))
}

fn packet_smoke_enabled() -> bool {
    matches!(
        env::var(ENABLE_ENV).ok().as_deref(),
        Some("1") | Some("true") | Some("TRUE") | Some("yes") | Some("YES")
    )
}

fn ensure_capture_tooling() {
    assert_command_works(&tcpdump_bin(), ["-D"]);
    assert_command_works(&tshark_bin(), ["--version"]);
}

fn assert_command_works<const N: usize>(command: &str, args: [&str; N]) {
    let output = Command::new(command).args(args).output();
    match output {
        Ok(output) if output.status.success() => {}
        Ok(output) => panic!(
            "{command} is not usable for packet smoke tests: status={}, stderr={}",
            output.status,
            String::from_utf8_lossy(&output.stderr)
        ),
        Err(err) => panic!("{command} is not available for packet smoke tests: {err}"),
    }
}

fn tcpdump_bin() -> String {
    env::var(TCPDUMP_BIN_ENV).unwrap_or_else(|_| "tcpdump".to_string())
}

fn tshark_bin() -> String {
    env::var(TSHARK_BIN_ENV).unwrap_or_else(|_| "tshark".to_string())
}

fn capture_interface() -> String {
    env::var(INTERFACE_ENV).unwrap_or_else(|_| {
        if cfg!(target_os = "macos") {
            "lo0".to_string()
        } else {
            "lo".to_string()
        }
    })
}

fn dynamic_fixture_config() -> FixtureConfig {
    FixtureConfig {
        bind_host: Ipv4Addr::LOCALHOST.to_string(),
        android_host: Ipv4Addr::LOCALHOST.to_string(),
        tcp_echo_port: 0,
        udp_echo_port: 0,
        tls_echo_port: 0,
        dns_udp_port: 0,
        dns_http_port: 0,
        dns_dot_port: 0,
        dns_dnscrypt_port: 0,
        dns_doq_port: 0,
        socks5_port: 0,
        control_port: 0,
        ..FixtureConfig::default()
    }
}

fn reserve_listen_port() -> u16 {
    TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .expect("bind ephemeral listener")
        .local_addr()
        .expect("read ephemeral listener address")
        .port()
}

fn http_echo_payload(fixture: &FixtureStack, path_token: &str) -> Vec<u8> {
    format!(
        "GET /{path_token} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
        fixture.manifest().fixture_domain
    )
    .into_bytes()
}

fn assert_fixture_event(run: &ScenarioRun, service: &str) -> Result<(), String> {
    if run.events.iter().any(|event| event.service == service) {
        Ok(())
    } else {
        Err(format!("expected fixture event for {service}, got {:?}", run.events))
    }
}

fn assert_stderr_contains(run: &ScenarioRun, needle: &str) -> Result<(), String> {
    if run.stderr.contains(needle) {
        Ok(())
    } else {
        Err(format!(
            "expected CLI stderr to contain {needle:?}, got:\n{}",
            run.stderr
        ))
    }
}

fn assert_tcp_payload_split(run: &ScenarioRun, port: u16) -> Result<(), String> {
    let count = run
        .packets
        .iter()
        .filter(|packet| is_tcp_outbound(packet, port) && field_u64(packet, "tcp.len").unwrap_or_default() > 0)
        .count();
    if count >= 2 {
        Ok(())
    } else {
        Err(format!("expected at least 2 outbound TCP payload packets for port {port}, got {count}"))
    }
}

fn assert_outbound_ttl(run: &ScenarioRun, port: u16, ttl: u8) -> Result<(), String> {
    if run.packets.iter().any(|packet| {
        is_outbound_to_port(packet, port)
            && (field_u64(packet, "ip.ttl") == Some(u64::from(ttl))
                || field_u64(packet, "ipv6.hlim") == Some(u64::from(ttl)))
    }) {
        Ok(())
    } else {
        Err(format!("expected outbound packet to port {port} with ttl {ttl}"))
    }
}

fn assert_outbound_urgent(run: &ScenarioRun, port: u16) -> Result<(), String> {
    if run.packets.iter().any(|packet| {
        is_tcp_outbound(packet, port)
            && (field_u64(packet, "tcp.flags.urg") == Some(1) || field_u64(packet, "tcp.urgent_pointer") == Some(1))
    }) {
        Ok(())
    } else {
        Err(format!("expected outbound urgent TCP packet to port {port}"))
    }
}

fn assert_udp_outbound_count_at_least(run: &ScenarioRun, port: u16, minimum: usize) -> Result<(), String> {
    let count = run.packets.iter().filter(|packet| is_udp_outbound(packet, port)).count();
    if count >= minimum {
        Ok(())
    } else {
        Err(format!("expected at least {minimum} outbound UDP packets to port {port}, got {count}"))
    }
}

fn assert_udp_low_source_port(run: &ScenarioRun, port: u16, upper_bound: u16) -> Result<(), String> {
    if run.packets.iter().any(|packet| {
        is_udp_outbound(packet, port) && field_u64(packet, "udp.srcport").is_some_and(|value| value <= u64::from(upper_bound))
    }) {
        Ok(())
    } else {
        Err(format!(
            "expected at least one outbound UDP packet to port {port} with source port <= {upper_bound}"
        ))
    }
}

fn assert_quic_version_present(run: &ScenarioRun, version: u32) -> Result<(), String> {
    if run
        .packets
        .iter()
        .any(|packet| field_u64(packet, "quic.version") == Some(u64::from(version)))
    {
        Ok(())
    } else {
        Err(format!("expected captured QUIC version {version:#x} in {:?}", run.paths.capture_json))
    }
}

fn is_outbound_to_port(packet: &Value, port: u16) -> bool {
    field_u64(packet, "tcp.dstport") == Some(u64::from(port)) || field_u64(packet, "udp.dstport") == Some(u64::from(port))
}

fn is_tcp_outbound(packet: &Value, port: u16) -> bool {
    field_u64(packet, "tcp.dstport") == Some(u64::from(port))
}

fn is_udp_outbound(packet: &Value, port: u16) -> bool {
    field_u64(packet, "udp.dstport") == Some(u64::from(port))
}

fn field_u64(packet: &Value, field: &str) -> Option<u64> {
    collect_field_values(packet, field).into_iter().find_map(|value| parse_numeric_field(&value))
}

fn collect_field_values(packet: &Value, field: &str) -> Vec<String> {
    let mut out = Vec::new();
    collect_field_values_inner(packet, field, &mut out);
    out
}

fn collect_field_values_inner(value: &Value, field: &str, out: &mut Vec<String>) {
    match value {
        Value::Object(map) => {
            for (key, nested) in map {
                if key == field {
                    collect_leaf_strings(nested, out);
                }
                collect_field_values_inner(nested, field, out);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_field_values_inner(item, field, out);
            }
        }
        _ => {}
    }
}

fn collect_leaf_strings(value: &Value, out: &mut Vec<String>) {
    match value {
        Value::String(text) => out.push(text.clone()),
        Value::Number(number) => out.push(number.to_string()),
        Value::Array(items) => {
            for item in items {
                collect_leaf_strings(item, out);
            }
        }
        Value::Object(map) => {
            for nested in map.values() {
                collect_leaf_strings(nested, out);
            }
        }
        _ => {}
    }
}

fn parse_numeric_field(value: &str) -> Option<u64> {
    let trimmed = value.trim();
    if let Some(hex) = trimmed.strip_prefix("0x").or_else(|| trimmed.strip_prefix("0X")) {
        return u64::from_str_radix(hex, 16).ok();
    }
    trimmed.parse::<u64>().ok()
}

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().expect("lock packet smoke test mutex")
}

fn write_json_pretty<T: serde::Serialize>(path: &Path, value: &T) -> io::Result<()> {
    let payload = serde_json::to_vec_pretty(value).map_err(io::Error::other)?;
    fs::write(path, payload)
}

struct ScenarioRun {
    manifest: FixtureManifest,
    events: Vec<FixtureEvent>,
    stderr: String,
    packets: Vec<Value>,
    paths: ScenarioPaths,
}

#[derive(Clone)]
struct ScenarioPaths {
    fixture_manifest: PathBuf,
    fixture_events: PathBuf,
    cli_stderr: PathBuf,
    capture_pcap: PathBuf,
    capture_json: PathBuf,
    host_autolearn_store: PathBuf,
}

impl ScenarioPaths {
    fn new(id: &str) -> io::Result<Self> {
        let root = match env::var_os(ARTIFACT_DIR_ENV) {
            Some(path) => PathBuf::from(path),
            None => {
                let nonce = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis();
                env::temp_dir().join(format!("ripdpi-packet-smoke-{id}-{}-{nonce}", std::process::id()))
            }
        };
        fs::create_dir_all(&root)?;
        Ok(Self {
            fixture_manifest: root.join("fixture-manifest.json"),
            fixture_events: root.join("fixture-events.json"),
            cli_stderr: root.join("cli-stderr.log"),
            capture_pcap: root.join("capture.pcap"),
            capture_json: root.join("capture.tshark.json"),
            host_autolearn_store: root.join("host-autolearn.json"),
        })
    }
}
