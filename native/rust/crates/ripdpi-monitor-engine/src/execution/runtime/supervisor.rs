use std::sync::Mutex;

use super::{
    CandidateCleanupReceipt, CandidateProbeRuntime, CandidateRuntimeExecutionEvidence, CandidateRuntimeShutdownMode,
    CandidateRuntimeTerminalReceipt, CandidateRuntimeWorkerOutcome,
};

const CANDIDATE_EXECUTION_EVIDENCE_LIMIT: usize = 32;

pub(crate) struct CandidateRuntimeSupervisor {
    state: Mutex<CandidateRuntimeSupervisorState>,
}

impl Default for CandidateRuntimeSupervisor {
    fn default() -> Self {
        Self { state: Mutex::new(CandidateRuntimeSupervisorState::empty()) }
    }
}

impl CandidateRuntimeSupervisor {
    pub(crate) fn supervise<'a>(&'a self, runtime: Box<dyn CandidateProbeRuntime>) -> CandidateRuntimeLease<'a> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).active += 1;
        CandidateRuntimeLease { supervisor: self, runtime: Some(runtime) }
    }

    pub(crate) fn record(&self, receipt: CandidateRuntimeTerminalReceipt) {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).record(receipt);
    }

    /// Returns the aggregate only after every registered candidate has been
    /// shut down and joined.  A terminal event must not be published before
    /// this barrier succeeds.
    pub(crate) fn terminal_receipt(&self) -> Option<CandidateRuntimeTerminalReceipt> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).terminal_receipt()
    }
}

struct CandidateRuntimeSupervisorState {
    cleanup: CandidateCleanupReceipt,
    shutdown_mode: CandidateRuntimeShutdownMode,
    worker_outcome: CandidateRuntimeWorkerOutcome,
    execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    execution_evidence_overflowed: bool,
    active: usize,
}

impl CandidateRuntimeSupervisorState {
    fn empty() -> Self {
        Self {
            cleanup: CandidateCleanupReceipt::default(),
            shutdown_mode: CandidateRuntimeShutdownMode::CleanShutdown,
            worker_outcome: CandidateRuntimeWorkerOutcome::Clean,
            execution_evidence: Vec::new(),
            execution_evidence_overflowed: false,
            active: 0,
        }
    }

    fn record(&mut self, receipt: CandidateRuntimeTerminalReceipt) {
        let cleanup = receipt.cleanup();
        self.cleanup.started += cleanup.started;
        self.cleanup.stopped += cleanup.stopped;
        self.cleanup.joined += cleanup.joined;
        self.cleanup.forced_abort += cleanup.forced_abort;
        self.shutdown_mode = merge_shutdown_mode(self.shutdown_mode, receipt.shutdown_mode());
        self.worker_outcome = merge_worker_outcome(self.worker_outcome, receipt.worker_outcome());
        self.execution_evidence_overflowed |= receipt.execution_evidence_overflowed();
        for evidence in receipt.execution_evidence() {
            if self.execution_evidence.len() >= CANDIDATE_EXECUTION_EVIDENCE_LIMIT {
                self.execution_evidence_overflowed = true;
                break;
            }
            self.execution_evidence.push(evidence.clone());
        }
    }

    fn receipt(&self) -> CandidateRuntimeTerminalReceipt {
        CandidateRuntimeTerminalReceipt::aggregate(
            self.cleanup,
            self.shutdown_mode,
            self.worker_outcome,
            self.execution_evidence.clone(),
            self.execution_evidence_overflowed,
        )
    }

    fn terminal_receipt(&self) -> Option<CandidateRuntimeTerminalReceipt> {
        if self.active != 0 {
            return None;
        }
        if matches!(self.shutdown_mode, CandidateRuntimeShutdownMode::AlreadyJoined)
            || matches!(self.worker_outcome, CandidateRuntimeWorkerOutcome::AlreadyJoined)
        {
            return None;
        }
        (self.cleanup.started == self.cleanup.stopped && self.cleanup.stopped == self.cleanup.joined)
            .then(|| self.receipt())
    }
}

fn merge_shutdown_mode(
    current: CandidateRuntimeShutdownMode,
    next: CandidateRuntimeShutdownMode,
) -> CandidateRuntimeShutdownMode {
    match (current, next) {
        (CandidateRuntimeShutdownMode::ForcedAbort, _) | (_, CandidateRuntimeShutdownMode::ForcedAbort) => {
            CandidateRuntimeShutdownMode::ForcedAbort
        }
        (CandidateRuntimeShutdownMode::AlreadyJoined, _) | (_, CandidateRuntimeShutdownMode::AlreadyJoined) => {
            CandidateRuntimeShutdownMode::AlreadyJoined
        }
        _ => CandidateRuntimeShutdownMode::CleanShutdown,
    }
}

fn merge_worker_outcome(
    current: CandidateRuntimeWorkerOutcome,
    next: CandidateRuntimeWorkerOutcome,
) -> CandidateRuntimeWorkerOutcome {
    match (current, next) {
        (CandidateRuntimeWorkerOutcome::RuntimePanicked, _) | (_, CandidateRuntimeWorkerOutcome::RuntimePanicked) => {
            CandidateRuntimeWorkerOutcome::RuntimePanicked
        }
        (CandidateRuntimeWorkerOutcome::RuntimeFailed, _) | (_, CandidateRuntimeWorkerOutcome::RuntimeFailed) => {
            CandidateRuntimeWorkerOutcome::RuntimeFailed
        }
        (CandidateRuntimeWorkerOutcome::AlreadyJoined, _) | (_, CandidateRuntimeWorkerOutcome::AlreadyJoined) => {
            CandidateRuntimeWorkerOutcome::AlreadyJoined
        }
        _ => CandidateRuntimeWorkerOutcome::Clean,
    }
}

/// Session-owned lifecycle lease for a launched candidate runtime.
///
/// Its `Drop` path is deliberately fail-closed: a panic or an early return
/// requests cancellation and performs the same forced-abort-and-join cleanup
/// as an explicit terminal barrier.
pub(crate) struct CandidateRuntimeLease<'a> {
    supervisor: &'a CandidateRuntimeSupervisor,
    runtime: Option<Box<dyn CandidateProbeRuntime>>,
}

impl CandidateRuntimeLease<'_> {
    pub(crate) fn runtime(&self) -> &dyn CandidateProbeRuntime {
        // Infallible: a lease exposes its runtime only before shutdown consumes it.
        self.runtime.as_deref().expect("candidate runtime lease must be live")
    }

    pub(crate) fn shutdown(mut self) -> CandidateRuntimeTerminalReceipt {
        // Infallible: shutdown takes ownership of a still-live lease.
        let receipt = self.runtime.take().expect("candidate runtime lease must be live").shutdown();
        self.finish(receipt.clone());
        receipt
    }

    fn finish(&mut self, receipt: CandidateRuntimeTerminalReceipt) {
        self.supervisor.record(receipt);
        let mut state = self.supervisor.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // Infallible: each supervised lease increments once and finishes once.
        state.active = state.active.checked_sub(1).expect("candidate runtime lease active count underflow");
    }
}

impl Drop for CandidateRuntimeLease<'_> {
    fn drop(&mut self) {
        if let Some(mut runtime) = self.runtime.take() {
            runtime.request_shutdown();
            let receipt = runtime.force_abort_and_join();
            self.finish(receipt);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;
    use crate::transport::TransportConfig;
    use crate::{CandidateCleanupReceipt, CandidateRuntimeTerminalStatus};

    struct TrackingRuntime {
        shutdowns: Arc<AtomicUsize>,
        forced_aborts: Arc<AtomicUsize>,
    }

    impl CandidateProbeRuntime for TrackingRuntime {
        fn transport(&self) -> TransportConfig {
            TransportConfig::Direct { route_experiment: None }
        }

        fn generation(&self) -> u64 {
            1
        }

        fn request_shutdown(&mut self) {}

        fn force_abort_and_join(&mut self) -> CandidateRuntimeTerminalReceipt {
            self.forced_aborts.fetch_add(1, Ordering::SeqCst);
            CandidateRuntimeTerminalReceipt::forced_abort(
                1,
                CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 },
                Vec::new(),
            )
            .expect("valid forced receipt")
        }

        fn shutdown(self: Box<Self>) -> CandidateRuntimeTerminalReceipt {
            self.shutdowns.fetch_add(1, Ordering::SeqCst);
            CandidateRuntimeTerminalReceipt::clean_shutdown(
                1,
                CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 },
                Vec::new(),
            )
            .expect("valid clean receipt")
        }
    }

    fn runtime(shutdowns: Arc<AtomicUsize>, forced_aborts: Arc<AtomicUsize>) -> Box<dyn CandidateProbeRuntime> {
        Box::new(TrackingRuntime { shutdowns, forced_aborts })
    }

    #[test]
    fn parallel_candidate_cleanup_receipts_leave_no_live_runtime_before_terminal_barrier() {
        let supervisor = CandidateRuntimeSupervisor::default();
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let forced_aborts = Arc::new(AtomicUsize::new(0));
        let first = supervisor.supervise(runtime(shutdowns.clone(), forced_aborts.clone()));
        let second = supervisor.supervise(runtime(shutdowns.clone(), forced_aborts));

        assert!(supervisor.terminal_receipt().is_none(), "two candidates are still live");
        first.shutdown();
        assert!(supervisor.terminal_receipt().is_none(), "one candidate remains live");
        second.shutdown();

        assert_eq!(
            supervisor.terminal_receipt().map(|receipt| receipt.cleanup()),
            Some(CandidateCleanupReceipt { started: 2, stopped: 2, joined: 2, forced_abort: 0 })
        );
        assert_eq!(shutdowns.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn deadline_and_external_cancel_share_the_terminal_barrier() {
        let make_cleanup = || {
            let supervisor = CandidateRuntimeSupervisor::default();
            let shutdowns = Arc::new(AtomicUsize::new(0));
            let forced_aborts = Arc::new(AtomicUsize::new(0));
            drop(supervisor.supervise(runtime(shutdowns, forced_aborts)));
            supervisor.terminal_receipt().expect("cancellation cleanup barrier")
        };

        let deadline = make_cleanup().cleanup();
        let external_cancel = make_cleanup();

        assert_eq!(deadline, external_cancel.cleanup());
        assert_eq!(deadline, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(external_cancel.terminal_status(), CandidateRuntimeTerminalStatus::ForcedAbort);
    }

    #[test]
    fn terminal_barrier_waits_for_blocking_candidate_cleanup_before_finished() {
        let supervisor = CandidateRuntimeSupervisor::default();
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let forced_aborts = Arc::new(AtomicUsize::new(0));
        let lease = supervisor.supervise(runtime(shutdowns.clone(), forced_aborts));

        assert!(supervisor.terminal_receipt().is_none(), "finished is forbidden while candidate is live");
        lease.shutdown();

        assert_eq!(shutdowns.load(Ordering::SeqCst), 1);
        assert_eq!(supervisor.terminal_receipt().expect("joined barrier").cleanup().joined, 1);
    }

    #[test]
    fn panic_or_external_cancellation_uses_forced_abort_barrier() {
        let supervisor = CandidateRuntimeSupervisor::default();
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let forced_aborts = Arc::new(AtomicUsize::new(0));
        let lease = supervisor.supervise(runtime(shutdowns, forced_aborts.clone()));

        drop(lease);

        let receipt = supervisor.terminal_receipt().expect("forced cleanup joined");
        assert_eq!(forced_aborts.load(Ordering::SeqCst), 1);
        assert_eq!(receipt.cleanup(), CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::ForcedAbort);
    }

    #[test]
    fn next_scan_gets_an_empty_supervisor_and_no_inherited_runtime() {
        let prior = CandidateRuntimeSupervisor::default();
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let forced_aborts = Arc::new(AtomicUsize::new(0));
        prior.supervise(runtime(shutdowns, forced_aborts)).shutdown();

        let next = CandidateRuntimeSupervisor::default();

        assert_eq!(
            next.terminal_receipt(),
            Some(CandidateRuntimeTerminalReceipt::aggregate(
                CandidateCleanupReceipt::default(),
                CandidateRuntimeShutdownMode::CleanShutdown,
                CandidateRuntimeWorkerOutcome::Clean,
                Vec::new(),
                false,
            ))
        );
    }

    #[test]
    fn terminal_receipt_constructor_rejects_incomplete_cleanup() {
        assert_eq!(
            CandidateRuntimeTerminalReceipt::forced_abort(
                1,
                CandidateCleanupReceipt { started: 1, stopped: 1, joined: 0, forced_abort: 1 },
                Vec::new(),
            ),
            None,
        );
    }

    #[test]
    fn terminal_barrier_rejects_already_joined_receipt() {
        let supervisor = CandidateRuntimeSupervisor::default();
        supervisor.record(CandidateRuntimeTerminalReceipt::already_joined());

        assert_eq!(supervisor.terminal_receipt(), None);
    }
}
