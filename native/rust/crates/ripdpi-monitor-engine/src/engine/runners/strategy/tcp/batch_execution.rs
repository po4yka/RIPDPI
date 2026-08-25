use crate::candidates::StrategyCandidateSpec;
use crate::classification::next_candidate_index;

use super::super::support::FamilyFailureTracker;

pub(super) const ROUND2_PARALLELISM: usize = 2;

pub(super) fn select_next_candidate_batch<'a>(
    pending_tcp_specs: &mut Vec<StrategyCandidateSpec>,
    tracker: &FamilyFailureTracker<'a>,
    recorded_candidate_count: usize,
    parallelism: usize,
) -> Vec<(usize, StrategyCandidateSpec)> {
    let mut batch = Vec::with_capacity(parallelism);
    while batch.len() < parallelism && !pending_tcp_specs.is_empty() {
        let spec = pending_tcp_specs.remove(next_candidate_index(pending_tcp_specs, tracker.blocked_family()));
        batch.push((recorded_candidate_count + batch.len() + 1, spec));
    }
    batch
}
