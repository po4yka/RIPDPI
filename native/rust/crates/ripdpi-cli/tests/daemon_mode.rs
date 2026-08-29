#![cfg(unix)]

use std::fs;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;

const PROCESS_TIMEOUT: Duration = Duration::from_secs(10);

struct DaemonCleanup {
    pid: Option<Pid>,
    pid_file: PathBuf,
}

impl DaemonCleanup {
    fn new(pid_file: PathBuf) -> Self {
        Self { pid: None, pid_file }
    }
}

impl Drop for DaemonCleanup {
    fn drop(&mut self) {
        if let Some(pid) = self.pid {
            let _ = kill(pid, Signal::SIGKILL);
        }
        let _ = fs::remove_file(&self.pid_file);
    }
}

#[test]
fn daemon_mode_writes_pid_file_and_removes_it_after_sigterm() {
    let pid_file = unique_pid_file();
    let mut cleanup = DaemonCleanup::new(pid_file.clone());
    let port = reserve_loopback_port();

    let status = Command::new(env!("CARGO_BIN_EXE_ripdpi"))
        .args(["--daemon", "--pidfile"])
        .arg(&pid_file)
        .args(["--ip", "127.0.0.1", "--port", &port.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .expect("launch daemon mode");
    assert!(status.success(), "invoking parent must report successful daemon startup: {status}");

    let pid = wait_for_pid_file(&pid_file);
    cleanup.pid = Some(pid);
    assert_eq!(kill(pid, None), Ok(()), "pid file must identify a live daemon process");

    kill(pid, Signal::SIGTERM).expect("request graceful daemon shutdown");
    wait_for_pid_file_removal(&pid_file);
    cleanup.pid = None;
}

fn reserve_loopback_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .expect("reserve loopback port")
        .port()
}

fn unique_pid_file() -> PathBuf {
    std::env::temp_dir().join(format!(
        "ripdpi-daemon-mode-{}-{}.pid",
        std::process::id(),
        std::thread::current().name().unwrap_or("unnamed")
    ))
}

fn wait_for_pid_file(path: &Path) -> Pid {
    let deadline = Instant::now() + PROCESS_TIMEOUT;
    loop {
        if let Ok(contents) = fs::read_to_string(path)
            && let Ok(raw_pid) = contents.trim().parse::<i32>()
        {
            return Pid::from_raw(raw_pid);
        }
        assert!(Instant::now() < deadline, "timed out waiting for daemon pid file: {}", path.display());
        std::thread::yield_now();
    }
}

fn wait_for_pid_file_removal(path: &Path) {
    let deadline = Instant::now() + PROCESS_TIMEOUT;
    while path.exists() {
        assert!(Instant::now() < deadline, "timed out waiting for daemon pid-file cleanup: {}", path.display());
        std::thread::yield_now();
    }
}
