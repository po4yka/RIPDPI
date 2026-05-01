pub fn stable_probe_hash(seed: u64, value: &str) -> u64 {
    let mut hash = seed ^ 0xcbf29ce484222325;
    for byte in value.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

pub fn ranged_probe_delay(seed: u64, lhs: &str, rhs: &str, min_ms: u64, max_ms: u64) -> u64 {
    if max_ms <= min_ms {
        return min_ms;
    }
    let spread = max_ms - min_ms;
    min_ms + (stable_probe_hash(stable_probe_hash(seed, lhs), rhs) % (spread + 1))
}

pub fn probe_session_seed(network_scope_key: Option<&str>, session_id: &str) -> u64 {
    stable_probe_hash(stable_probe_hash(0x9e37_79b9_7f4a_7c15, network_scope_key.unwrap_or("default")), session_id)
}
