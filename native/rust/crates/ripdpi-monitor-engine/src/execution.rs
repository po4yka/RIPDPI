mod lanes;
mod runner_contract;
mod runtime;
mod scoring;

pub(crate) use lanes::DefaultStrategyLaneExecutor;
pub(crate) use runner_contract::StrategyLaneExecutor;
pub(crate) use runtime::CandidateRuntimeSupervisor;
pub use runtime::{
    CandidateCleanupReceipt, CandidateDesyncExecutionDisposition, CandidateDesyncExecutionReceipt,
    CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeExecutionEvidence, CandidateRuntimeLauncher,
    CandidateRuntimeTerminalReceipt, CandidateRuntimeTerminalStatus, PreparedCandidateRuntime,
    UnavailableCandidateRuntimeLauncher,
};
pub(super) use scoring::{
    CandidateExecution, eliminated_candidate_summary, not_applicable_candidate_execution, skipped_candidate_summary,
    winning_candidate_index_with,
};

#[cfg(test)]
pub(super) use runtime::freeze_adaptive_fake_ttl_for_probe;
