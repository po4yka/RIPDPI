use std::io;
#[cfg(test)]
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};

pub use ripdpi_proxy_runtime_adapter::model::config::{process_settings, ProcessSettings};
use ripdpi_proxy_runtime_adapter::platform::process as process_platform;

static SHUTDOWN: AtomicBool = AtomicBool::new(false);

pub struct ProcessGuard {
    _inner: process_platform::ProcessGuard,
}

impl ProcessGuard {
    pub fn prepare(settings: ProcessSettings) -> io::Result<Self> {
        SHUTDOWN.store(false, Ordering::Release);
        let inner = process_platform::ProcessGuard::prepare(settings)?;
        install_signal_handlers()?;
        Ok(Self { _inner: inner })
    }
}

pub(crate) fn shutdown_requested() -> bool {
    SHUTDOWN.load(Ordering::Acquire)
}

fn request_shutdown() {
    SHUTDOWN.store(true, Ordering::Release);
}

pub fn prepare_embedded() {
    SHUTDOWN.store(false, Ordering::Release);
}

extern "C" fn handle_signal(_signal: std::os::raw::c_int) {
    request_shutdown();
}

fn install_signal_handlers() -> io::Result<()> {
    process_platform::install_shutdown_signal_handlers(handle_signal)
}

#[cfg(test)]
mod tests {
    use std::sync::{LazyLock, Mutex};

    use super::*;
    use ripdpi_proxy_runtime_adapter::model::config::ProcessSettings;

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

        let guard = ProcessGuard::prepare(ProcessSettings { daemonize: false, pid_file_path: None })
            .expect("prepare process guard");

        assert!(!shutdown_requested());
        drop(guard);
    }

    #[test]
    fn prepare_with_pid_file_writes_and_removes_pidfile() {
        let _lock = PROCESS_TEST_MUTEX.lock().expect("lock process test mutex");
        let path = temp_pid_path();
        let settings = ProcessSettings { daemonize: false, pid_file_path: Some(path.clone()) };

        {
            let guard = ProcessGuard::prepare(settings).expect("prepare process guard with pidfile");
            let contents = std::fs::read_to_string(&path).expect("pidfile contents");
            assert_eq!(contents, std::process::id().to_string());
            drop(guard);
        }

        assert!(!path.exists(), "pidfile should be removed on drop");
    }
}
