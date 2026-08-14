mod domain_outcomes;
mod notes;
mod score_state;
mod serialization;
mod summary;
mod winner_selection;

use crate::candidates::StrategyCandidateSpec;

pub use score_state::{CandidateScore, ProbeSample};
pub use summary::{
    CandidateExecution, build_candidate_execution, eliminated_candidate_summary, failed_candidate_execution,
    not_applicable_candidate_execution, skipped_candidate_summary,
};
#[cfg(test)]
pub use winner_selection::winning_candidate_index;
pub use winner_selection::winning_candidate_index_with;

pub(in crate::execution) use summary::cancelled_candidate_execution;

pub fn candidate_proxy_config_json(spec: &StrategyCandidateSpec) -> Option<String> {
    serialization::candidate_proxy_config_json(spec)
}

pub fn candidate_notes(spec: &StrategyCandidateSpec, extra_notes: &[&str]) -> Vec<String> {
    notes::candidate_notes(spec, extra_notes)
}

#[cfg(test)]
mod tests;
