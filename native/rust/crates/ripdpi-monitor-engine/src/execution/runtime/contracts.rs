mod attempt_correlation;
mod desync_receipt;
mod error;
mod execution_evidence;
mod runtime_boundary;
mod terminal_receipt;
mod terminal_status;

pub use attempt_correlation::CandidateAttemptCorrelationId;
pub use desync_receipt::{CandidateDesyncExecutionDisposition, CandidateDesyncExecutionReceipt};
pub use error::CandidateRuntimeError;
pub use execution_evidence::CandidateRuntimeExecutionEvidence;
pub use runtime_boundary::{CandidateProbeRuntime, CandidateRuntimeLauncher, PreparedCandidateRuntime};
pub use terminal_receipt::{CandidateCleanupReceipt, CandidateRuntimeTerminalReceipt};
pub use terminal_status::{
    CandidateRuntimeShutdownMode, CandidateRuntimeTerminalStatus, CandidateRuntimeWorkerOutcome,
};
