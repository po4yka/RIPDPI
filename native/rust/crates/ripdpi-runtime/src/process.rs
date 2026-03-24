use std::fs::{self, File, OpenOptions};
use std::io;
use std::io::Write;
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::RuntimeConfig;

static SHUTDOWN: AtomicBool = AtomicBool::new(false);

pub struct ProcessGuard {
    _pid_file: Option<PidFileGuard>,
}

impl ProcessGuard {
    pub fn prepare(config: &RuntimeConfig) -> io::Result<Self> {
        SHUTDOWN.store(false, Ordering::Release);
        if config.process.daemonize {
            daemonize()?;
        }
        install_signal_handlers()?;
        let pid_file = match config.process.pid_file.as_deref() {
            Some(path) => Some(PidFileGuard::create(Path::new(path))?),
            None => None,
        };
        Ok(Self { _pid_file: pid_file })
    }
}

pub fn shutdown_requested() -> bool {
    SHUTDOWN.load(Ordering::Acquire)
}

pub fn request_shutdown() {
    SHUTDOWN.store(true, Ordering::Release);
}

pub fn prepare_embedded() {
    SHUTDOWN.store(false, Ordering::Release);
}

extern "C" fn handle_signal(_signal: libc::c_int) {
    request_shutdown();
}

fn install_signal_handlers() -> io::Result<()> {
    use nix::sys::signal::{signal, SigHandler, Signal};
    for sig in [Signal::SIGINT, Signal::SIGTERM, Signal::SIGHUP] {
        // SAFETY: handle_signal only writes to an atomic bool, which is async-signal-safe.
        unsafe { signal(sig, SigHandler::Handler(handle_signal)) }
            .map_err(|e| io::Error::from_raw_os_error(e as i32))?;
    }
    Ok(())
}

#[allow(deprecated)]
fn daemonize() -> io::Result<()> {
    // daemon(false, false): chdir to "/" and redirect stdio to /dev/null.
    // nix::unistd::daemon is only available on Linux/Android/FreeBSD/Solaris/NetBSD;
    // fall back to the raw libc call on other platforms (e.g. macOS for local dev).
    #[cfg(any(
        target_os = "linux",
        target_os = "android",
        target_os = "freebsd",
        target_os = "solaris",
        target_os = "illumos",
        target_os = "netbsd",
        target_os = "openbsd"
    ))]
    {
        nix::unistd::daemon(false, false).map_err(|e| io::Error::from_raw_os_error(e as i32))
    }
    #[cfg(not(any(
        target_os = "linux",
        target_os = "android",
        target_os = "freebsd",
        target_os = "solaris",
        target_os = "illumos",
        target_os = "netbsd",
        target_os = "openbsd"
    )))]
    {
        let rc = unsafe { libc::daemon(0, 0) };
        if rc == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

struct PidFileGuard {
    file: File,
    path: PathBuf,
}

impl PidFileGuard {
    fn create(path: &Path) -> io::Result<Self> {
        let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;

        let mut lock =
            libc::flock { l_type: libc::F_WRLCK as _, l_whence: libc::SEEK_CUR as _, l_start: 0, l_len: 0, l_pid: 0 };
        let rc = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &mut lock) };
        if rc != 0 {
            return Err(io::Error::last_os_error());
        }

        file.set_len(0)?;
        write!(file, "{}", std::process::id())?;
        file.flush()?;

        Ok(Self { file, path: path.to_path_buf() })
    }
}

impl Drop for PidFileGuard {
    fn drop(&mut self) {
        let _ = self.file.flush();
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_pid_path() -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).expect("system clock before unix epoch").as_nanos();
        std::env::temp_dir().join(format!("ripdpi-process-{stamp}.pid"))
    }

    #[test]
    fn prepare_resets_shutdown_state() {
        request_shutdown();
        assert!(shutdown_requested());

        let guard = ProcessGuard::prepare(&RuntimeConfig::default()).expect("prepare process guard");

        assert!(!shutdown_requested());
        drop(guard);
    }

    #[test]
    fn prepare_with_pid_file_writes_and_removes_pidfile() {
        let path = temp_pid_path();
        let mut config = RuntimeConfig::default();
        config.process.pid_file = Some(path.display().to_string());

        {
            let guard = ProcessGuard::prepare(&config).expect("prepare process guard with pidfile");
            let contents = std::fs::read_to_string(&path).expect("pidfile contents");
            assert_eq!(contents, std::process::id().to_string());
            drop(guard);
        }

        assert!(!path.exists(), "pidfile should be removed on drop");
    }
}
