use ripdpi_config::RuntimeConfig;

use crate::runtime_policy::types::GroupPolicy;

pub(super) fn build_group_policies(config: &RuntimeConfig) -> Vec<GroupPolicy> {
    config
        .groups
        .iter()
        .map(|group| GroupPolicy {
            detect: group.matches.detect,
            fail_count: group.policy.fail_count,
            pri: group.policy.pri,
        })
        .collect()
}

pub(super) fn initial_group_order(config: &RuntimeConfig) -> Vec<usize> {
    (0..config.groups.len()).collect()
}

pub(super) fn swap_group_order(order: &mut [usize], groups: &mut [GroupPolicy], lhs: usize, rhs: usize) {
    let Some(lhs_pos) = order.iter().position(|&index| index == lhs) else {
        return;
    };
    let Some(rhs_pos) = order.iter().position(|&index| index == rhs) else {
        return;
    };
    order.swap(lhs_pos, rhs_pos);
    if lhs == rhs {
        return;
    }

    let lhs_detect = groups.get(lhs).map(|group| group.detect);
    let rhs_detect = groups.get(rhs).map(|group| group.detect);
    if let (Some(lhs_detect), Some(rhs_detect)) = (lhs_detect, rhs_detect) {
        if let Some(group) = groups.get_mut(lhs) {
            group.detect = rhs_detect;
        }
        if let Some(group) = groups.get_mut(rhs) {
            group.detect = lhs_detect;
        }
    }
}
