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
//! site) and the `nix::sys::signal::signal` handler install (the
//! caller-supplied handler must be async-signal-safe).

use std::io;
use std::num::NonZeroUsize;
use std::os::raw::c_int;
use std::thread;

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

pub fn install_shutdown_signal_handlers(handler: extern "C" fn(c_int)) -> io::Result<()> {
    use nix::sys::signal::{signal, SigHandler, Signal};

    for sig in [Signal::SIGINT, Signal::SIGTERM, Signal::SIGHUP] {
        // SAFETY: the caller-provided handler must be async-signal-safe.
        unsafe { signal(sig, SigHandler::Handler(handler)) }.map_err(io::Error::from)?;
    }
    Ok(())
}
