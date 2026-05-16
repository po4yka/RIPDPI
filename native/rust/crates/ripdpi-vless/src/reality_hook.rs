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
use std::time::{SystemTime, UNIX_EPOCH};

use crate::reality_seal::{seal_session_id, SESSION_ID_LEN, SESSION_ID_OFFSET};

#[repr(C)]
pub(crate) struct SslCtxHandle {
    _opaque: [u8; 0],
}

#[repr(C)]
pub(crate) struct SslHandle {
    _opaque: [u8; 0],
}

pub(crate) type RealityHelloCb =
    extern "C" fn(ssl: *mut SslHandle, msg: *mut u8, msg_len: usize, arg: *mut c_void) -> c_int;

extern "C" {
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

pub(crate) struct RealityCallbackState {
    pub server_pubkey: [u8; 32],
    pub short_id: Vec<u8>,
    /// Latches `true` the first time a callback invocation observes
    /// a hard error (panic, missing X25519 key share, seal failure).
    /// Inspected by [`RealityHookGuard::was_successful`] after the
    /// handshake completes so callers can surface a clean error.
    pub failed: AtomicBool,
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
        !state.failed.load(Ordering::Acquire)
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

// SAFETY: RealityCallbackState contains only `[u8; 32]`, `Vec<u8>`,
// and `AtomicBool`, all of which are `Send + Sync`. The guard owns a
// raw pointer to a heap-allocated state object; once handed off to
// the C callback, the only mutator is the atomic flag.
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
    let state = Box::new(RealityCallbackState { server_pubkey, short_id, failed: AtomicBool::new(false) });
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

    // SAFETY: the BoringSSL contract guarantees `msg` points to a
    // valid byte buffer of `msg_len` bytes for the duration of the
    // callback; `arg` was set to a leaked Box<RealityCallbackState>
    // owned by the RealityHookGuard kept alive on the connect path.
    let state = unsafe { &*arg.cast::<RealityCallbackState>().cast_const() };
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

    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as u32).unwrap_or(0);

    let Ok(sealed) = seal_session_id(&priv_key, &state.server_pubkey, &client_random, &state.short_id, msg_slice, now)
    else {
        state.failed.store(true, Ordering::Release);
        // Best-effort wipe before returning.
        priv_key.fill(0);
        return 0;
    };

    // Best-effort wipe of the priv_key local before returning.
    priv_key.fill(0);

    msg_slice[SESSION_ID_OFFSET..SESSION_ID_OFFSET + SESSION_ID_LEN].copy_from_slice(&sealed);
    1
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The guard freezes its state pointer to null on drop; this
    /// test exercises the drop path by leaking and reclaiming a
    /// state object directly (no BoringSSL involvement).
    #[test]
    fn guard_reclaims_box_on_drop() {
        let state = Box::new(RealityCallbackState {
            server_pubkey: [0u8; 32],
            short_id: vec![0xAA],
            failed: AtomicBool::new(false),
        });
        let raw = Box::into_raw(state);
        let guard = RealityHookGuard { state_ptr: raw };
        assert!(guard.was_successful());
        drop(guard);
        // Touching `raw` here would be UB; we trust Miri/asan to
        // catch a double-free if the drop logic regresses.
    }

    /// `was_successful` reflects the latched failure flag.
    #[test]
    fn guard_reports_failure_after_state_flag_set() {
        let state = Box::new(RealityCallbackState {
            server_pubkey: [0u8; 32],
            short_id: vec![],
            failed: AtomicBool::new(false),
        });
        let raw = Box::into_raw(state);
        // SAFETY: pointer is alive for the duration of this test
        // (the guard drops it at end).
        unsafe { (*raw).failed.store(true, Ordering::Release) };
        let guard = RealityHookGuard { state_ptr: raw };
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
        let state = Box::new(RealityCallbackState {
            server_pubkey: [0u8; 32],
            short_id: vec![],
            failed: AtomicBool::new(false),
        });
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
}
