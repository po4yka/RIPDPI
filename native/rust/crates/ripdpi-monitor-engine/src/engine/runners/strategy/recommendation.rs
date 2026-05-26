use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::set_progress;
use crate::types::ScanProgress;

use super::super::super::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome,
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
        if !prepare_strategy_probe_report(plan, runtime) {
            return RunnerOutcome::Completed;
        }
        runtime.completed_steps += 1;
        set_progress(
            &runtime.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: self.phase().to_string(),
                completed_steps: runtime.completed_steps,
                total_steps: plan.total_steps,
                message: "Prepared strategy recommendation".to_string(),
                is_finished: false,
                latest_probe_target: None,
                latest_probe_outcome: Some("ready".to_string()),
                strategy_probe_progress: None,
            },
        );
        RunnerOutcome::Completed
    }
}
