use std::collections::HashMap;
use std::net::SocketAddr;
use std::process;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::runtime_policy::RetrySelectionPenalty;
use ripdpi_desync::AdaptivePlannerHints;

const FNV_OFFSET: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RetryLane {
    TcpTls,
    TcpOther,
    UdpQuic,
    UdpOther,
}

impl RetryLane {
    fn as_str(self) -> &'static str {
        match self {
            Self::TcpTls => "tcp_tls",
            Self::TcpOther => "tcp_other",
            Self::UdpQuic => "udp_quic",
            Self::UdpOther => "udp_other",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct RetrySignature {
    network_scope_key: String,
    lane: RetryLane,
    target_key: String,
    group_index: usize,
    adaptive_hash: u64,
}

impl RetrySignature {
    pub fn new(
        network_scope_key: impl Into<String>,
        lane: RetryLane,
        target_key: impl Into<String>,
        group_index: usize,
        adaptive_hash: u64,
    ) -> Self {
        Self {
            network_scope_key: network_scope_key.into(),
            lane,
            target_key: target_key.into(),
            group_index,
            adaptive_hash,
        }
    }

    pub fn hash(&self) -> u64 {
        let mut hash = FNV_OFFSET;
        stable_hash_update(&mut hash, self.network_scope_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.lane.as_str().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.target_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.group_index.to_string().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.adaptive_hash.to_string().as_bytes());
        hash
    }

    pub fn family_hash(&self) -> u64 {
        let mut hash = FNV_OFFSET;
        stable_hash_update(&mut hash, self.network_scope_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.lane.as_str().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.target_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.group_index.to_string().as_bytes());
        hash
    }
}

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

#[derive(Debug, Clone)]
struct SignatureFailureState {
    consecutive_failures: u32,
    last_failure_ms: u64,
    suppress_until_ms: u64,
}

#[derive(Debug, Clone)]
pub struct RetryPacer {
    policy: RetryStealthPolicy,
    session_seed: u64,
    signature_failures: HashMap<u64, SignatureFailureState>,
    family_cooldowns: HashMap<u64, u64>,
}

impl Default for RetryPacer {
    fn default() -> Self {
        Self::new(RetryStealthPolicy::default())
    }
}

impl RetryPacer {
    pub fn new(policy: RetryStealthPolicy) -> Self {
        Self {
            policy,
            session_seed: stable_hash_combine(
                process::id() as u64,
                now_ms()
                    ^ (SystemTime::now().duration_since(UNIX_EPOCH).ok().map_or(0, |value| value.as_nanos() as u64)),
            ),
            signature_failures: HashMap::new(),
            family_cooldowns: HashMap::new(),
        }
    }

    pub fn record_failure(&mut self, signature: &RetrySignature, now_ms: u64) -> RetryDecision {
        let signature_hash = signature.hash();
        let family_delay = self.family_delay_ms(signature_hash);
        let state = self.signature_failures.entry(signature_hash).or_insert(SignatureFailureState {
            consecutive_failures: 0,
            last_failure_ms: 0,
            suppress_until_ms: 0,
        });
        if now_ms.saturating_sub(state.last_failure_ms) <= self.policy.same_signature_window_ms {
            state.consecutive_failures = state.consecutive_failures.saturating_add(1);
        } else {
            state.consecutive_failures = 1;
        }
        state.last_failure_ms = now_ms;
        state.suppress_until_ms = now_ms.saturating_add(self.policy.same_signature_window_ms);
        let consecutive_failures = state.consecutive_failures;
        let suppress_until_ms = state.suppress_until_ms;

        let family_until = now_ms.saturating_add(family_delay);
        self.family_cooldowns
            .entry(signature.family_hash())
            .and_modify(|value| *value = (*value).max(family_until))
            .or_insert(family_until);

        RetryDecision {
            backoff_ms: self.same_signature_backoff_ms(signature_hash, consecutive_failures),
            suppress_same_signature_until_ms: suppress_until_ms,
            family_cooldown_until_ms: family_until,
            reason: "same_signature_retry",
        }
    }

    pub fn clear_success(&mut self, signature: &RetrySignature) {
        self.signature_failures.remove(&signature.hash());
        self.family_cooldowns.remove(&signature.family_hash());
    }

    pub fn penalty_for(&self, signature: &RetrySignature, now_ms: u64) -> RetrySelectionPenalty {
        let signature_hash = signature.hash();
        let same_signature_cooldown_ms = self
            .signature_failures
            .get(&signature_hash)
            .map_or(0, |state| state.suppress_until_ms.saturating_sub(now_ms));
        let family_cooldown_ms =
            self.family_cooldowns.get(&signature.family_hash()).copied().unwrap_or_default().saturating_sub(now_ms);
        RetrySelectionPenalty {
            same_signature_cooldown_ms,
            family_cooldown_ms,
            diversification_rank: stable_hash_combine(self.session_seed, signature_hash),
        }
    }

    pub fn retry_delay_for(&self, signature: &RetrySignature, now_ms: u64) -> Option<RetryDecision> {
        let signature_hash = signature.hash();
        if let Some(state) = self.signature_failures.get(&signature_hash) {
            let remaining = state.suppress_until_ms.saturating_sub(now_ms);
            if remaining > 0 {
                return Some(RetryDecision {
                    backoff_ms: self.same_signature_backoff_ms(signature_hash, state.consecutive_failures),
                    suppress_same_signature_until_ms: state.suppress_until_ms,
                    family_cooldown_until_ms: self
                        .family_cooldowns
                        .get(&signature.family_hash())
                        .copied()
                        .unwrap_or_default(),
                    reason: "same_signature_retry",
                });
            }
        }
        let family_until = self.family_cooldowns.get(&signature.family_hash()).copied().unwrap_or_default();
        if family_until > now_ms {
            return Some(RetryDecision {
                backoff_ms: family_until.saturating_sub(now_ms),
                suppress_same_signature_until_ms: 0,
                family_cooldown_until_ms: family_until,
                reason: "same_family_retry",
            });
        }
        None
    }

    fn same_signature_backoff_ms(&self, signature_hash: u64, consecutive_failures: u32) -> u64 {
        let index =
            consecutive_failures.saturating_sub(1).min((self.policy.same_signature_backoff_ms.len() - 1) as u32);
        apply_jitter(
            self.policy.same_signature_backoff_ms[index as usize],
            self.policy.jitter_ratio,
            stable_hash_combine(self.session_seed, signature_hash ^ u64::from(consecutive_failures)),
        )
    }

    fn family_delay_ms(&self, signature_hash: u64) -> u64 {
        let spread = self.policy.family_delay_max_ms.saturating_sub(self.policy.family_delay_min_ms);
        if spread == 0 {
            return self.policy.family_delay_min_ms;
        }
        self.policy.family_delay_min_ms + (stable_hash_combine(self.session_seed, signature_hash) % (spread + 1))
    }
}

pub fn adaptive_signature_hash(fake_ttl: Option<u8>, hints: AdaptivePlannerHints) -> u64 {
    let mut hash = FNV_OFFSET;
    if let Some(value) = fake_ttl {
        stable_hash_update(&mut hash, value.to_string().as_bytes());
    }
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{hints:?}").as_bytes());
    hash
}

pub fn stable_hash_combine(lhs: u64, rhs: u64) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, lhs.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, rhs.to_string().as_bytes());
    hash
}

fn apply_jitter(base_ms: u64, ratio: f64, seed: u64) -> u64 {
    if base_ms == 0 {
        return 0;
    }
    let basis_points = ((seed % 7_001) as i64) - 3_500;
    let normalized = basis_points as f64 / 10_000.0;
    let factor = 1.0 + (normalized * ratio / 0.35);
    ((base_ms as f64) * factor).round().max(1.0) as u64
}

fn stable_hash_update(hash: &mut u64, bytes: &[u8]) {
    for byte in bytes {
        *hash ^= u64::from(*byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

pub fn target_key(host: Option<&str>, dest: SocketAddr) -> String {
    host.map(str::trim).filter(|value| !value.is_empty()).map_or_else(|| dest.to_string(), str::to_ascii_lowercase)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_signature(group_index: usize) -> RetrySignature {
        RetrySignature::new("scope-a", RetryLane::TcpTls, "example.org", group_index, 17)
    }

    #[test]
    fn repeated_failures_escalate_same_signature_backoff() {
        let mut pacer = RetryPacer::new(RetryStealthPolicy { jitter_ratio: 0.0, ..RetryStealthPolicy::default() });
        let signature = sample_signature(0);
        let first = pacer.record_failure(&signature, 1_000);
        let second = pacer.record_failure(&signature, 2_000);
        let third = pacer.record_failure(&signature, 3_000);
        let fourth = pacer.record_failure(&signature, 4_000);
        let fifth = pacer.record_failure(&signature, 5_000);

        assert_eq!(first.backoff_ms, 300);
        assert_eq!(second.backoff_ms, 700);
        assert_eq!(third.backoff_ms, 1_500);
        assert_eq!(fourth.backoff_ms, 3_000);
        assert_eq!(fifth.backoff_ms, 3_000);
    }

    #[test]
    fn success_clears_signature_and_family_cooldowns() {
        let mut pacer = RetryPacer::new(RetryStealthPolicy { jitter_ratio: 0.0, ..RetryStealthPolicy::default() });
        let signature = sample_signature(0);
        pacer.record_failure(&signature, 1_000);
        let penalty = pacer.penalty_for(&signature, 1_001);
        assert!(penalty.same_signature_cooldown_ms > 0);
        assert!(penalty.family_cooldown_ms > 0);

        pacer.clear_success(&signature);
        let penalty = pacer.penalty_for(&signature, 1_001);
        assert_eq!(penalty.same_signature_cooldown_ms, 0);
        assert_eq!(penalty.family_cooldown_ms, 0);
    }

    #[test]
    fn different_groups_in_same_family_only_get_family_delay() {
        let mut pacer = RetryPacer::new(RetryStealthPolicy { jitter_ratio: 0.0, ..RetryStealthPolicy::default() });
        let failed = sample_signature(0);
        let sibling = RetrySignature::new("scope-a", RetryLane::TcpTls, "example.org", 0, 18);
        pacer.record_failure(&failed, 1_000);

        let penalty = pacer.penalty_for(&sibling, 1_001);
        assert_eq!(penalty.same_signature_cooldown_ms, 0);
        assert!(penalty.family_cooldown_ms > 0);
    }

    #[test]
    fn retry_delay_for_sibling_signature_uses_same_family_reason() {
        let mut pacer = RetryPacer::new(RetryStealthPolicy { jitter_ratio: 0.0, ..RetryStealthPolicy::default() });
        let failed = sample_signature(0);
        let sibling = RetrySignature::new("scope-a", RetryLane::TcpTls, "example.org", 0, 18);

        pacer.record_failure(&failed, 1_000);
        let decision = pacer.retry_delay_for(&sibling, 1_001).expect("family retry decision");

        assert_eq!(decision.reason, "same_family_retry");
        assert!((80..=200).contains(&decision.backoff_ms));
        assert_eq!(decision.suppress_same_signature_until_ms, 0);
    }

    #[test]
    fn retry_delay_clears_after_signature_window_expires() {
        let mut pacer = RetryPacer::new(RetryStealthPolicy { jitter_ratio: 0.0, ..RetryStealthPolicy::default() });
        let signature = sample_signature(0);

        let first = pacer.record_failure(&signature, 1_000);
        let after_window = first.suppress_same_signature_until_ms.saturating_add(1);

        assert!(pacer.retry_delay_for(&signature, after_window).is_none());
    }
}
