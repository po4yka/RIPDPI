use std::fs::{self, File, OpenOptions};
use std::io;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};

use daemonize::Daemonize;
use nix::fcntl::{Flock, FlockArg};

use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;

static SHUTDOWN: AtomicBool = AtomicBool::new(false);

pub struct ProcessGuard {
    _pid_file: Option<PidFileGuard>,
}

impl ProcessGuard {
    pub fn prepare(config: &RuntimeConfig) -> io::Result<Self> {
        SHUTDOWN.store(false, Ordering::Release);
        let pid_file_path = config.process.pid_file.as_deref().map(PathBuf::from);
        if config.process.daemonize {
            daemonize(pid_file_path.as_deref())?;
        }
        install_signal_handlers()?;
        let pid_file = match (config.process.daemonize, pid_file_path) {
            (true, Some(path)) => Some(PidFileGuard::remove_on_drop(path)),
            (false, Some(path)) => Some(PidFileGuard::create(&path)?),
            (_, None) => None,
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
        unsafe { signal(sig, SigHandler::Handler(handle_signal)) }.map_err(io::Error::from)?;
    }
    Ok(())
}

fn daemonize(pid_file: Option<&Path>) -> io::Result<()> {
    let daemon = pid_file.map_or_else(Daemonize::new, |path| Daemonize::new().pid_file(path)).working_directory("/");
    daemon.start().map_err(io::Error::other)
}

struct PidFileGuard {
    file: Option<Flock<File>>,
    path: PathBuf,
}

impl PidFileGuard {
    fn create(path: &Path) -> io::Result<Self> {
        let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
        let lock = Flock::lock(file.try_clone()?, FlockArg::LockExclusiveNonblock)
            .map_err(|(_, error)| io::Error::from(error))?;

        file.set_len(0)?;
        write!(file, "{}", std::process::id())?;
        file.flush()?;

        Ok(Self { file: Some(lock), path: path.to_path_buf() })
    }

    fn remove_on_drop(path: PathBuf) -> Self {
        Self { file: None, path }
    }
}

impl Drop for PidFileGuard {
    fn drop(&mut self) {
        if let Some(file) = self.file.as_mut() {
            let _ = file.flush();
        }
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{LazyLock, Mutex};

    use super::*;

    static PROCESS_TEST_MUTEX: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

    fn temp_pid_path() -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).expect("system clock before unix epoch").as_nanos();
        std::env::temp_dir().join(format!("ripdpi-process-{stamp}.pid"))
    }

    #[test]
    fn prepare_resets_shutdown_state() {
        let _lock = PROCESS_TEST_MUTEX.lock().expect("lock process test mutex");
        request_shutdown();
        assert!(shutdown_requested());

        let guard = ProcessGuard::prepare(&RuntimeConfig::default()).expect("prepare process guard");

        assert!(!shutdown_requested());
        drop(guard);
    }

    #[test]
    fn prepare_with_pid_file_writes_and_removes_pidfile() {
        let _lock = PROCESS_TEST_MUTEX.lock().expect("lock process test mutex");
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
