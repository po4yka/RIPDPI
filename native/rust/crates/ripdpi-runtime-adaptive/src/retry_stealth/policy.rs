#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RetryDecision {
    pub backoff_ms: u64,
    pub suppress_same_signature_until_ms: u64,
    pub family_cooldown_until_ms: u64,
    pub reason: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RetryStealthPolicy {
    pub same_signature_window_ms: u64,
    pub same_signature_backoff_ms: [u64; 4],
    pub jitter_ratio: f64,
    pub family_delay_min_ms: u64,
    pub family_delay_max_ms: u64,
}

impl Default for RetryStealthPolicy {
    fn default() -> Self {
        Self {
            same_signature_window_ms: 15_000,
            same_signature_backoff_ms: [300, 700, 1_500, 3_000],
            jitter_ratio: 0.35,
            family_delay_min_ms: 80,
            family_delay_max_ms: 200,
        }
    }
}

pub(crate) fn apply_jitter(base_ms: u64, ratio: f64, seed: u64) -> u64 {
    if base_ms == 0 {
        return 0;
    }

    let basis_points = ((seed % 7_001) as i64) - 3_500;
    let normalized = basis_points as f64 / 10_000.0;
    let factor = 1.0 + (normalized * ratio / 0.35);
    ((base_ms as f64) * factor).round().max(1.0) as u64
}
