use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::{
    ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerArtifacts, RunnerOutcome,
};
use crate::telegram::run_telegram_probe;

pub(in crate::engine::runners) struct TelegramRunner;

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
