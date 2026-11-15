use super::{
    AssertUnwindSafe, FixtureStack, ScenarioPaths, Value, assert_fixture_event, assert_stderr_contains,
    assert_tcp_payload_to_port_captured, assert_tcp_request_split, build_cli_args, catch_unwind, collect_field_values,
    drive_http_echo_strict, dynamic_fixture_config, field_u64, fs, is_tcp_outbound, packet_smoke_enabled,
    reserve_listen_port, resume_unwind, run_capture_scenario, split_oracle_packet, split_oracle_payload,
    start_cli_process, stop_cli_process, supports_await_writable_split, test_guard, wait_for_proxy_listener,
};

pub(super) fn run_capture() {
    if packet_smoke_enabled() {
        assert!(supports_await_writable_split(), "adaptive Applied evidence requires Linux/Android split support");
    }
    run_capture_scenario(
        "cli_packet_smoke_adaptive_plain_fallback",
        |_| fallback_args(),
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        drive_round_trip,
        |run| {
            assert_fixture_event(run, "tcp_echo")?;
            assert_no_evolution_credit(&run.stderr)?;
            assert_tcp_payload_to_port_captured(run, run.manifest.tcp_echo_port)?;
            // The runner sets one artifact directory for this registered selector.
            // Keep the negative control before the positive case writes its artifacts.
            for path in [
                &run.paths.fixture_manifest,
                &run.paths.fixture_events,
                &run.paths.cli_stderr,
                &run.paths.capture_pcap,
                &run.paths.capture_json,
            ] {
                let name = path.file_name().ok_or("missing artifact filename")?.to_string_lossy();
                fs::rename(path, path.with_file_name(format!("plain-fallback-{name}")))
                    .map_err(|err| format!("failed to retain plain fallback evidence: {err}"))?;
            }
            Ok(())
        },
    );
    run_capture_scenario(
        "cli_packet_smoke_adaptive_family",
        |paths| {
            let mut args: Vec<String> = [
                "-K",
                "h",
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
            .collect();
            args.push(paths.host_autolearn_store.to_string_lossy().into_owned());
            args
        },
        |manifest| format!("tcp and port {}", manifest.tcp_echo_port),
        drive_round_trip,
        |run| {
            assert_fixture_event(run, "tcp_echo")?;
            assert_adaptive_request_splits(&run.packets, run.manifest.tcp_echo_port, &run.manifest.fixture_domain)?;
            assert_stderr_contains(run, "strategy evolution selected combo")?;
            let successes = run.stderr.matches("strategy evolution recorded success").count();
            if successes != 2 {
                return Err(format!(
                    "expected one evolution success for each of two fixture flows, got {successes}: {}",
                    run.stderr
                ));
            }
            assert_stderr_contains(run, "autolearn event")?;
            let store = fs::read_to_string(&run.paths.host_autolearn_store)
                .map_err(|err| format!("failed to read host autolearn store: {err}"))?;
            if !store.contains(&run.manifest.fixture_domain) {
                return Err(format!(
                    "expected host autolearn store to contain {}, got: {store}",
                    run.manifest.fixture_domain
                ));
            }
            Ok(())
        },
    );
}

fn fallback_args() -> Vec<String> {
    ["-K", "t", "-s", "auto(host)", "--strategy-evolution", "--evolution-epsilon", "1.0"]
        .into_iter()
        .map(str::to_string)
        .collect()
}

fn drive_round_trip(port: u16, fixture: &FixtureStack) -> Result<(), String> {
    for round in 0..2 {
        drive_http_echo_strict(port, fixture, &format!("adaptive-{round}"))?;
    }
    Ok(())
}

fn assert_no_evolution_credit(stderr: &str) -> Result<(), String> {
    let plain_forwarded = stderr.lines().any(|line| {
        line.contains("first outbound payload forwarded")
            && line.contains("strategy_family")
            && line.contains("\"plain\"")
    });
    if !stderr.contains("strategy evolution selected combo") || !plain_forwarded {
        return Err(format!("expected selected evolution attempt and plain fallback: {stderr}"));
    }
    if stderr.contains("strategy evolution recorded success") || stderr.contains("strategy evolution recorded failure")
    {
        return Err(format!("plain fallback must not receive evolution credit: {stderr}"));
    }
    Ok(())
}

fn assert_adaptive_request_splits(packets: &[Value], port: u16, host: &str) -> Result<(), String> {
    let mut streams = Vec::new();
    for round in 0..2 {
        let prefix = format!("GET /adaptive-{round} HTTP/1.1\r\nHost: ");
        let expected = format!("{prefix}{host}\r\nConnection: close\r\n\r\n");
        let stream = packets
            .iter()
            .filter(|packet| is_tcp_outbound(packet, port))
            .find(|packet| {
                collect_field_values(packet, "tcp.payload").iter().any(|payload| {
                    split_oracle_payload(payload, expected.len())
                        .is_some_and(|bytes| bytes.starts_with(prefix.as_bytes()))
                })
            })
            .and_then(|packet| field_u64(packet, "tcp.stream"))
            .ok_or_else(|| format!("missing captured adaptive-{round} request stream"))?;
        if streams.contains(&stream) {
            return Err("adaptive requests must use distinct TCP streams".to_string());
        }
        streams.push(stream);
        let flow: Vec<_> =
            packets.iter().filter(|packet| field_u64(packet, "tcp.stream") == Some(stream)).cloned().collect();
        // Check a wire property, not the planner's choice among adaptive markers.
        if !(prefix.len()..=prefix.len() + host.len())
            .any(|boundary| assert_tcp_request_split(&flow, port, &expected, boundary).is_ok())
        {
            return Err(format!("adaptive-{round} lacks an exact, complete request split in the Host region"));
        }
    }
    Ok(())
}

#[test]
fn plain_fallback_does_not_record_evolution_credit() {
    let _guard = test_guard();
    let paths = ScenarioPaths::new("plain-fallback-feedback").expect("create artifact directory");
    let fixture = FixtureStack::start(dynamic_fixture_config()).expect("start loopback fixture");
    let port = reserve_listen_port();
    let mut cli = start_cli_process(&build_cli_args(port, fallback_args()), &paths.cli_stderr).expect("start CLI");
    let result = catch_unwind(AssertUnwindSafe(|| {
        wait_for_proxy_listener(port, &mut cli).map_err(|err| err.to_string())?;
        drive_round_trip(port, &fixture)
    }));
    stop_cli_process(&mut cli).expect("stop CLI");
    match result {
        Ok(result) => result.expect("loopback HTTP echo through plain fallback"),
        Err(panic) => resume_unwind(panic),
    }
    assert!(fixture.events().snapshot().iter().any(|event| event.service == "tcp_echo"));
    let stderr = fs::read_to_string(&paths.cli_stderr).expect("read CLI stderr");
    assert_no_evolution_credit(&stderr).expect("plain fallback has no evolution credit");
}

#[test]
fn adaptive_oracle_requires_two_complete_split_flows() {
    let packets = adaptive_packets(true);
    assert_eq!(assert_adaptive_request_splits(&packets, 8080, "fixture.test"), Ok(()));
    assert!(assert_adaptive_request_splits(&adaptive_packets(false), 8080, "fixture.test").is_err());
    assert!(assert_adaptive_request_splits(&packets[..2], 8080, "fixture.test").is_err());
    let mut corrupt = packets.clone();
    corrupt[3]["layers"]["tcp"]["tcp.payload"] = Value::String("ff".to_string());
    assert!(assert_adaptive_request_splits(&corrupt, 8080, "fixture.test").is_err());
}

fn adaptive_packets(split: bool) -> Vec<Value> {
    (0..2)
        .flat_map(|round| {
            let prefix = format!("GET /adaptive-{round} HTTP/1.1\r\nHost: ");
            let request = format!("{prefix}fixture.test\r\nConnection: close\r\n\r\n");
            if split {
                vec![
                    split_oracle_packet(round, 100, &request.as_bytes()[..prefix.len()]),
                    split_oracle_packet(round, 100 + prefix.len() as u32, &request.as_bytes()[prefix.len()..]),
                ]
            } else {
                vec![split_oracle_packet(round, 100, request.as_bytes())]
            }
        })
        .collect()
}
