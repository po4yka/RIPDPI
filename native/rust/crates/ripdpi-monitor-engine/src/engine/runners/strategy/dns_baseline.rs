mod overrides;

use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::classification::classified_failure_probe_result;
use crate::observations::observations_for_results;
use crate::strategy::detect_strategy_probe_dns_tampering_with_context_and_cancellation;
use crate::types::ProbeResult;
use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

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
        let stop = || runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline();
        let deadline = [runtime.stage_deadline(), runtime.scan_deadline()].into_iter().flatten().min();
        let baseline = with_scan_io_deadline(deadline, || {
            detect_strategy_probe_dns_tampering_with_context_and_cancellation(
                &plan.request.domain_targets,
                strategy_plan.runtime_context.as_ref(),
                &plan.probe_context,
                stop,
            )
        });
        let interrupted = stop();
        let mut results = Vec::new();
        if let Some(baseline) = baseline {
            if let Some(failure) = baseline.failure {
                results.push(classified_failure_probe_result("Current strategy", &failure));
                runtime.strategy.baseline_failure = Some(failure);
            }
            overrides::record_dns_override_targets(plan, runtime, &baseline.encrypted_ip_overrides);
            results.extend(baseline.results);
        }
        if interrupted {
            // The current stage remains incomplete. Keep earlier targets without consuming its planned step.
            runtime.observations.extend(observations_for_results(&results));
            runtime.results.extend(results);
            return RunnerOutcome::Cancelled;
        }
        if results.is_empty() {
            results.push(ProbeResult {
                probe_type: "dns_integrity".to_string(),
                target: "Strategy DNS baseline".to_string(),
                outcome: "dns_oracle_unavailable".to_string(),
                details: Vec::new(),
            });
        }
        let (outcome, message, level) = super::dns_baseline_status::baseline_status(runtime, &results);
        runtime.record_step(
            plan,
            self.phase(),
            message.to_string(),
            Some("dns_baseline".to_string()),
            Some(outcome.to_string()),
            None,
            RunnerArtifacts::from_results(results, "strategy_probe", level, message.to_string()),
        );
        RunnerOutcome::Completed
    }
}
