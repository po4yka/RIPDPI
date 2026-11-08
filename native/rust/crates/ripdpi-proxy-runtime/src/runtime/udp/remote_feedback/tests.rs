use super::*;

fn tracker(payloads: &[&[u8]]) -> FlowFeedback {
    let mut feedback = FlowFeedback::default();
    feedback.budget = Some(new_budget());
    for (index, payload) in payloads.iter().enumerate() {
        feedback.pending_id = Some(index as u64 + 1);
        feedback.record_send(payload);
    }
    feedback
}

#[test]
fn remote_error_matches_a_unique_prefix_not_the_last_datagram() {
    let mut feedback = tracker(&[b"earlier", b"latest"]);
    assert_eq!(feedback.matching_id(b"ear"), Some(1));
    assert_eq!(feedback.matching_id(b"ear"), None);
    assert_eq!(feedback.matching_id(b"latest"), Some(2));
}

#[test]
fn short_quotes_and_repeated_payloads_never_select_an_arbitrary_datagram() {
    assert_eq!(tracker(&[b"one"]).matching_id(b""), Some(1));
    assert_eq!(tracker(&[b"one", b"two"]).matching_id(b""), None);
    assert_eq!(tracker(&[b"same", b"same"]).matching_id(b"same"), None);
    assert_eq!(tracker(&[b"prefix-a", b"prefix-b"]).matching_id(b"prefix"), None);
    let prefix = [7; PREFIX_LEN + 10];
    let mut longer = prefix;
    longer[PREFIX_LEN + 1] = 8;
    assert_eq!(tracker(&[&prefix, &longer]).matching_id(&prefix), None);
}

#[test]
fn history_limits_disable_feedback_and_release_the_association_budget() {
    let mut feedback = tracker(&[b"old"]);
    let budget = feedback.budget.as_ref().expect("active budget").clone();
    for id in 2..=HISTORY_LIMIT as u64 + 1 {
        feedback.pending_id = Some(id);
        feedback.record_send(b"new");
    }
    assert!(feedback.poisoned);
    assert_eq!(feedback.matching_id(b"old"), None);
    assert_eq!(budget.get(), 0);
    let mut feedback = tracker(&[b"old"]);
    let budget = feedback.budget.as_ref().expect("active budget").clone();
    budget.set(ASSOCIATION_BUDGET);
    feedback.pending_id = Some(2);
    feedback.record_send(b"new");
    assert!(feedback.poisoned);
    assert_eq!(budget.get(), ASSOCIATION_BUDGET - RECORD_CHARGE);
}

#[test]
fn untracked_replay_disables_feedback_and_drop_releases_history() {
    let mut feedback = tracker(&[b"first"]);
    let budget = feedback.budget.as_ref().expect("active budget").clone();
    feedback.record_send(b"untracked");
    assert!(feedback.poisoned);
    assert_eq!(budget.get(), 0);
    let feedback = tracker(&[b"first", b"second"]);
    let budget = feedback.budget.as_ref().expect("active budget").clone();
    drop(feedback);
    assert_eq!(budget.get(), 0);
}

#[test]
fn a_partial_execution_cannot_leave_an_unrecorded_duplicate_eligible_for_old_feedback() {
    let mut feedback = tracker(&[b"same"]);
    feedback.execution_failed(1);
    feedback.pending_id = Some(2);
    feedback.record_send(b"same");
    assert_eq!(feedback.matching_id(b"same"), None);
    let mut feedback = tracker(&[b"old"]);
    feedback.execution_failed(0);
    assert_eq!(feedback.matching_id(b"old"), Some(1), "a latched old errno keeps its prior record");
}
