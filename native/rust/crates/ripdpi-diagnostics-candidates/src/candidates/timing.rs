use super::prelude::*;

pub fn candidate_pause_ms(seed: u64, candidate: &StrategyCandidateSpec, failed: bool) -> u64 {
    if failed {
        ranged_probe_delay(seed, candidate.id, "candidate_failed", 400, 900)
    } else {
        ranged_probe_delay(seed, candidate.id, "candidate_gap", 120, 350)
    }
}

pub fn target_probe_pause_ms(seed: u64, candidate: &StrategyCandidateSpec, target_key: &str) -> u64 {
    ranged_probe_delay(seed, candidate.id, target_key, 120, 350)
}
