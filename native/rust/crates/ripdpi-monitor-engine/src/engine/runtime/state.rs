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

    pub(in crate::engine) fn set_scan_deadline(&mut self, deadline: std::time::Instant) {
        self.scan_deadline = Some(deadline);
    }

    pub(in crate::engine) fn begin_stage_budget(&mut self, remaining_stages: usize) {
        let Some(scan_deadline) = self.scan_deadline else {
            self.stage_deadline = None;
            return;
        };
        if remaining_stages <= 1 {
            self.stage_deadline = None;
            return;
        }

        let now = std::time::Instant::now();
        let remaining = scan_deadline.saturating_duration_since(now);
        let divisor = u32::try_from(remaining_stages).unwrap_or(u32::MAX);
        self.stage_deadline = Some(now + remaining / divisor);
    }

    pub(in crate::engine) fn clear_stage_budget(&mut self) {
        self.stage_deadline = None;
    }

    pub(in crate::engine) fn is_past_deadline(&self) -> bool {
        self.scan_deadline().is_some_and(|deadline| std::time::Instant::now() >= deadline)
    }

    pub(in crate::engine) fn is_past_scan_deadline(&self) -> bool {
        self.scan_deadline.is_some_and(|deadline| std::time::Instant::now() >= deadline)
    }

    pub(in crate::engine) fn scan_deadline(&self) -> Option<std::time::Instant> {
        match (self.scan_deadline, self.stage_deadline) {
            (Some(scan), Some(stage)) => Some(scan.min(stage)),
            (scan, None) => scan,
            (None, stage) => stage,
        }
    }

    pub(in crate::engine) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}
