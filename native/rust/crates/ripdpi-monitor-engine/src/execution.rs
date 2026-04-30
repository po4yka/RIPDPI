mod lanes;
mod runtime;
mod scoring;

pub(super) use lanes::{execute_quic_candidate, execute_tcp_candidate};
pub(super) use scoring::{
    eliminated_candidate_summary, not_applicable_candidate_execution, skipped_candidate_summary,
    winning_candidate_index, CandidateExecution,
};

#[cfg(test)]
pub(super) use runtime::freeze_adaptive_fake_ttl_for_probe;
