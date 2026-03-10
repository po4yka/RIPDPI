use std::env;
use std::ffi::OsString;
use std::io;
use std::net::{SocketAddr, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::OnceLock;
use std::time::Duration;

use windows_service::define_windows_service;
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_dispatcher;

use crate::process;

const SERVICE_NAME: &str = "ByeDPI";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;
const ERROR_FAILED_SERVICE_CONTROLLER_CONNECT: i32 = 1063;

static SERVICE_ARGS: OnceLock<Vec<String>> = OnceLock::new();
static SERVICE_RUNNER: OnceLock<fn(Vec<String>) -> i32> = OnceLock::new();
static SERVICE_EXIT_CODE: AtomicI32 = AtomicI32::new(1);

define_windows_service!(ffi_service_main, service_main);

pub fn maybe_run_as_service(args: &[String], runner: fn(Vec<String>) -> i32) -> io::Result<Option<i32>> {
    if SERVICE_ARGS.set(args.to_vec()).is_err() || SERVICE_RUNNER.set(runner).is_err() {
        return Err(io::Error::new(io::ErrorKind::AlreadyExists, "windows service dispatcher already initialized"));
    }
    SERVICE_EXIT_CODE.store(1, Ordering::Relaxed);

    match service_dispatcher::start(SERVICE_NAME, ffi_service_main) {
        Ok(()) => Ok(Some(SERVICE_EXIT_CODE.load(Ordering::Relaxed))),
        Err(err) if service_dispatch_error(&err) == Some(ERROR_FAILED_SERVICE_CONTROLLER_CONNECT) => Ok(None),
        Err(err) => Err(io::Error::other(err.to_string())),
    }
}

pub fn service_main(_arguments: Vec<OsString>) {
    SERVICE_EXIT_CODE.store(run_service(), Ordering::Relaxed);
}

fn run_service() -> i32 {
    let _ = set_service_working_directory();

    let event_handler = move |control_event| -> ServiceControlHandlerResult { service_control_handler(control_event) };

    let Ok(status_handle) = service_control_handler::register(SERVICE_NAME, event_handler) else {
        return 1;
    };
    if status_handle.set_service_status(running_status()).is_err() {
        return 1;
    }

    let exit_code = run_saved_args();
    let _ = status_handle.set_service_status(stopped_status(exit_code));
    exit_code
}

fn running_status() -> ServiceStatus {
    ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    }
}

fn stopped_status(exit_code: i32) -> ServiceStatus {
    ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state: ServiceState::Stopped,
        controls_accepted: ServiceControlAccept::empty(),
        exit_code: ServiceExitCode::Win32(exit_code.max(0) as u32),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    }
}

fn run_saved_args() -> i32 {
    let Some(runner) = SERVICE_RUNNER.get().copied() else {
        return 1;
    };
    let args = SERVICE_ARGS.get().cloned().unwrap_or_default();
    run_with_saved_args(runner, &args)
}

fn set_service_working_directory() -> io::Result<()> {
    let exe = env::current_exe()?;
    set_service_working_directory_for_executable(&exe)
}

fn set_service_working_directory_for_executable(exe: &Path) -> io::Result<()> {
    let Some(parent) = exe.parent() else {
        return Ok(());
    };
    env::set_current_dir(parent)
}

fn service_control_handler(control_event: ServiceControl) -> ServiceControlHandlerResult {
    match control_event {
        ServiceControl::Stop | ServiceControl::Shutdown => {
            process::request_shutdown();
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        _ => ServiceControlHandlerResult::NotImplemented,
    }
}

fn run_with_saved_args(runner: fn(Vec<String>) -> i32, args: &[String]) -> i32 {
    runner(args.to_vec())
}

fn service_dispatch_error(err: &windows_service::Error) -> Option<i32> {
    match err {
        windows_service::Error::Winapi(source) => source.raw_os_error(),
        _ => None,
    }
}

pub fn enable_tcp_fastopen_connect() -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "tcp fast open connect is linux-only"))
}

pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    Ok(())
}

pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Ok(())
}

pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "transparent proxy mode is linux-only"))
}

pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Ok(())
}

pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Ok(())
}

pub fn send_fake_tcp(stream: &TcpStream, fake_prefix: &[u8], ttl: u8, md5sig: bool, default_ttl: u8) -> io::Result<()> {
    super::fallback::send_fake_tcp_best_effort(stream, fake_prefix, ttl, md5sig, default_ttl, set_tcp_md5sig)
}

pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::process as std_process;
    use std::sync::Mutex;
    use std::time::{SystemTime, UNIX_EPOCH};

    static CAPTURED_ARGS: Mutex<Option<Vec<String>>> = Mutex::new(None);

    fn capture_runner(args: Vec<String>) -> i32 {
        *CAPTURED_ARGS.lock().expect("capture args mutex poisoned") = Some(args);
        23
    }

    #[test]
    fn service_name_matches_c_oracle() {
        assert_eq!(SERVICE_NAME, "ByeDPI");
    }

    #[test]
    fn running_status_accepts_stop_and_shutdown() {
        let status = running_status();
        assert_eq!(status.service_type, SERVICE_TYPE);
        assert_eq!(status.current_state, ServiceState::Running);
        assert!(status.controls_accepted.contains(ServiceControlAccept::STOP));
        assert!(status.controls_accepted.contains(ServiceControlAccept::SHUTDOWN));
    }

    #[test]
    fn stop_and_shutdown_controls_request_shutdown() {
        process::reset_shutdown_for_test();
        assert!(matches!(service_control_handler(ServiceControl::Stop), ServiceControlHandlerResult::NoError));
        assert!(process::shutdown_requested());

        process::reset_shutdown_for_test();
        assert!(matches!(service_control_handler(ServiceControl::Shutdown), ServiceControlHandlerResult::NoError));
        assert!(process::shutdown_requested());
    }

    #[test]
    fn interrogate_control_keeps_service_running() {
        process::reset_shutdown_for_test();
        assert!(matches!(service_control_handler(ServiceControl::Interrogate), ServiceControlHandlerResult::NoError));
        assert!(!process::shutdown_requested());
    }

    #[test]
    fn saved_process_args_are_reused_for_service_runner() {
        let expected = vec!["--ip".to_string(), "127.0.0.1".to_string(), "--fake".to_string()];
        *CAPTURED_ARGS.lock().expect("capture args mutex poisoned") = None;

        let exit_code = run_with_saved_args(capture_runner, &expected);
        let captured = CAPTURED_ARGS.lock().expect("capture args mutex poisoned").clone();

        assert_eq!(exit_code, 23);
        assert_eq!(captured.as_deref(), Some(expected.as_slice()));
    }

    #[test]
    fn service_working_directory_uses_executable_parent() {
        let unique = SystemTime::now().duration_since(UNIX_EPOCH).expect("system time before unix epoch").as_nanos();
        let root = env::temp_dir().join(format!("ciadpi-service-test-{}-{unique}", std_process::id()));
        let bin_dir = root.join("bin");
        fs::create_dir_all(&bin_dir).expect("create temporary bin dir");
        let exe = bin_dir.join("ciadpi.exe");

        let original_dir = env::current_dir().expect("read current dir");
        set_service_working_directory_for_executable(&exe).expect("set working directory from executable");
        let current_dir = env::current_dir().expect("read updated current dir");
        env::set_current_dir(&original_dir).expect("restore current dir");
        fs::remove_dir_all(&root).expect("remove temporary test dir");

        assert_eq!(current_dir, bin_dir);
    }

    #[test]
    fn stopped_status_reports_runner_exit_code() {
        let status = stopped_status(7);
        assert_eq!(status.service_type, SERVICE_TYPE);
        assert_eq!(status.current_state, ServiceState::Stopped);
        assert_eq!(status.controls_accepted, ServiceControlAccept::empty());
        assert_eq!(status.exit_code, ServiceExitCode::Win32(7));
    }
}
