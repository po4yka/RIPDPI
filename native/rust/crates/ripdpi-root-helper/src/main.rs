mod handlers;

use std::fs;
use std::io;
use std::os::unix::net::UnixListener;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use tracing::{error, info, warn};

use ripdpi_root_helper_protocol as protocol;
use ripdpi_root_helper_protocol::{
    HelperRequest, CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FLAGGED_TCP_PAYLOAD,
    CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_MULTI_DISORDER_TCP,
    CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_SEQOVL_TCP, CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
};

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".parse().unwrap()),
        )
        .with_writer(std::io::stderr)
        .init();

    let socket_path = parse_args();
    info!(path = %socket_path, "ripdpi-root-helper starting");

    // Remove stale socket file.
    if Path::new(&socket_path).exists() {
        let _ = fs::remove_file(&socket_path);
    }

    let listener = match UnixListener::bind(&socket_path) {
        Ok(l) => l,
        Err(e) => {
            error!(path = %socket_path, %e, "failed to bind Unix socket");
            std::process::exit(1);
        }
    };

    // Restrict socket permissions to owner only.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600));
    }

    let running = Arc::new(AtomicBool::new(true));

    // Handle SIGTERM for clean shutdown.
    unsafe {
        libc::signal(libc::SIGTERM, signal_handler as *const () as libc::sighandler_t);
    }
    RUNNING.store(true, Ordering::SeqCst);

    info!(path = %socket_path, "listening for connections");

    while running.load(Ordering::Relaxed) && RUNNING.load(Ordering::SeqCst) {
        // Use a short accept timeout so we can check the shutdown flag.
        listener.set_nonblocking(false).ok();

        match listener.accept() {
            Ok((stream, _addr)) => {
                if let Err(e) = handle_connection(&stream) {
                    warn!(%e, "connection handler error");
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
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

fn handle_connection(stream: &std::os::unix::net::UnixStream) -> io::Result<()> {
    use std::time::Duration;

    stream.set_read_timeout(Some(Duration::from_secs(30)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;

    let (data, received_fd) = protocol::recv_message(stream, "peer closed connection")?;

    let request: HelperRequest = serde_json::from_slice(&data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("invalid request JSON: {e}")))?;

    info!(command = %request.command, "received command");

    let (response, reply_fd) = dispatch_command(&request, received_fd);

    // Close received fd if we didn't consume it and it wasn't returned.
    if let Some(fd) = received_fd {
        if reply_fd != Some(fd) {
            // fd was consumed by the handler (wrapped in FromRawFd + IntoRawFd).
            // Don't double-close.
        }
    }

    let json =
        serde_json::to_vec(&response).map_err(|e| io::Error::other(format!("failed to serialize response: {e}")))?;

    protocol::send_message(stream, &json, reply_fd)?;

    Ok(())
}

fn dispatch_command(
    request: &HelperRequest,
    received_fd: Option<std::os::fd::RawFd>,
) -> (protocol::HelperResponse, Option<std::os::fd::RawFd>) {
    match request.command.as_str() {
        CMD_PROBE_CAPABILITIES => handlers::handle_probe_capabilities(),

        CMD_SEND_FAKE_RST => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_fake_rst requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_fake_rst(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_FLAGGED_TCP_PAYLOAD => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_flagged_tcp_payload requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_flagged_tcp_payload(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_SEQOVL_TCP => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_seqovl_tcp requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_seqovl_tcp(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_MULTI_DISORDER_TCP => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_multi_disorder_tcp requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_multi_disorder_tcp(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_ORDERED_TCP_SEGMENTS => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_ordered_tcp_segments requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_ordered_tcp_segments(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_IP_FRAGMENTED_TCP => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_ip_fragmented_tcp requires a stream fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_ip_fragmented_tcp(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_IP_FRAGMENTED_UDP => {
            let Some(fd) = received_fd else {
                return (protocol::HelperResponse::error("send_ip_fragmented_udp requires a socket fd"), None);
            };
            match serde_json::from_value(request.params.clone()) {
                Ok(params) => handlers::handle_send_ip_fragmented_udp(fd, params),
                Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
            }
        }

        CMD_SEND_SYN_HIDE_TCP => match serde_json::from_value(request.params.clone()) {
            Ok(params) => handlers::handle_send_syn_hide_tcp(params),
            Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
        },

        CMD_SEND_ICMP_WRAPPED_UDP => match serde_json::from_value(request.params.clone()) {
            Ok(params) => handlers::handle_send_icmp_wrapped_udp(params),
            Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
        },

        CMD_RECV_ICMP_WRAPPED_UDP => match serde_json::from_value(request.params.clone()) {
            Ok(params) => handlers::handle_recv_icmp_wrapped_udp(params),
            Err(e) => (protocol::HelperResponse::error(format!("invalid params: {e}")), None),
        },

        CMD_SHUTDOWN => {
            info!("shutdown command received");
            RUNNING.store(false, Ordering::SeqCst);
            (protocol::HelperResponse::success(serde_json::Value::Null), None)
        }

        other => (protocol::HelperResponse::error(format!("unknown command: {other}")), None),
    }
}

// ---------------------------------------------------------------------------
// CLI argument parsing
// ---------------------------------------------------------------------------

fn parse_args() -> String {
    let args: Vec<String> = std::env::args().collect();
    let mut socket_path = None;

    let mut i = 1;
    while i < args.len() {
        if args[i].as_str() == "--socket" {
            i += 1;
            if i < args.len() {
                socket_path = Some(args[i].clone());
            }
        }
        i += 1;
    }

    socket_path.unwrap_or_else(|| {
        eprintln!("Usage: ripdpi-root-helper --socket <path>");
        std::process::exit(1);
    })
}

// ---------------------------------------------------------------------------
// Signal handling
// ---------------------------------------------------------------------------

static RUNNING: AtomicBool = AtomicBool::new(false);

extern "C" fn signal_handler(_sig: libc::c_int) {
    RUNNING.store(false, Ordering::SeqCst);
}
