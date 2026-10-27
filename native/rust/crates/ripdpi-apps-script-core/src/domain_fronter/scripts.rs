//! Rotation with failover over deployments (and front SNI names): one that fails is
//! skipped for a while when another one is available, so an exhausted quota or a
//! blocked name stops taking 1/N of requests.

use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use super::FronterError;

/// How long a deployment that failed is tried only after the others.
pub(super) const DEPLOYMENT_COOLDOWN: Duration = Duration::from_secs(60);

pub(super) struct ScriptPool {
    ids: Vec<String>,
    /// How long an entry that failed is tried only after the others.
    cooldown: Duration,
    next: AtomicUsize,
    /// Per deployment: end of its cooldown in milliseconds since `epoch`; 0 when healthy.
    cooling_until_ms: Vec<AtomicU64>,
    epoch: Instant,
}

/// One failed attempt on one deployment.
pub(super) struct AttemptError {
    pub(super) error: FronterError,
    /// The request reached Apps Script, so the target may have seen it.
    pub(super) sent: bool,
    /// The deployment itself failed (connect, quota, auth, error page), not the target.
    pub(super) deployment_fault: bool,
}

impl ScriptPool {
    pub(super) fn new(ids: Vec<String>, cooldown: Duration) -> Self {
        let cooling_until_ms = ids.iter().map(|_| AtomicU64::new(0)).collect();
        Self { ids, cooldown, next: AtomicUsize::new(0), cooling_until_ms, epoch: Instant::now() }
    }

    pub(super) fn id(&self, index: usize) -> &str {
        &self.ids[index]
    }

    /// Deployments to try for one request: round-robin from the next one, cooling ones last.
    pub(super) fn attempt_order(&self) -> Vec<usize> {
        self.attempt_order_at(self.now_ms())
    }

    fn attempt_order_at(&self, now_ms: u64) -> Vec<usize> {
        let len = self.ids.len();
        if len == 0 {
            return Vec::new();
        }
        // Ordering: the counter only spreads requests; no state is published through it.
        let start = self.next.fetch_add(1, Ordering::Relaxed) % len;
        let mut order: Vec<usize> = (0..len).map(|offset| (start + offset) % len).collect();
        order.sort_by_key(|&index| self.is_cooling(index, now_ms));
        order
    }

    /// The entry to use next: the first of [`Self::attempt_order`].
    pub(super) fn pick(&self) -> Option<usize> {
        self.attempt_order().first().copied()
    }

    pub(super) fn record(&self, index: usize, healthy: bool) {
        let until = if healthy { 0 } else { self.now_ms() + self.cooldown.as_millis() as u64 };
        // Ordering: an independent per-deployment hint; a stale read only changes the try order.
        self.cooling_until_ms[index].store(until, Ordering::Relaxed);
    }

    #[cfg(test)]
    pub(super) fn is_cooling_now(&self, index: usize) -> bool {
        self.is_cooling(index, self.now_ms())
    }

    fn is_cooling(&self, index: usize, now_ms: u64) -> bool {
        self.cooling_until_ms[index].load(Ordering::Relaxed) > now_ms
    }

    fn now_ms(&self) -> u64 {
        u64::try_from(self.epoch.elapsed().as_millis()).unwrap_or(u64::MAX)
    }
}

impl AttemptError {
    /// Whether the same request may go to the next deployment. A request that reached
    /// Apps Script is repeated only when the deployment failed (a target error would fail
    /// the same way everywhere) and repeating it cannot change anything (GET, HEAD).
    pub(super) fn may_retry(&self, method: &str) -> bool {
        let repeatable = method.eq_ignore_ascii_case("GET") || method.eq_ignore_ascii_case("HEAD");
        !self.sent || (self.deployment_fault && repeatable)
    }
}

impl AttemptError {
    /// The request reached Apps Script and only the target fetch failed.
    pub(super) fn is_target_error(&self) -> bool {
        self.sent && !self.deployment_fault
    }
}

/// Whether an error the relay script reported is about the deployment (quota, limit,
/// authorization) rather than the target fetch.
pub(super) fn is_deployment_error(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    ["too many times", "quota", "limit exceeded", "unauthorized"].iter().any(|marker| message.contains(marker))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pool(count: usize) -> ScriptPool {
        ScriptPool::new((0..count).map(|index| format!("script-{index}")).collect(), DEPLOYMENT_COOLDOWN)
    }

    #[test]
    fn requests_rotate_and_a_failed_deployment_is_tried_last_until_its_cooldown_ends() {
        let pool = pool(3);
        assert_eq!(pool.attempt_order_at(0), vec![0, 1, 2]);
        assert_eq!(pool.attempt_order_at(0), vec![1, 2, 0]);

        pool.record(2, false);
        let now = pool.now_ms();
        assert_eq!(pool.attempt_order_at(now), vec![0, 1, 2], "rotation starts at 2, which is cooling");
        let after_cooldown = now + DEPLOYMENT_COOLDOWN.as_millis() as u64 + 1;
        assert_eq!(pool.attempt_order_at(after_cooldown), vec![0, 1, 2]);
        assert_eq!(pool.attempt_order_at(after_cooldown), vec![1, 2, 0]);

        pool.record(1, false);
        assert!(pool.is_cooling(1, pool.now_ms()));
        pool.record(1, true);
        assert!(!pool.is_cooling(1, pool.now_ms()), "a success ends the cooldown");
    }

    #[test]
    fn an_empty_pool_has_nothing_to_try() {
        assert!(pool(0).attempt_order().is_empty());
    }

    #[test]
    fn only_unsent_or_repeatable_requests_move_to_the_next_deployment() {
        let attempt = |sent| AttemptError { error: FronterError::Timeout, sent, deployment_fault: true };
        assert!(attempt(false).may_retry("POST"));
        assert!(attempt(true).may_retry("get"));
        assert!(attempt(true).may_retry("HEAD"));
        assert!(!attempt(true).may_retry("POST"));
        assert!(!attempt(true).may_retry("DELETE"));

        let target_error = AttemptError { error: FronterError::Timeout, sent: true, deployment_fault: false };
        assert!(!target_error.may_retry("GET"), "a target error is not repeated on other deployments");
    }

    #[test]
    fn quota_and_authorization_errors_are_deployment_errors() {
        assert!(is_deployment_error("Exception: Service invoked too many times for one day: urlfetch."));
        assert!(is_deployment_error("Bandwidth quota exceeded"));
        assert!(is_deployment_error("unauthorized"));
        assert!(!is_deployment_error("Exception: DNS error: https://target.example"));
    }
}
