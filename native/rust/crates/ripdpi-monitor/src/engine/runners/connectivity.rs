use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::{
    build_network_environment_probe, push_event, run_circumvention_probe, run_dns_probe, run_domain_probe,
    run_quic_probe, run_service_probe, run_tcp_probe, run_throughput_probe,
};
use crate::telegram::run_telegram_probe;

use super::super::report::{build_report, connectivity_summary};
use super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(super) struct EnvironmentRunner;
pub(super) struct DnsRunner;
pub(super) struct WebRunner;
pub(super) struct QuicRunner;
pub(super) struct TcpRunner;
pub(super) struct ServiceRunner;
pub(super) struct CircumventionRunner;
pub(super) struct ThroughputRunner;
pub(super) struct TelegramRunner;

impl ExecutionStageRunner for EnvironmentRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Environment
    }

    fn phase(&self) -> &'static str {
        "environment"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.network_snapshot.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(snapshot) = plan.request.network_snapshot.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let probe = build_network_environment_probe(Some(snapshot)).expect("snapshot probe");
        let artifacts = RunnerArtifacts::from_probe(probe.clone(), "network_environment", &plan.request.path_mode);
        runtime.record_step(
            plan,
            self.phase(),
            "Collected network environment".to_string(),
            Some(probe.target.clone()),
            Some(probe.outcome.clone()),
            None,
            artifacts,
        );
        let warn = |shared: &_, msg: String| {
            push_event(
                shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                "engine",
                "warn",
                msg,
            );
        };
        if snapshot.transport == "none" {
            warn(&runtime.shared, "OS reports no network; aborting scan".to_string());
            runtime.finish_with_report(build_report(
                plan.session_id.clone(),
                plan.request.clone(),
                plan.started_at,
                connectivity_summary(&runtime.results, &plan.request.path_mode),
                runtime.results.clone(),
                runtime.observations.clone(),
                None,
                None,
            ));
            return RunnerOutcome::Finished;
        }
        if !snapshot.validated && !snapshot.captive_portal {
            warn(&runtime.shared, "OS reports unvalidated network; probe results may be unreliable".to_string());
        }
        RunnerOutcome::Completed
    }
}

macro_rules! connectivity_runner {
    ($runner:ident, $id:expr, $phase:expr, $len:expr, $iter:expr, $body:expr, $label:expr, $source:expr) => {
        impl ExecutionStageRunner for $runner {
            fn id(&self) -> ExecutionStageId {
                $id
            }

            fn phase(&self) -> &'static str {
                $phase
            }

            fn total_steps(&self, plan: &ExecutionPlan) -> usize {
                $len(plan)
            }

            fn run(
                &self,
                plan: &ExecutionPlan,
                runtime: &mut ExecutionRuntime,
                tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
            ) -> RunnerOutcome {
                for item in $iter(plan) {
                    if runtime.is_cancelled() {
                        return RunnerOutcome::Cancelled;
                    }
                    let label = $label(item.clone());
                    let probe = $body(item, plan, tls_verifier);
                    let outcome = probe.outcome.clone();
                    let artifacts = RunnerArtifacts::from_probe(probe, $source, &plan.request.path_mode);
                    runtime.record_step(plan, self.phase(), label.clone(), Some(label), Some(outcome), None, artifacts);
                }
                RunnerOutcome::Completed
            }
        }
    };
}

connectivity_runner!(
    DnsRunner,
    ExecutionStageId::Dns,
    "dns",
    |plan: &ExecutionPlan| plan.request.dns_targets.len(),
    |plan: &ExecutionPlan| plan.request.dns_targets.clone(),
    |target: crate::types::DnsTarget, plan: &ExecutionPlan, _tls| {
        run_dns_probe(&target, &plan.transport, &plan.request.path_mode)
    },
    |target: crate::types::DnsTarget| format!("DNS {}", target.domain),
    "dns_integrity"
);

connectivity_runner!(
    WebRunner,
    ExecutionStageId::Web,
    "reachability",
    |plan: &ExecutionPlan| plan.request.domain_targets.len(),
    |plan: &ExecutionPlan| plan.request.domain_targets.clone(),
    |target: crate::types::DomainTarget, plan: &ExecutionPlan, tls| run_domain_probe(&target, &plan.transport, tls),
    |target: crate::types::DomainTarget| format!("Reachability {}", target.host),
    "domain_reachability"
);

connectivity_runner!(
    QuicRunner,
    ExecutionStageId::Quic,
    "quic",
    |plan: &ExecutionPlan| plan.request.quic_targets.len(),
    |plan: &ExecutionPlan| plan.request.quic_targets.clone(),
    |target: crate::types::QuicTarget, plan: &ExecutionPlan, _tls| run_quic_probe(&target, &plan.transport),
    |target: crate::types::QuicTarget| format!("QUIC {}", target.host),
    "quic_reachability"
);

connectivity_runner!(
    TcpRunner,
    ExecutionStageId::Tcp,
    "tcp",
    |plan: &ExecutionPlan| plan.request.tcp_targets.len(),
    |plan: &ExecutionPlan| plan.request.tcp_targets.clone(),
    |target: crate::types::TcpTarget, plan: &ExecutionPlan, _tls| {
        run_tcp_probe(&target, &plan.request.whitelist_sni, &plan.transport)
    },
    |target: crate::types::TcpTarget| format!("TCP {}", target.provider),
    "tcp_fat_header"
);

connectivity_runner!(
    ServiceRunner,
    ExecutionStageId::Service,
    "service",
    |plan: &ExecutionPlan| plan.request.service_targets.len(),
    |plan: &ExecutionPlan| plan.request.service_targets.clone(),
    |target: crate::types::ServiceTarget, plan: &ExecutionPlan, tls| run_service_probe(&target, &plan.transport, tls),
    |target: crate::types::ServiceTarget| format!("Service {}", target.service),
    "service_reachability"
);

connectivity_runner!(
    CircumventionRunner,
    ExecutionStageId::Circumvention,
    "circumvention",
    |plan: &ExecutionPlan| plan.request.circumvention_targets.len(),
    |plan: &ExecutionPlan| plan.request.circumvention_targets.clone(),
    |target: crate::types::CircumventionTarget, plan: &ExecutionPlan, tls| {
        run_circumvention_probe(&target, &plan.transport, tls)
    },
    |target: crate::types::CircumventionTarget| format!("Circumvention {}", target.tool),
    "circumvention_reachability"
);

connectivity_runner!(
    ThroughputRunner,
    ExecutionStageId::Throughput,
    "throughput",
    |plan: &ExecutionPlan| plan.request.throughput_targets.len(),
    |plan: &ExecutionPlan| plan.request.throughput_targets.clone(),
    |target: crate::types::ThroughputTarget, plan: &ExecutionPlan, _tls| run_throughput_probe(&target, &plan.transport),
    |target: crate::types::ThroughputTarget| format!("Throughput {}", target.label),
    "throughput_window"
);

impl ExecutionStageRunner for TelegramRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Telegram
    }

    fn phase(&self) -> &'static str {
        "telegram"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.request.telegram_target.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(target) = plan.request.telegram_target.as_ref() else {
            return RunnerOutcome::Completed;
        };
        if runtime.is_cancelled() {
            return RunnerOutcome::Cancelled;
        }
        let probe = run_telegram_probe(target, &plan.transport);
        let outcome = probe.outcome.clone();
        let artifacts = RunnerArtifacts::from_probe(probe, "telegram", &plan.request.path_mode);
        runtime.record_step(
            plan,
            self.phase(),
            "Telegram availability checked".to_string(),
            Some("telegram.org".to_string()),
            Some(outcome),
            None,
            artifacts,
        );
        RunnerOutcome::Completed
    }
}
