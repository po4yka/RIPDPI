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
        let Some(failure) = baseline.failure.as_ref() else {
            // Clean network: keep the collected DNS-integrity evidence and close
            // the stage's progress step instead of dropping both.
            let target_count = baseline.results.len();
            let message = format!("Baseline DNS integrity verified across {target_count} targets");
            let artifacts = RunnerArtifacts::from_results(baseline.results.clone(), "strategy_probe", "info", message);
            runtime.record_step(
                plan,
                self.phase(),
                "Strategy baseline DNS integrity verified".to_string(),
                Some("dns_baseline".to_string()),
                None,
                None,
                artifacts,
            );
            tracing::info!(targets = baseline.results.len(), "strategy probe: baseline DNS clean");
            return RunnerOutcome::Completed;
        };
        let artifacts = RunnerArtifacts::from_results(
            baseline.results.clone(),
            "strategy_probe",
            "warn",
            format!("Baseline classified as {} with {}", failure.class.as_str(), failure.action.as_str(),),
        );
        runtime.record_step(
            plan,
            self.phase(),
            "Strategy baseline DNS classification".to_string(),
            Some("dns_baseline".to_string()),
            Some(failure.class.as_str().to_string()),
            None,
            artifacts,
        );
        runtime.results.push(classified_failure_probe_result("Current strategy", failure));
        tracing::info!(failure_class = ?failure.class, action = ?failure.action, "strategy probe: baseline classified");
        runtime.strategy.baseline_failure = Some(failure.clone());

        // If we have encrypted IP overrides, build override targets so TCP/QUIC
        // runners can probe using trusted IPs instead of poisoned system DNS.
        overrides::record_dns_override_targets(plan, runtime, &baseline.encrypted_ip_overrides);

        // Continue to TCP/QUIC runners instead of short-circuiting, so we get
        // actual strategy effectiveness data even on DNS-tampered networks.
        RunnerOutcome::Completed
    }
}
