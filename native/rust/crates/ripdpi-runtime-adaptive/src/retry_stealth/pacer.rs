use std::collections::HashMap;
use std::process;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::retry_stealth::hash::stable_hash_combine;
use crate::retry_stealth::identity::RetrySignature;
use crate::retry_stealth::policy::{RetryDecision, RetryStealthPolicy, apply_jitter};
use ripdpi_runtime_policy::runtime_policy::RetrySelectionPenalty;

#[derive(Debug, Clone)]
pub struct RetryPacer {
    policy: RetryStealthPolicy,
    session_seed: u64,
    signature_failures: HashMap<u64, SignatureFailureState>,
    family_cooldowns: HashMap<u64, u64>,
}

#[derive(Debug, Clone)]
struct SignatureFailureState {
    consecutive_failures: u32,
    last_failure_ms: u64,
    suppress_until_ms: u64,
}

impl RetryPacer {
    pub fn new(policy: RetryStealthPolicy) -> Self {
        Self {
            policy,
            session_seed: stable_hash_combine(process::id() as u64, now_ms() ^ system_time_nanos_low()),
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
            Some(RetryDecision {
                backoff_ms: family_until.saturating_sub(now_ms),
                suppress_same_signature_until_ms: 0,
                family_cooldown_until_ms: family_until,
                reason: "same_family_retry",
            })
        } else {
            None
        }
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

impl Default for RetryPacer {
    fn default() -> Self {
        Self::new(RetryStealthPolicy::default())
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

fn system_time_nanos_low() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).ok().map_or(0, |value| value.as_nanos() as u64)
}
