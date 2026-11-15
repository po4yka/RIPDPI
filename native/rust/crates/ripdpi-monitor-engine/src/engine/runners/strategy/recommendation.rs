use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};

mod report;

pub(in crate::engine) use report::prepare_strategy_probe_report;

pub(in crate::engine::runners) struct StrategyRecommendationRunner;

impl ExecutionStageRunner for StrategyRecommendationRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::StrategyRecommendation
    }

    fn phase(&self) -> &'static str {
        "recommendation"
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        usize::from(plan.strategy.is_some())
    }

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let ready = prepare_strategy_probe_report(plan, runtime);
        let (message, outcome) = if ready {
            ("Prepared strategy report", "ready")
        } else {
            ("No candidate evidence for strategy report", "not_applicable")
        };
        runtime.record_step(
            plan,
            self.phase(),
            message.to_string(),
            None,
            Some(outcome.to_string()),
            None,
            RunnerArtifacts::empty(),
        );
        RunnerOutcome::Completed
    }
}
