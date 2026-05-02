use std::collections::BTreeSet;

pub(super) fn normalize_answers(answers: Vec<String>) -> Vec<String> {
    answers
        .into_iter()
        .map(|answer| answer.trim().to_string())
        .filter(|answer| !answer.is_empty())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}
