mod builders;
mod execution;
mod features;
mod synthetic;

pub(in crate::execution) use builders::cancelled_candidate_execution;
pub use builders::{build_candidate_execution, failed_candidate_execution, not_applicable_candidate_execution};
pub use execution::CandidateExecution;
pub use synthetic::{eliminated_candidate_summary, skipped_candidate_summary};
