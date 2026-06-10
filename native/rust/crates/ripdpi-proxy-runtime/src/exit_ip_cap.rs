//! Per-exit-IP concurrent outbound-TLS-session cap.
//!
//! Russian home-ISP DPI (e.g. MTS Novosibirsk, per the `tls-policing-home-isps`
//! wiki) flags a host that opens an unusually high number of *simultaneous* TLS
//! sessions to a single exit IP — a behavioural proxy-detection signal that is
//! independent of the ClientHello bytes ([`crate`]-level fingerprint rotation
//! addresses the latter). Capping concurrent sessions per `(exit_ip, transport)`
//! keeps the outbound connection profile within the range a real browser would
//! produce.
//!
//! This module is the accounting primitive (a counter + RAII guard + per-transport
//! cap table). The session-establishment path acquires a slot before opening a new
//! outbound TLS session; when [`ExitIpSessionLimiter::try_acquire`] returns
//! [`None`] the caller should prefer muxing a new stream onto an existing session
//! rather than opening another (mux-preference wiring is a follow-up).
//!
//! Caps are advisory throughput shaping, not a security boundary: the guard is a
//! plain counter, released on drop, with no blocking or `.await` held across the
//! lock.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};

/// Default concurrent-session cap applied to a transport with no explicit entry.
///
/// `8` matches the measured safe ceiling for VLESS + REALITY + Vision on port
/// 443 against RU home-ISP policing (`tls-policing-home-isps`).
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

    /// Override the cap for a specific transport id (builder style).
    #[must_use]
    pub fn with_transport(mut self, transport: impl Into<String>, cap: usize) -> Self {
        self.per_transport.insert(transport.into(), cap);
        self
    }

    /// The cap that applies to `transport` (its override, else the default).
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

/// Tracks the number of in-flight outbound TLS sessions per `(exit_ip, transport)`
/// and refuses to exceed the configured cap. Cheap to clone (shares the counter).
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

    /// Try to reserve a session slot for `(exit_ip, transport)`.
    ///
    /// Returns a guard that releases the slot on drop, or [`None`] when the cap
    /// for `transport` is already reached (the caller should mux onto an existing
    /// session instead of opening a new one).
    #[must_use]
    pub fn try_acquire(&self, exit_ip: IpAddr, transport: &str) -> Option<ExitIpSessionGuard> {
        let cap = self.caps.cap_for(transport);
        let key = (exit_ip, transport.to_owned());
        // Advisory cap accounting (see module doc): a poisoned counter is
        // recovered rather than propagated/panicked. The count may be slightly
        // inconsistent after a panicking holder, which is acceptable for
        // throughput shaping that is not a security boundary.
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let active = counts.entry(key.clone()).or_insert(0);
        if *active >= cap {
            return None;
        }
        *active += 1;
        Some(ExitIpSessionGuard { counts: Arc::clone(&self.counts), key })
    }

    /// Poison the shared counts mutex (test-only) so the poison-recovery paths
    /// can be exercised without `unsafe` or a real concurrent panic race.
    #[cfg(all(test, not(feature = "loom")))]
    fn poison_for_test(&self) {
        let counts = Arc::clone(&self.counts);
        // A thread that panics while holding the lock leaves the mutex poisoned.
        let _ = std::thread::spawn(move || {
            let _held = counts.lock().expect("lock to poison");
            panic!("intentional poison");
        })
        .join();
    }

    /// Current number of in-flight sessions for `(exit_ip, transport)`.
    #[must_use]
    pub fn active(&self, exit_ip: IpAddr, transport: &str) -> usize {
        let key = (exit_ip, transport.to_owned());
        // Advisory read: recover a poisoned counter instead of panicking.
        self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner).get(&key).copied().unwrap_or(0)
    }
}

/// RAII slot reservation; releases the `(exit_ip, transport)` slot on drop.
#[derive(Debug)]
pub struct ExitIpSessionGuard {
    counts: Arc<Mutex<SessionCounts>>,
    key: (IpAddr, String),
}

impl Drop for ExitIpSessionGuard {
    fn drop(&mut self) {
        // NEVER panic in Drop: a panic during unwinding aborts the process on
        // stable Rust. The cap is advisory throughput shaping, not a security
        // boundary (see module doc), so a poisoned counter on release is
        // recovered via `into_inner` rather than panicked. A poisoned counter
        // may be slightly inconsistent; the decrement below is best-effort.
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(active) = counts.get_mut(&self.key) {
            *active = active.saturating_sub(1);
            if *active == 0 {
                counts.remove(&self.key);
            }
        }
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::{DEFAULT_EXIT_IP_SESSION_CAP, ExitIpSessionCaps, ExitIpSessionLimiter};

    fn ip(last: u8) -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(203, 0, 113, last))
    }

    #[test]
    fn default_cap_is_eight() {
        assert_eq!(DEFAULT_EXIT_IP_SESSION_CAP, 8);
        assert_eq!(ExitIpSessionCaps::default().cap_for("vless_reality_vision"), 8);
    }

    #[test]
    fn five_simultaneous_sessions_fit_under_the_default_cap() {
        // Mirrors the test-lab 5-simultaneous-connections probe at the unit level.
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::default());
        let guards: Vec<_> =
            (0..5).map(|_| limiter.try_acquire(ip(1), "vless_reality_vision").expect("slot")).collect();
        assert_eq!(limiter.active(ip(1), "vless_reality_vision"), 5);
        assert_eq!(guards.len(), 5);
    }

    #[test]
    fn ninth_session_is_refused_when_cap_is_eight() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::default());
        let _held: Vec<_> = (0..8).map(|_| limiter.try_acquire(ip(1), "vless").expect("slot")).collect();
        assert_eq!(limiter.active(ip(1), "vless"), 8);
        assert!(limiter.try_acquire(ip(1), "vless").is_none(), "9th session must be refused at cap 8");
    }

    #[test]
    fn dropping_a_guard_frees_a_slot() {
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(1));
        let guard = limiter.try_acquire(ip(1), "vless").expect("first slot");
        assert!(limiter.try_acquire(ip(1), "vless").is_none(), "cap of 1 is full");
        drop(guard);
        assert_eq!(limiter.active(ip(1), "vless"), 0);
        assert!(limiter.try_acquire(ip(1), "vless").is_some(), "slot freed after drop");
    }

    #[test]
    fn guard_drops_cleanly_when_mutex_is_poisoned() {
        // Reproduces the panic-in-Drop bug: a poisoned counts mutex must not
        // cause `ExitIpSessionGuard::drop` to panic (which would abort the
        // process during unwinding). The cap is advisory, so the poison is
        // recovered rather than propagated.
        let limiter = ExitIpSessionLimiter::new(ExitIpSessionCaps::new(2));
        let guard = limiter.try_acquire(ip(1), "vless").expect("first slot");
        limiter.poison_for_test();
        // Dropping the guard over a poisoned mutex must not panic/abort.
        drop(guard);
        // The recovery paths in `active` / `try_acquire` must also not panic.
        let _ = limiter.active(ip(1), "vless");
        let _second = limiter.try_acquire(ip(1), "vless").expect("slot after poison recovery");
    }

    #[test]
    fn caps_are_independent_per_exit_ip_and_per_transport() {
        let caps = ExitIpSessionCaps::new(8).with_transport("hysteria2", 2);
        let limiter = ExitIpSessionLimiter::new(caps);
        // Per-transport override: hysteria2 caps at 2.
        let _h: Vec<_> = (0..2).map(|_| limiter.try_acquire(ip(1), "hysteria2").expect("h slot")).collect();
        assert!(limiter.try_acquire(ip(1), "hysteria2").is_none(), "hysteria2 cap of 2 reached");
        // A different transport on the same IP uses the default cap.
        assert!(limiter.try_acquire(ip(1), "vless").is_some(), "vless unaffected by hysteria2 cap");
        // A different exit IP is fully independent.
        assert!(limiter.try_acquire(ip(2), "hysteria2").is_some(), "distinct exit IP has its own budget");
    }
}
