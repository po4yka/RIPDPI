use std::collections::BTreeMap;

use crate::runtime_policy::{ConnectionRoute, RetrySelectionPenalty, RuntimePolicy};

pub(crate) fn select_best_candidate(
    policy: &RuntimePolicy,
    eligible: &[usize],
    attempted_mask: u64,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
) -> Option<ConnectionRoute> {
    let mut ranked = eligible.to_vec();
    ranked.sort_by_key(|index| {
        let penalty = retry_penalty(retry_penalties, *index);
        let group = policy.groups.get(*index);
        (
            penalty.same_signature_cooldown_ms > 0,
            penalty.family_cooldown_ms > 0,
            group.map_or(0, |value| value.fail_count),
            group.map_or(0, |value| value.pri),
            penalty.diversification_rank,
            *index,
        )
    });
    ranked.into_iter().next().map(|group_index| ConnectionRoute { group_index, attempted_mask })
}

fn retry_penalty(
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    group_index: usize,
) -> RetrySelectionPenalty {
    retry_penalties.and_then(|value| value.get(&group_index).copied()).unwrap_or_default()
}
