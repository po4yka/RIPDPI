mod plan;
mod report;
mod runners;
mod runtime;

use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{push_event, set_progress, set_report};
use crate::transport::transport_for_request_with_session;
#[cfg(test)]
use crate::types::{ProbeResult, ProbeTaskFamily};
use crate::types::{ScanKind, ScanProgress, ScanRequest, SharedState};
use crate::CandidateRuntimeLauncher;

use plan::build_execution_plan;
#[cfg(test)]
use plan::connectivity_stage_order;
use report::{build_report, connectivity_summary};
use runners::execution_coordinator;
#[cfg(test)]
use runtime::cancelled_run_summary;
use runtime::{
    publish_cancelled_run, CollectedStageOutcome, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner,
    RunnerArtifacts, RunnerOutcome,
};

pub fn run_engine_scan(
    shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    session_id: String,
    request: ScanRequest,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    candidate_runtime_launcher: Arc<dyn CandidateRuntimeLauncher>,
) {
    let started_at = crate::util::now_ms();
    let transport = transport_for_request_with_session(&request, &session_id);
    let mut plan = match build_execution_plan(session_id.clone(), request.clone(), started_at, transport.clone()) {
        Ok(plan) => plan,
        Err(message) => {
            let report =
                build_report(session_id.clone(), request, started_at, message, Vec::new(), Vec::new(), None, None);
            set_report(&shared, report);
            set_progress(
                &shared,
                ScanProgress {
                    session_id,
                    phase: "finished".to_string(),
                    completed_steps: 1,
                    total_steps: 1,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
            return;
        }
    };
    let coordinator = execution_coordinator(candidate_runtime_launcher);
    plan.total_steps = coordinator.total_steps(&plan);

    set_progress(
        &shared,
        ScanProgress {
            session_id: plan.session_id.clone(),
            phase: "starting".to_string(),
            completed_steps: 0,
            total_steps: plan.total_steps,
            message: format!("Preparing {}", plan.request.display_name),
            is_finished: false,
            latest_probe_target: None,
            latest_probe_outcome: None,
            strategy_probe_progress: None,
        },
    );
    push_event(
        &shared,
        &plan.session_id,
        &plan.request.profile_id,
        &plan.request.path_mode,
        "engine",
        "info",
        format!(
            "Starting {} in {:?} transport={}",
            plan.request.display_name,
            plan.request.path_mode,
            crate::transport::describe_transport(&plan.transport)
        ),
    );

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    runtime.set_scan_deadline(
        std::time::Instant::now() + std::time::Duration::from_millis(plan.request.scan_deadline_ms.unwrap_or(360_000)),
    );
    match coordinator.run(&plan, &mut runtime, tls_verifier.as_ref()) {
        RunnerOutcome::Cancelled => {
            publish_cancelled_run(&plan, &shared, runtime);
        }
        RunnerOutcome::Finished => {
            if let Some(report) = runtime.final_report {
                set_report(&shared, report);
            }
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
        }
        RunnerOutcome::Completed => {
            let summary = match plan.request.kind {
                ScanKind::Connectivity => connectivity_summary(&runtime.results, &plan.request.path_mode),
                ScanKind::StrategyProbe => {
                    runtime.strategy.summary.clone().unwrap_or_else(|| "Automatic probing finished".to_string())
                }
            };
            let report = build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                summary,
                runtime.results,
                runtime.observations,
                runtime.strategy.strategy_probe_report,
                None,
            );
            set_report(&shared, report);
            push_event(
                &shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "info",
                "Diagnostics finished".to_string(),
            );
            set_progress(
                &shared,
                ScanProgress {
                    session_id: plan.session_id,
                    phase: "finished".to_string(),
                    completed_steps: plan.total_steps,
                    total_steps: plan.total_steps,
                    message: "Diagnostics finished".to_string(),
                    is_finished: true,
                    latest_probe_target: None,
                    latest_probe_outcome: None,
                    strategy_probe_progress: None,
                },
            );
        }
    }
}

#[doc(hidden)]
pub(crate) fn connectivity_partial_report_contract_fixture() -> crate::types::ScanReport {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let request = connectivity_partial_report_request();
    let mut plan = build_execution_plan(
        "connectivity-partial-contract".to_string(),
        request,
        1_700_000_000_000,
        crate::transport::direct_transport(),
    )
    .expect("contract fixture execution plan");
    let coordinator = runtime::ExecutionCoordinator::new(vec![
        Box::new(ContractEnvironmentRunner),
        Box::new(ContractProbeRunner {
            id: ExecutionStageId::Dns,
            phase: "dns",
            message: "DNS fixture.example.test",
            probe_type: "dns_integrity",
            target: "fixture.example.test",
            outcome: "dns_match",
            artifact_source: "dns_integrity",
            cancel_after_collect: false,
        }),
        Box::new(ContractProbeRunner {
            id: ExecutionStageId::Tcp,
            phase: "tcp",
            message: "TCP fixture",
            probe_type: "tcp_fat_header",
            target: "127.0.0.1:443 (fixture)",
            outcome: "tcp_fat_header_ok",
            artifact_source: "tcp_fat_header",
            cancel_after_collect: false,
        }),
        Box::new(ContractProbeRunner {
            id: ExecutionStageId::Quic,
            phase: "quic",
            message: "QUIC fixture-quic-one.example.test",
            probe_type: "quic_reachability",
            target: "fixture-quic-one.example.test",
            outcome: "quic_initial_response",
            artifact_source: "quic_reachability",
            cancel_after_collect: true,
        }),
    ]);
    plan.total_steps = coordinator.total_steps(&plan);

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    let outcome = coordinator.run(&plan, &mut runtime, None);
    assert!(matches!(outcome, RunnerOutcome::Cancelled));
    publish_cancelled_run(&plan, &shared, runtime);

    let mut report = shared.lock().expect("contract fixture shared state").report.clone().expect("partial report");
    report.started_at = 1_700_000_000_000;
    report.finished_at = 1_700_000_000_123;
    report.metrics_summary = None;
    report
}

fn connectivity_partial_report_request() -> ScanRequest {
    ScanRequest {
        profile_id: "connectivity-partial-contract".to_string(),
        display_name: "Connectivity partial contract".to_string(),
        path_mode: crate::types::ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: crate::types::DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: Vec::new(),
        proxy_host: None,
        proxy_port: None,
        probe_tasks: vec![
            crate::types::ProbeTask {
                family: crate::types::ProbeTaskFamily::Dns,
                target_id: "dns-fixture".to_string(),
                label: "DNS fixture".to_string(),
            },
            crate::types::ProbeTask {
                family: crate::types::ProbeTaskFamily::Tcp,
                target_id: "tcp-fixture".to_string(),
                label: "TCP fixture".to_string(),
            },
            crate::types::ProbeTask {
                family: crate::types::ProbeTaskFamily::Quic,
                target_id: "quic-fixture".to_string(),
                label: "QUIC fixture".to_string(),
            },
        ],
        domain_targets: vec![crate::types::DomainTarget {
            host: "fixture.example.test".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: vec!["127.0.0.2".to_string()],
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".to_string(),
            is_control: false,
        }],
        dns_targets: vec![crate::types::DnsTarget {
            domain: "fixture.example.test".to_string(),
            udp_server: Some("127.0.0.1:5353".to_string()),
            encrypted_resolver_id: Some("fixture-doh".to_string()),
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("127.0.0.1".to_string()),
            encrypted_port: Some(8053),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec!["127.0.0.1".to_string()],
            encrypted_doh_url: Some("http://127.0.0.1:8053/dns-query".to_string()),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec!["127.0.0.1".to_string()],
        }],
        tcp_targets: vec![crate::types::TcpTarget {
            id: "tcp-fixture".to_string(),
            provider: "fixture".to_string(),
            ip: "127.0.0.1".to_string(),
            port: 443,
            sni: Some("fixture.example.test".to_string()),
            asn: None,
            host_header: Some("fixture.example.test".to_string()),
            fat_header_requests: Some(1),
            alt_port: None,
        }],
        quic_targets: vec![
            crate::types::QuicTarget {
                host: "fixture-quic-one.example.test".to_string(),
                connect_ip: Some("127.0.0.1".to_string()),
                connect_ips: Vec::new(),
                port: 443,
            },
            crate::types::QuicTarget {
                host: "fixture-quic-two.example.test".to_string(),
                connect_ip: Some("127.0.0.2".to_string()),
                connect_ips: Vec::new(),
                port: 443,
            },
        ],
        service_targets: Vec::new(),
        circumvention_targets: Vec::new(),
        throughput_targets: Vec::new(),
        whitelist_sni: vec!["fixture.example.test".to_string()],
        telegram_target: None,
        strategy_probe: None,
        network_snapshot: Some(ripdpi_monitor_adapter::proxy_config::NetworkSnapshot {
            transport: "wifi".to_string(),
            validated: true,
            private_dns_mode: "system".to_string(),
            dns_servers: vec!["127.0.0.1".to_string()],
            captured_at_ms: 1_700_000_000_000,
            ..Default::default()
        }),
        route_probe: None,
        scan_deadline_ms: None,
        diagnostic_tls_keylog_path: None,
    }
}

struct ContractEnvironmentRunner;

impl ExecutionStageRunner for ContractEnvironmentRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Environment
    }

    fn phase(&self) -> &'static str {
        "environment"
    }

    fn total_steps(&self, plan: &runtime::ExecutionPlan) -> usize {
        usize::from(plan.request.network_snapshot.is_some())
    }

    fn run(
        &self,
        plan: &runtime::ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let probe = crate::connectivity::build_network_environment_probe(plan.request.network_snapshot.as_ref())
            .expect("contract fixture network snapshot");
        runtime.record_step(
            plan,
            self.phase(),
            "Collected network environment".to_string(),
            Some(probe.target.clone()),
            Some(probe.outcome.clone()),
            None,
            RunnerArtifacts::from_probe(probe, "network_environment", &plan.request.path_mode),
        );
        RunnerOutcome::Completed
    }
}

struct ContractProbeRunner {
    id: ExecutionStageId,
    phase: &'static str,
    message: &'static str,
    probe_type: &'static str,
    target: &'static str,
    outcome: &'static str,
    artifact_source: &'static str,
    cancel_after_collect: bool,
}

impl ExecutionStageRunner for ContractProbeRunner {
    fn id(&self) -> ExecutionStageId {
        self.id.clone()
    }

    fn phase(&self) -> &'static str {
        self.phase
    }

    fn total_steps(&self, plan: &runtime::ExecutionPlan) -> usize {
        match self.id {
            ExecutionStageId::Dns => plan.request.dns_targets.len(),
            ExecutionStageId::Tcp => plan.request.tcp_targets.len(),
            ExecutionStageId::Quic => plan.request.quic_targets.len(),
            _ => 0,
        }
    }

    fn run_collecting(
        &self,
        plan: &runtime::ExecutionPlan,
        cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        let probe = crate::types::ProbeResult {
            probe_type: self.probe_type.to_string(),
            target: self.target.to_string(),
            outcome: self.outcome.to_string(),
            details: vec![crate::types::ProbeDetail { key: "fixture".to_string(), value: "bundled".to_string() }],
        };
        let step = runtime::CollectedStep {
            phase: self.phase,
            message: self.message.to_string(),
            latest_probe_target: Some(self.target.to_string()),
            latest_probe_outcome: Some(self.outcome.to_string()),
            artifacts: RunnerArtifacts::from_probe(probe, self.artifact_source, &plan.request.path_mode),
        };
        if self.cancel_after_collect {
            cancel.store(true, std::sync::atomic::Ordering::Release);
            CollectedStageOutcome::Cancelled(vec![step])
        } else {
            CollectedStageOutcome::Completed(vec![step])
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connectivity_summary_reports_bucket_breakdown() {
        let mut results = vec![probe("network_environment", "wifi", "network_available")];
        results.extend((0..24).map(|index| probe("dns_integrity", format!("dns-{index}"), "dns_match")));
        results.extend((0..4).map(|index| probe("domain_reachability", format!("tls-{index}"), "tls_ok")));
        results.push(probe("tcp_fat_header", "8.8.8.8:443 (Google DNS)", "whitelist_sni_ok"));
        results.push(probe("tcp_fat_header", "172.67.70.222:443 (Cloudflare)", "whitelist_sni_failed"));

        assert_eq!(
            connectivity_summary(&results, &crate::types::ScanPathMode::RawPath),
            "31 completed · 30 healthy · 1 failed",
        );
    }

    #[test]
    fn connectivity_summary_omits_zero_value_non_healthy_buckets() {
        assert_eq!(connectivity_summary(&[], &crate::types::ScanPathMode::RawPath), "0 completed · 0 healthy");
    }

    #[test]
    fn connectivity_stage_order_deduplicates_requested_families() {
        let request = scan_request(
            ScanKind::Connectivity,
            vec![
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-1".to_string(),
                    label: "tcp".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Dns,
                    target_id: "dns-1".to_string(),
                    label: "dns".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Tcp,
                    target_id: "tcp-2".to_string(),
                    label: "tcp2".to_string(),
                },
                crate::types::ProbeTask {
                    family: ProbeTaskFamily::Service,
                    target_id: "svc-1".to_string(),
                    label: "service".to_string(),
                },
            ],
            None,
        );

        assert_eq!(
            connectivity_stage_order(&request),
            vec![
                ExecutionStageId::Environment,
                ExecutionStageId::Tcp,
                ExecutionStageId::Dns,
                ExecutionStageId::Service,
            ],
        );
    }

    #[test]
    fn run_engine_scan_records_planning_errors_in_report_and_progress() {
        let shared = Arc::new(Mutex::new(SharedState::default()));
        let cancel = Arc::new(AtomicBool::new(false));
        let request = scan_request(
            ScanKind::StrategyProbe,
            Vec::new(),
            Some(crate::types::StrategyProbeRequest {
                suite_id: "phase1".to_string(),
                base_proxy_config_json: None,
                target_selection: None,
                max_candidates: None,
            }),
        );

        run_engine_scan(
            shared.clone(),
            cancel,
            "session-1".to_string(),
            request,
            None,
            Arc::new(crate::execution::UnavailableCandidateRuntimeLauncher),
        );

        let state = shared.lock().expect("shared state lock");
        let report = state.report.clone().expect("report");
        let progress = state.progress.clone().expect("progress");

        assert_eq!(report.summary, "strategy_probe scan requires baseProxyConfigJson");
        assert!(report.results.is_empty());
        assert_eq!(progress.phase, "finished");
        assert_eq!(progress.completed_steps, 1);
        assert_eq!(progress.total_steps, 1);
        assert!(progress.is_finished);
    }

    #[test]
    fn cancelled_run_summary_reports_partial_results_when_probe_data_exists() {
        assert_eq!(cancelled_run_summary(true), "Scan completed with partial results");
    }

    #[test]
    fn cancelled_run_summary_reports_cancelled_when_probe_data_is_empty() {
        assert_eq!(cancelled_run_summary(false), "Scan cancelled");
    }

    fn probe(probe_type: &str, target: impl Into<String>, outcome: &str) -> ProbeResult {
        ProbeResult {
            probe_type: probe_type.to_string(),
            target: target.into(),
            outcome: outcome.to_string(),
            details: Vec::new(),
        }
    }

    fn scan_request(
        kind: ScanKind,
        probe_tasks: Vec<crate::types::ProbeTask>,
        strategy_probe: Option<crate::types::StrategyProbeRequest>,
    ) -> ScanRequest {
        ScanRequest {
            profile_id: "profile".to_string(),
            display_name: "Phase 1".to_string(),
            path_mode: crate::types::ScanPathMode::RawPath,
            kind,
            family: crate::types::DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: Vec::new(),
            proxy_host: None,
            proxy_port: None,
            probe_tasks,
            domain_targets: Vec::new(),
            dns_targets: Vec::new(),
            tcp_targets: Vec::new(),
            quic_targets: Vec::new(),
            service_targets: Vec::new(),
            circumvention_targets: Vec::new(),
            throughput_targets: Vec::new(),
            whitelist_sni: Vec::new(),
            telegram_target: None,
            strategy_probe,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
        }
    }
}
