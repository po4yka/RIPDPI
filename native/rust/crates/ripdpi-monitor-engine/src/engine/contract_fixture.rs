use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use rustls::client::danger::ServerCertVerifier;

use super::runtime::{
    self, CollectedStageOutcome, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts,
    RunnerOutcome, publish_cancelled_run,
};
use crate::types::{ScanKind, ScanRequest, SharedState};

/// A single recorded step from a connectivity runner's `run_collecting` output,
/// scrubbed of non-deterministic fields (timestamps, OS-specific addresses).
#[derive(Debug, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct RunnerStepSnapshot {
    pub phase: String,
    pub message: String,
    pub latest_probe_target: Option<String>,
    pub latest_probe_outcome: Option<String>,
}

/// Full behavioral snapshot record for one connectivity runner.
#[derive(Debug, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct RunnerParityRecord {
    pub stage_id: String,
    pub phase: String,
    pub total_steps: usize,
    pub outcome: String,
    pub steps: Vec<RunnerStepSnapshot>,
}

/// Public entry point for the behavioral-parity snapshot.
///
/// Builds the deterministic no-network fixture plan then delegates to the
/// runners module (the only place runner structs are in scope).
#[doc(hidden)]
pub fn connectivity_runner_parity_snapshot() -> Vec<RunnerParityRecord> {
    let plan = parity_fixture_plan();
    super::runners::connectivity_runner_parity_snapshot(&plan)
}

fn parity_fixture_plan() -> super::runtime::ExecutionPlan {
    let request = ScanRequest {
        profile_id: "connectivity-parity-fixture".to_string(),
        display_name: "Connectivity parity fixture".to_string(),
        path_mode: crate::types::ScanPathMode::RawPath,
        kind: ScanKind::Connectivity,
        family: crate::types::DiagnosticProfileFamily::General,
        region_tag: None,
        manual_only: false,
        pack_refs: Vec::new(),
        proxy_host: None,
        proxy_port: None,
        in_path_route: None,
        probe_tasks: Vec::new(),
        domain_targets: Vec::new(),
        dns_targets: Vec::new(),
        tcp_targets: Vec::new(),
        quic_targets: Vec::new(),
        service_targets: Vec::new(),
        circumvention_targets: Vec::new(),
        throughput_targets: Vec::new(),
        whitelist_sni: Vec::new(),
        telegram_target: None,
        strategy_probe: None,
        confirm_good_dpi_evidence: None,
        network_snapshot: None,
        route_probe: None,
        scan_deadline_ms: None,
        diagnostic_tls_keylog_path: None,
    };
    super::plan::build_execution_plan("parity-session".to_string(), request, 0, crate::transport::direct_transport())
        .unwrap()
}

#[doc(hidden)]
pub(crate) fn connectivity_partial_report_contract_fixture() -> crate::types::ScanReport {
    let shared = Arc::new(Mutex::new(SharedState::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let request = connectivity_partial_report_request();
    let mut plan = super::plan::build_execution_plan(
        "connectivity-partial-contract".to_string(),
        request,
        1_700_000_000_000,
        crate::transport::direct_transport(),
    )
    .expect("contract fixture execution plan");
    let coordinator = runtime::ExecutionCoordinator::new(contract_runners());
    plan.total_steps = coordinator.total_steps(&plan);

    let mut runtime = ExecutionRuntime::new(shared.clone(), cancel);
    let outcome = coordinator.run(&plan, &mut runtime, None);
    assert!(matches!(outcome, RunnerOutcome::Cancelled));
    publish_cancelled_run(&plan, &shared, runtime, None, None);

    let mut report = shared.lock().expect("contract fixture shared state").report.clone().expect("partial report");
    report.started_at = 1_700_000_000_000;
    report.finished_at = 1_700_000_000_123;
    report.metrics_summary = None;
    report
}

fn contract_runners() -> Vec<Box<dyn ExecutionStageRunner + Send + Sync>> {
    vec![
        Box::new(ContractEnvironmentRunner),
        Box::new(ContractProbeRunner::new(
            ExecutionStageId::Dns,
            "dns",
            "DNS fixture.example.test",
            "dns_integrity",
            "fixture.example.test",
            "dns_match",
            false,
        )),
        Box::new(ContractProbeRunner::new(
            ExecutionStageId::Tcp,
            "tcp",
            "TCP fixture",
            "tcp_fat_header",
            "127.0.0.1:443 (fixture)",
            "tcp_fat_header_ok",
            false,
        )),
        Box::new(ContractProbeRunner::new(
            ExecutionStageId::Quic,
            "quic",
            "QUIC fixture-quic-one.example.test",
            "quic_reachability",
            "fixture-quic-one.example.test",
            "quic_initial_response",
            true,
        )),
    ]
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
        in_path_route: None,
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
            concurrency_probe: None,
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
        confirm_good_dpi_evidence: None,
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
    cancel_after_collect: bool,
}

impl ContractProbeRunner {
    fn new(
        id: ExecutionStageId,
        phase: &'static str,
        message: &'static str,
        probe_type: &'static str,
        target: &'static str,
        outcome: &'static str,
        cancel_after_collect: bool,
    ) -> Self {
        Self { id, phase, message, probe_type, target, outcome, cancel_after_collect }
    }
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
            artifacts: RunnerArtifacts::from_probe(probe, self.probe_type, &plan.request.path_mode),
        };
        if self.cancel_after_collect {
            cancel.store(true, Ordering::Release);
            CollectedStageOutcome::Cancelled(vec![step])
        } else {
            CollectedStageOutcome::Completed(vec![step])
        }
    }
}
