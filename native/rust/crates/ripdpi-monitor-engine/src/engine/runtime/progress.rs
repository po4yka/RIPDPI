use crate::connectivity::{push_event, set_progress};
use crate::types::{ProbeResult, ScanProgress, StrategyProbeLiveProgress, StrategyProbeProgressLane};

use super::artifacts::RunnerArtifacts;
use super::plan::ExecutionPlan;
use super::state::ExecutionRuntime;

impl ExecutionRuntime {
    pub(in crate::engine) fn record_stage_budget_skips(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        stage_id: &str,
        count: usize,
    ) {
        for skipped_index in 1..=count {
            let target = format!("{stage_id} stage budget ({skipped_index}/{count})");
            self.record_step(
                plan,
                phase,
                format!("Skipped {target}: stage budget exhausted"),
                Some(target.clone()),
                Some("skipped_by_stage_budget".to_string()),
                None,
                RunnerArtifacts::from_results(
                    vec![ProbeResult {
                        probe_type: stage_id.to_string(),
                        target,
                        outcome: "skipped_by_stage_budget".to_string(),
                        details: Vec::new(),
                    }],
                    "execution_coordinator",
                    "info",
                    "Stage budget exhausted; continuing with the next configured family".to_string(),
                ),
            );
            self.replace_last_executed_stage_step_with_budget_skip();
        }
    }

    pub(in crate::engine) fn publish_progress(
        &self,
        plan: &ExecutionPlan,
        phase: &str,
        completed_steps: usize,
        message: String,
        latest_probe_target: Option<String>,
        latest_probe_outcome: Option<String>,
        strategy_probe_progress: Option<StrategyProbeLiveProgress>,
    ) {
        set_progress(
            &self.shared,
            ScanProgress {
                session_id: plan.session_id.clone(),
                phase: phase.to_string(),
                completed_steps,
                total_steps: plan.total_steps,
                message,
                is_finished: false,
                latest_probe_target,
                latest_probe_outcome,
                strategy_probe_progress,
            },
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub(in crate::engine) fn publish_strategy_probe_candidate_started(
        &self,
        plan: &ExecutionPlan,
        phase: &str,
        lane: StrategyProbeProgressLane,
        candidate_index: usize,
        candidate_total: usize,
        candidate_id: &str,
        candidate_label: &str,
        message: String,
    ) {
        let progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
            succeeded_targets: 0,
            total_targets: 0,
        };
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            Some(candidate_label.to_string()),
            None,
            Some(progress),
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub(in crate::engine) fn record_step(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        message: String,
        latest_probe_target: Option<String>,
        latest_probe_outcome: Option<String>,
        strategy_probe_progress: Option<StrategyProbeLiveProgress>,
        artifacts: RunnerArtifacts,
    ) {
        self.results.extend(artifacts.probe_results);
        self.observations.extend(artifacts.observations);
        for event in artifacts.events {
            push_event(
                &self.shared,
                &plan.session_id,
                &plan.request.profile_id,
                &plan.request.path_mode,
                &event.source,
                &event.level,
                event.message,
            );
        }
        self.completed_steps += 1;
        self.record_stage_step(false);
        self.publish_progress(
            plan,
            phase,
            self.completed_steps,
            message,
            latest_probe_target,
            latest_probe_outcome,
            strategy_probe_progress,
        );
    }

    #[allow(clippy::too_many_arguments)]
    pub(in crate::engine) fn record_skipped_strategy_probe_candidate(
        &mut self,
        plan: &ExecutionPlan,
        phase: &str,
        lane: StrategyProbeProgressLane,
        candidate_index: usize,
        candidate_total: usize,
        candidate_id: &str,
        candidate_label: &str,
        latest_probe_outcome: Option<String>,
        message: String,
    ) {
        let progress = StrategyProbeLiveProgress {
            lane,
            candidate_index,
            candidate_total,
            candidate_id: candidate_id.to_string(),
            candidate_label: candidate_label.to_string(),
            succeeded_targets: 0,
            total_targets: 0,
        };
        self.record_step(
            plan,
            phase,
            message,
            Some(candidate_label.to_string()),
            latest_probe_outcome,
            Some(progress),
            RunnerArtifacts::empty(),
        );
    }
}
