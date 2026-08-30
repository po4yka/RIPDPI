#![forbid(unsafe_code)]

//! Shared per-exit-IP concurrent-session admission policy.
//!
//! The limiter counts physical outbound sessions per `(exit_ip, transport)`.
//! Callers hold the returned guard for exactly the lifetime of one physical
//! session. Logical streams multiplexed inside that session do not acquire
//! additional guards.
//!
//! Caps are throughput shaping, not an authorization boundary. The counter is
//! therefore poison-tolerant and never panics while releasing a guard.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

/// Default concurrent-session cap applied to a transport with no override.
pub const DEFAULT_EXIT_IP_SESSION_CAP: usize = 8;

/// Per-transport concurrent-session caps, with a fallback default.
#[derive(Debug, Clone)]
pub struct ExitIpSessionCaps {
    default_cap: usize,
    per_transport: HashMap<String, usize>,
}

impl ExitIpSessionCaps {
    /// Caps with the given fallback and no per-transport overrides.
    #[must_use]
    pub fn new(default_cap: usize) -> Self {
        Self { default_cap, per_transport: HashMap::new() }
    }

    /// Override the cap for a specific transport identifier.
    #[must_use]
    pub fn with_transport(mut self, transport: impl Into<String>, cap: usize) -> Self {
        self.per_transport.insert(transport.into(), cap);
        self
    }

    /// The cap that applies to `transport`.
    #[must_use]
    pub fn cap_for(&self, transport: &str) -> usize {
        self.per_transport.get(transport).copied().unwrap_or(self.default_cap)
    }
}

impl Default for ExitIpSessionCaps {
    fn default() -> Self {
        Self::new(DEFAULT_EXIT_IP_SESSION_CAP)
    }
}

type SessionCounts = HashMap<(IpAddr, String), usize>;

/// Counts in-flight physical sessions per `(exit_ip, transport)`.
///
/// Clones share the same admission state and therefore enforce one budget.
#[derive(Debug, Clone)]
pub struct ExitIpSessionLimiter {
    counts: Arc<Mutex<SessionCounts>>,
    caps: ExitIpSessionCaps,
}

impl ExitIpSessionLimiter {
    /// Build a limiter with the given caps.
    #[must_use]
    pub fn new(caps: ExitIpSessionCaps) -> Self {
        Self { counts: Arc::new(Mutex::new(HashMap::new())), caps }
    }

    /// Reserve one physical session slot, or return `None` at the cap.
    #[must_use]
    pub fn try_acquire(&self, exit_ip: IpAddr, transport: &str) -> Option<ExitIpSessionGuard> {
        let cap = self.caps.cap_for(transport);
        if cap == 0 {
            return None;
        }
        let key = (exit_ip, transport.to_owned());
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let active = counts.entry(key.clone()).or_insert(0);
        if *active >= cap {
            return None;
        }
        *active += 1;
        Some(ExitIpSessionGuard { counts: Arc::clone(&self.counts), key })
    }

    /// Current number of in-flight sessions for `(exit_ip, transport)`.
    #[must_use]
    pub fn active(&self, exit_ip: IpAddr, transport: &str) -> usize {
        let key = (exit_ip, transport.to_owned());
        self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&key).copied().unwrap_or(0)
    }

    /// Poison the counter so poison recovery can be verified without unsafe code.
    #[cfg(test)]
    fn poison_for_test(&self) {
        let counts = Arc::clone(&self.counts);
        let _ = std::thread::spawn(move || {
            let _held = counts.lock().expect("lock to poison");
            panic!("intentional poison");
        })
        .join();
    }
}

/// A physical-session slot that is released on drop.
#[derive(Debug)]
pub struct ExitIpSessionGuard {
    counts: Arc<Mutex<SessionCounts>>,
    key: (IpAddr, String),
}

impl Drop for ExitIpSessionGuard {
    fn drop(&mut self) {
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(active) = counts.get_mut(&self.key) {
            *active = active.saturating_sub(1);
            if *active == 0 {
                counts.remove(&self.key);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::{DEFAULT_EXIT_IP_SESSION_CAP, ExitIpSessionCaps, ExitIpSessionLimiter};

    fn ip(last: u8) -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(203, 0, 113, last))
    }

    #[test]
    fn default_cap_is_eight() {
        assert_eq!(DEFAULT_EXIT_IP_SESSION_CAP, 8);
        assert_eq!(ExitIpSessionCaps::default().cap_for("vless_reality"), 8);
    }

    #[test]
    fn ninth_session_is_refused_when_cap_is_eight() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::default());
        let _held: Vec<_> = (0..8).map(|_| limiter.try_acquire(ip(1), "vless_reality").expect("slot")).collect();

        assert_eq!(limiter.active(ip(1), "vless_reality"), 8);
        assert!(limiter.try_acquire(ip(1), "vless_reality").is_none());
    }

    #[test]
    fn dropping_a_guard_frees_a_slot() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(1));
        let guard = limiter.try_acquire(ip(1), "vless_reality").expect("first slot");
        assert!(limiter.try_acquire(ip(1), "vless_reality").is_none());

        drop(guard);

        assert_eq!(limiter.active(ip(1), "vless_reality"), 0);
        assert!(limiter.try_acquire(ip(1), "vless_reality").is_some());
    }

    #[test]
    fn guard_drop_recovers_a_poisoned_counter() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(2));
        let guard = limiter.try_acquire(ip(1), "vless_reality").expect("first slot");
        limiter.poison_for_test();

        drop(guard);

        assert_eq!(limiter.active(ip(1), "vless_reality"), 0);
        assert!(limiter.try_acquire(ip(1), "vless_reality").is_some());
    }

    #[test]
    fn caps_are_independent_per_exit_ip_and_transport() {
        let caps = ExitIpSessionCaps::new(8).with_transport("hysteria2", 2);
        let limiter = ExitIpSessionLimiter::new(caps);
        let _h: Vec<_> = (0..2).map(|_| limiter.try_acquire(ip(1), "hysteria2").expect("slot")).collect();

        assert!(limiter.try_acquire(ip(1), "hysteria2").is_none());
        assert!(limiter.try_acquire(ip(1), "vless_reality").is_some());
        assert!(limiter.try_acquire(ip(2), "hysteria2").is_some());
    }

    #[test]
    fn independent_limiters_do_not_double_count_the_same_key() {
        let direct_path = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(1));
        let relay_path = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(1));
        let _direct = direct_path.try_acquire(ip(1), "vless_reality").expect("direct slot");

        assert_eq!(direct_path.active(ip(1), "vless_reality"), 1);
        assert_eq!(relay_path.active(ip(1), "vless_reality"), 0);
        assert!(relay_path.try_acquire(ip(1), "vless_reality").is_some());
    }

    #[test]
    fn zero_cap_refuses_without_tracking_a_key() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(0));

        assert!(limiter.try_acquire(ip(1), "vless_reality").is_none());
        assert_eq!(limiter.active(ip(1), "vless_reality"), 0);
        assert!(limiter.counts.lock().expect("counts").is_empty());
    }

    #[test]
    fn clones_share_one_admission_budget() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(1));
        let clone = limiter.clone();
        let guard = limiter.try_acquire(ip(1), "vless_reality").expect("first slot");

        assert_eq!(clone.active(ip(1), "vless_reality"), 1);
        assert!(clone.try_acquire(ip(1), "vless_reality").is_none());

        drop(guard);
        assert!(clone.try_acquire(ip(1), "vless_reality").is_some());
    }
}
