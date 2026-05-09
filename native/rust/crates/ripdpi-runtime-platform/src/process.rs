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
    thread::available_parallelism().map(NonZeroUsize::get).unwrap_or(fallback)
}

pub fn install_shutdown_signal_handlers(handler: extern "C" fn(c_int)) -> io::Result<()> {
    use nix::sys::signal::{signal, SigHandler, Signal};

    for sig in [Signal::SIGINT, Signal::SIGTERM, Signal::SIGHUP] {
        // SAFETY: the caller-provided handler must be async-signal-safe.
        unsafe { signal(sig, SigHandler::Handler(handler)) }.map_err(io::Error::from)?;
    }
    Ok(())
}
