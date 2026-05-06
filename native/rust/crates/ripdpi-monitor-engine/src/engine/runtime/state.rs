use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use ripdpi_monitor_adapter::failure::ClassifiedFailure;

use crate::types::{
    DomainTarget, ProbeObservation, ProbeResult, QuicTarget, ScanReport, SharedState, StrategyProbeCandidateSummary,
    StrategyProbeReport,
};

pub(in crate::engine) struct ExecutionRuntime {
    pub(in crate::engine) shared: Arc<Mutex<SharedState>>,
    cancel: Arc<AtomicBool>,
    pub(in crate::engine) completed_steps: usize,
    pub(in crate::engine) results: Vec<ProbeResult>,
    pub(in crate::engine) observations: Vec<ProbeObservation>,
    pub(in crate::engine) final_report: Option<ScanReport>,
    pub(in crate::engine) strategy: StrategyExecutionState,
    pub(in crate::engine) scan_deadline: Option<std::time::Instant>,
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

    pub(in crate::engine) fn finish_with_report(&mut self, report: ScanReport) {
        self.final_report = Some(report);
    }
}

#[derive(Default)]
pub(in crate::engine) struct StrategyExecutionState {
    pub(in crate::engine) baseline_failure: Option<ClassifiedFailure>,
    pub(in crate::engine) tcp_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(in crate::engine) quic_candidates: Vec<StrategyProbeCandidateSummary>,
    pub(in crate::engine) summary: Option<String>,
    pub(in crate::engine) strategy_probe_report: Option<StrategyProbeReport>,
    /// When DNS tampering is detected, holds domain targets with `connect_ip`
    /// set to encrypted-DNS-resolved addresses, bypassing poisoned system DNS.
    pub(in crate::engine) dns_override_domain_targets: Option<Vec<DomainTarget>>,
    pub(in crate::engine) dns_override_quic_targets: Option<Vec<QuicTarget>>,
}
