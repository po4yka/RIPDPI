use crate::connectivity::run_selective_matrix_attempt;
use crate::engine::runtime::{
    CollectedStageOutcome, CollectedStep, ExecutionPlan, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
};
use crate::types::{ProbeDetail, ProbeResult, SelectiveMatrixConfig};
use crate::util::{active_scan_io_deadline, with_scan_io_deadline};
use rustls::client::danger::ServerCertVerifier;
use std::collections::BTreeSet;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

pub(in crate::engine::runners) struct SelectiveMatrixRunner;
impl ExecutionStageRunner for SelectiveMatrixRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::SelectiveAvailability
    }
    fn phase(&self) -> &'static str {
        "selective_availability"
    }
    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        plan.request.selective_matrix.as_ref().map_or(0, |c| c.targets.len() * c.repetitions + 1)
    }
    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let Some(config) = &plan.request.selective_matrix else {
            return CollectedStageOutcome::Completed(vec![]);
        };
        let own_deadline = Instant::now() + Duration::from_secs(120);
        let deadline = active_scan_io_deadline().map_or(own_deadline, |outer| outer.min(own_deadline));
        with_scan_io_deadline(Some(deadline), || {
            let mut results = Vec::new();
            let mut steps = Vec::new();
            // Each round visits every cohort before a repeat of the same target.
            let mut targets = Vec::new();
            let cohorts = ["declared_available", "domestic", "global", "user"];
            for index in 0..config.targets.len() {
                for cohort in cohorts {
                    if let Some(target) = config.targets.iter().filter(|t| t.cohort == cohort).nth(index) {
                        targets.push(target);
                    }
                }
            }
            if config.validate().is_ok() {
                'rounds: for attempt in 1..=config.repetitions {
                    for target in &targets {
                        if super::interrupted(cancel) {
                            break 'rounds;
                        }
                        let result =
                            run_selective_matrix_attempt(config, target, attempt, &plan.transport, cancel, verifier);
                        steps.push(step(plan, result.clone()));
                        results.push(result);
                    }
                }
            }
            steps.push(step(plan, summarize(config, &results)));
            if super::interrupted(cancel) {
                CollectedStageOutcome::Cancelled(steps)
            } else {
                CollectedStageOutcome::Completed(steps)
            }
        })
    }
}
fn step(plan: &ExecutionPlan, result: ProbeResult) -> CollectedStep {
    CollectedStep {
        phase: "selective_availability",
        latest_probe_target: Some(result.target.clone()),
        message: result.target.clone(),
        latest_probe_outcome: Some(result.outcome.clone()),
        artifacts: RunnerArtifacts::from_probe(result, "selective_availability", &plan.request.path_mode),
    }
}
fn detail<'a>(result: &'a ProbeResult, key: &str) -> &'a str {
    result.details.iter().find(|d| d.key == key).map_or("", |d| d.value.as_str())
}
fn summarize(config: &SelectiveMatrixConfig, results: &[ProbeResult]) -> ProbeResult {
    let expected = config.targets.len() * config.repetitions;
    let complete = config.validate().is_ok()
        && results.len() == expected
        && results.iter().all(|r| !matches!(r.outcome.as_str(), "matrix_target_invalid" | "matrix_target_cancelled"));
    let mut healthy = BTreeSet::new();
    let mut failed = BTreeSet::new();
    let mut all_healthy = true;
    let mut all_failed = true;
    for target in &config.targets {
        let rows: Vec<_> = results.iter().filter(|r| detail(r, "targetId") == target.id).collect();
        let available = rows.len() == config.repetitions && rows.iter().all(|r| r.outcome == "matrix_target_available");
        let transport_failed = rows.len() == config.repetitions
            && rows.iter().all(|r| r.outcome == "matrix_target_transport_failed")
            && rows.windows(2).all(|w| detail(w[0], "failureStage") == detail(w[1], "failureStage"));
        all_healthy &= available;
        all_failed &= transport_failed;
        if target.cohort == "declared_available" && available {
            healthy.insert(&target.infrastructure_group);
        }
        if matches!(target.cohort.as_str(), "domestic" | "global") && transport_failed {
            failed.insert(&target.infrastructure_group);
        }
    }
    let ambiguous = results.iter().any(|r| {
        matches!(
            r.outcome.as_str(),
            "matrix_target_http_error" | "matrix_target_body_incomplete" | "matrix_target_tls_error"
        )
    });
    let outcome = if !complete || config.repetitions < 2 || ambiguous {
        "matrix_inconclusive"
    } else if all_healthy {
        "matrix_available"
    } else if all_failed {
        "matrix_unavailable"
    } else if healthy.len() >= 2 && failed.len() >= 2 && healthy.is_disjoint(&failed) {
        "matrix_selective"
    } else {
        "matrix_mixed"
    };
    ProbeResult {
        probe_type: "selective_availability_summary".into(),
        target: "Selective availability matrix".into(),
        outcome: outcome.into(),
        details: vec![
            ProbeDetail { key: "catalogVersion".into(), value: config.catalog_version.clone() },
            ProbeDetail { key: "completedAttempts".into(), value: results.len().to_string() },
            ProbeDetail { key: "expectedAttempts".into(), value: expected.to_string() },
            ProbeDetail { key: "coverageComplete".into(), value: complete.to_string() },
            ProbeDetail { key: "availableDeclaredGroups".into(), value: healthy.len().to_string() },
            ProbeDetail { key: "failedComparisonGroups".into(), value: failed.len().to_string() },
            ProbeDetail { key: "cause".into(), value: "not_established".into() },
            ProbeDetail { key: "remoteHealth".into(), value: "unknown".into() },
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::SelectiveMatrixTarget;
    fn fixture() -> (SelectiveMatrixConfig, Vec<ProbeResult>) {
        let targets = (0..4)
            .map(|i| SelectiveMatrixTarget {
                id: i.to_string(),
                label: i.to_string(),
                url: format!("https://target-{i}.example.com/"),
                cohort: if i < 2 { "declared_available" } else { "global" }.into(),
                infrastructure_group: i.to_string(),
                source_url: String::new(),
                source_date: String::new(),
                last_verified_at: None,
                connect_ips: vec![],
            })
            .collect();
        let config = SelectiveMatrixConfig {
            version: 1,
            catalog_version: "test".into(),
            targets,
            repetitions: 2,
            timeout_ms: 5000,
            max_response_bytes: 1024,
        };
        let rows = config
            .targets
            .iter()
            .flat_map(|target| {
                (0..2).map(move |_| ProbeResult {
                    probe_type: "selective_availability".into(),
                    target: target.label.clone(),
                    outcome: if target.cohort == "declared_available" {
                        "matrix_target_available"
                    } else {
                        "matrix_target_transport_failed"
                    }
                    .into(),
                    details: vec![
                        ProbeDetail { key: "targetId".into(), value: target.id.clone() },
                        ProbeDetail { key: "failureStage".into(), value: "tcp".into() },
                    ],
                })
            })
            .collect();
        (config, rows)
    }
    #[test]
    fn repeated_independent_evidence_supports_selectivity() {
        let (config, rows) = fixture();
        assert_eq!(summarize(&config, &rows).outcome, "matrix_selective");
    }
    #[test]
    fn missing_attempts_server_errors_and_shared_infrastructure_do_not() {
        let (mut config, mut rows) = fixture();
        assert_eq!(summarize(&config, &rows[..7]).outcome, "matrix_inconclusive");
        rows[7].outcome = "matrix_target_http_error".into();
        assert_eq!(summarize(&config, &rows).outcome, "matrix_inconclusive");
        rows[7].outcome = "matrix_target_transport_failed".into();
        config.targets[3].infrastructure_group = "0".into();
        assert_eq!(summarize(&config, &rows).outcome, "matrix_mixed");
    }
    #[test]
    fn repeated_tls_blackholes_are_transport_failures_not_cancellation() {
        use std::io::{Read, Write};
        use std::net::{Ipv4Addr, TcpListener};
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            for _ in 0..4 {
                let (mut socket, _) = listener.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
                let mut greeting = [0; 2];
                socket.read_exact(&mut greeting).unwrap();
                let mut methods = vec![0; greeting[1] as usize];
                socket.read_exact(&mut methods).unwrap();
                socket.write_all(&[5, 0]).unwrap();
                let mut request = [0; 10];
                socket.read_exact(&mut request).unwrap();
                assert_eq!(request[3], 1);
                socket.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 1]).unwrap();
                let mut hello = [0; 1];
                socket.read_exact(&mut hello).unwrap();
                std::thread::sleep(Duration::from_millis(110));
            }
        });
        let (mut config, mut rows) = fixture();
        config.timeout_ms = 100;
        rows.retain(|r| detail(r, "targetId") == "0" || detail(r, "targetId") == "1");
        for target in &mut config.targets {
            target.connect_ips = vec!["8.8.8.8".into()];
        }
        let transport = crate::transport::TransportConfig::Socks5 { host: "127.0.0.1".into(), port, credentials: None };
        let started = Instant::now();
        for target in &config.targets[2..] {
            for attempt in 1..=2 {
                let row =
                    run_selective_matrix_attempt(&config, target, attempt, &transport, &AtomicBool::new(false), None);
                assert_eq!(row.outcome, "matrix_target_transport_failed", "{:?}", row.details);
                rows.push(row);
            }
        }
        assert!(started.elapsed() >= Duration::from_millis(350));
        assert!(started.elapsed() < Duration::from_secs(2));
        assert_eq!(summarize(&config, &rows).outcome, "matrix_selective");
        server.join().unwrap();
    }

    #[test]
    fn all_failed_does_not_claim_selectivity() {
        let (config, mut rows) = fixture();
        for row in &mut rows {
            row.outcome = "matrix_target_transport_failed".into();
        }
        assert_eq!(summarize(&config, &rows).outcome, "matrix_unavailable");
    }
}
