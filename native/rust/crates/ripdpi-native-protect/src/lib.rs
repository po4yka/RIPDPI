//! Process-global VPN socket-protection callback registry.
//!
//! Holds at most one `ProtectCallback` — the JNI shim that calls
//! `VpnService.protect(int)` — in a process-global slot. `libripdpi.so` and
//! `libripdpi-warp.so` each link their own copy of this crate, so each `.so`
//! has an independent slot driven by its own `jniRegisterVpnProtect` /
//! `jniUnregisterVpnProtect` pair.
//!
//! ## Generation guard
//!
//! The slot is process-global, so an asymmetric register/unregister can let a
//! stale `unregister` from a torn-down VPN session clear a *newer* session's
//! callback (a "stale unregister"). Outbound sockets would then fail
//! [`protect_socket_via_callback`] and risk a routing loop into the TUN — see
//! `.claude/rules/vpnservice-protect-invariant.md`.
//!
//! [`register_protect_callback_versioned`] stamps the slot with a monotonic
//! [`ProtectGeneration`] and returns it; [`unregister_protect_callback_if`]
//! clears the slot only when the stored generation still matches, so a late
//! unregister from a superseded session becomes a no-op instead of clobbering
//! the live registration.
//!
//! Unlike the sibling root-helper registry
//! (`ripdpi-runtime-platform::root_helper`), this registry's register and
//! unregister are two *separate JNI calls* with no Rust object spanning them,
//! so the generation token round-trips through the JNI boundary:
//! `jniRegisterVpnProtect` returns it as a `jlong` and
//! `jniUnregisterVpnProtect(jlong)` takes it back. See
//! `docs/architecture/JNI_CONTRACT.md` §8.
//!
//! [`register_protect_callback`] / [`unregister_protect_callback`] remain as
//! unconditional back-compat wrappers for callers that do not race a later
//! session (a single active VPN session — the common case — is unaffected:
//! the generation increments and always matches on release).

#![forbid(unsafe_code)]

use std::io;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

pub trait ProtectCallback: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
}

/// Monotonic token identifying one [`register_protect_callback_versioned`]
/// call.
///
/// Returned by the versioned register and passed back to
/// [`unregister_protect_callback_if`] so a stale unregister cannot clear a slot
/// a later session re-registered. The raw [`token`](Self::token) value
/// round-trips across the JNI boundary as a `jlong`; only equality against a
/// value the registry handed out is meaningful. Token `0` is never issued, so
/// it is a safe sentinel for "no registration" / a failed JNI register.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProtectGeneration(u64);

impl ProtectGeneration {
    /// The raw token value, for round-tripping across the JNI boundary.
    #[must_use]
    pub const fn token(self) -> u64 {
        self.0
    }

    /// Reconstruct a generation from a token previously returned by
    /// [`register_protect_callback_versioned`]. A token the registry never
    /// issued (including `0`) simply never matches the stored generation, so
    /// [`unregister_protect_callback_if`] is then a safe no-op.
    #[must_use]
    pub const fn from_token(token: u64) -> Self {
        Self(token)
    }
}

/// The registered callback plus the generation that registered it.
struct RegisteredProtect {
    generation: ProtectGeneration,
    callback: Arc<dyn ProtectCallback>,
}

static PROTECT_CB: RwLock<Option<RegisteredProtect>> = RwLock::new(None);
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

/// Register the VPN protect callback and return the [`ProtectGeneration`]
/// stamped on the registry slot.
///
/// Prefer this over [`register_protect_callback`]: keep the returned
/// generation and release through [`unregister_protect_callback_if`] so a
/// stale unregister from a superseded VPN session cannot clobber a newer
/// session's callback.
pub fn register_protect_callback_versioned(cb: Arc<dyn ProtectCallback>) -> ProtectGeneration {
    // Relaxed: the counter only needs uniqueness, not ordering against the
    // slot write below — the write lock provides the happens-before edge.
    let generation = ProtectGeneration(NEXT_GENERATION.fetch_add(1, Ordering::Relaxed));
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = Some(RegisteredProtect { generation, callback: cb });
    generation
}

/// Register the VPN protect callback.
///
/// Unconditional back-compat wrapper over
/// [`register_protect_callback_versioned`] that discards the generation.
/// Callers that may race a later session should use the versioned form
/// together with [`unregister_protect_callback_if`].
pub fn register_protect_callback(cb: Arc<dyn ProtectCallback>) {
    let _ = register_protect_callback_versioned(cb);
}

/// Unregister the VPN protect callback only if the slot still carries
/// `generation`.
///
/// Returns `true` when the slot was cleared, and `false` — a safe no-op — when
/// a newer `register_protect_callback*` call has already superseded this
/// generation (the stale unregister is ignored) or when no callback is
/// registered at all. This is the safe release path for the paired JNI
/// register/unregister calls.
pub fn unregister_protect_callback_if(generation: ProtectGeneration) -> bool {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    match guard.as_ref() {
        Some(registered) if registered.generation == generation => {
            *guard = None;
            true
        }
        // Stale generation (superseded by a newer session) or empty slot:
        // ignore the unregister so a live registration is never clobbered.
        Some(_) | None => false,
    }
}

/// Unregister the VPN protect callback.
///
/// Unconditional back-compat wrapper: clears whatever callback is in the slot.
/// Prefer [`unregister_protect_callback_if`] when a stale unregister could
/// otherwise clear a newer session's callback.
pub fn unregister_protect_callback() {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = None;
}

pub fn protect_socket_via_callback(fd: RawFd) -> io::Result<()> {
    let guard = PROTECT_CB.read().expect("protect callback lock poisoned");
    match guard.as_ref() {
        Some(registered) => registered.callback.protect(fd),
        None => Err(io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered")),
    }
}

/// Returns whether a protect callback is registered.
///
/// Fail **closed** on lock poison: a poisoned lock is treated as "callback
/// present / must attempt protect", so the skip-protect consumers that gate on
/// `if !has_protect_callback() { return Ok(()) }` never silently emit an
/// unprotected non-loopback connect/bind. The subsequent
/// [`protect_socket_via_callback`] call then surfaces the poison via its
/// `.expect(...)` (a crash — still fail-closed) rather than a routing loop into
/// the TUN. This aligns the accessor's poison policy with
/// [`protect_socket_via_callback`]. See `.claude/rules/vpnservice-protect-invariant.md`.
pub fn has_protect_callback() -> bool {
    match PROTECT_CB.read() {
        Ok(guard) => guard.is_some(),
        // Poisoned: recover the guard and report its actual state. Under any
        // live VPN session the last-written value is `Some(callback)`, so this
        // biases toward "present" → consumers attempt protect (fail-closed)
        // instead of skipping it (fail-open); a genuinely empty slot still
        // reports false, preserving desktop / VPN-down no-op semantics.
        Err(poisoned) => poisoned.into_inner().is_some(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use std::sync::atomic::AtomicI32;

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    struct TestCallback {
        last_fd: AtomicI32,
    }

    impl TestCallback {
        fn new() -> Self {
            Self { last_fd: AtomicI32::new(-1) }
        }
    }

    impl ProtectCallback for TestCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            // Release pairs with Acquire in the test assertion below.
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    #[test]
    fn no_callback_returns_error() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        let result = protect_socket_via_callback(42);
        assert!(result.is_err());
        assert_eq!(result.expect_err("missing callback").kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn register_and_invoke() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        register_protect_callback(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(99).is_ok());
        // Acquire pairs with Release in TestCallback::protect.
        assert_eq!(cb_ref.last_fd.load(Ordering::Acquire), 99);

        unregister_protect_callback();
        assert!(!has_protect_callback());
    }

    /// Characterizes the unconditional back-compat wrapper: a single
    /// [`unregister_protect_callback`] clears whatever callback is in the slot,
    /// even one a *later* `register` installed. Generation-aware callers use
    /// [`unregister_protect_callback_if`] instead — see the tests below.
    #[test]
    fn unregister_is_unconditional_for_overwritten_callbacks() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Session A registers, then session B overwrites the slot.
        register_protect_callback(Arc::new(TestCallback::new()));
        register_protect_callback(Arc::new(TestCallback::new()));
        assert!(has_protect_callback());

        // A single unconditional unregister clears session B's callback.
        unregister_protect_callback();
        assert!(!has_protect_callback());
    }

    #[test]
    fn versioned_register_hands_out_distinct_generations() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        let first = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        let second = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        assert_ne!(first, second);
        unregister_protect_callback();
    }

    /// A stale unregister from a superseded VPN session must not clear the
    /// newer session's callback — the core stale-unregister hazard.
    #[test]
    fn stale_unregister_does_not_clear_a_newer_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Session A registers, then session B supersedes it before A's
        // unregister runs.
        let stale = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        let current = register_protect_callback_versioned(Arc::new(TestCallback::new()));

        // A's late unregister carries a generation the slot no longer holds —
        // it is ignored and session B's callback survives.
        assert!(!unregister_protect_callback_if(stale));
        assert!(has_protect_callback());

        // B's own unregister carries the matching generation and clears it.
        assert!(unregister_protect_callback_if(current));
        assert!(!has_protect_callback());
    }

    /// The current generation's unregister clears the callback it registered.
    #[test]
    fn current_generation_unregister_clears_the_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        let generation = register_protect_callback_versioned(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(7).is_ok());
        // Acquire pairs with Release in TestCallback::protect.
        assert_eq!(cb_ref.last_fd.load(Ordering::Acquire), 7);

        assert!(unregister_protect_callback_if(generation));
        assert!(!has_protect_callback());

        // Releasing an already-cleared generation is a harmless no-op.
        assert!(!unregister_protect_callback_if(generation));
    }

    /// A token the registry never issued — including `0`, which a failed JNI
    /// register hands back — is a safe no-op and never clears a live slot.
    #[test]
    fn unregister_with_unknown_token_is_a_safe_noop() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Empty slot: unregistering any token is a no-op.
        assert!(!unregister_protect_callback_if(ProtectGeneration::from_token(0)));

        // Live slot: an unknown token does not clear it.
        let generation = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        assert!(!unregister_protect_callback_if(ProtectGeneration::from_token(0)));
        assert!(has_protect_callback());

        // The genuine generation still clears it.
        assert!(unregister_protect_callback_if(generation));
    }
}
