mod hash;
mod identity;
mod pacer;
mod policy;
mod target;

pub use hash::stable_hash_combine;
pub use identity::{RetryLane, RetrySignature};
pub use pacer::RetryPacer;
pub use policy::{RetryDecision, RetryStealthPolicy};
pub use target::{adaptive_signature_hash, target_key};

#[cfg(test)]
mod tests {
    use super::{RetryLane, RetryPacer, RetrySignature, RetryStealthPolicy};

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
