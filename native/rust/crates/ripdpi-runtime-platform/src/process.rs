//! OS-primitive adapter — process / thread / signal primitives.
//!
//! CPU-parallelism detection, shutdown signal-handler installation, and
//! double-fork daemonization. Uses `libc` / `nix` directly (no
//! `ripdpi-privileged-ops` round-trip) because these are unprivileged
//! process facilities, not network syscalls. Surfaced through the
//! `capability` facade — see the follow-up note in `README.md`.
//!
//! ## Unsafe surface
//!
//! Four `unsafe` calls, each with a per-call `// SAFETY:` note: the
//! `libc::sysconf(_SC_NPROCESSORS_ONLN)` query (always defined on
//! Linux/Android — the negative-return error case is checked at the call
//! site), the `nix::sys::signal::signal` install of this module's fixed,
//! async-signal-safe atomic handler, the two `nix::unistd::fork` calls in
//! [`daemonize`], and the `libc::umask` call in [`daemonize`].

use std::io;
use std::num::NonZeroUsize;
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

/// File-mode creation mask applied by [`daemonize`], matching the default of
/// the removed upstream `daemonize` crate so daemon-written files keep their
/// previous permission profile.
const DAEMON_UMASK: libc::mode_t = 0o027;

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

/// Detach the calling process into a background daemon.
///
/// Performs the classic double-fork sequence: the first fork lets the
/// invoking parent exit while the intermediate child is reparented to init,
/// `setsid` detaches from the controlling terminal, and the second fork
/// ensures the daemon can never reacquire one. The working directory becomes
/// `/`, the umask is tightened to [`DAEMON_UMASK`], and stdin, stdout, and
/// stderr are redirected to `/dev/null`.
///
/// The invoking parent waits for the intermediate child and exits with its
/// status, so startup failures raised before the second fork stay observable
/// to whoever started the daemon. Only the final daemon child returns; both
/// parent processes exit and never return normally.
pub fn daemonize() -> io::Result<()> {
    use nix::sys::wait::WaitStatus;
    use nix::unistd::{ForkResult, chdir, fork, setsid};

    // First fork: detach from the invoking shell. The parent mirrors the
    // intermediate child's exit status so early failures remain visible.
    let first = {
        // SAFETY: forking this single-threaded startup path is the documented
        // purpose of `daemonize`; the parent branch only waits and exits.
        unsafe { fork() }
    }
    .map_err(io::Error::from)?;
    if let ForkResult::Parent { child } = first {
        return match nix::sys::wait::waitpid(child, None) {
            Ok(WaitStatus::Exited(_, code)) => std::process::exit(code),
            Ok(_) => std::process::exit(1),
            Err(error) => Err(io::Error::from(error)),
        };
    }

    chdir("/").map_err(io::Error::from)?;
    setsid().map_err(io::Error::from)?;

    {
        // SAFETY: umask only mutates this process's file-mode creation mask
        // and cannot fail; the libc binding is a raw foreign call.
        let _ = unsafe { libc::umask(DAEMON_UMASK) };
    }

    // Second fork: the session leader exits so the daemon can never
    // reacquire a controlling terminal.
    let second = {
        // SAFETY: same single-threaded startup context as the first fork;
        // the intermediate parent only exits.
        unsafe { fork() }
    }
    .map_err(io::Error::from)?;
    if let ForkResult::Parent { .. } = second {
        std::process::exit(0);
    }

    redirect_standard_streams_to_devnull()
}

/// Point stdin, stdout, and stderr at `/dev/null` so the daemon neither
/// holds terminal descriptors nor emits output to them.
fn redirect_standard_streams_to_devnull() -> io::Result<()> {
    use nix::unistd::{dup2_stderr, dup2_stdin, dup2_stdout};
    use std::os::fd::AsFd;

    let devnull = std::fs::OpenOptions::new().read(true).write(true).open("/dev/null")?;
    dup2_stdin(devnull.as_fd()).map_err(io::Error::from)?;
    dup2_stdout(devnull.as_fd()).map_err(io::Error::from)?;
    dup2_stderr(devnull.as_fd()).map_err(io::Error::from)?;
    Ok(())
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
