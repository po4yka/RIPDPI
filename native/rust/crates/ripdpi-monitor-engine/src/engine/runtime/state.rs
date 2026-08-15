use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

mod strategy_state;

use crate::types::{ProbeObservation, ProbeResult, ScanReport, SharedState};
use ripdpi_diagnostics_contracts::types::ExecutionStageSnapshot;

use strategy_state::StrategyExecutionState;

pub(in crate::engine) struct ExecutionRuntime {
    pub(in crate::engine) shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub(in crate::engine) completed_steps: usize,
    pub(in crate::engine) results: Vec<ProbeResult>,
    pub(in crate::engine) observations: Vec<ProbeObservation>,
    pub(in crate::engine) final_report: Option<ScanReport>,
    pub(in crate::engine) strategy: StrategyExecutionState,
    pub(in crate::engine) scan_deadline: Option<std::time::Instant>,
    stage_deadline: Option<std::time::Instant>,
    active_stage: Option<usize>,
    stage_executions: Vec<ExecutionStageSnapshot>,
}

impl ExecutionRuntime {
    pub(in crate::engine) fn new(shared: Arc<Mutex<SharedState>>, cancel: Arc<AtomicBool>) -> Self {
        Self {
            shared,
            cancel,
            completed_steps: 0,
            results: Vec::new(),
            observations: Vec::new(),
            final_report: None,
            strategy: StrategyExecutionState::default(),
            scan_deadline: None,
            stage_deadline: None,
            active_stage: None,
            stage_executions: Vec::new(),
        }
    }

    pub(in crate::engine) fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    pub(in crate::engine) fn cancel_token(&self) -> &AtomicBool {
        &self.cancel
    }

    pub(in crate::engine) fn set_scan_deadline(&mut self, deadline: std::time::Instant) {
        self.scan_deadline = Some(deadline);
    }

    pub(in crate::engine) fn is_past_deadline(&self) -> bool {
        self.scan_deadline.is_some_and(|d| std::time::Instant::now() >= d)
    }

    pub(in crate::engine) fn scan_deadline(&self) -> Option<std::time::Instant> {
        self.scan_deadline
    }

    pub(in crate::engine) fn set_stage_deadline(&mut self, deadline: Option<std::time::Instant>) {
        self.stage_deadline = deadline;
    }

    pub(in crate::engine) fn stage_deadline(&self) -> Option<std::time::Instant> {
        self.stage_deadline
    }

    pub(in crate::engine) fn is_past_stage_deadline(&self) -> bool {
        self.stage_deadline.is_some_and(|deadline| std::time::Instant::now() >= deadline)
    }

    pub(in crate::engine) fn begin_stage(&mut self, stage_id: &str, planned_steps: usize) {
        self.stage_executions.push(ExecutionStageSnapshot {
            stage_id: stage_id.to_string(),
            planned_steps,
            executed_steps: 0,
            skipped_by_stage_budget_steps: 0,
            skipped_by_global_deadline_steps: 0,
        });
        self.active_stage = Some(self.stage_executions.len() - 1);
    }

    pub(in crate::engine) fn finish_stage(&mut self) {
        self.active_stage = None;
    }

    pub(in crate::engine) fn record_stage_step(&mut self, skipped_by_stage_budget: bool) {
        if let Some(index) = self.active_stage
            && let Some(stage) = self.stage_executions.get_mut(index)
        {
            if skipped_by_stage_budget {
                stage.skipped_by_stage_budget_steps += 1;
            } else {
                stage.executed_steps += 1;
            }
        }
    }

    pub(in crate::engine) fn replace_last_executed_stage_step_with_budget_skip(&mut self) {
        if let Some(index) = self.active_stage
            && let Some(stage) = self.stage_executions.get_mut(index)
        {
            stage.executed_steps = stage.executed_steps.saturating_sub(1);
            stage.skipped_by_stage_budget_steps += 1;
        }
    }

    pub(in crate::engine) fn stage_executions(&self) -> Vec<ExecutionStageSnapshot> {
        self.stage_executions.clone()
    }

    pub(in crate::engine) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}
