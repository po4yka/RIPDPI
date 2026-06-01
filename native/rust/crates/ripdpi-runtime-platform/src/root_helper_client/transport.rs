use std::io;
use std::os::fd::RawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use ripdpi_root_helper_protocol::{
    HelperRequest, HelperResponse, recv_message, send_message, valid_session_nonce, validate_request,
};

pub(super) struct ClientTransport {
    socket_path: String,
}

impl ClientTransport {
    pub(super) fn new(socket_path: String) -> Self {
        Self { socket_path }
    }

    pub(super) fn socket_path(&self) -> String {
        self.socket_path.clone()
    }

    fn session_nonce_path(&self) -> String {
        session_nonce_path(&self.socket_path)
    }

    pub(super) fn send_command(
        &self,
        command: &str,
        params: serde_json::Value,
        fd: Option<RawFd>,
    ) -> io::Result<(HelperResponse, Option<RawFd>)> {
        // Client-side descriptor pre-validation: an unknown command, a
        // missing inbound fd for an fd-carrying command, or an extra fd for
        // a non-fd command is rejected here, BEFORE we open the Unix socket
        // and load the session nonce. Mirrors the helper's pre-dispatch
        // check; both sides see the same typed error from
        // `validate_request`.
        let descriptor = validate_request(command, fd.is_some(), !params.is_null())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;

        let session_nonce = load_session_nonce(&self.session_nonce_path())?;
        let stream = UnixStream::connect(&self.socket_path)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(10)))?;

        let request = HelperRequest { command: command.to_owned(), params, session_nonce: Some(session_nonce) };
        let json = serde_json::to_vec(&request).map_err(|e| io::Error::other(format!("serialize request: {e}")))?;

        send_message(&stream, &json, fd)?;

        let (resp_bytes, reply_fd) = recv_message(&stream, "helper closed connection")?;

        let response: HelperResponse = serde_json::from_slice(&resp_bytes).map_err(|e| {
            close_raw_fd(reply_fd);
            io::Error::new(io::ErrorKind::InvalidData, format!("invalid response: {e}"))
        })?;

        if !response.ok {
            close_raw_fd(reply_fd);
            let msg = response.error.unwrap_or_else(|| "unknown helper error".into());
            return Err(io::Error::other(msg));
        }
        if reply_fd.is_some() && !descriptor.may_return_outbound_fd {
            close_raw_fd(reply_fd);
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("{command} returned an unexpected root-helper fd"),
            ));
        }

        Ok((response, reply_fd))
    }
}

fn session_nonce_path(socket_path: &str) -> String {
    format!("{socket_path}.nonce")
}

fn load_session_nonce(path: &str) -> io::Result<String> {
    let nonce = std::fs::read_to_string(path)?;
    let nonce = nonce.trim().to_owned();
    if !valid_session_nonce(&nonce) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid root-helper session nonce"));
    }
    Ok(nonce)
}

fn close_raw_fd(fd: Option<RawFd>) {
    if let Some(fd) = fd {
        // SAFETY: fd ownership was transferred to this process by SCM_RIGHTS
        // and this path rejects the response before returning the descriptor.
        unsafe {
            libc::close(fd);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{ClientTransport, load_session_nonce, session_nonce_path};
    use std::io::Write;
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::os::unix::net::UnixListener;
    use std::thread;

    use ripdpi_root_helper_protocol::{HelperResponse, recv_message, send_message};

    #[test]
    fn derives_session_nonce_path_from_socket_path() {
        assert_eq!(session_nonce_path("/tmp/ripdpi-root-helper.sock"), "/tmp/ripdpi-root-helper.sock.nonce");
    }

    /// An unknown command is rejected by the client's descriptor pre-validator
    /// before the socket is opened — the IO error kind discriminates the
    /// validation rejection from a real connect/read failure.
    #[test]
    fn send_command_rejects_unknown_command_before_connecting() {
        let transport = ClientTransport::new("/nonexistent/socket".into());
        let error = transport
            .send_command("totally_unknown_command_v999", serde_json::Value::Null, None)
            .expect_err("unknown command must error");
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(
            error.to_string().contains("unknown command"),
            "expected the validator's UnknownCommand message, got {error:?}",
        );
    }

    /// A fd-carrying command sent without an fd is rejected by the client
    /// before the socket is opened — the validator catches the same shape
    /// the helper would reject.
    #[test]
    fn send_command_rejects_missing_fd_for_fd_carrying_command_before_connecting() {
        let transport = ClientTransport::new("/nonexistent/socket".into());
        let error = transport
            .send_command(
                ripdpi_root_helper_protocol::CMD_SEND_FAKE_RST,
                serde_json::json!({ "default_ttl": 64 }),
                None,
            )
            .expect_err("missing fd must error");
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(
            error.to_string().contains("fd"),
            "expected the validator's MissingFd message to mention fd, got {error:?}",
        );
    }

    /// A `probe_capabilities` request from a legacy client with no params
    /// passes the validator and would proceed to the socket — the error
    /// surfaces from the missing nonce file at `/nonexistent/...`, NOT from
    /// the validator. This pins that valid no-fd / no-params calls keep
    /// flowing through the validator unchanged.
    #[test]
    fn send_command_passes_well_formed_no_params_request_through_validator() {
        let transport = ClientTransport::new("/nonexistent/socket".into());
        let error = transport
            .send_command(ripdpi_root_helper_protocol::CMD_PROBE_CAPABILITIES, serde_json::Value::Null, None)
            .expect_err("missing nonce file must error");
        // The validator passed — the failure is at the nonce file load step.
        assert_ne!(error.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn load_session_nonce_rejects_missing_or_malformed_nonce_file() {
        let nonce_path = std::env::temp_dir().join(format!(
            "ripdpi-root-helper-missing-{}-{}.nonce",
            std::process::id(),
            unique_suffix()
        ));
        let nonce_path = nonce_path.to_string_lossy().to_string();
        assert_eq!(load_session_nonce(&nonce_path).expect_err("missing nonce").kind(), std::io::ErrorKind::NotFound);

        std::fs::write(&nonce_path, "short").expect("write malformed nonce");
        assert_eq!(
            load_session_nonce(&nonce_path).expect_err("malformed nonce").kind(),
            std::io::ErrorKind::PermissionDenied
        );
        let _ = std::fs::remove_file(nonce_path);
    }

    #[test]
    fn send_command_rejects_unexpected_success_reply_fd_and_closes_it() {
        let fixture = TransportFixture::start(|stream| {
            let (_request, inbound_fd) = recv_message(&stream, "client closed").expect("request");
            close_owned_raw_fd(inbound_fd.expect("client sends request fd"));
            let (read_fd, write_fd) = nix::unistd::pipe().expect("create reply-fd pipe");
            send_message(
                &stream,
                &serde_json::to_vec(&HelperResponse::success(serde_json::Value::Null)).expect("response JSON"),
                Some(read_fd.as_raw_fd()),
            )
            .expect("send unexpected reply fd");
            drop(read_fd);
            std::fs::File::from(write_fd)
        });
        let request_fd = std::fs::File::open("/dev/null").expect("open request fd");

        let error = fixture
            .transport
            .send_command(
                ripdpi_root_helper_protocol::CMD_SEND_FAKE_RST,
                serde_json::json!({ "default_ttl": 64 }),
                Some(request_fd.as_raw_fd()),
            )
            .expect_err("fake_rst must reject unexpected reply fd");
        let mut write_fd = fixture.join();
        let write_error = write_fd.write_all(b"x").expect_err("client must close the unexpected reply fd");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("unexpected root-helper fd"));
        assert_eq!(write_error.kind(), std::io::ErrorKind::BrokenPipe);
    }

    #[test]
    fn send_command_closes_reply_fd_attached_to_helper_error() {
        let fixture = TransportFixture::start(|stream| {
            let (_request, inbound_fd) = recv_message(&stream, "client closed").expect("request");
            close_owned_raw_fd(inbound_fd.expect("client sends request fd"));
            let (read_fd, write_fd) = nix::unistd::pipe().expect("create reply-fd pipe");
            send_message(
                &stream,
                &serde_json::to_vec(&HelperResponse::error("synthetic helper failure")).expect("response JSON"),
                Some(read_fd.as_raw_fd()),
            )
            .expect("send error reply fd");
            drop(read_fd);
            std::fs::File::from(write_fd)
        });
        let request_fd = std::fs::File::open("/dev/null").expect("open request fd");

        let error = fixture
            .transport
            .send_command(
                ripdpi_root_helper_protocol::CMD_SEND_FAKE_TCP,
                serde_json::json!({ "original_prefix": [], "fake_prefix": [], "ttl": 1, "default_ttl": 64 }),
                Some(request_fd.as_raw_fd()),
            )
            .expect_err("helper error must propagate");
        let mut write_fd = fixture.join();
        let write_error = write_fd.write_all(b"x").expect_err("client must close error reply fd");

        assert_eq!(error.to_string(), "synthetic helper failure");
        assert_eq!(write_error.kind(), std::io::ErrorKind::BrokenPipe);
    }

    #[test]
    fn send_command_returns_allowed_replacement_fd_to_the_caller() {
        let fixture = TransportFixture::start(|stream| {
            let (_request, inbound_fd) = recv_message(&stream, "client closed").expect("request");
            close_owned_raw_fd(inbound_fd.expect("client sends request fd"));
            let (read_fd, write_fd) = nix::unistd::pipe().expect("create reply-fd pipe");
            send_message(
                &stream,
                &serde_json::to_vec(&HelperResponse::success(serde_json::Value::Null)).expect("response JSON"),
                Some(read_fd.as_raw_fd()),
            )
            .expect("send allowed reply fd");
            drop(read_fd);
            std::fs::File::from(write_fd)
        });
        let request_fd = std::fs::File::open("/dev/null").expect("open request fd");

        let (_response, reply_fd) = fixture
            .transport
            .send_command(
                ripdpi_root_helper_protocol::CMD_SEND_FAKE_TCP,
                serde_json::json!({ "original_prefix": [], "fake_prefix": [], "ttl": 1, "default_ttl": 64 }),
                Some(request_fd.as_raw_fd()),
            )
            .expect("fake_tcp may return a replacement fd");
        let reply_fd = reply_fd.expect("allowed replacement fd");
        let mut write_fd = fixture.join();
        write_fd.write_all(b"x").expect("returned fd keeps the pipe readable");
        close_owned_raw_fd(reply_fd);
        let write_error = write_fd.write_all(b"x").expect_err("closing returned fd removes the final reader");

        assert_eq!(write_error.kind(), std::io::ErrorKind::BrokenPipe);
    }

    fn unique_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
    }

    struct TransportFixture<T> {
        transport: ClientTransport,
        socket_path: String,
        join_handle: thread::JoinHandle<T>,
    }

    impl<T> TransportFixture<T> {
        fn start(handler: impl FnOnce(std::os::unix::net::UnixStream) -> T + Send + 'static) -> Self
        where
            T: Send + 'static,
        {
            let socket_path = std::env::temp_dir().join(format!(
                "ripdpi-root-helper-transport-{}-{}.sock",
                std::process::id(),
                unique_suffix()
            ));
            let socket_path = socket_path.to_string_lossy().to_string();
            let nonce_path = session_nonce_path(&socket_path);
            std::fs::write(&nonce_path, "abcdefghijklmnopqrstuvwxyzABCDEF").expect("write nonce");
            let listener = UnixListener::bind(&socket_path).expect("bind transport test socket");
            let join_handle = thread::spawn(move || {
                let (stream, _addr) = listener.accept().expect("accept helper client");
                handler(stream)
            });
            Self { transport: ClientTransport::new(socket_path.clone()), socket_path, join_handle }
        }

        fn join(self) -> T {
            let result = self.join_handle.join().expect("server thread");
            let _ = std::fs::remove_file(&self.socket_path);
            let _ = std::fs::remove_file(session_nonce_path(&self.socket_path));
            result
        }
    }

    fn close_owned_raw_fd(fd: std::os::fd::RawFd) {
        // SAFETY: ownership of this duplicate fd came from recv_message.
        unsafe {
            drop(OwnedFd::from_raw_fd(fd));
        }
    }
}
