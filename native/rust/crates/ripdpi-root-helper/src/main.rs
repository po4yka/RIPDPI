// Lint floor for a crate that contains `unsafe` (raw-fd adoption over
// SCM_RIGHTS, `libc::close`/`libc::chown`/`libc::signal` syscall wrappers).
// Mirrors the convention adopted by ripdpi-vless / ripdpi-privileged-ops /
// ripdpi-io-uring / ripdpi-proxy-runtime: re-enable the workspace-deferred
// unsafe-documentation lints locally so every `unsafe` block in this crate
// carries an inline `// SAFETY:` rationale (or a `# Safety` rustdoc block for
// `unsafe fn`) at build time, ahead of the gradual workspace-wide migration.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod dispatch;
mod handlers;

use std::ffi::CString;
use std::fs;
use std::io;
use std::os::fd::{AsFd, FromRawFd, IntoRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use nix::sys::signal::{self, SigHandler, Signal};
use tracing::{error, info, warn};

#[cfg(target_os = "android")]
use std::process::Command;

use ripdpi_root_helper_protocol as protocol;
use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, HelperRequest, HelperResponse, PROTOCOL_VERSION, valid_session_nonce,
};

struct RootHelperConfig {
    socket_path: PathBuf,
    session_nonce: String,
}

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".parse().unwrap()),
        )
        .with_writer(std::io::stderr)
        .init();

    let config = parse_args();
    let socket_path = config.socket_path;
    let session_nonce = config.session_nonce;
    info!(path = %socket_path.display(), "ripdpi-root-helper starting");

    // Remove stale socket file.
    if socket_path.exists() {
        let _ = fs::remove_file(&socket_path);
    }

    let listener = match UnixListener::bind(&socket_path) {
        Ok(l) => l,
        Err(e) => {
            error!(path = %socket_path.display(), %e, "failed to bind Unix socket");
            std::process::exit(1);
        }
    };

    if let Err(e) = prepare_socket_for_app(&socket_path) {
        error!(path = %socket_path.display(), %e, "failed to prepare root-helper socket for app access");
        let _ = fs::remove_file(&socket_path);
        std::process::exit(1);
    }
    if let Err(e) = listener.set_nonblocking(true) {
        error!(path = %socket_path.display(), %e, "failed to configure root-helper socket");
        let _ = fs::remove_file(&socket_path);
        std::process::exit(1);
    }

    let running = Arc::new(AtomicBool::new(true));

    // Handle SIGTERM for clean shutdown.
    // SAFETY: installing a signal disposition is `unsafe` because the handler runs
    // asynchronously. `signal_handler` is a real `extern "C" fn` whose body only
    // performs an `AtomicBool::store` on the `'static RUNNING` flag, which is
    // async-signal-safe. Going through `nix`'s typed `SigHandler::Handler` instead
    // of a raw `libc::signal` call buys two things: the handler's `extern "C"
    // fn(c_int)` signature is type-checked at the call site, and the install result
    // is checked rather than silently discarded. It also drops the gratuitous
    // `signal_handler as *const () as sighandler_t` double-cast — nix performs the
    // single, unavoidable `fn -> sighandler_t` conversion internally (the libc
    // signal ABI is integer-typed). We replace the previously installed handler
    // (the process default) and never need to restore it.
    let installed = unsafe { signal::signal(Signal::SIGTERM, SigHandler::Handler(signal_handler)) };
    if let Err(err) = installed {
        warn!(%err, "failed to install SIGTERM handler");
    }
    RUNNING.store(true, Ordering::SeqCst);

    info!(path = %socket_path.display(), "listening for connections");

    while running.load(Ordering::Relaxed) && RUNNING.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _addr)) => {
                if let Err(e) = handle_connection(&stream, &session_nonce) {
                    warn!(%e, "connection handler error");
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(100));
                continue;
            }
            Err(e) => {
                if !RUNNING.load(Ordering::SeqCst) {
                    break;
                }
                error!(%e, "accept failed");
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
        }
    }

    info!("shutting down");
    let _ = fs::remove_file(&socket_path);
}

fn handle_connection(stream: &UnixStream, session_nonce: &str) -> io::Result<()> {
    use std::time::Duration;

    stream.set_read_timeout(Some(Duration::from_secs(30)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;

    let (data, received_fd) = protocol::recv_message(stream, "peer closed connection")?;

    let request: HelperRequest = serde_json::from_slice(&data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("invalid request JSON: {e}")))?;

    if !session_nonce_matches(session_nonce, request.session_nonce.as_deref()) {
        warn!(command = %request.command, "rejected command with invalid root-helper session nonce");
        send_response(stream, HelperResponse::error("invalid root-helper session nonce"), None)?;
        return Ok(());
    }

    info!(command = %request.command, "received command");

    // `dispatch_command` takes sole, total ownership of `received_fd`: it
    // either closes the inbound fd on every rejection / decode-error / handler
    // path, or returns it in `reply_fd`. A returned reply descriptor is
    // immediately adopted here and borrowed only for the SCM_RIGHTS transfer.
    // So `handle_connection` must NOT close `received_fd` here — doing so would
    // double-close a descriptor dispatch already released (the previous
    // dead/inverted accounting block, audit F17).
    let dispatch = dispatch::dispatch_command(&request, received_fd.map(IntoRawFd::into_raw_fd));
    if dispatch.shutdown_requested {
        RUNNING.store(false, Ordering::SeqCst);
    }
    let response = dispatch.response;
    let reply_fd = dispatch.reply_fd.map(|fd| {
        // SAFETY: dispatch transfers unique ownership of every returned reply
        // descriptor to this call; no other path retains it, it remains live,
        // and this `OwnedFd` adopts it exactly once.
        unsafe { OwnedFd::from_raw_fd(fd) }
    });

    send_response(stream, response, reply_fd)?;

    Ok(())
}

fn send_response(stream: &UnixStream, response: HelperResponse, reply_fd: Option<OwnedFd>) -> io::Result<()> {
    // Stamp every outbound response with the current protocol / capability
    // version so clients can gate features on a known-recent helper. Pre-
    // versioned clients tolerate the new fields (HelperResponse derives
    // serde defaults).
    let response = response.with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
    let json =
        serde_json::to_vec(&response).map_err(|e| io::Error::other(format!("failed to serialize response: {e}")))?;
    protocol::send_message(stream, &json, reply_fd.as_ref().map(AsFd::as_fd))
}

fn prepare_socket_for_app(socket_path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;

    let parent = socket_path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "root-helper socket path has no parent"))?;
    let parent_metadata = fs::metadata(parent)?;
    chown_socket(socket_path, parent_metadata.uid(), parent_metadata.gid())?;
    fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600))?;
    restore_android_label(socket_path);
    Ok(())
}

fn chown_socket(socket_path: &Path, uid: u32, gid: u32) -> io::Result<()> {
    let c_path = CString::new(socket_path.as_os_str().as_bytes()).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("root-helper socket path contains NUL byte: {}", socket_path.display()),
        )
    })?;
    // SAFETY: `c_path` is a valid, NUL-terminated filesystem path and the uid/gid values come from `metadata(2)`.
    let rc = unsafe { libc::chown(c_path.as_ptr(), uid, gid) };
    if rc == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

#[cfg(target_os = "android")]
fn restore_android_label(socket_path: &Path) {
    match Command::new("/system/bin/restorecon").arg(socket_path).status() {
        Ok(status) if status.success() => {}
        Ok(status) => warn!(path = %socket_path.display(), ?status, "restorecon failed for root-helper socket"),
        Err(e) => warn!(path = %socket_path.display(), %e, "failed to execute restorecon for root-helper socket"),
    }
}

#[cfg(not(target_os = "android"))]
fn restore_android_label(_socket_path: &Path) {}

fn session_nonce_matches(expected: &str, actual: Option<&str>) -> bool {
    let Some(actual) = actual else {
        return false;
    };
    if expected.len() != actual.len() {
        return false;
    }
    expected.bytes().zip(actual.bytes()).fold(0u8, |acc, (left, right)| acc | (left ^ right)) == 0
}

// ---------------------------------------------------------------------------
// CLI argument parsing
// ---------------------------------------------------------------------------

fn parse_args() -> RootHelperConfig {
    match parse_args_from_env() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("Usage: ripdpi-root-helper --socket <path> --session-nonce-file <path>");
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}

fn parse_args_from_env() -> io::Result<RootHelperConfig> {
    let mut args = pico_args::Arguments::from_env();
    let socket_path =
        args.value_from_str("--socket").map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let nonce_path: PathBuf = args
        .value_from_str("--session-nonce-file")
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let remaining = args.finish();
    if !remaining.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unexpected arguments: {remaining:?}")));
    }
    let session_nonce = read_session_nonce_file(&nonce_path)?;
    Ok(RootHelperConfig { socket_path, session_nonce })
}

fn read_session_nonce_file(path: &Path) -> io::Result<String> {
    let nonce = fs::read_to_string(path)?;
    let nonce = nonce.trim().to_owned();
    if !valid_session_nonce(&nonce) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid root-helper session nonce"));
    }
    Ok(nonce)
}

// ---------------------------------------------------------------------------
// Signal handling
// ---------------------------------------------------------------------------

static RUNNING: AtomicBool = AtomicBool::new(false);

extern "C" fn signal_handler(_sig: libc::c_int) {
    RUNNING.store(false, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    use std::os::unix::net::UnixStream;
    use std::sync::Mutex;
    use std::thread;

    use ripdpi_root_helper_protocol::{
        CMD_SHUTDOWN, HelperRequest, HelperResponse, PROTOCOL_VERSION, recv_message, send_message,
    };

    use super::{
        RUNNING, handle_connection, prepare_socket_for_app, read_session_nonce_file, send_response,
        session_nonce_matches,
    };

    const TEST_NONCE: &str = "abcdefghijklmnopqrstuvwxyzABCDEF";
    static RUNNING_TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn session_nonce_match_requires_exact_value() {
        assert!(session_nonce_matches(TEST_NONCE, Some(TEST_NONCE)));
        assert!(!session_nonce_matches(TEST_NONCE, None));
        assert!(!session_nonce_matches(TEST_NONCE, Some("abcdefghijklmnopqrstuvwxyzABCDEG")));
        assert!(!session_nonce_matches(TEST_NONCE, Some("short")));
    }

    #[test]
    fn rejects_command_with_wrong_session_nonce_before_dispatch() {
        let _lock = RUNNING_TEST_LOCK.lock().expect("running test lock");
        RUNNING.store(true, std::sync::atomic::Ordering::SeqCst);
        let (client, server) = UnixStream::pair().expect("socket pair");
        let server = thread::spawn(move || handle_connection(&server, TEST_NONCE).expect("handle connection"));
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SHUTDOWN.to_string(),
            params: serde_json::Value::Null,
            session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEG".to_string()),
        };
        send_message(&client, &serde_json::to_vec(&request).expect("request JSON"), None).expect("send request");

        let (payload, fd) = recv_message(&client, "server closed").expect("response");
        assert!(fd.is_none());
        let response: HelperResponse = serde_json::from_slice(&payload).expect("response JSON");
        assert!(!response.ok);
        assert_eq!(response.error.as_deref(), Some("invalid root-helper session nonce"));
        server.join().expect("server thread");
        assert!(RUNNING.load(std::sync::atomic::Ordering::SeqCst));
    }

    #[test]
    fn accepts_command_with_matching_session_nonce() {
        let _lock = RUNNING_TEST_LOCK.lock().expect("running test lock");
        RUNNING.store(true, std::sync::atomic::Ordering::SeqCst);
        let (client, server) = UnixStream::pair().expect("socket pair");
        let server = thread::spawn(move || handle_connection(&server, TEST_NONCE).expect("handle connection"));
        let request = HelperRequest {
            protocol_version: Some(PROTOCOL_VERSION),
            command: CMD_SHUTDOWN.to_string(),
            params: serde_json::Value::Null,
            session_nonce: Some(TEST_NONCE.to_string()),
        };
        send_message(&client, &serde_json::to_vec(&request).expect("request JSON"), None).expect("send request");

        let (payload, fd) = recv_message(&client, "server closed").expect("response");
        assert!(fd.is_none());
        let response: HelperResponse = serde_json::from_slice(&payload).expect("response JSON");
        assert!(response.ok);
        server.join().expect("server thread");
        assert!(!RUNNING.load(std::sync::atomic::Ordering::SeqCst));
    }

    #[test]
    fn send_response_closes_local_reply_fd_after_successful_transfer() {
        let (sender, receiver) = UnixStream::pair().expect("socket pair");
        let (read_end, write_end) = nix::unistd::pipe().expect("pipe");
        let local_reply_fd = read_end.as_raw_fd();

        send_response(&sender, HelperResponse::success(serde_json::Value::Null), Some(read_end))
            .expect("send response");

        assert!(crate::dispatch::test_support::fd_is_closed(local_reply_fd));
        let (_payload, received_fd) = recv_message(&receiver, "sender closed").expect("receive response");
        let received_fd = received_fd.expect("received reply fd");
        nix::unistd::write(&write_end, b"x").expect("write pipe byte");
        let mut byte = [0_u8; 1];
        assert_eq!(nix::unistd::read(&received_fd, &mut byte).expect("read pipe byte"), 1);
        assert_eq!(byte, [b'x']);
    }

    #[test]
    fn send_response_closes_local_reply_fd_when_send_fails() {
        let (sender, _receiver) = UnixStream::pair().expect("socket pair");
        let (read_end, _write_end) = nix::unistd::pipe().expect("pipe");
        let local_reply_fd = read_end.as_raw_fd();
        let oversized_response = HelperResponse::success(serde_json::Value::String("x".repeat(8192)));

        let error =
            send_response(&sender, oversized_response, Some(read_end)).expect_err("oversized response must fail");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(crate::dispatch::test_support::fd_is_closed(local_reply_fd));
    }

    #[test]
    fn reads_only_valid_session_nonce_files() {
        let path =
            std::env::temp_dir().join(format!("ripdpi-root-helper-nonce-{}-{}", std::process::id(), unique_suffix()));

        std::fs::write(&path, format!("{TEST_NONCE}\n")).expect("write nonce");
        assert_eq!(read_session_nonce_file(&path).expect("read nonce"), TEST_NONCE);

        std::fs::write(&path, "short").expect("write malformed nonce");
        assert_eq!(
            read_session_nonce_file(&path).expect_err("malformed nonce").kind(),
            std::io::ErrorKind::PermissionDenied
        );

        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn prepare_socket_for_app_uses_app_directory_owner_and_private_mode() {
        let base_dir = std::path::PathBuf::from(format!("/tmp/rhs-{}-{}", std::process::id(), unique_suffix()));
        fs::create_dir(&base_dir).expect("create temp dir");
        let socket_path = base_dir.join("root_helper.sock");
        let listener = std::os::unix::net::UnixListener::bind(&socket_path).expect("bind socket");

        prepare_socket_for_app(&socket_path).expect("prepare socket");

        let parent_metadata = fs::metadata(&base_dir).expect("parent metadata");
        let socket_metadata = fs::metadata(&socket_path).expect("socket metadata");
        assert_eq!(socket_metadata.uid(), parent_metadata.uid());
        assert_eq!(socket_metadata.gid(), parent_metadata.gid());
        assert_eq!(socket_metadata.permissions().mode() & 0o777, 0o600);

        drop(listener);
        let _ = fs::remove_file(&socket_path);
        let _ = fs::remove_dir(&base_dir);
    }

    fn unique_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
    }
}
