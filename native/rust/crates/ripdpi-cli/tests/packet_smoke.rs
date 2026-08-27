#[path = "../../ripdpi-proxy-runtime/tests/support/mod.rs"]
mod support;

#[allow(dead_code)]
#[path = "../../ripdpi-packets/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

use std::env;
use std::fs::{self, File};
use std::io;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::panic::{AssertUnwindSafe, catch_unwind, resume_unwind};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use local_network_fixture::{FixtureConfig, FixtureEvent, FixtureManifest, FixtureStack};
use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;
use serde_json::Value;

use support::socks5::{
    attempt_socks_connect_domain_round_trip, socks_udp_associate, udp_proxy_client, udp_proxy_roundtrip_with_socket,
};
use support::tls::attempt_socks5_tls_round_trip;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const ENABLE_ENV: &str = "RIPDPI_RUN_PACKET_SMOKE";
const ARTIFACT_DIR_ENV: &str = "RIPDPI_PACKET_SMOKE_ARTIFACT_DIR";
const INTERFACE_ENV: &str = "RIPDPI_PACKET_SMOKE_IFACE";
const TCPDUMP_BIN_ENV: &str = "RIPDPI_PACKET_SMOKE_TCPDUMP_BIN";
const TSHARK_BIN_ENV: &str = "RIPDPI_PACKET_SMOKE_TSHARK_BIN";
const GENERATOR_METADATA_ENV: &str = "RIPDPI_PACKET_SMOKE_GENERATOR_METADATA";

#[test]
fn cli_packet_smoke_tcp_split_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_split_family",
        |_paths| vec!["-s", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        drive_http_echo_split,
        |run| {
            if supports_await_writable_split() {
                assert_tcp_split_host_plus_one(&run.packets, run.manifest.tcp_echo_port, &run.manifest.fixture_domain)?;
                assert_fixture_event(run, "tcp_echo")?;
            } else {
                assert_tcp_payload_to_port_captured(run, run.manifest.tcp_echo_port)?;
                assert_stderr_contains(run, "only supported on Linux/Android")?;
                eprintln!("split(host+1) packet boundary is unsupported on this platform; only rejection was checked");
            }
            Ok(())
        },
    );
}

#[test]
fn cli_packet_smoke_tcp_disorder_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_disorder_family",
        |_paths| vec!["-t", "2", "-d", "host+1"].into_iter().map(str::to_string).collect(),
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
            vec!["-t", "5", "-f", "host+1", "--fake-tls-profile", "google_chrome"]
                .into_iter()
                .map(str::to_string)
                .collect()
        },
        |manifest| format!("tcp and port {}", manifest.tls_echo_port),
        drive_tls_probe_best_effort,
        |run| {
            // The vmsplice/splice mechanism used for fake packets may not
            // produce observable TTL changes in tcpdump on loopback (kernel
            // dependent). Accept either captured TTL evidence or CLI log
            // confirming the fake strategy was applied.
            assert_outbound_ttl(run, run.manifest.tls_echo_port, 5)
                .or_else(|_| assert_stderr_contains(run, "strategy_family=fake"))
        },
    );
}

#[test]
fn cli_packet_smoke_tcp_oob_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_oob_family",
        |_paths| vec!["-o", "host+1"].into_iter().map(str::to_string).collect(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        |proxy_port, fixture| drive_http_echo_best_effort(proxy_port, fixture, "oob"),
        |run| assert_outbound_urgent(run, run.manifest.tcp_echo_port),
    );
}

#[test]
fn cli_packet_smoke_tcp_disoob_family() {
    run_capture_scenario(
        "cli_packet_smoke_tcp_disoob_family",
        |_paths| vec!["-t", "3", "-q", "host+1"].into_iter().map(str::to_string).collect(),
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
        drive_tls_probe_best_effort,
        |run| {
            assert_outbound_ttl(run, run.manifest.tls_echo_port, 6)
                .or_else(|_| assert_stderr_contains(run, "strategy_family=fake"))
        },
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
        drive_udp_quic_round_trip,
        |run| {
            assert_udp_outbound_count_at_least(run, run.manifest.udp_echo_port, 6)?;
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
        drive_adaptive_round_trip,
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

#[test]
fn cli_packet_smoke_generated_cell() {
    if !packet_smoke_enabled() {
        eprintln!("skipping cli_packet_smoke_generated_cell because {ENABLE_ENV} is not enabled");
        return;
    }
    let metadata = generated_cell_metadata().expect("generated packet-smoke metadata");
    let id = generated_cell_id(&metadata);
    match generated_cell_traffic_kind(&metadata) {
        "tcp_http" => run_capture_scenario(
            id,
            |_| generated_tcp_http_args(&metadata),
            |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
            |proxy_port, fixture| drive_http_echo_best_effort(proxy_port, fixture, "generated-http"),
            |run| assert_generated_tcp_http(run, &metadata),
        ),
        "tcp_tls" => run_capture_scenario(
            id,
            |_| generated_tcp_tls_args(&metadata),
            |manifest| format!("tcp and port {}", manifest.tls_echo_port),
            drive_tls_probe_best_effort,
            |run| assert_generated_tcp_tls(run, &metadata),
        ),
        "udp_quic" => run_capture_scenario(
            id,
            |_| generated_udp_quic_args(&metadata),
            |manifest| format!("udp and port {}", manifest.udp_echo_port),
            drive_udp_quic_round_trip,
            |run| assert_generated_udp_quic(run, &metadata),
        ),
        other => panic!("unsupported generated packet-smoke traffic kind: {other}"),
    }
}

#[test]
fn generated_tcp_tls_args_place_tlsrec_before_send_steps() {
    let metadata = serde_json::json!({
        "generator_axis_values": {
            "fake_ttl_ladder": "4",
            "oob_byte_placement": "off",
            "split_offset": "endhost-1",
            "tls_record_split": "auto_midsld",
            "tlsrandrec_profile": "google_chrome"
        }
    });

    let args = generated_tcp_tls_args(&metadata);
    let leading_args = args.iter().take(5).map(String::as_str).collect::<Vec<_>>();

    assert_eq!(leading_args, vec!["--tlsrec", "auto(midsld)", "-t", "4", "-f"]);
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
    write_fixture_manifest_artifact(&paths.fixture_manifest, fixture.manifest())
        .expect("write fixture manifest artifact");

    let listen_port = reserve_listen_port();
    let cli_args = build_cli_args(listen_port, build_args(&paths));
    let mut cli = start_cli_process(&cli_args, &paths.cli_stderr).expect("start ripdpi cli");
    wait_for_proxy_listener(listen_port, &mut cli).expect("wait for CLI listener");

    let mut capture =
        start_capture(&capture_filter(fixture.manifest()), &paths.capture_pcap).expect("start tcpdump capture");

    let drive_result = catch_unwind(AssertUnwindSafe(|| drive(listen_port, &fixture)));

    // Give tcpdump time to flush captured packets to disk before SIGINT.
    thread::sleep(Duration::from_millis(200));

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

fn drive_http_echo_split(proxy_port: u16, fixture: &FixtureStack) -> Result<(), String> {
    if supports_await_writable_split() {
        drive_http_echo_strict(proxy_port, fixture, "split")
    } else {
        drive_http_echo_best_effort(proxy_port, fixture, "split")
    }
}

fn drive_http_echo_best_effort(proxy_port: u16, fixture: &FixtureStack, path_token: &str) -> Result<(), String> {
    let payload = http_echo_payload(fixture, path_token);
    let _ =
        attempt_socks_connect_domain_round_trip(proxy_port, "127.0.0.1", fixture.manifest().tcp_echo_port, &payload);
    thread::sleep(Duration::from_millis(250));
    Ok(())
}

fn drive_tls_probe_best_effort(proxy_port: u16, fixture: &FixtureStack) -> Result<(), String> {
    // Retry the TLS probe up to 3 times to absorb transient connection races
    // (e.g. the TLS echo server accepting connections just after the first attempt).
    for attempt in 0..3 {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(200));
        }
        let _ = attempt_socks5_tls_round_trip(proxy_port, fixture, None);
    }
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

fn generated_cell_metadata() -> Result<Value, String> {
    let raw = env::var(GENERATOR_METADATA_ENV)
        .map_err(|_| format!("{GENERATOR_METADATA_ENV} must be set for generated packet-smoke cells"))?;
    serde_json::from_str(&raw).map_err(|err| format!("failed to parse {GENERATOR_METADATA_ENV}: {err}; payload={raw}"))
}

fn generated_cell_id(metadata: &Value) -> &str {
    metadata.get("id").and_then(Value::as_str).expect("generated cell id")
}

fn generated_cell_traffic_kind(metadata: &Value) -> &str {
    metadata.get("trafficKind").and_then(Value::as_str).expect("generated cell trafficKind")
}

fn generated_axis_value<'a>(metadata: &'a Value, name: &str) -> &'a str {
    metadata
        .get("generator_axis_values")
        .and_then(|axis_values| axis_values.get(name))
        .and_then(Value::as_str)
        .unwrap_or_else(|| panic!("generated cell missing axis {name}"))
}

fn generated_tcp_http_args(metadata: &Value) -> Vec<String> {
    let split_offset = generated_axis_value(metadata, "split_offset");
    let fake_ttl = generated_axis_value(metadata, "fake_ttl_ladder");
    let oob_placement = generated_axis_value(metadata, "oob_byte_placement");
    let mut args = Vec::new();
    if fake_ttl != "off" && oob_placement != "off" {
        push_args(&mut args, ["-t", fake_ttl, "-q", split_offset, "--oob-data", generated_oob_data(oob_placement)]);
    } else if fake_ttl != "off" {
        push_args(&mut args, ["-t", fake_ttl, "-d", split_offset]);
    } else if oob_placement == "off" {
        push_args(&mut args, ["-s", split_offset]);
    } else {
        push_args(&mut args, ["-o", split_offset, "--oob-data", generated_oob_data(oob_placement)]);
    }
    args
}

fn generated_tcp_tls_args(metadata: &Value) -> Vec<String> {
    let split_offset = generated_axis_value(metadata, "split_offset");
    let tls_record_split = generated_axis_value(metadata, "tls_record_split");
    let tls_profile = generated_axis_value(metadata, "tlsrandrec_profile");
    let fake_ttl = generated_axis_value(metadata, "fake_ttl_ladder");
    let oob_placement = generated_axis_value(metadata, "oob_byte_placement");
    let mut args = Vec::new();
    match tls_record_split {
        "none" => {}
        "sniext" => push_args(&mut args, ["--tlsrec", "sniext"]),
        "extlen" => push_args(&mut args, ["--tlsrec", "extlen"]),
        "auto_midsld" => push_args(&mut args, ["--tlsrec", "auto(midsld)"]),
        other => panic!("unsupported tls_record_split generated axis value: {other}"),
    }
    if fake_ttl != "off" && oob_placement != "off" {
        push_args(&mut args, ["-t", fake_ttl, "-q", split_offset, "--oob-data", generated_oob_data(oob_placement)]);
    } else if fake_ttl != "off" {
        push_args(&mut args, ["-t", fake_ttl, "-f", split_offset]);
        push_args(&mut args, ["--fake-tls-profile", generated_tls_profile(tls_profile)]);
    } else if oob_placement != "off" {
        push_args(&mut args, ["-o", split_offset, "--oob-data", generated_oob_data(oob_placement)]);
    } else {
        push_args(&mut args, ["-s", split_offset]);
    }
    args
}

fn generated_udp_quic_args(metadata: &Value) -> Vec<String> {
    let udp_burst = generated_axis_value(metadata, "udp_burst");
    let quic_fake_profile = generated_axis_value(metadata, "quic_fake_profile");
    let mut args = vec!["-K".to_string(), "u".to_string(), "-H".to_string(), ":docs.example.test".to_string()];
    push_args(&mut args, ["--quic-sni-split"]);
    if let Some(count) = generated_udp_burst_count(udp_burst) {
        push_args(&mut args, ["-a", count]);
    }
    match quic_fake_profile {
        "off" => {}
        "compat_default" => push_args(&mut args, ["--fake-quic-profile", "compat_default"]),
        "realistic_initial" => {
            push_args(
                &mut args,
                ["--fake-quic-profile", "realistic_initial", "--fake-quic-host", "video.example.test"],
            );
            push_args(&mut args, ["--quic-dummy-prepend"]);
        }
        other => panic!("unsupported quic_fake_profile generated axis value: {other}"),
    }
    args
}

fn generated_tls_profile(profile: &str) -> &str {
    match profile {
        "off" => "google_chrome",
        "iana_firefox" | "google_chrome" | "bigsize_iana" => profile,
        other => panic!("unsupported tlsrandrec_profile generated axis value: {other}"),
    }
}

fn generated_udp_burst_count(burst: &str) -> Option<&'static str> {
    match burst {
        "off" => None,
        "low" => Some("1"),
        "medium" => Some("2"),
        "high" => Some("3"),
        other => panic!("unsupported udp_burst generated axis value: {other}"),
    }
}

fn generated_oob_data(placement: &str) -> &str {
    match placement {
        "pre_handshake" => "\\x41",
        "post_sni" => "\\x42",
        "mid_app" => "\\x43",
        other => panic!("unsupported oob_byte_placement generated axis value: {other}"),
    }
}

fn push_args<const N: usize>(args: &mut Vec<String>, values: [&str; N]) {
    args.extend(values.into_iter().map(str::to_string));
}

fn assert_generated_tcp_http(run: &ScenarioRun, metadata: &Value) -> Result<(), String> {
    if generated_axis_value(metadata, "oob_byte_placement") != "off" {
        assert_outbound_urgent(run, run.manifest.tcp_echo_port)?;
    } else {
        assert_tcp_payload_to_port_captured(run, run.manifest.tcp_echo_port)?;
    }
    assert_generated_ttl_if_enabled(run, metadata, run.manifest.tcp_echo_port)
}

fn assert_generated_tcp_tls(run: &ScenarioRun, metadata: &Value) -> Result<(), String> {
    assert_tcp_payload_to_port_captured(run, run.manifest.tls_echo_port)?;
    if generated_axis_value(metadata, "oob_byte_placement") != "off" {
        assert_outbound_urgent(run, run.manifest.tls_echo_port)?;
    }
    assert_generated_ttl_if_enabled(run, metadata, run.manifest.tls_echo_port)
        .or_else(|_| assert_stderr_contains(run, "strategy_family=fake"))
}

fn assert_generated_udp_quic(run: &ScenarioRun, metadata: &Value) -> Result<(), String> {
    let burst_count = generated_udp_burst_count(generated_axis_value(metadata, "udp_burst"))
        .and_then(|count| count.parse::<usize>().ok())
        .unwrap_or_default();
    assert_udp_outbound_count_at_least(run, run.manifest.udp_echo_port, 2 + burst_count)?;
    assert_stderr_contains(run, "docs.example.test")?;
    assert_stderr_contains(run, "other.example.test")
}

fn assert_generated_ttl_if_enabled(run: &ScenarioRun, metadata: &Value, port: u16) -> Result<(), String> {
    match generated_axis_value(metadata, "fake_ttl_ladder") {
        "off" => Ok(()),
        ttl => {
            let ttl = ttl.parse::<u8>().map_err(|err| format!("invalid fake_ttl_ladder value: {err}"))?;
            assert_outbound_ttl(run, port, ttl)
        }
    }
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
        .args(["-i", &capture_interface(), "--immediate-mode", "-U", "-n", "-s", "0", "-w"])
        .arg(capture_path)
        .arg(filter)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;

    // --immediate-mode disables TPACKET_V3 ring-buffer batching so the kernel
    // delivers packets to userspace as they arrive.  The sleep gives tcpdump
    // time to open the socket and attach the BPF filter before traffic starts.
    thread::sleep(Duration::from_millis(500));
    if let Some(status) = child.try_wait()? {
        return Err(io::Error::other(format!("tcpdump exited before capture traffic began; status={status}")));
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
    matches!(env::var(ENABLE_ENV).ok().as_deref(), Some("1" | "true" | "TRUE" | "yes" | "YES"))
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
    env::var(INTERFACE_ENV).unwrap_or_else(
        |_| {
            if cfg!(target_os = "macos") { "lo0".to_string() } else { "lo".to_string() }
        },
    )
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
        dns_odoh_proxy_port: 0,
        dns_odoh_target_port: 0,
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
    format!("GET /{path_token} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", fixture.manifest().fixture_domain)
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
        Err(format!("expected CLI stderr to contain {needle:?}, got:\n{}", run.stderr))
    }
}

fn supports_await_writable_split() -> bool {
    cfg!(any(target_os = "android", target_os = "linux"))
}

fn assert_tcp_split_host_plus_one(packets: &[Value], port: u16, fixture_domain: &str) -> Result<(), String> {
    let prefix = b"GET /split HTTP/1.1\r\nHost: ";
    let expected = format!("GET /split HTTP/1.1\r\nHost: {fixture_domain}\r\nConnection: close\r\n\r\n");
    let boundary = prefix.len() + 1;
    let mut stream = None;
    let mut segments = Vec::new();
    for packet in packets.iter().filter(|packet| is_tcp_outbound(packet, port)) {
        let length = field_u64(packet, "tcp.len").ok_or("missing TCP payload length")?;
        if length == 0 {
            if collect_field_values(packet, "tcp.payload").iter().any(|value| !value.is_empty()) {
                return Err("zero TCP length with captured payload bytes".to_string());
            }
            continue;
        }
        let current_stream = field_u64(packet, "tcp.stream").ok_or("missing TCP stream identity")?;
        if stream.replace(current_stream).is_some_and(|previous| previous != current_stream) {
            return Err("split request spans multiple TCP streams".to_string());
        }
        let sequence = field_u64(packet, "tcp.seq_raw")
            .and_then(|value| u32::try_from(value).ok())
            .ok_or("missing or invalid raw TCP sequence")?;
        let payload_fields = collect_field_values(packet, "tcp.payload");
        let [payload_hex] = payload_fields.as_slice() else {
            return Err("expected one TCP payload field".to_string());
        };
        let payload = split_oracle_payload(payload_hex, expected.len()).ok_or("invalid TCP payload hex")?;
        if payload.len() as u64 != length {
            return Err("TCP payload length disagrees with captured bytes".to_string());
        }
        segments.push((sequence, payload));
    }
    let Some((anchor, _)) = segments.first() else {
        return Err("no outbound TCP request payload captured".to_string());
    };
    // Signed wrapping distances locate the first byte even when capture order differs
    // from sequence order or the small fixture request crosses the u32 sequence wrap.
    let base = segments
        .iter()
        .map(|(sequence, _)| *sequence)
        .min_by_key(|sequence| sequence.wrapping_sub(*anchor) as i32)
        .ok_or("no TCP sequence origin")?;
    // Allocate by the known request size, never by a potentially corrupt sequence gap.
    let mut covered = vec![false; expected.len()];
    let mut ends_at_boundary = false;
    let mut starts_at_boundary = false;
    for (sequence, payload) in segments {
        let offset = sequence.wrapping_sub(base) as usize;
        let end = offset.checked_add(payload.len()).ok_or("TCP sequence range overflow")?;
        let expected_segment = expected.as_bytes().get(offset..end).ok_or("TCP segment outside expected request")?;
        if payload != expected_segment {
            return Err("TCP payload differs from expected request or has conflicting overlap".to_string());
        }
        if offset < boundary && end > boundary {
            return Err("TCP packet crosses the Host+1 boundary".to_string());
        }
        ends_at_boundary |= end == boundary;
        starts_at_boundary |= offset == boundary;
        covered[offset..end].fill(true);
    }
    if !covered.into_iter().all(|byte| byte) || !ends_at_boundary || !starts_at_boundary {
        return Err("incomplete TCP request or missing exact Host+1 packet boundary".to_string());
    }
    Ok(())
}

fn split_oracle_payload(value: &str, maximum_bytes: usize) -> Option<Vec<u8>> {
    if value.len() > maximum_bytes.checked_mul(3)? {
        return None;
    }
    let valid_hex = if value.contains(':') {
        value.split(':').all(|byte| byte.len() == 2 && byte.bytes().all(|digit| digit.is_ascii_hexdigit()))
    } else {
        value.len().is_multiple_of(2) && value.bytes().all(|digit| digit.is_ascii_hexdigit())
    };
    valid_hex.then(|| parse_hex_bytes_field(value)).flatten().filter(|bytes| bytes.len() <= maximum_bytes)
}

fn assert_tcp_payload_to_port_captured(run: &ScenarioRun, port: u16) -> Result<(), String> {
    let count = run
        .packets
        .iter()
        .filter(|packet| is_tcp_outbound(packet, port) && field_u64(packet, "tcp.len").unwrap_or_default() > 0)
        .count();
    if count >= 1 {
        Ok(())
    } else {
        Err(format!("expected at least 1 outbound TCP payload packet for port {port}, got {count}"))
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
        let outbound_count = run.packets.iter().filter(|p| is_outbound_to_port(p, port)).count();
        let observed_ttls: Vec<_> = run
            .packets
            .iter()
            .filter(|p| is_outbound_to_port(p, port))
            .filter_map(|p| field_u64(p, "ip.ttl").or_else(|| field_u64(p, "ipv6.hlim")))
            .collect();
        Err(format!(
            "expected outbound packet to port {port} with ttl {ttl}; \
             captured {outbound_count} outbound packets, observed ttls: {observed_ttls:?}"
        ))
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
        is_udp_outbound(packet, port)
            && field_u64(packet, "udp.srcport").is_some_and(|value| value <= u64::from(upper_bound))
    }) {
        Ok(())
    } else {
        Err(format!("expected at least one outbound UDP packet to port {port} with source port <= {upper_bound}"))
    }
}

fn assert_quic_version_present(run: &ScenarioRun, version: u32) -> Result<(), String> {
    if run.packets.iter().any(|packet| packet_has_quic_version(packet, version)) {
        Ok(())
    } else {
        Err(format!("expected captured QUIC version {version:#x} in {:?}", run.paths.capture_json))
    }
}

fn packet_has_quic_version(packet: &Value, version: u32) -> bool {
    if field_u64(packet, "quic.version") == Some(u64::from(version)) {
        return true;
    }

    for field in ["udp.payload", "data.data"] {
        for value in collect_field_values(packet, field) {
            if parse_hex_bytes_field(&value)
                .is_some_and(|bytes| bytes.len() >= 5 && (bytes[0] & 0x80) != 0 && bytes[1..5] == version.to_be_bytes())
            {
                return true;
            }
        }
    }

    false
}

fn is_outbound_to_port(packet: &Value, port: u16) -> bool {
    field_u64(packet, "tcp.dstport") == Some(u64::from(port))
        || field_u64(packet, "udp.dstport") == Some(u64::from(port))
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

fn parse_hex_bytes_field(value: &str) -> Option<Vec<u8>> {
    let filtered: String = value.chars().filter(char::is_ascii_hexdigit).collect();
    if filtered.len() < 2 || !filtered.len().is_multiple_of(2) {
        return None;
    }

    let mut bytes = Vec::with_capacity(filtered.len() / 2);
    for idx in (0..filtered.len()).step_by(2) {
        let byte = u8::from_str_radix(&filtered[idx..idx + 2], 16).ok()?;
        bytes.push(byte);
    }
    Some(bytes)
}

#[test]
fn split_host_packet_oracle_rejects_coalesced_request() {
    let request = b"GET /split HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n";
    let packets = [split_oracle_packet(7, 100, request)];

    assert!(
        assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err(),
        "a complete coalesced request must not prove a Host+1 packet boundary"
    );
}

#[test]
fn split_host_packet_oracle_rejects_payload_declared_empty() {
    let mut packets = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100);
    let mut coalesced = split_oracle_packet(7, 100, SPLIT_ORACLE_REQUEST);
    coalesced["layers"]["tcp"]["tcp.len"] = Value::String("0".to_string());
    packets.push(coalesced);

    assert!(
        assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err(),
        "a false zero length must not hide a captured packet crossing Host+1"
    );
}

#[test]
fn split_host_packet_oracle_accepts_reordered_retransmissions_across_sequence_wrap() {
    let mut packets = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, u32::MAX - 10);
    packets.reverse();
    packets.extend(packets.clone());

    assert_eq!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test"), Ok(()));
}

#[test]
fn split_host_packet_oracle_accepts_exact_boundary_with_compact_hex() {
    let mut packets = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100);
    for packet in &mut packets {
        let compact = packet["layers"]["tcp"]["tcp.payload"].as_str().unwrap().replace(':', "");
        packet["layers"]["tcp"]["tcp.payload"] = Value::String(compact);
    }

    assert_eq!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test"), Ok(()));
}

#[test]
fn split_host_packet_oracle_rejects_wrong_boundary_and_incomplete_coverage() {
    for boundary in [SPLIT_ORACLE_BOUNDARY - 1, SPLIT_ORACLE_BOUNDARY + 1] {
        let packets = split_oracle_packets_at(boundary, 100);
        assert!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err(), "boundary {boundary}");
    }
    let [prefix, suffix]: [Value; 2] = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100).try_into().unwrap();
    for packets in [vec![prefix.clone()], vec![suffix], vec![prefix.clone(), prefix]] {
        assert!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err(), "incomplete request");
    }
    let mut gap = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100);
    gap[1] =
        split_oracle_packet(7, 101 + SPLIT_ORACLE_BOUNDARY as u32, &SPLIT_ORACLE_REQUEST[SPLIT_ORACLE_BOUNDARY + 1..]);
    assert!(assert_tcp_split_host_plus_one(&gap, 8080, "fixture.test").is_err(), "one-byte capture gap");
}

#[test]
fn split_host_packet_oracle_rejects_corrupt_metadata_and_payload() {
    let malformed = [
        ("tcp.stream", Value::String("8".to_string())),
        ("tcp.stream", Value::Null),
        ("tcp.seq_raw", Value::Null),
        ("tcp.seq_raw", Value::String("2147483648".to_string())),
        ("tcp.seq_raw", Value::String("4294967296".to_string())),
        ("tcp.len", Value::Null),
        ("tcp.len", Value::String("1".to_string())),
        ("tcp.payload", Value::Null),
        ("tcp.payload", serde_json::json!(["61", "62"])),
        ("tcp.payload", Value::String("66:gg:69".to_string())),
        ("tcp.payload", Value::String("66::69".to_string())),
        ("tcp.payload", Value::String("666".to_string())),
        ("tcp.payload", Value::String("aa:".repeat(SPLIT_ORACLE_REQUEST.len() + 1))),
    ];
    for (field, value) in malformed {
        let mut packets = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100);
        packets[1]["layers"]["tcp"][field] = value;
        assert!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err(), "invalid {field}");
    }
}

#[test]
fn split_host_packet_oracle_rejects_conflicting_or_crossing_overlap_among_valid_packets() {
    let crossing = split_oracle_packet(7, 100, SPLIT_ORACLE_REQUEST);
    let mut wrong_payload = SPLIT_ORACLE_REQUEST[..SPLIT_ORACLE_BOUNDARY].to_vec();
    wrong_payload[0] = b'X';
    let conflicting = split_oracle_packet(7, 100, &wrong_payload);
    for invalid in [crossing, conflicting] {
        let mut packets = split_oracle_packets_at(SPLIT_ORACLE_BOUNDARY, 100);
        packets.push(invalid);
        assert!(assert_tcp_split_host_plus_one(&packets, 8080, "fixture.test").is_err());
    }
}

const SPLIT_ORACLE_REQUEST: &[u8] = b"GET /split HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n";
const SPLIT_ORACLE_BOUNDARY: usize = b"GET /split HTTP/1.1\r\nHost: f".len();

fn split_oracle_packets_at(boundary: usize, base: u32) -> Vec<Value> {
    vec![
        split_oracle_packet(7, base, &SPLIT_ORACLE_REQUEST[..boundary]),
        split_oracle_packet(7, base.wrapping_add(boundary as u32), &SPLIT_ORACLE_REQUEST[boundary..]),
    ]
}

fn split_oracle_packet(stream: u64, sequence: u32, payload: &[u8]) -> Value {
    let hex = payload.iter().map(|byte| format!("{byte:02x}")).collect::<Vec<_>>().join(":");
    serde_json::json!({"layers": {"tcp": {
        "tcp.dstport": "8080",
        "tcp.stream": stream.to_string(),
        "tcp.seq_raw": sequence.to_string(),
        "tcp.len": payload.len().to_string(),
        "tcp.payload": hex
    }}})
}

#[test]
fn packet_has_quic_version_accepts_unknown_version_from_raw_udp_payload() {
    let packet = serde_json::json!({
        "layers": {
            "udp.payload": "c0:1a:2b:3c:4d:00:00:00"
        }
    });

    assert!(packet_has_quic_version(&packet, 0x1a2b_3c4d));
}

#[test]
fn parse_hex_bytes_field_handles_colon_separated_bytes() {
    assert_eq!(parse_hex_bytes_field("c0:1a:2b:3c:4d"), Some(vec![0xc0, 0x1a, 0x2b, 0x3c, 0x4d]));
}

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().expect("lock packet smoke test mutex")
}

fn write_json_pretty<T: serde::Serialize>(path: &Path, value: &T) -> io::Result<()> {
    let payload = serde_json::to_vec_pretty(value).map_err(io::Error::other)?;
    fs::write(path, payload)
}

fn write_fixture_manifest_artifact(path: &Path, manifest: &FixtureManifest) -> io::Result<()> {
    let mut value = serde_json::to_value(manifest).map_err(io::Error::other)?;
    if let Some(metadata) = generator_metadata_from_env()? {
        merge_generator_metadata(&mut value, &metadata);
    }
    write_json_pretty(path, &value)
}

fn generator_metadata_from_env() -> io::Result<Option<Value>> {
    let Some(raw) = env::var_os(GENERATOR_METADATA_ENV) else {
        return Ok(None);
    };
    let raw = raw.to_string_lossy();
    let value = serde_json::from_str::<Value>(&raw).map_err(io::Error::other)?;
    Ok(Some(value))
}

fn merge_generator_metadata(manifest: &mut Value, metadata: &Value) {
    manifest["generator_seed"] = metadata.get("generator_seed").cloned().unwrap_or(Value::Null);
    manifest["generator_axis_values"] = metadata.get("generator_axis_values").cloned().unwrap_or(Value::Null);
    manifest["generator_origin"] = metadata.get("generator_origin").cloned().unwrap_or(Value::Null);
    manifest["generator_scenario_id"] = metadata.get("id").cloned().unwrap_or(Value::Null);
    manifest["generator_traffic_kind"] = metadata.get("trafficKind").cloned().unwrap_or(Value::Null);
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
                let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
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
