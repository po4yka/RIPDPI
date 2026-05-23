use std::io;
use std::os::fd::RawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use ripdpi_root_helper_protocol::{
    recv_message, send_message, valid_session_nonce, validate_request, HelperRequest, HelperResponse,
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
        validate_request(command, fd.is_some(), !params.is_null())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;

        let session_nonce = load_session_nonce(&self.session_nonce_path())?;
        let stream = UnixStream::connect(&self.socket_path)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(10)))?;

        let request = HelperRequest { command: command.to_owned(), params, session_nonce: Some(session_nonce) };
        let json = serde_json::to_vec(&request).map_err(|e| io::Error::other(format!("serialize request: {e}")))?;

        send_message(&stream, &json, fd)?;

        let (resp_bytes, reply_fd) = recv_message(&stream, "helper closed connection")?;

        let response: HelperResponse = serde_json::from_slice(&resp_bytes)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("invalid response: {e}")))?;

        if !response.ok {
            let msg = response.error.unwrap_or_else(|| "unknown helper error".into());
            return Err(io::Error::other(msg));
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

#[cfg(test)]
mod tests {
    use super::{load_session_nonce, session_nonce_path, ClientTransport};

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

    fn unique_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
    }
}
