use crate::candidates::StrategyCandidateSpec;

pub fn candidate_notes(spec: &StrategyCandidateSpec, extra_notes: &[&str]) -> Vec<String> {
    spec.notes.iter().copied().chain(extra_notes.iter().copied()).map(str::to_string).collect()
}
