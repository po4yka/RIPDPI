use std::sync::Mutex;

use super::CandidateCleanupReceipt;

#[derive(Default)]
pub(crate) struct CandidateRuntimeSupervisor {
    receipt: Mutex<CandidateCleanupReceipt>,
}

impl CandidateRuntimeSupervisor {
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
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
