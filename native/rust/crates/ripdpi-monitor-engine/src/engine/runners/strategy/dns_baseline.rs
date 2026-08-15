mod overrides;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::classification::classified_failure_probe_result;
use crate::strategy::detect_strategy_probe_dns_tampering_with_context_and_cancellation;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

pub(in crate::engine::runners) struct StrategyDnsBaselineRunner;

impl ExecutionStageRunner for StrategyDnsBaselineRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyDnsBaseline
    }

    fn phase(&self) -> &'static str {
        "dns_baseline"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(!plan.request.domain_targets.is_empty())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let Some(strategy_plan) = plan.strategy.as_ref() else {
            return RunnerOutcome::Completed;
        };
        let targets = &plan.request.domain_targets;
        let context = strategy_plan.runtime_context.as_ref();
        let Some(baseline) = detect_strategy_probe_dns_tampering_with_context_and_cancellation(
            targets,
            context,
            &plan.probe_context,
            || runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline(),
        ) else {
            return RunnerOutcome::Completed;
        };
        let artifacts = RunnerArtifacts::from_results(
            baseline.results.clone(),
            "strategy_probe",
            "warn",
            format!(
                "Baseline classified as {} with {}",
                baseline.failure.class.as_str(),
                baseline.failure.action.as_str(),
            ),
        );
        runtime.record_step(
            plan,
            self.phase(),
            "Strategy baseline DNS classification".to_string(),
            Some("dns_baseline".to_string()),
            Some(baseline.failure.class.as_str().to_string()),
            None,
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", &baseline.failure));
        tracing::info!(failure_class = ?baseline.failure.class, action = ?baseline.failure.action, "strategy probe: baseline classified");
        runtime.strategy.baseline_failure = Some(baseline.failure);

        // If we have encrypted IP overrides, build override targets so TCP/QUIC
        // runners can probe using trusted IPs instead of poisoned system DNS.
        overrides::record_dns_override_targets(plan, runtime, &baseline.encrypted_ip_overrides);

        // Continue to TCP/QUIC runners instead of short-circuiting, so we get
        // actual strategy effectiveness data even on DNS-tampered networks.
        RunnerOutcome::Completed
    }
}
