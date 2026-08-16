use std::sync::Mutex;

use super::{CandidateProbeRuntime, CandidateRuntimeTerminalReceipt, CandidateRuntimeTerminalStatus};

#[derive(Default)]
pub(crate) struct CandidateRuntimeSupervisor {
    receipt: Mutex<CandidateRuntimeTerminalReceipt>,
    active: Mutex<usize>,
}

impl CandidateRuntimeSupervisor {
    pub(crate) fn supervise<'a>(&'a self, runtime: Box<dyn CandidateProbeRuntime>) -> CandidateRuntimeLease<'a> {
        *self.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner) += 1;
        CandidateRuntimeLease { supervisor: self, runtime: Some(runtime) }
    }

    pub(crate) fn record(&self, receipt: CandidateRuntimeTerminalReceipt) {
        let mut total = self.receipt.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        total.cleanup.started += receipt.cleanup.started;
        total.cleanup.stopped += receipt.cleanup.stopped;
        total.cleanup.joined += receipt.cleanup.joined;
        total.cleanup.forced_abort += receipt.cleanup.forced_abort;
        total.terminal_status = merge_terminal_status(total.terminal_status, receipt.terminal_status);
        total.execution_evidence.extend(receipt.execution_evidence);
    }

    pub(crate) fn receipt(&self) -> CandidateRuntimeTerminalReceipt {
        self.receipt.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
    }

    /// Returns the aggregate only after every registered candidate has been
    /// shut down and joined.  A terminal event must not be published before
    /// this barrier succeeds.
    pub(crate) fn terminal_receipt(&self) -> Option<CandidateRuntimeTerminalReceipt> {
        if *self.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner) != 0 {
            return None;
        }
        let receipt = self.receipt();
        (receipt.cleanup.started == receipt.cleanup.stopped && receipt.cleanup.stopped == receipt.cleanup.joined)
            .then_some(receipt)
    }
}

fn merge_terminal_status(
    current: CandidateRuntimeTerminalStatus,
    next: CandidateRuntimeTerminalStatus,
) -> CandidateRuntimeTerminalStatus {
    use CandidateRuntimeTerminalStatus::{AlreadyJoined, CleanShutdown, ForcedAbort, RuntimeFailed, RuntimePanicked};

    match (current, next) {
        (RuntimePanicked, _) | (_, RuntimePanicked) => RuntimePanicked,
        (RuntimeFailed, _) | (_, RuntimeFailed) => RuntimeFailed,
        (ForcedAbort, _) | (_, ForcedAbort) => ForcedAbort,
        (CleanShutdown, status) => status,
        (status, CleanShutdown) => status,
        (AlreadyJoined, AlreadyJoined) => AlreadyJoined,
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
        let mut active = self.supervisor.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // Infallible: each supervised lease increments once and finishes once.
        *active = active.checked_sub(1).expect("candidate runtime lease active count underflow");
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
    use crate::CandidateCleanupReceipt;
    use crate::transport::TransportConfig;

    struct TrackingRuntime {
        shutdowns: Arc<AtomicUsize>,
        forced_aborts: Arc<AtomicUsize>,
    }

    impl CandidateProbeRuntime for TrackingRuntime {
        fn transport(&self) -> TransportConfig {
            TransportConfig::Direct { route_experiment: None }
        }

        fn request_shutdown(&mut self) {}

        fn force_abort_and_join(&mut self) -> CandidateRuntimeTerminalReceipt {
            self.forced_aborts.fetch_add(1, Ordering::SeqCst);
            CandidateRuntimeTerminalReceipt::forced_abort(CandidateCleanupReceipt {
                started: 1,
                stopped: 1,
                joined: 1,
                forced_abort: 1,
            })
        }

        fn shutdown(self: Box<Self>) -> CandidateRuntimeTerminalReceipt {
            self.shutdowns.fetch_add(1, Ordering::SeqCst);
            CandidateRuntimeTerminalReceipt::clean_shutdown(CandidateCleanupReceipt {
                started: 1,
                stopped: 1,
                joined: 1,
                forced_abort: 0,
            })
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
            supervisor.terminal_receipt().map(|receipt| receipt.cleanup),
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

        let deadline = make_cleanup().cleanup;
        let external_cancel = make_cleanup();

        assert_eq!(deadline, external_cancel.cleanup);
        assert_eq!(deadline, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(external_cancel.terminal_status, CandidateRuntimeTerminalStatus::ForcedAbort);
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
        assert_eq!(supervisor.terminal_receipt().expect("joined barrier").cleanup.joined, 1);
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
        assert_eq!(receipt.cleanup, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
        assert_eq!(receipt.terminal_status, CandidateRuntimeTerminalStatus::ForcedAbort);
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
            Some(CandidateRuntimeTerminalReceipt::clean_shutdown(CandidateCleanupReceipt::default()))
        );
    }

    #[test]
    fn terminal_barrier_rejects_incomplete_cleanup_receipt() {
        let supervisor = CandidateRuntimeSupervisor::default();
        supervisor.record(CandidateRuntimeTerminalReceipt::forced_abort(CandidateCleanupReceipt {
            started: 1,
            stopped: 1,
            joined: 0,
            forced_abort: 1,
        }));

        assert_eq!(supervisor.terminal_receipt(), None);
    }
}
