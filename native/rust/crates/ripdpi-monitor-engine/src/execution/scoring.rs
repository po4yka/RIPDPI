mod attempts;
mod candidate_execution;
mod domain_outcomes;
mod notes;
mod score_accumulation;
mod score_state;
mod serialization;
mod summary;
mod winner_selection;

pub use attempts::ProbeAttemptMetadata;
pub use candidate_execution::CandidateExecution;
pub use notes::candidate_notes;
pub use score_state::{CandidateScore, ProbeSample};
pub use serialization::candidate_proxy_config_json;
pub use summary::{
    build_candidate_execution, eliminated_candidate_summary, failed_candidate_execution,
    not_applicable_candidate_execution, skipped_candidate_summary,
};
#[cfg(test)]
pub use winner_selection::winning_candidate_index;
pub use winner_selection::winning_candidate_index_with;

pub(in crate::execution) use candidate_execution::cancelled_candidate_execution;

#[cfg(test)]
mod tests;
