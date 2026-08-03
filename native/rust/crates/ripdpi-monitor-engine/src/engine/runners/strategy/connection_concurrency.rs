use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Barrier, Mutex, mpsc};
use std::time::{Duration, Instant};

use rustls::client::danger::ServerCertVerifier;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use ripdpi_monitor_adapter::failure::{
    ConnectionConcurrencyCell as ClassifierCell, ConnectionConcurrencyCellStatus as ClassifierStatus,
    ConnectionConcurrencyVerdict as ClassifierVerdict, classify_connection_concurrency_matrix,
};

use crate::types::{
    ConnectionConcurrencyAssessment, ConnectionConcurrencyCellStatus, ConnectionConcurrencyObservationFact,
    ConnectionConcurrencyVerdict, ObservationKind, ProbeDetail, ProbeObservation, ProbeResult,
};

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

const QUICK_LEVELS: &[u16] = &[1, 4];
const AUDIT_LEVELS: &[u16] = &[1, 2, 4, 8];
const MAX_LAUNCH_SPREAD_MS: u32 = 400;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const CELL_RESULT_DEADLINE: Duration = Duration::from_secs(6);

pub(in crate::engine::runners) struct StrategyConnectionConcurrencyRunner;

impl ExecutionStageRunner for StrategyConnectionConcurrencyRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyConnectionConcurrency
    }

    fn phase(&self) -> &'static str {
        "connection_concurrency"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        let levels = levels_for(plan);
        let post_checks = levels.iter().filter(|level| **level > 1).count();
        eligible_targets(plan).count() * ripdpi_tls_profiles::AVAILABLE_PROFILES.len() * (levels.len() + post_checks)
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let levels = levels_for(plan);
        let current_profile = current_profile(plan);
        let mut cells = Vec::new();
        for (target_index, target) in eligible_targets(plan).enumerate() {
            let metadata = target.concurrency_probe.as_ref().expect("eligibility checked");
            let address = match resolve_target(target) {
                Ok(address) => address,
                Err(error) => {
                    let skipped = skipped_target_cells(
                        target,
                        metadata.cohort_id.as_str(),
                        levels,
                        format!("target resolution failed: {error}"),
                    );
                    for cell in &skipped {
                        record_cell(plan, runtime, cell);
                    }
                    cells.extend(skipped);
                    continue;
                }
            };
            let profiles = rotated_profiles(plan.session_id.as_bytes(), target_index);
            let target_matrix = collect_target_matrix(
                target,
                metadata,
                levels,
                &profiles,
                || runtime.is_cancelled() || runtime.is_past_deadline(),
                |profile, level| {
                    execute_cell(target, &metadata.cohort_id, address, profile, level, runtime.scan_deadline())
                },
            );
            for cell in &target_matrix.cells {
                record_cell(plan, runtime, cell);
            }
            cells.extend(target_matrix.cells);
            if target_matrix.cancelled {
                finalize_assessment(runtime, &cells, &current_profile);
                return RunnerOutcome::Cancelled;
            }
        }
        finalize_assessment(runtime, &cells, &current_profile);
        RunnerOutcome::Completed
    }
}

struct TargetMatrixOutcome {
    cells: Vec<ClassifierCell>,
    cancelled: bool,
}

fn collect_target_matrix<ShouldStop, Execute>(
    target: &crate::types::DomainTarget,
    metadata: &crate::types::ConcurrencyProbeTargetMetadata,
    levels: &[u16],
    profiles: &[&str],
    mut should_stop: ShouldStop,
    mut execute: Execute,
) -> TargetMatrixOutcome
where
    ShouldStop: FnMut() -> bool,
    Execute: FnMut(&str, u16) -> ClassifierCell,
{
    let mut cells = Vec::new();
    let mut frozen = false;
    for (profile_index, &profile) in profiles.iter().enumerate() {
        for (level_index, &level) in levels.iter().enumerate() {
            if should_stop() {
                for (remaining_profile_index, &remaining_profile) in profiles.iter().enumerate().skip(profile_index) {
                    let first_level = if remaining_profile_index == profile_index { level_index } else { 0 };
                    cells.extend(levels.iter().skip(first_level).map(|remaining_level| {
                        skipped_cell(
                            target,
                            &metadata.cohort_id,
                            remaining_profile,
                            *remaining_level,
                            "scan cancelled before cell execution",
                        )
                    }));
                }
                return TargetMatrixOutcome { cells, cancelled: true };
            }
            let mut cell = if frozen {
                skipped_cell(target, &metadata.cohort_id, profile, level, "target frozen by failed post-check")
            } else if level > metadata.max_parallelism {
                skipped_cell(target, &metadata.cohort_id, profile, level, "catalog parallelism limit")
            } else {
                execute(profile, level)
            };
            if !frozen && level > 1 && !cell.contaminated && cell.status != ClassifierStatus::Skipped {
                let mut post_check = execute(profile, 1);
                if post_check.status != ClassifierStatus::Healthy {
                    frozen = true;
                    cell.contaminated = true;
                    cell.status = ClassifierStatus::Contaminated;
                    cell.skip_reason = Some("same-profile concurrency-1 post-check failed".to_string());
                    post_check.contaminated = true;
                    post_check.status = ClassifierStatus::Contaminated;
                    post_check.skip_reason = Some("post-check ran during target freeze".to_string());
                }
                cells.push(post_check);
            }
            cells.push(cell);
        }
    }
    TargetMatrixOutcome { cells, cancelled: false }
}

fn levels_for(plan: &ExecutionPlan) -> &'static [u16] {
    match plan.request.strategy_probe.as_ref().map(|request| request.suite_id.as_str()) {
        Some("full_matrix_v1") => AUDIT_LEVELS,
        _ => QUICK_LEVELS,
    }
}

fn current_profile(plan: &ExecutionPlan) -> String {
    plan.request
        .strategy_probe
        .as_ref()
        .and_then(|request| request.base_proxy_config_json.as_deref())
        .and_then(|config| ripdpi_monitor_adapter::proxy_config::parse_proxy_config_json(config).ok())
        .and_then(|payload| {
            if let ripdpi_monitor_adapter::proxy_config::ProxyConfigPayload::Ui { config, .. } = payload {
                Some(config.fake_packets.tls_fingerprint_profile)
            } else {
                None
            }
        })
        .and_then(|profile| ripdpi_tls_profiles::AVAILABLE_PROFILES.contains(&profile.as_str()).then_some(profile))
        .unwrap_or_else(|| "chrome_stable".to_string())
}

fn eligible_targets(plan: &ExecutionPlan) -> impl Iterator<Item = &crate::types::DomainTarget> {
    plan.request.domain_targets.iter().filter(|target| target.concurrency_probe.is_some())
}

fn resolve_target(target: &crate::types::DomainTarget) -> io::Result<SocketAddr> {
    let port = target.https_port.unwrap_or(443);
    let host = target.connect_ip.as_deref().unwrap_or(target.host.as_str());
    (host, port).to_socket_addrs()?.next().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no target address"))
}

fn rotated_profiles(seed: &[u8], target_index: usize) -> Vec<&'static str> {
    let profiles = ripdpi_tls_profiles::AVAILABLE_PROFILES;
    let offset = (seed.iter().fold(0usize, |value, byte| value.wrapping_add(usize::from(*byte))) + target_index)
        % profiles.len();
    profiles.iter().cycle().skip(offset).take(profiles.len()).copied().collect()
}

fn execute_cell(
    target: &crate::types::DomainTarget,
    cohort_id: &str,
    address: SocketAddr,
    profile: &str,
    parallelism: u16,
    deadline: Option<Instant>,
) -> ClassifierCell {
    execute_cell_with(target, cohort_id, address, profile, parallelism, deadline, |host, address, profile| {
        let timeout =
            ripdpi_diagnostics_contracts::util::bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(str::to_string)?;
        let connector = ripdpi_tls_profiles::build_connector(profile, true).map_err(|error| error.to_string())?;
        let stream = protected_tcp_connect(address, timeout).map_err(|error| error.to_string())?;
        stream.set_read_timeout(Some(timeout)).map_err(|error| error.to_string())?;
        stream.set_write_timeout(Some(timeout)).map_err(|error| error.to_string())?;
        connector.connect(host, stream).map_err(|error| error.to_string())
    })
}

fn protected_tcp_connect(address: SocketAddr, timeout: Duration) -> io::Result<TcpStream> {
    let domain = if address.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    ripdpi_runtime_platform::vpn::protect_diagnostics_socket(&socket, None)?;
    socket.connect_timeout(&SockAddr::from(address), timeout)?;
    Ok(socket.into())
}

fn execute_cell_with<F, T>(
    target: &crate::types::DomainTarget,
    cohort_id: &str,
    address: SocketAddr,
    profile: &str,
    parallelism: u16,
    deadline: Option<Instant>,
    connect: F,
) -> ClassifierCell
where
    F: Fn(&str, SocketAddr, &str) -> Result<T, String> + Sync,
{
    let start_barrier = Arc::new(Barrier::new(usize::from(parallelism)));
    let launch_times = Arc::new(Mutex::new(Vec::with_capacity(usize::from(parallelism))));
    let active = Arc::new(AtomicU16::new(0));
    let peak = Arc::new(AtomicU16::new(0));
    let started = Instant::now();
    let outcomes: Vec<Result<(), String>> = std::thread::scope(|scope| {
        let (outcome_tx, outcome_rx) = mpsc::channel();
        let mut handles = Vec::with_capacity(usize::from(parallelism));
        let mut release_txs = Vec::with_capacity(usize::from(parallelism));
        for index in 0..parallelism {
            let start_barrier = Arc::clone(&start_barrier);
            let launch_times = Arc::clone(&launch_times);
            let active = Arc::clone(&active);
            let peak = Arc::clone(&peak);
            let outcome_tx = outcome_tx.clone();
            let (release_tx, release_rx) = mpsc::sync_channel(1);
            release_txs.push(release_tx);
            let connect = &connect;
            handles.push(scope.spawn(move || {
                ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
                    start_barrier.wait();
                    launch_times.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(Instant::now());
                    match catch_unwind(AssertUnwindSafe(|| connect(target.host.as_str(), address, profile))) {
                        Ok(Ok(stream)) => {
                            let now_active = active.fetch_add(1, Ordering::AcqRel) + 1;
                            peak.fetch_max(now_active, Ordering::AcqRel);
                            let _ = outcome_tx.send((usize::from(index), Ok(())));
                            let _ = release_rx.recv_timeout(CELL_RESULT_DEADLINE);
                            drop(stream);
                            active.fetch_sub(1, Ordering::AcqRel);
                        }
                        Ok(Err(error)) => {
                            let signal = classify_error(&error).to_string();
                            let _ = outcome_tx.send((usize::from(index), Err(signal)));
                        }
                        Err(_) => {
                            let _ = outcome_tx.send((usize::from(index), Err("connection_freeze".to_string())));
                        }
                    }
                });
            }));
        }
        drop(outcome_tx);

        let deadline = Instant::now() + CELL_RESULT_DEADLINE;
        let mut outcomes: Vec<Option<Result<(), String>>> = (0..parallelism).map(|_| None).collect();
        let mut received = 0usize;
        while received < usize::from(parallelism) {
            let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                break;
            };
            match outcome_rx.recv_timeout(remaining) {
                Ok((index, outcome)) if outcomes.get(index).is_some_and(Option::is_none) => {
                    outcomes[index] = Some(outcome);
                    received += 1;
                }
                Ok(_) => {}
                Err(mpsc::RecvTimeoutError::Timeout | mpsc::RecvTimeoutError::Disconnected) => break,
            }
        }
        for release in release_txs {
            let _ = release.send(());
        }
        for (index, handle) in handles.into_iter().enumerate() {
            if handle.join().is_err() {
                outcomes[index] = Some(Err("connection_freeze".to_string()));
            }
        }
        outcomes
            .into_iter()
            .map(|outcome| outcome.unwrap_or_else(|| Err("connection_freeze".to_string())))
            .collect::<Vec<_>>()
    });
    let times = launch_times.lock().expect("launch times lock");
    let launch_spread_ms =
        times.iter().min().zip(times.iter().max()).map_or(0, |(first, last)| duration_ms(last.duration_since(*first)));
    let success_count = outcomes.iter().filter(|outcome| outcome.is_ok()).count() as u16;
    let failure_count = parallelism.saturating_sub(success_count);
    let mut block_signals = outcomes.into_iter().filter_map(Result::err).collect::<Vec<_>>();
    block_signals.sort();
    block_signals.dedup();
    let status = classify_status(parallelism, success_count, !block_signals.is_empty(), launch_spread_ms);
    ClassifierCell {
        target_cohort: cohort_id.to_string(),
        target_id: target.host.clone(),
        tls_profile_id: profile.to_string(),
        requested_parallelism: parallelism,
        observed_peak_parallelism: peak.load(Ordering::Acquire),
        launch_spread_ms,
        burst_window_ms: duration_ms(started.elapsed()),
        success_count,
        failure_count,
        block_signals,
        status,
        contaminated: launch_spread_ms > MAX_LAUNCH_SPREAD_MS,
        skip_reason: (launch_spread_ms > MAX_LAUNCH_SPREAD_MS).then(|| "launch spread exceeded 400 ms".to_string()),
    }
}

fn duration_ms(duration: Duration) -> u32 {
    u32::try_from(duration.as_millis()).unwrap_or(u32::MAX)
}

fn classify_status(parallelism: u16, successes: u16, has_signal: bool, spread_ms: u32) -> ClassifierStatus {
    if spread_ms > MAX_LAUNCH_SPREAD_MS {
        ClassifierStatus::Contaminated
    } else if successes.saturating_mul(4) >= parallelism.saturating_mul(3) {
        ClassifierStatus::Healthy
    } else if successes.saturating_mul(4) <= parallelism && has_signal {
        ClassifierStatus::Blocked
    } else {
        ClassifierStatus::Mixed
    }
}

fn classify_error(error: &str) -> &'static str {
    let error = error.to_ascii_lowercase();
    if error.contains("timed out") || error.contains("timeout") {
        "silent_drop"
    } else if error.contains("reset") || error.contains("broken pipe") {
        "tcp_reset"
    } else if error.contains("alert") || error.contains("handshake") || error.contains("ssl") {
        "tls_alert"
    } else {
        "connection_freeze"
    }
}

fn skipped_cell(
    target: &crate::types::DomainTarget,
    cohort_id: &str,
    profile: &str,
    parallelism: u16,
    reason: &str,
) -> ClassifierCell {
    ClassifierCell {
        target_cohort: cohort_id.to_string(),
        target_id: target.host.clone(),
        tls_profile_id: profile.to_string(),
        requested_parallelism: parallelism,
        observed_peak_parallelism: 0,
        launch_spread_ms: 0,
        burst_window_ms: 0,
        success_count: 0,
        failure_count: 0,
        block_signals: Vec::new(),
        status: ClassifierStatus::Skipped,
        contaminated: false,
        skip_reason: Some(reason.to_string()),
    }
}

fn skipped_target_cells(
    target: &crate::types::DomainTarget,
    cohort_id: &str,
    levels: &[u16],
    reason: String,
) -> Vec<ClassifierCell> {
    ripdpi_tls_profiles::AVAILABLE_PROFILES
        .iter()
        .flat_map(|profile| levels.iter().map(|level| skipped_cell(target, cohort_id, profile, *level, &reason)))
        .collect()
}

fn record_cell(plan: &ExecutionPlan, runtime: &mut ExecutionRuntime, cell: &ClassifierCell) {
    let status = contract_status(cell.status);
    let fact = ConnectionConcurrencyObservationFact {
        cohort_id: cell.target_cohort.clone(),
        tls_profile_id: cell.tls_profile_id.clone(),
        requested_parallelism: cell.requested_parallelism,
        observed_peak_parallelism: cell.observed_peak_parallelism,
        launch_spread_ms: cell.launch_spread_ms,
        burst_window_ms: cell.burst_window_ms,
        successes: cell.success_count,
        failures: cell.failure_count,
        block_signals: cell.block_signals.clone(),
        status,
        contaminated: cell.contaminated,
        skip_reason: cell.skip_reason.clone(),
    };
    let outcome = format!("{status:?}").to_ascii_lowercase();
    let observation = ProbeObservation {
        kind: ObservationKind::ConnectionConcurrency,
        target: cell.target_id.clone(),
        dns: None,
        domain: None,
        tcp: None,
        quic: None,
        service: None,
        circumvention: None,
        telegram: None,
        throughput: None,
        strategy: None,
        connection_concurrency: Some(fact),
        evidence: cell.block_signals.clone(),
    };
    let result = ProbeResult {
        probe_type: "connection_concurrency".to_string(),
        target: cell.target_id.clone(),
        outcome: outcome.clone(),
        details: vec![
            ProbeDetail { key: "tls_profile_id".to_string(), value: cell.tls_profile_id.clone() },
            ProbeDetail { key: "requested_parallelism".to_string(), value: cell.requested_parallelism.to_string() },
            ProbeDetail {
                key: "observed_peak_parallelism".to_string(),
                value: cell.observed_peak_parallelism.to_string(),
            },
        ],
    };
    runtime.record_step(
        plan,
        "connection_concurrency",
        format!("Concurrency {} × {}: {outcome}", cell.tls_profile_id, cell.requested_parallelism),
        Some(cell.target_id.clone()),
        Some(outcome),
        None,
        RunnerArtifacts { probe_results: vec![result], observations: vec![observation], events: Vec::new() },
    );
}

fn contract_status(status: ClassifierStatus) -> ConnectionConcurrencyCellStatus {
    match status {
        ClassifierStatus::Healthy => ConnectionConcurrencyCellStatus::Healthy,
        ClassifierStatus::Blocked => ConnectionConcurrencyCellStatus::Blocked,
        ClassifierStatus::Mixed => ConnectionConcurrencyCellStatus::Mixed,
        ClassifierStatus::Contaminated => ConnectionConcurrencyCellStatus::Contaminated,
        ClassifierStatus::Skipped => ConnectionConcurrencyCellStatus::Skipped,
    }
}

fn finalize_assessment(runtime: &mut ExecutionRuntime, cells: &[ClassifierCell], current_profile: &str) {
    let mut assessment = classify_connection_concurrency_matrix(cells, Some(current_profile));
    assessment.warnings.push(
        "The global-platform-control-v1 cohort tests axis M only; it does not establish a domestic-AS condition."
            .to_string(),
    );
    assessment
        .warnings
        .push("Browser-originated TLS in proxy mode may bypass owned-stack fingerprint selection.".to_string());
    runtime.strategy.connection_concurrency_assessment = Some(ConnectionConcurrencyAssessment {
        classifier_version: assessment.classifier_version,
        verdict: match assessment.verdict {
            ClassifierVerdict::NoInteraction => ConnectionConcurrencyVerdict::NoInteraction,
            ClassifierVerdict::FingerprintOnly => ConnectionConcurrencyVerdict::FingerprintOnly,
            ClassifierVerdict::ConcurrencyOnly => ConnectionConcurrencyVerdict::ConcurrencyOnly,
            ClassifierVerdict::ConjunctionSuspected => ConnectionConcurrencyVerdict::ConjunctionSuspected,
            ClassifierVerdict::ConjunctionConfirmed => ConnectionConcurrencyVerdict::ConjunctionConfirmed,
            ClassifierVerdict::Inconclusive => ConnectionConcurrencyVerdict::Inconclusive,
        },
        selected_profile_id: assessment.recommended_tls_profile_id,
        safe_cap: assessment.recommended_max_parallel_handshakes,
        planned_cells: usize::from(assessment.planned_cells),
        clean_cells: usize::from(assessment.clean_cells),
        affected_targets: usize::from(assessment.affected_target_count),
        healthy_caps_by_profile: assessment.healthy_caps_by_profile,
        warnings: assessment.warnings,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use local_network_fixture::{FixtureConfig, FixtureStack};

    fn target() -> crate::types::DomainTarget {
        crate::types::DomainTarget {
            host: "fixture.test".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: Vec::new(),
            https_port: Some(443),
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: Some(crate::types::ConcurrencyProbeTargetMetadata {
                cohort_id: "fixture".to_string(),
                max_parallelism: 8,
            }),
        }
    }

    #[test]
    fn thresholds_keep_mixed_results_out_of_blocked_cells() {
        assert_eq!(classify_status(4, 3, false, 10), ClassifierStatus::Healthy);
        assert_eq!(classify_status(4, 1, true, 10), ClassifierStatus::Blocked);
        assert_eq!(classify_status(4, 2, true, 10), ClassifierStatus::Mixed);
        assert_eq!(classify_status(4, 4, false, 401), ClassifierStatus::Contaminated);
    }

    #[test]
    fn profile_rotation_distributes_first_profile_by_target() {
        let first = rotated_profiles(b"seed", 0);
        let second = rotated_profiles(b"seed", 1);
        assert_ne!(first[0], second[0]);
        assert_eq!(first.len(), 6);
    }

    #[test]
    fn synthetic_pair_failure_records_real_barrier_peak_and_existing_signal() {
        let cell = execute_cell_with(
            &target(),
            "fixture",
            "127.0.0.1:443".parse().expect("address"),
            "safari_stable",
            4,
            None,
            |_, _, profile| {
                if profile == "safari_stable" { Err("connection reset".to_string()) } else { Ok(()) }
            },
        );
        assert_eq!(cell.status, ClassifierStatus::Blocked);
        assert_eq!(cell.requested_parallelism, 4);
        assert!(cell.launch_spread_ms <= MAX_LAUNCH_SPREAD_MS);
        assert_eq!(cell.block_signals, vec!["tcp_reset"]);
    }

    #[test]
    fn synthetic_successes_are_held_until_observed_peak_reaches_requested_level() {
        let cell = execute_cell_with(
            &target(),
            "fixture",
            "127.0.0.1:443".parse().expect("address"),
            "firefox_stable",
            4,
            None,
            |_, _, _| Ok(()),
        );
        assert_eq!(cell.status, ClassifierStatus::Healthy);
        assert_eq!(cell.observed_peak_parallelism, 4);
    }

    #[test]
    fn panicking_connector_is_reported_without_stranding_peer_threads() {
        let calls = AtomicU16::new(0);
        let started = Instant::now();
        let cell = execute_cell_with(
            &target(),
            "fixture",
            "127.0.0.1:443".parse().expect("address"),
            "chrome_stable",
            4,
            None,
            |_, _, _| {
                if calls.fetch_add(1, Ordering::AcqRel) == 0 {
                    panic!("synthetic connector panic");
                }
                Ok(())
            },
        );

        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(cell.success_count, 3);
        assert_eq!(cell.failure_count, 1);
        assert!(cell.block_signals.contains(&"connection_freeze".to_string()));
    }

    #[test]
    fn local_fixture_accepts_every_canonical_profile_with_real_parallel_streams() {
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
        .expect("start local network fixture");
        let manifest = fixture.manifest();
        let address = SocketAddr::from(([127, 0, 0, 1], manifest.tls_echo_port));
        let target = crate::types::DomainTarget {
            host: manifest.fixture_domain.clone(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: Vec::new(),
            https_port: Some(manifest.tls_echo_port),
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: Some(crate::types::ConcurrencyProbeTargetMetadata {
                cohort_id: "fixture".to_string(),
                max_parallelism: 8,
            }),
        };

        for profile in ripdpi_tls_profiles::AVAILABLE_PROFILES {
            let cell = execute_cell_with(&target, "fixture", address, profile, 2, None, |host, address, profile| {
                let connector =
                    ripdpi_tls_profiles::build_connector(profile, false).map_err(|error| error.to_string())?;
                let stream = protected_tcp_connect(address, CONNECT_TIMEOUT).map_err(|error| error.to_string())?;
                connector.connect(host, stream).map_err(|error| error.to_string())
            });
            assert_eq!(cell.status, ClassifierStatus::Healthy, "{profile}");
            assert_eq!(cell.observed_peak_parallelism, 2, "{profile}");
        }
    }

    #[test]
    fn failed_same_profile_post_check_freezes_target_and_skips_remaining_profiles() {
        let target = target();
        let metadata = target.concurrency_probe.as_ref().expect("metadata");
        let mut calls = 0;
        let outcome = collect_target_matrix(
            &target,
            metadata,
            QUICK_LEVELS,
            &["chrome_stable", "firefox_stable"],
            || false,
            |profile, level| {
                calls += 1;
                let status = if profile == "chrome_stable" && level == 1 && calls > 2 {
                    ClassifierStatus::Blocked
                } else {
                    ClassifierStatus::Healthy
                };
                test_cell(profile, level, status)
            },
        );

        assert!(!outcome.cancelled);
        assert!(outcome.cells.iter().any(|cell| cell.status == ClassifierStatus::Contaminated));
        assert!(outcome.cells.iter().any(|cell| {
            cell.tls_profile_id == "firefox_stable"
                && cell.status == ClassifierStatus::Skipped
                && cell.skip_reason.as_deref() == Some("target frozen by failed post-check")
        }));
    }

    #[test]
    fn cancellation_returns_partial_cells_for_report_recovery() {
        let target = target();
        let metadata = target.concurrency_probe.as_ref().expect("metadata");
        let checks = AtomicU16::new(0);
        let outcome = collect_target_matrix(
            &target,
            metadata,
            QUICK_LEVELS,
            &["chrome_stable", "firefox_stable"],
            || checks.fetch_add(1, Ordering::AcqRel) >= 1,
            |profile, level| test_cell(profile, level, ClassifierStatus::Healthy),
        );

        assert!(outcome.cancelled);
        assert_eq!(outcome.cells.iter().filter(|cell| cell.status == ClassifierStatus::Healthy).count(), 1);
        assert!(outcome.cells.iter().any(|cell| cell.status == ClassifierStatus::Skipped));
        let assessment = classify_connection_concurrency_matrix(&outcome.cells, Some("chrome_stable"));
        assert_eq!(assessment.verdict, ClassifierVerdict::Inconclusive);
    }

    fn test_cell(profile: &str, level: u16, status: ClassifierStatus) -> ClassifierCell {
        ClassifierCell {
            target_cohort: "fixture".to_string(),
            target_id: "fixture.test".to_string(),
            tls_profile_id: profile.to_string(),
            requested_parallelism: level,
            observed_peak_parallelism: level,
            launch_spread_ms: 1,
            burst_window_ms: 10,
            success_count: u16::from(status == ClassifierStatus::Healthy) * level,
            failure_count: u16::from(status == ClassifierStatus::Blocked) * level,
            block_signals: (status == ClassifierStatus::Blocked)
                .then(|| "connection_freeze".to_string())
                .into_iter()
                .collect(),
            status,
            contaminated: false,
            skip_reason: None,
        }
    }
}
