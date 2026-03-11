#[cfg(unix)]
use std::fs::{self, File, OpenOptions};
use std::io;
#[cfg(unix)]
use std::io::Write;
#[cfg(unix)]
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::RuntimeConfig;

static SHUTDOWN: AtomicBool = AtomicBool::new(false);

pub struct ProcessGuard {
    #[cfg(unix)]
    _pid_file: Option<PidFileGuard>,
}

impl ProcessGuard {
    pub fn prepare(config: &RuntimeConfig) -> io::Result<Self> {
        SHUTDOWN.store(false, Ordering::Relaxed);
        #[cfg(unix)]
        {
            if config.daemonize {
                daemonize()?;
            }
            install_signal_handlers()?;
            let pid_file = match config.pid_file.as_deref() {
                Some(path) => Some(PidFileGuard::create(Path::new(path))?),
                None => None,
            };
            Ok(Self { _pid_file: pid_file })
        }

        #[cfg(not(unix))]
        {
            if config.daemonize || config.pid_file.is_some() {
                return Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "daemon and pidfile support are unavailable on this platform",
                ));
            }
            Ok(Self {})
        }
    }
}

pub fn shutdown_requested() -> bool {
    SHUTDOWN.load(Ordering::Relaxed)
}

pub fn request_shutdown() {
    SHUTDOWN.store(true, Ordering::Relaxed);
}

pub fn prepare_embedded() {
    SHUTDOWN.store(false, Ordering::Relaxed);
}

#[cfg(all(test, target_os = "windows"))]
pub(crate) fn reset_shutdown_for_test() {
    SHUTDOWN.store(false, Ordering::Relaxed);
}

#[cfg(unix)]
extern "C" fn handle_signal(_signal: libc::c_int) {
    request_shutdown();
}

#[cfg(unix)]
fn install_signal_handlers() -> io::Result<()> {
    for signal in [libc::SIGINT, libc::SIGTERM, libc::SIGHUP] {
        let handler = handle_signal as *const () as libc::sighandler_t;
        let prev = unsafe { libc::signal(signal, handler) };
        if prev == libc::SIG_ERR {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

#[cfg(unix)]
#[allow(deprecated)]
fn daemonize() -> io::Result<()> {
    let rc = unsafe { libc::daemon(0, 0) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(unix)]
struct PidFileGuard {
    file: File,
    path: PathBuf,
}

#[cfg(unix)]
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

#[cfg(unix)]
impl Drop for PidFileGuard {
    fn drop(&mut self) {
        let _ = self.file.flush();
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(unix)]
use std::os::fd::AsRawFd;

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_pid_path() -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).expect("system clock before unix epoch").as_nanos();
        std::env::temp_dir().join(format!("ciadpi-process-{stamp}.pid"))
    }

    #[test]
    fn prepare_resets_shutdown_state() {
        request_shutdown();
        assert!(shutdown_requested());

        let guard = ProcessGuard::prepare(&RuntimeConfig::default()).expect("prepare process guard");

        assert!(!shutdown_requested());
        drop(guard);
    }

    #[cfg(unix)]
    #[test]
    fn prepare_with_pid_file_writes_and_removes_pidfile() {
        let path = temp_pid_path();
        let config = RuntimeConfig { pid_file: Some(path.display().to_string()), ..RuntimeConfig::default() };

        {
            let guard = ProcessGuard::prepare(&config).expect("prepare process guard with pidfile");
            let contents = std::fs::read_to_string(&path).expect("pidfile contents");
            assert_eq!(contents, std::process::id().to_string());
            drop(guard);
        }

        assert!(!path.exists(), "pidfile should be removed on drop");
    }
}
