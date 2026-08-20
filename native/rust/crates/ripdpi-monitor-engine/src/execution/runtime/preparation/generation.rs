use std::sync::atomic::{AtomicU64, Ordering};

use super::super::contracts::CandidateRuntimeError;

static NEXT_CANDIDATE_GENERATION: AtomicU64 = AtomicU64::new(1);

pub(super) fn next_candidate_generation() -> Result<u64, CandidateRuntimeError> {
    // Relaxed is sufficient: this counter supplies identity only; runtime publication and joins provide ordering.
    NEXT_CANDIDATE_GENERATION
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |current| current.checked_add(1).filter(|next| *next != 0))
        .map_err(|_| CandidateRuntimeError::Preparation("candidate generation space exhausted".to_string()))
}
