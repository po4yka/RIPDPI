use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

mod deadline;
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
    scan_deadline: Option<std::time::Instant>,
    stage_deadline: Option<std::time::Instant>,
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
        }
    }

    pub(in crate::engine) fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }

    pub(in crate::engine) fn cancel_token(&self) -> &AtomicBool {
        &self.cancel
    }

    pub(in crate::engine) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}
