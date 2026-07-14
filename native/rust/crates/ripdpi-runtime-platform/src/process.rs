//! OS-primitive adapter — process / thread / signal primitives.
//!
//! CPU-parallelism detection and shutdown signal-handler installation. Uses
//! `libc` / `nix` directly (no `ripdpi-privileged-ops` round-trip) because
//! these are unprivileged process facilities, not network syscalls. Surfaced
//! through the `capability` facade — see the follow-up note in `README.md`.
//!
//! ## Unsafe surface
//!
//! Two `unsafe` calls, each with a per-call `// SAFETY:` note: the
//! `libc::sysconf(_SC_NPROCESSORS_ONLN)` query (always defined on
//! Linux/Android — the negative-return error case is checked at the call
//! site) and the `nix::sys::signal::signal` install of this module's fixed,
//! async-signal-safe atomic handler.

use std::io;
use std::num::NonZeroUsize;
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

static SHUTDOWN_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn detected_parallelism(fallback: usize) -> usize {
    // On Android, std::thread::available_parallelism() reads cgroup files
    // that SELinux denies on Android 14+, polluting logcat with avc: denied
    // entries. Use sysconf(_SC_NPROCESSORS_ONLN) directly to skip that probe.
    #[cfg(target_os = "android")]
    {
        // SAFETY: sysconf(_SC_NPROCESSORS_ONLN) is always defined on
        // Linux/Android; a negative return indicates an error, checked below.
        let n = unsafe { libc::sysconf(libc::_SC_NPROCESSORS_ONLN) };
        if n > 0 {
            return n as usize;
        }
    }
    thread::available_parallelism().map_or(fallback, NonZeroUsize::get)
}

extern "C" fn handle_shutdown_signal(_signal: c_int) {
    SHUTDOWN_REQUESTED.store(true, Ordering::Release);
}

/// Install the fixed process shutdown handler for SIGINT, SIGTERM, and SIGHUP.
pub fn install_shutdown_signal_handlers() -> io::Result<()> {
    use nix::sys::signal::{SigHandler, Signal, signal};

    for sig in [Signal::SIGINT, Signal::SIGTERM, Signal::SIGHUP] {
        // SAFETY: `handle_shutdown_signal` performs one lock-free AtomicBool
        // store, does not allocate or unwind, and has process lifetime.
        unsafe { signal(sig, SigHandler::Handler(handle_shutdown_signal)) }.map_err(io::Error::from)?;
    }
    Ok(())
}

#[must_use]
pub fn shutdown_requested() -> bool {
    SHUTDOWN_REQUESTED.load(Ordering::Acquire)
}

pub fn reset_shutdown_request() {
    SHUTDOWN_REQUESTED.store(false, Ordering::Release);
}

pub fn request_shutdown() {
    SHUTDOWN_REQUESTED.store(true, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shutdown_state_is_owned_by_fixed_platform_handler() {
        reset_shutdown_request();
        assert!(!shutdown_requested());

        handle_shutdown_signal(libc::SIGTERM);

        assert!(shutdown_requested());
        reset_shutdown_request();
    }
}
