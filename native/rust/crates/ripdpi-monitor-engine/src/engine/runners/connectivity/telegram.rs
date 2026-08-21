use std::sync::Arc;

use rustls::client::danger::ServerCertVerifier;

use crate::engine::runtime::{ExecutionPlan, ExecutionRuntime, ExecutionStageId, ExecutionStageRunner, RunnerOutcome};

use super::telegram_record::record_telegram_probe;

const PHASE: &str = "telegram";
#[cfg(test)]
pub(super) const PHASE_TEST: &str = PHASE;

pub(in crate::engine::runners) struct TelegramRunner;

impl ExecutionStageRunner for TelegramRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::Telegram
    }

    fn phase(&self) -> &'static str {
        PHASE
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
        if runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline() {
            return RunnerOutcome::Cancelled;
        }
        record_telegram_probe(plan, runtime, target, self.phase());
        if runtime.is_cancelled() || runtime.is_past_deadline() || runtime.is_past_stage_deadline() {
            RunnerOutcome::Cancelled
        } else {
            RunnerOutcome::Completed
        }
    }
}
