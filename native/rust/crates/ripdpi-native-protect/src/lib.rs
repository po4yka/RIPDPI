//! Process-global VPN socket-protection callback registry.
//!
//! Holds at most one `ProtectCallback` — the JNI shim that calls
//! `VpnService.protect(int)` — in a process-global slot. `libripdpi.so` and
//! `libripdpi-warp.so` each link their own copy of this crate, so each `.so`
//! has an independent slot driven by its own `jniRegisterVpnProtect` /
//! `jniUnregisterVpnProtect` pair.
//!
//! ## Stale-unregister hazard (TODO: generation guard)
//!
//! `register_protect_callback` overwrites the slot and
//! `unregister_protect_callback` clears it unconditionally. If a VPN session is
//! torn down and a new one starts before the old session's unregister runs,
//! the stale unregister clears the *new* session's callback — outbound sockets
//! then fail `protect_socket_via_callback` and risk a routing loop into the
//! TUN (see `.claude/rules/vpnservice-protect-invariant.md`).
//!
//! For a single active VPN session — the normal case — register and
//! unregister are strictly paired and the hazard does not arise; behaviour
//! here is correct as-is. The `unregister_is_unconditional_*` test pins the
//! current contract.
//!
//! `ripdpi-runtime-platform::root_helper` fixes the equivalent hazard for the
//! root-helper registry with a generation token, but it can do so entirely in
//! Rust because its register/unregister callers are RAII guards that hold the
//! token. This registry's register and unregister are two *separate JNI calls*
//! with no Rust object spanning them, so a correct generation guard needs the
//! token to round-trip through the JNI boundary:
//!
//! TODO(vpn-protect-generation): make this registry generation-safe.
//!   1. `register_protect_callback` returns a `ProtectGeneration` newtype over
//!      `u64`; keep the current `()`-returning form as a back-compat wrapper.
//!   2. Add `unregister_protect_callback_if(ProtectGeneration) -> bool` that
//!      clears the slot only when the stored generation still matches.
//!   3. Change the JNI contract so `jniRegisterVpnProtect` returns the
//!      generation as a `jlong` and `jniUnregisterVpnProtect(jlong)` takes it
//!      back — a JNI *signature* change touching `RipDpiProxyNativeBindings`,
//!      `RipDpiWarpNativeBindings`, the four Rust export symbols, and
//!      `VpnNativeProtectRegistration.kt` (which would hold the token between
//!      register and unregister). It is a wire-contract change and must land
//!      as its own reviewed commit — see `docs/architecture/JNI_CONTRACT.md`
//!      §8 for the full step list.

use std::io;
use std::os::fd::RawFd;
use std::sync::{Arc, RwLock};

pub trait ProtectCallback: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;
}

// TODO(vpn-protect-generation): this slot has no generation guard; a stale
// unregister from a superseded VPN session can clear a newer session's
// callback. See the crate-level docs above for the exact change required
// (it needs a JNI signature change, so it is tracked, not done inline).
static PROTECT_CB: RwLock<Option<Arc<dyn ProtectCallback>>> = RwLock::new(None);

pub fn register_protect_callback(cb: Arc<dyn ProtectCallback>) {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = Some(cb);
}

pub fn unregister_protect_callback() {
    let mut guard = PROTECT_CB.write().expect("protect callback lock poisoned");
    *guard = None;
}

pub fn protect_socket_via_callback(fd: RawFd) -> io::Result<()> {
    let guard = PROTECT_CB.read().expect("protect callback lock poisoned");
    match guard.as_ref() {
        Some(cb) => cb.protect(fd),
        None => Err(io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered")),
    }
}

pub fn has_protect_callback() -> bool {
    PROTECT_CB.read().is_ok_and(|guard| guard.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::Mutex;

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

    /// Characterizes the current (un-guarded) unregister contract: a single
    /// unregister clears whatever callback is in the slot, even one a *later*
    /// `register` installed. This is the stale-unregister hazard tracked by
    /// `TODO(vpn-protect-generation)` in the crate docs — pinned here so the
    /// eventual generation guard is a deliberate, test-visible change.
    #[test]
    fn unregister_is_unconditional_for_overwritten_callbacks() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Session A registers, then session B overwrites the slot.
        register_protect_callback(Arc::new(TestCallback::new()));
        register_protect_callback(Arc::new(TestCallback::new()));
        assert!(has_protect_callback());

        // A single unregister clears session B's callback unconditionally —
        // no generation check guards it today.
        unregister_protect_callback();
        assert!(!has_protect_callback());
    }
}
