#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeShutdownMode {
    CleanShutdown,
    ForcedAbort,
    AlreadyJoined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeWorkerOutcome {
    Clean,
    RuntimeFailed,
    RuntimePanicked,
    AlreadyJoined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum CandidateRuntimeTerminalStatus {
    CleanShutdown,
    ForcedAbort,
    RuntimeFailed,
    RuntimePanicked,
    AlreadyJoined,
}

impl CandidateRuntimeTerminalStatus {
    pub(super) fn from_modes(
        shutdown_mode: CandidateRuntimeShutdownMode,
        worker_outcome: CandidateRuntimeWorkerOutcome,
    ) -> Self {
        match worker_outcome {
            CandidateRuntimeWorkerOutcome::RuntimePanicked => Self::RuntimePanicked,
            CandidateRuntimeWorkerOutcome::RuntimeFailed => Self::RuntimeFailed,
            CandidateRuntimeWorkerOutcome::AlreadyJoined => Self::AlreadyJoined,
            CandidateRuntimeWorkerOutcome::Clean => match shutdown_mode {
                CandidateRuntimeShutdownMode::ForcedAbort => Self::ForcedAbort,
                CandidateRuntimeShutdownMode::AlreadyJoined => Self::AlreadyJoined,
                CandidateRuntimeShutdownMode::CleanShutdown => Self::CleanShutdown,
            },
        }
    }
}
