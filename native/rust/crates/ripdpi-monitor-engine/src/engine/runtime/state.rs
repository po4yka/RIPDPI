use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

mod strategy_state;

use crate::types::{ProbeObservation, ProbeResult, ScanReport, SharedState};
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
    pub(super) stage_deadline: Option<std::time::Instant>,
    pub(super) active_stage: Option<usize>,
    pub(super) stage_executions: Vec<ripdpi_diagnostics_contracts::types::ExecutionStageSnapshot>,
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

    pub(in crate::engine) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}
