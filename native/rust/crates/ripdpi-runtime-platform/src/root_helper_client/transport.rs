use std::io;
use std::os::fd::RawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use ripdpi_root_helper_protocol::{recv_message, send_message, HelperRequest, HelperResponse};

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

    pub(super) fn send_command(
        &self,
        command: &str,
        params: serde_json::Value,
        fd: Option<RawFd>,
    ) -> io::Result<(HelperResponse, Option<RawFd>)> {
        let stream = UnixStream::connect(&self.socket_path)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(10)))?;

        let request = HelperRequest { command: command.to_owned(), params };
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
