use std::io;
use std::os::fd::RawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use ripdpi_root_helper_protocol::{recv_message, send_message, valid_session_nonce, HelperRequest, HelperResponse};

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
    use super::{load_session_nonce, session_nonce_path};

    #[test]
    fn derives_session_nonce_path_from_socket_path() {
        assert_eq!(session_nonce_path("/tmp/ripdpi-root-helper.sock"), "/tmp/ripdpi-root-helper.sock.nonce");
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
