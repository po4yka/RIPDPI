use std::sync::Mutex;
use std::time::Duration;

use super::{CandidateCleanupReceipt, CandidateProbeRuntime};

#[derive(Default)]
pub(crate) struct CandidateRuntimeSupervisor {
    receipt: Mutex<CandidateCleanupReceipt>,
    active: Mutex<usize>,
}

impl CandidateRuntimeSupervisor {
    pub(crate) fn supervise<'a>(&'a self, runtime: Box<dyn CandidateProbeRuntime>) -> CandidateRuntimeLease<'a> {
        *self.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner) += 1;
        CandidateRuntimeLease { supervisor: self, runtime: Some(runtime) }
    }

    pub(crate) fn record(&self, receipt: CandidateCleanupReceipt) {
        let mut total = self.receipt.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        total.started += receipt.started;
        total.stopped += receipt.stopped;
        total.joined += receipt.joined;
        total.forced_abort += receipt.forced_abort;
    }

    pub(crate) fn receipt(&self) -> CandidateCleanupReceipt {
        *self.receipt.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Returns the aggregate only after every registered candidate has been
    /// shut down and joined.  A terminal event must not be published before
    /// this barrier succeeds.
    pub(crate) fn terminal_receipt(&self) -> Option<CandidateCleanupReceipt> {
        (*self.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner) == 0).then(|| self.receipt())
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
        self.runtime.as_deref().expect("candidate runtime lease must be live")
    }

    pub(crate) fn shutdown(mut self) -> CandidateCleanupReceipt {
        let receipt = self.runtime.take().expect("candidate runtime lease must be live").shutdown();
        self.finish(receipt);
        receipt
    }

    fn finish(&mut self, receipt: CandidateCleanupReceipt) {
        self.supervisor.record(receipt);
        let mut active = self.supervisor.active.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *active = active.checked_sub(1).expect("candidate runtime lease active count underflow");
    }
}

impl Drop for CandidateRuntimeLease<'_> {
    fn drop(&mut self) {
        if let Some(mut runtime) = self.runtime.take() {
            runtime.request_shutdown();
            let receipt = runtime.force_abort_and_join(Duration::from_secs(1));
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

    struct TrackingRuntime {
        shutdowns: Arc<AtomicUsize>,
        forced_aborts: Arc<AtomicUsize>,
    }

    impl CandidateProbeRuntime for TrackingRuntime {
        fn transport(&self) -> TransportConfig {
            TransportConfig::Direct { route_experiment: None }
        }

        fn request_shutdown(&mut self) {}

        fn force_abort_and_join(&mut self, _grace: Duration) -> CandidateCleanupReceipt {
            self.forced_aborts.fetch_add(1, Ordering::SeqCst);
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 }
        }

        fn shutdown(self: Box<Self>) -> CandidateCleanupReceipt {
            self.shutdowns.fetch_add(1, Ordering::SeqCst);
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 }
        }
    }

    fn runtime(shutdowns: Arc<AtomicUsize>, forced_aborts: Arc<AtomicUsize>) -> Box<dyn CandidateProbeRuntime> {
        Box::new(TrackingRuntime { shutdowns, forced_aborts })
    }

    #[test]
    fn parallel_candidate_cleanup_receipts_leave_no_live_runtime_before_terminal_barrier() {
        let supervisor = CandidateRuntimeSupervisor::default();
        supervisor.record(CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 });
        supervisor.record(CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 0 });

        assert_eq!(
            supervisor.receipt(),
            CandidateCleanupReceipt { started: 2, stopped: 2, joined: 2, forced_abort: 0 }
        );
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
        assert_eq!(supervisor.terminal_receipt().expect("joined barrier").joined, 1);
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
        assert_eq!(receipt, CandidateCleanupReceipt { started: 1, stopped: 1, joined: 1, forced_abort: 1 });
    }

    #[test]
    fn next_scan_gets_an_empty_supervisor_and_no_inherited_runtime() {
        let prior = CandidateRuntimeSupervisor::default();
        let shutdowns = Arc::new(AtomicUsize::new(0));
        let forced_aborts = Arc::new(AtomicUsize::new(0));
        prior.supervise(runtime(shutdowns, forced_aborts)).shutdown();

        let next = CandidateRuntimeSupervisor::default();

        assert_eq!(next.terminal_receipt(), Some(CandidateCleanupReceipt::default()));
    }
}
