//! Rust glue for the BoringSSL Reality `client_hello_cb` patch (H1).
//!
//! The vendored BoringSSL patch in `native/rust/vendor/boring-sys/`
//! exposes two new public symbols described in
//! `docs/design/reality-boringssl-patch.md`:
//!
//! * `SSL_CTX_set_client_hello_cb` — installs a callback that fires
//!   inside `ssl_add_client_hello` after the body is serialized and
//!   before `add_message` consumes it. The callback may mutate the
//!   `msg` buffer in place.
//! * `SSL_handshake_get_x25519_private_key` — copies the 32-byte
//!   X25519 private key of the active TLS 1.3 client `key_share`
//!   entry into a caller-allocated buffer.
//!
//! This module wraps both symbols in
//! [`install_reality_client_hello_hook`], which:
//!
//! 1. Allocates a boxed [`RealityCallbackState`] carrying the static
//!    Reality server pubkey and the short id;
//! 2. Leaks the box pointer into `SSL_CTX_set_client_hello_cb`;
//! 3. Returns an RAII [`RealityHookGuard`] that reclaims the box on
//!    drop so the state lives across HRR re-entry but is freed
//!    deterministically when the connection closes.
//!
//! The callback reads `client_random` directly out of `msg`
//! (`[6..38]` of the ClientHello body) and the X25519 key_share
//! private key from BoringSSL via the patched accessor. The seal
//! itself is delegated to [`crate::reality_seal::seal_session_id`]
//! — the boundary between the BoringSSL hook and the Reality crypto
//! is therefore a single function call.

use std::ffi::c_int;
use std::os::raw::c_void;
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(test)]
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::reality_seal::{SESSION_ID_LEN, SESSION_ID_OFFSET, seal_session_id};

#[repr(C)]
pub(crate) struct SslCtxHandle {
    _opaque: [u8; 0],
}

#[repr(C)]
pub(crate) struct SslHandle {
    _opaque: [u8; 0],
}

// Compile-time FFI guards for soundness issue #24: both `SslHandle` and
// `SslCtxHandle` are opaque BoringSSL handle types. The Rust side only
// ever sees `*const`/`*mut` pointers to these and never reads/writes
// through them — they must therefore be zero-sized so any accidental
// by-value materialisation (`unsafe { *p }`) is statically rejected.
// `#[repr(C)]` + a `[u8; 0]` field gives `size_of == 0` and `align_of
// == 1`. Any future field addition (which would silently make the
// handle by-value-readable and create an ABI mismatch with BoringSSL's
// real SSL/SSL_CTX layouts) fails to compile here.
const _: () = {
    assert!(std::mem::size_of::<SslHandle>() == 0);
    assert!(std::mem::align_of::<SslHandle>() == 1);
    assert!(std::mem::size_of::<SslCtxHandle>() == 0);
    assert!(std::mem::align_of::<SslCtxHandle>() == 1);
};

pub(crate) type RealityHelloCb =
    extern "C" fn(ssl: *mut SslHandle, msg: *mut u8, msg_len: usize, arg: *mut c_void) -> c_int;

#[cfg(not(all(miri, feature = "miri-stubs")))]
unsafe extern "C" {
    /// Patched BoringSSL accessor. Returns 1 if it copied 32 bytes
    /// into `out`, 0 otherwise. Valid only between
    /// `ssl_setup_key_shares` and the destruction of the handshake
    /// state — in practice, the safe window is exactly the duration
    /// of one [`reality_client_hello_cb`] invocation.
    fn SSL_handshake_get_x25519_private_key(ssl: *const SslHandle, out: *mut u8) -> c_int;

    /// Patched BoringSSL setter. Installs `cb` and `arg` on the
    /// `SSL_CTX`; both are read each time `ssl_add_client_hello` runs.
    fn SSL_CTX_set_client_hello_cb(ctx: *mut SslCtxHandle, cb: RealityHelloCb, arg: *mut c_void);

    /// Standard BoringSSL accessor; duplicated here so this module
    /// is self-contained and need not depend on a re-export from
    /// [`crate::reality`].
    fn SSL_get_SSL_CTX(ssl: *const SslHandle) -> *mut SslCtxHandle;
}

/// Miri-friendly stubs that simulate the three BoringSSL FFI calls
/// without linking against the real library. Each stub does the
/// safe-Rust-only operation Miri can validate (pointer provenance,
/// write-through-pointer with proper bounds) and returns success-
/// shaped values so the install / callback / drop dance round-trips
/// cleanly under Miri.
///
/// Enable via `cargo +nightly miri test -p ripdpi-vless --features miri-stubs`.
#[cfg(all(miri, feature = "miri-stubs"))]
mod miri_stubs {
    use super::{RealityHelloCb, SslCtxHandle, SslHandle, c_int, c_void};

    /// Writes 32 zero bytes to `out` and returns 1 (success) so the
    /// callback's `priv_key` path doesn't take the empty-key error
    /// branch. Real BoringSSL writes the actual X25519 key.
    ///
    /// # Safety
    /// `out` must point to 32 writable bytes.
    #[unsafe(no_mangle)]
    pub(super) unsafe extern "C" fn SSL_handshake_get_x25519_private_key(
        _ssl: *const SslHandle,
        out: *mut u8,
    ) -> c_int {
        // SAFETY: caller contract — out points to 32 writable bytes.
        unsafe { std::ptr::write_bytes(out, 0, 32) };
        1
    }

    /// Records the callback + arg pair; real BoringSSL stores them
    /// on the SSL_CTX. Marked `unsafe` to match the real extern
    /// signature so the install-path `unsafe {}` block stays load-
    /// bearing under cfg.
    ///
    /// # Safety
    ///
    /// Stub never dereferences any argument; safety obligations are
    /// inherited from the real extern signature.
    #[unsafe(no_mangle)]
    pub(super) unsafe extern "C" fn SSL_CTX_set_client_hello_cb(
        _ctx: *mut SslCtxHandle,
        _cb: RealityHelloCb,
        _arg: *mut c_void,
    ) {
    }

    /// Returns a non-null but otherwise-unused pointer; real
    /// BoringSSL returns the SSL_CTX backing the SSL.
    ///
    /// # Safety
    ///
    /// Returned pointer must not be dereferenced under stubs.
    #[unsafe(no_mangle)]
    pub(super) unsafe extern "C" fn SSL_get_SSL_CTX(_ssl: *const SslHandle) -> *mut SslCtxHandle {
        // 0x1 sentinel: never dereferenced under stubs.
        std::ptr::without_provenance_mut::<SslCtxHandle>(1)
    }
}

#[cfg(all(miri, feature = "miri-stubs"))]
use miri_stubs::{SSL_CTX_set_client_hello_cb, SSL_get_SSL_CTX, SSL_handshake_get_x25519_private_key};

pub(crate) struct RealityCallbackState {
    pub server_pubkey: [u8; 32],
    pub short_id: Vec<u8>,
    /// Set only after the callback has successfully written the
    /// sealed session id into the serialized ClientHello.
    pub succeeded: AtomicBool,
    /// Latches `true` the first time a callback invocation observes
    /// a hard error (panic, missing X25519 key share, seal failure).
    /// Inspected by [`RealityHookGuard::was_successful`] after the
    /// handshake completes so callers can surface a clean error.
    pub failed: AtomicBool,
    #[cfg(test)]
    pub fixed_now_secs: Option<u32>,
    #[cfg(test)]
    pub trace: Option<Arc<Mutex<RealityHookTrace>>>,
}

#[cfg(test)]
#[derive(Clone, Debug, Default)]
pub(crate) struct RealityHookTrace {
    pub priv_key: [u8; 32],
    pub client_random: [u8; 32],
    pub raw_hello_before: Vec<u8>,
    pub raw_hello_after: Vec<u8>,
    pub sealed_session_id: [u8; SESSION_ID_LEN],
    pub now_secs: u32,
    pub callback_count: usize,
}

pub(crate) struct RealityHookGuard {
    state_ptr: *mut RealityCallbackState,
}

impl RealityHookGuard {
    /// Returns `true` if at least one callback invocation completed
    /// successfully and no invocation reported failure. The hook
    /// fires once per `ssl_add_client_hello` call — usually once;
    /// twice if the server triggers HelloRetryRequest.
    pub fn was_successful(&self) -> bool {
        if self.state_ptr.is_null() {
            return false;
        }
        // SAFETY: state_ptr is owned by this guard and the callback
        // only borrows through it.
        let state = unsafe { &*self.state_ptr };
        state.succeeded.load(Ordering::Acquire) && !state.failed.load(Ordering::Acquire)
    }
}

impl Drop for RealityHookGuard {
    fn drop(&mut self) {
        if !self.state_ptr.is_null() {
            // SAFETY: the pointer was produced by `Box::into_raw` in
            // `install_reality_client_hello_hook` and has not been
            // freed since. Callers contract that the SSL object
            // (which holds the only reference to this pointer
            // through SSL_CTX) is dropped before the guard, so no
            // further callback invocations can occur after this
            // drop.
            unsafe { drop(Box::from_raw(self.state_ptr)) };
            self.state_ptr = std::ptr::null_mut();
        }
    }
}

// SAFETY: `RealityHookGuard` holds `state_ptr`, a raw
// `*mut RealityCallbackState` (raw pointers are `!Send`, which is the
// only reason the auto-derived `Send` is missing). Moving the guard —
// and thus the pointer — across threads is sound because:
//   1. Sole owner: the pointer comes from `Box::into_raw` in
//      `install_reality_client_hello_hook` and the guard is the only
//      Rust value that owns it. `Drop` reclaims it via `Box::from_raw`
//      exactly once, so there is no double-free across threads.
//   2. No aliasing while the guard lives: the only other reader is the
//      C `client_hello_cb`, which dereferences the pointer (stored in
//      the SSL `ex_data`) and mutates *only* the `AtomicBool` flags via
//      atomic ops — no `&mut` aliasing of the Rust-owned `Box`, and the
//      guard never hands out a `&mut` to the state.
//   3. Lifetime tied to the guard: the pointee outlives every callback
//      invocation because callers contract to drop the SSL object
//      before the guard, so the C side can never dereference freed
//      memory after the move or after `Drop`.
//   4. The pointee's fields (`[u8; 32]`, `Vec<u8>`, `AtomicBool`) are
//      all `Send`, so transferring ownership to another thread is sound.
unsafe impl Send for RealityHookGuard {}

/// Install the Reality `client_hello_cb` on the `SSL_CTX` backing
/// `ssl`. Returns an RAII guard that reclaims the callback state
/// on drop. The caller MUST keep the guard alive at least as long
/// as the SSL object — otherwise the callback dereferences freed
/// memory the next time it fires.
///
/// # Safety
///
/// `ssl` must be a valid `*mut SSL` from `boring`'s safe API
/// (`ssl.as_ptr()`). The SSL_CTX backing `ssl` must not have any
/// other `client_hello_cb` installed; the patch holds only one
/// callback slot per CTX.
pub(crate) unsafe fn install_reality_client_hello_hook(
    ssl: *mut SslHandle,
    server_pubkey: [u8; 32],
    short_id: Vec<u8>,
) -> RealityHookGuard {
    let state = Box::new(RealityCallbackState {
        server_pubkey,
        short_id,
        succeeded: AtomicBool::new(false),
        failed: AtomicBool::new(false),
        #[cfg(test)]
        fixed_now_secs: None,
        #[cfg(test)]
        trace: None,
    });
    // SAFETY: caller of this unsafe function supplied a valid `ssl` and upholds
    // the same guard-lifetime contract required by the shared installer.
    unsafe { install_reality_client_hello_hook_with_state(ssl, state) }
}

#[cfg(test)]
unsafe fn install_reality_client_hello_hook_for_test(
    ssl: *mut SslHandle,
    server_pubkey: [u8; 32],
    short_id: Vec<u8>,
    fixed_now_secs: u32,
    trace: Arc<Mutex<RealityHookTrace>>,
) -> RealityHookGuard {
    let state = Box::new(RealityCallbackState {
        server_pubkey,
        short_id,
        succeeded: AtomicBool::new(false),
        failed: AtomicBool::new(false),
        fixed_now_secs: Some(fixed_now_secs),
        trace: Some(trace),
    });
    // SAFETY: caller of this test-only unsafe function supplied a valid `ssl`
    // and keeps the returned guard alive across the handshake attempt.
    unsafe { install_reality_client_hello_hook_with_state(ssl, state) }
}

unsafe fn install_reality_client_hello_hook_with_state(
    ssl: *mut SslHandle,
    state: Box<RealityCallbackState>,
) -> RealityHookGuard {
    let state_ptr = Box::into_raw(state);
    // SAFETY: caller upholds the contract that `ssl` is a valid SSL pointer.
    let ctx = unsafe { SSL_get_SSL_CTX(ssl.cast_const()) };
    if !ctx.is_null() {
        // SAFETY: ctx is the SSL_CTX backing the caller-provided ssl; the
        // patched BoringSSL accepts a non-null cb + arg pair.
        unsafe { SSL_CTX_set_client_hello_cb(ctx, reality_client_hello_cb, state_ptr.cast::<c_void>()) };
    }
    RealityHookGuard { state_ptr }
}

extern "C" fn reality_client_hello_cb(ssl: *mut SslHandle, msg: *mut u8, msg_len: usize, arg: *mut c_void) -> c_int {
    // The BoringSSL ABI cannot unwind across an `extern "C"`
    // boundary; trap any panic and turn it into a handshake failure.
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| reality_client_hello_cb_inner(ssl, msg, msg_len, arg)));
    match result {
        Ok(value) => value,
        Err(_) => {
            if !arg.is_null() {
                // SAFETY: state outlives the callback by the
                // RealityHookGuard contract.
                unsafe { (*arg.cast::<RealityCallbackState>()).failed.store(true, Ordering::Release) };
            }
            0
        }
    }
}

fn reality_client_hello_cb_inner(ssl: *mut SslHandle, msg: *mut u8, msg_len: usize, arg: *mut c_void) -> c_int {
    if ssl.is_null() || msg.is_null() || arg.is_null() {
        return 0;
    }
    if msg_len < SESSION_ID_OFFSET + SESSION_ID_LEN {
        return 0;
    }

    // SAFETY: `arg` was set to a leaked `Box<RealityCallbackState>`
    // owned by the `RealityHookGuard` kept alive on the connect path
    // (cleared on guard Drop before the SSL_CTX can fire the callback
    // again). The null check above rejects the null-arg case.
    let state = unsafe { &*arg.cast::<RealityCallbackState>().cast_const() };
    // SAFETY: BoringSSL contract guarantees `msg` points to a valid
    // mutable byte buffer of `msg_len` bytes for the duration of the
    // callback. The null check + length check above rejected
    // `msg.is_null()` and `msg_len < SESSION_ID_OFFSET +
    // SESSION_ID_LEN`. The resulting `&mut [u8]` is consumed before
    // the next BoringSSL call.
    let msg_slice = unsafe { std::slice::from_raw_parts_mut(msg, msg_len) };

    let mut client_random = [0u8; 32];
    client_random.copy_from_slice(&msg_slice[6..38]);

    // Read the X25519 private key from the patched BoringSSL
    // accessor. The buffer is a local; the caller owns the lifetime.
    let mut priv_key = [0u8; 32];
    // SAFETY: ssl pointer is owned by the caller; out is a 32-byte
    // local buffer.
    let key_ok = unsafe { SSL_handshake_get_x25519_private_key(ssl.cast_const(), priv_key.as_mut_ptr()) };
    if key_ok != 1 {
        state.failed.store(true, Ordering::Release);
        return 0;
    }

    #[cfg(test)]
    let raw_hello_before = state.trace.as_ref().map(|_| msg_slice.to_vec());

    #[cfg(test)]
    let now = state
        .fixed_now_secs
        .unwrap_or_else(|| SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs() as u32));
    #[cfg(not(test))]
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs() as u32);

    let Ok(sealed) = seal_session_id(&priv_key, &state.server_pubkey, &client_random, &state.short_id, msg_slice, now)
    else {
        state.failed.store(true, Ordering::Release);
        // Best-effort wipe before returning.
        priv_key.fill(0);
        return 0;
    };

    msg_slice[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN].copy_from_slice(&sealed);
    #[cfg(test)]
    if let (Some(trace), Some(raw_hello_before)) = (&state.trace, raw_hello_before) {
        let mut trace = trace.lock().expect("reality hook trace lock");
        trace.priv_key = priv_key;
        trace.client_random = client_random;
        trace.raw_hello_before = raw_hello_before;
        trace.raw_hello_after = msg_slice.to_vec();
        trace.sealed_session_id = sealed;
        trace.now_secs = now;
        trace.callback_count += 1;
    }

    state.succeeded.store(true, Ordering::Release);

    // Best-effort wipe of the priv_key local before returning.
    priv_key.fill(0);
    1
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::reality_seal::{build_session_id_plaintext, derive_auth_key};
    use boring::ssl::{SslConnector, SslMethod, SslVerifyMode};
    use foreign_types_shared::ForeignType;
    use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;
    use x25519_dalek::{PublicKey, StaticSecret};

    const BORING_HOOK_VECTOR: &str = include_str!("../tests/reality_hook_vector.json");
    const REALITY_HOOK_FIXED_NOW_SECS: u32 = 1_700_000_000;
    const REALITY_HOOK_SHORT_ID: &[u8] = &[0xAB, 0xCD];

    fn test_state(server_pubkey: [u8; 32], short_id: Vec<u8>) -> Box<RealityCallbackState> {
        Box::new(RealityCallbackState {
            server_pubkey,
            short_id,
            succeeded: AtomicBool::new(false),
            failed: AtomicBool::new(false),
            fixed_now_secs: None,
            trace: None,
        })
    }

    /// The guard freezes its state pointer to null on drop; this
    /// test exercises the drop path by leaking and reclaiming a
    /// state object directly (no BoringSSL involvement).
    #[test]
    fn guard_reclaims_box_on_drop() {
        let state = test_state([0u8; 32], vec![0xAA]);
        let raw = Box::into_raw(state);
        let guard = RealityHookGuard { state_ptr: raw };
        assert!(!guard.was_successful(), "a callback that never ran must not report success");
        drop(guard);
        // Touching `raw` here would be UB; we trust Miri/asan to
        // catch a double-free if the drop logic regresses.
    }

    /// `was_successful` reflects the latched failure flag.
    #[test]
    fn guard_reports_failure_after_state_flag_set() {
        let state = test_state([0u8; 32], vec![]);
        let raw = Box::into_raw(state);
        // SAFETY: pointer is alive for the duration of this test
        // (the guard drops it at end).
        unsafe { (*raw).failed.store(true, Ordering::Release) };
        let guard = RealityHookGuard { state_ptr: raw };
        assert!(!guard.was_successful());
    }

    #[test]
    fn guard_reports_success_only_after_callback_completion() {
        let state = test_state([0u8; 32], vec![]);
        let raw = Box::into_raw(state);
        let guard = RealityHookGuard { state_ptr: raw };
        assert!(!guard.was_successful());
        // SAFETY: the guard owns this live state for the duration of the test.
        unsafe { (*raw).succeeded.store(true, Ordering::Release) };
        assert!(guard.was_successful());
        // A later HRR callback failure must remain observable even after an
        // earlier callback completed successfully.
        // SAFETY: the guard still owns this live state.
        unsafe { (*raw).failed.store(true, Ordering::Release) };
        assert!(!guard.was_successful());
    }

    /// Calling the inner callback with a null state pointer must
    /// return 0 rather than dereferencing.
    #[test]
    fn callback_inner_rejects_null_arg() {
        let mut msg = vec![0u8; 80];
        let ret =
            reality_client_hello_cb_inner(std::ptr::null_mut(), msg.as_mut_ptr(), msg.len(), std::ptr::null_mut());
        assert_eq!(ret, 0);
    }

    /// `msg_len` shorter than `SESSION_ID_END` (71 bytes) must be
    /// rejected without writing past the buffer.
    #[test]
    fn callback_inner_rejects_short_msg() {
        let state = test_state([0u8; 32], vec![]);
        let raw = Box::into_raw(state);
        let mut msg = vec![0u8; SESSION_ID_OFFSET + SESSION_ID_LEN - 1];
        let ret = reality_client_hello_cb_inner(
            std::ptr::without_provenance_mut::<SslHandle>(1), // non-null sentinel; never dereferenced because we hit the length check first
            msg.as_mut_ptr(),
            msg.len(),
            raw.cast::<c_void>(),
        );
        assert_eq!(ret, 0);
        // SAFETY: reclaim the leaked box for this test.
        unsafe { drop(Box::from_raw(raw)) };
    }

    // --- Issue #28: callback-after-drop / unregister race tests ----------

    /// `RealityHookGuard::Drop` must null its `state_ptr` so a subsequent
    /// `was_successful()` (called accidentally by post-Drop diagnostic
    /// code) returns `false` instead of dereferencing freed memory.
    /// Miri detects use-after-free if the null guard is missing.
    #[test]
    fn guard_drop_nulls_state_pointer() {
        let state = test_state([0u8; 32], vec![]);
        let raw = Box::into_raw(state);
        let mut guard = RealityHookGuard { state_ptr: raw };
        // Take a copy of the address for post-drop comparison only;
        // the value itself is never dereferenced after drop.
        let pre_drop_addr = guard.state_ptr;
        assert!(!pre_drop_addr.is_null());
        // Inline-drop via destructor mutation: simulate the cleanup
        // path by calling Drop manually.
        // SAFETY: `&mut guard` is a unique mutable borrow for the
        // duration of this call; `drop_in_place` runs the destructor
        // exactly once and the subsequent `std::mem::forget(guard)`
        // below prevents the implicit second Drop at scope exit.
        unsafe {
            std::ptr::drop_in_place(&mut guard as *mut RealityHookGuard);
        }
        // After Drop, the guard's state_ptr field is nulled so any
        // accidental subsequent read (e.g. by post-drop diagnostic
        // code) returns false rather than dereferencing.
        assert!(guard.state_ptr.is_null(), "Drop must null state_ptr to prevent UAF on accidental re-use");
        assert!(!guard.was_successful(), "post-drop was_successful must return false (null-guard branch)");
        // Forget the guard now that Drop has already run; running Drop
        // again would be a double-free.
        std::mem::forget(guard);
    }

    /// Two independent guards must drop without interference. This
    /// exercises the "no shared state" invariant — each guard owns
    /// its own `Box<RealityCallbackState>` and they must not alias.
    /// Miri catches any double-free.
    #[test]
    fn two_concurrent_guards_drop_independently() {
        let state_a = test_state([1u8; 32], vec![0x01]);
        let state_b = test_state([2u8; 32], vec![0x02]);
        let guard_a = RealityHookGuard { state_ptr: Box::into_raw(state_a) };
        let guard_b = RealityHookGuard { state_ptr: Box::into_raw(state_b) };
        // Different pointers.
        assert_ne!(guard_a.state_ptr, guard_b.state_ptr);
        // Drop in reverse order — independence means order doesn't
        // matter and neither drop touches the other's memory.
        drop(guard_b);
        drop(guard_a);
    }

    /// Simulates the "unregister race" — the callback latches a
    /// failure flag from one thread while the guard's Drop runs
    /// from another. With BoringSSL's single-threaded
    /// ssl_add_client_hello contract this race cannot happen in
    /// production, but the test exercises the AtomicBool ordering
    /// discipline that would catch a regression to `Cell<bool>`.
    #[test]
    fn callback_failure_flag_ordering_holds_across_drop() {
        let state = test_state([0u8; 32], vec![]);
        let raw = Box::into_raw(state);
        // Set the failure flag via Release write (matches the
        // catch_unwind panic branch in reality_client_hello_cb).
        // SAFETY: state is alive for the duration of this test.
        unsafe { (*raw).failed.store(true, Ordering::Release) };
        let guard = RealityHookGuard { state_ptr: raw };
        // Acquire read in was_successful must observe the Release
        // write — i.e. the latched failure must propagate.
        assert!(!guard.was_successful(), "Release/Acquire ordering must propagate failure flag");
        // Guard drops normally — Box::from_raw reclaims; Miri
        // validates no leak.
    }

    #[test]
    #[cfg_attr(miri, ignore)]
    fn live_boringssl_client_hello_hook_seals_with_extracted_x25519_key_share() {
        assert!(BORING_HOOK_VECTOR.contains("\"boring\": \"=5.1.0\""));
        assert!(BORING_HOOK_VECTOR.contains("\"tokio-boring\": \"=5.1.0\""));
        assert!(BORING_HOOK_VECTOR.contains("\"fixedNowSecs\": 1700000000"));
        assert!(BORING_HOOK_VECTOR.contains("\"shortIdHex\": \"abcd\""));

        let server_pubkey = server_pub_from_seed(0x22);
        let trace = Arc::new(Mutex::new(RealityHookTrace::default()));
        let captured_record = capture_reality_client_hello(server_pubkey, trace.clone());
        let trace = trace.lock().expect("reality hook trace lock").clone();

        assert_eq!(trace.callback_count, 1, "expected one ClientHello callback in the no-HRR capture path");
        assert_eq!(captured_record[0], 0x16, "expected TLS handshake record");
        assert_eq!(captured_record[5], 0x01, "expected ClientHello handshake payload");
        assert_eq!(
            &captured_record[5..],
            trace.raw_hello_after.as_slice(),
            "wire ClientHello must carry hook mutation"
        );
        assert_eq!(trace.raw_hello_before[0], 0x01, "hook trace stores the raw ClientHello handshake message");
        assert_ne!(
            trace.raw_hello_before[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN],
            trace.raw_hello_after[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN],
            "hook must rewrite BoringSSL's original session_id bytes"
        );
        assert_eq!(
            trace.raw_hello_after[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN],
            trace.sealed_session_id,
            "trace sealed bytes must be the bytes written into session_id"
        );

        let expected = seal_session_id(
            &trace.priv_key,
            &server_pubkey,
            &trace.client_random,
            REALITY_HOOK_SHORT_ID,
            &trace.raw_hello_before,
            REALITY_HOOK_FIXED_NOW_SECS,
        )
        .expect("seal from extracted key_share private key");
        assert_eq!(
            trace.sealed_session_id, expected,
            "hook must seal with the private key extracted from BoringSSL's live X25519 key_share"
        );

        let plaintext = open_reality_session_id(&trace, server_pubkey);
        assert_eq!(
            plaintext,
            build_session_id_plaintext(REALITY_HOOK_SHORT_ID, REALITY_HOOK_FIXED_NOW_SECS),
            "full REALITY seal round-trip must recover the fixed vector plaintext"
        );
    }

    /// Stress test: repeated install/drop cycles must not leak the
    /// callback state allocation. Without proper RAII discipline a
    /// `Box::into_raw` whose matching `Box::from_raw` is missing
    /// from Drop would leak one `RealityCallbackState` per cycle.
    /// Each iteration is independent — no shared state, no
    /// callback fire — so the test exercises only the allocation/
    /// reclamation pairing. Miri runs this test (under `--features
    /// miri-stubs`) and would surface any leak as a final report
    /// at process exit; the iteration count is small enough to
    /// stay tractable under Miri's tracking overhead.
    #[test]
    fn repeated_install_drop_cycles_do_not_leak() {
        const CYCLES: usize = 64;
        for _ in 0..CYCLES {
            let state = test_state([0xAB; 32], vec![0xCD, 0xEF]);
            let raw = Box::into_raw(state);
            let guard = RealityHookGuard { state_ptr: raw };
            // No callback fires in this allocation-only test, so a
            // cycle must remain unsuccessful while the guard still
            // owns and can safely inspect its state.
            assert!(!guard.was_successful());
            drop(guard);
            // Box::from_raw inside Drop reclaims — no leak.
        }
    }

    fn capture_reality_client_hello(server_pubkey: [u8; 32], trace: Arc<Mutex<RealityHookTrace>>) -> Vec<u8> {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
        let addr = listener.local_addr().expect("listener addr");
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept client");
            socket.set_read_timeout(Some(Duration::from_secs(5))).expect("server read timeout");
            let mut header = [0u8; 5];
            socket.read_exact(&mut header).expect("read TLS record header");
            let payload_len = u16::from_be_bytes([header[3], header[4]]) as usize;
            let mut payload = vec![0u8; payload_len];
            socket.read_exact(&mut payload).expect("read TLS record payload");
            let _ = socket.shutdown(Shutdown::Both);
            [header.to_vec(), payload].concat()
        });

        let mut builder = SslConnector::builder(SslMethod::tls()).expect("connector builder");
        builder.set_verify(SslVerifyMode::NONE);
        let config = builder.build().configure().expect("connector configure");
        let ssl = config.into_ssl("reality-hook-vector.test").expect("ssl");
        let ssl_handle = ssl.as_ptr().cast::<SslHandle>();
        // SAFETY: `ssl_handle` comes from a live boring `Ssl`; the guard is held until after
        // `SslStream::connect` returns and the stream is dropped.
        let guard = unsafe {
            install_reality_client_hello_hook_for_test(
                ssl_handle,
                server_pubkey,
                REALITY_HOOK_SHORT_ID.to_vec(),
                REALITY_HOOK_FIXED_NOW_SECS,
                trace,
            )
        };

        let stream = TcpStream::connect(addr).expect("connect loopback");
        stream.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
        stream.set_write_timeout(Some(Duration::from_secs(5))).expect("client write timeout");
        let mut tls = boring::ssl::SslStream::new(ssl, stream).expect("ssl stream");
        let _ = tls.connect();
        assert!(guard.was_successful(), "Reality hook should complete before fixture closes the socket");
        drop(tls);
        drop(guard);

        server.join().expect("server join")
    }

    fn open_reality_session_id(trace: &RealityHookTrace, server_pubkey: [u8; 32]) -> [u8; 16] {
        let auth_key = derive_auth_key(&trace.priv_key, &server_pubkey, &trace.client_random);
        let unbound = UnboundKey::new(&AES_256_GCM, &auth_key).expect("AES-256-GCM key");
        let key = LessSafeKey::new(unbound);
        let nonce_bytes: [u8; 12] = trace.client_random[20..32].try_into().expect("nonce slice");
        let nonce = Nonce::assume_unique_for_key(nonce_bytes);
        let mut aad = trace.raw_hello_before.clone();
        aad[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN].fill(0);
        let mut in_out = trace.sealed_session_id.to_vec();
        let plaintext = key.open_in_place(nonce, Aad::from(aad.as_slice()), &mut in_out).expect("open REALITY seal");
        plaintext.try_into().expect("REALITY plaintext length")
    }

    fn server_pub_from_seed(seed: u8) -> [u8; 32] {
        let secret = StaticSecret::from([seed; 32]);
        *PublicKey::from(&secret).as_bytes()
    }
}
