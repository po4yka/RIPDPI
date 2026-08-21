use super::execution_evidence::execution_evidence_matches_generation;
use super::{
    CandidateRuntimeExecutionEvidence, CandidateRuntimeShutdownMode, CandidateRuntimeTerminalStatus,
    CandidateRuntimeWorkerOutcome,
};

pub type CandidateCleanupReceipt = crate::types::CandidateRuntimeCleanupReceipt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CandidateRuntimeTerminalReceipt {
    generation: u64,
    cleanup: CandidateCleanupReceipt,
    shutdown_mode: CandidateRuntimeShutdownMode,
    worker_outcome: CandidateRuntimeWorkerOutcome,
    execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    execution_evidence_overflowed: bool,
}

impl CandidateRuntimeTerminalReceipt {
    pub fn clean_shutdown(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_completed_cleanup(&cleanup, false)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                CandidateRuntimeShutdownMode::CleanShutdown,
                CandidateRuntimeWorkerOutcome::Clean,
                execution_evidence,
                false,
            )
        })
    }

    pub fn forced_abort(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_completed_cleanup(&cleanup, true)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| {
            Self::new(
                generation,
                cleanup,
                CandidateRuntimeShutdownMode::ForcedAbort,
                CandidateRuntimeWorkerOutcome::Clean,
                execution_evidence,
                false,
            )
        })
    }

    pub fn runtime_failed(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        Self::failed(
            generation,
            cleanup,
            shutdown_mode,
            CandidateRuntimeWorkerOutcome::RuntimeFailed,
            execution_evidence,
        )
    }

    pub fn runtime_panicked(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        Self::failed(
            generation,
            cleanup,
            shutdown_mode,
            CandidateRuntimeWorkerOutcome::RuntimePanicked,
            execution_evidence,
        )
    }

    fn failed(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
    ) -> Option<Self> {
        (generation != 0
            && valid_failed_cleanup(&cleanup, shutdown_mode)
            && execution_evidence_matches_generation(generation, &execution_evidence))
        .then(|| Self::new(generation, cleanup, shutdown_mode, worker_outcome, execution_evidence, false))
    }

    pub fn already_joined() -> Self {
        Self::new(
            0,
            CandidateCleanupReceipt::default(),
            CandidateRuntimeShutdownMode::AlreadyJoined,
            CandidateRuntimeWorkerOutcome::AlreadyJoined,
            Vec::new(),
            false,
        )
    }

    pub fn with_execution_evidence_overflowed(mut self, overflowed: bool) -> Self {
        self.execution_evidence_overflowed = overflowed;
        self
    }

    fn new(
        generation: u64,
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
        execution_evidence_overflowed: bool,
    ) -> Self {
        Self { generation, cleanup, shutdown_mode, worker_outcome, execution_evidence, execution_evidence_overflowed }
    }

    pub(crate) fn aggregate(
        cleanup: CandidateCleanupReceipt,
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
        execution_evidence: Vec<CandidateRuntimeExecutionEvidence>,
        execution_evidence_overflowed: bool,
    ) -> Self {
        Self::new(0, cleanup, shutdown_mode, worker_outcome, execution_evidence, execution_evidence_overflowed)
    }

    pub fn cleanup(&self) -> CandidateCleanupReceipt {
        self.cleanup.clone()
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn terminal_status(&self) -> CandidateRuntimeTerminalStatus {
        CandidateRuntimeTerminalStatus::from_modes(self.shutdown_mode, self.worker_outcome)
    }

    pub fn shutdown_mode(&self) -> CandidateRuntimeShutdownMode {
        self.shutdown_mode
    }

    pub fn worker_outcome(&self) -> CandidateRuntimeWorkerOutcome {
        self.worker_outcome
    }

    pub fn execution_evidence(&self) -> &[CandidateRuntimeExecutionEvidence] {
        &self.execution_evidence
    }

    pub fn execution_evidence_overflowed(&self) -> bool {
        self.execution_evidence_overflowed
    }
}

fn valid_completed_cleanup(cleanup: &CandidateCleanupReceipt, forced: bool) -> bool {
    cleanup.started > 0
        && cleanup.started == cleanup.stopped
        && if forced {
            // A bounded forced stop may detach a non-cooperating OS thread.
            // `stopped` records the requested/observed stop, while `joined`
            // remains the truthful completed-join count.
            cleanup.joined <= cleanup.stopped && cleanup.forced_abort > 0
        } else {
            cleanup.stopped == cleanup.joined && cleanup.forced_abort == 0
        }
}

fn valid_failed_cleanup(cleanup: &CandidateCleanupReceipt, shutdown_mode: CandidateRuntimeShutdownMode) -> bool {
    cleanup.started > 0
        && cleanup.joined == cleanup.started
        && match shutdown_mode {
            CandidateRuntimeShutdownMode::CleanShutdown => cleanup.forced_abort == 0,
            CandidateRuntimeShutdownMode::ForcedAbort => cleanup.forced_abort > 0,
            CandidateRuntimeShutdownMode::AlreadyJoined => false,
        }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forced_detach_is_terminal_without_claiming_a_join() {
        let receipt = CandidateRuntimeTerminalReceipt::forced_abort(
            1,
            CandidateCleanupReceipt { started: 1, stopped: 1, joined: 0, forced_abort: 1, ..Default::default() },
            Vec::new(),
        )
        .expect("forced stop is terminal even when its OS thread was detached");

        assert_eq!(receipt.cleanup().joined, 0);
        assert_eq!(receipt.terminal_status(), CandidateRuntimeTerminalStatus::ForcedAbort);
    }
}
