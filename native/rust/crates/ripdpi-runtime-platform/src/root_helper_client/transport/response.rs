use std::io;
use std::os::fd::OwnedFd;
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};

use ripdpi_root_helper_protocol::{CMD_SEND_SEQOVL_TCP, HelperResponse, recv_message_with_deadline};

/// Read a reply only after the request has been delivered to the helper.
pub(super) fn receive_response(
    stream: &UnixStream,
    command: &str,
    timeout: Duration,
) -> io::Result<(HelperResponse, Option<OwnedFd>)> {
    let result = (|| {
        let (bytes, fd) = recv_message_with_deadline(stream, "helper closed connection", Instant::now() + timeout)?;
        let response: HelperResponse = serde_json::from_slice(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid response: {error}")))?;
        response.validate_protocol_version().map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        Ok((response, fd))
    })();
    result.map_err(|error| {
        if command == CMD_SEND_SEQOVL_TCP {
            // A lost or invalid reply cannot prove that no genuine bytes left.
            io::Error::other(error)
        } else {
            error
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_root_helper_protocol::{CMD_PROTOCOL_PREFLIGHT, send_message};

    #[test]
    fn seqovl_reply_errors_are_terminal_without_changing_other_commands() {
        for bytes in [None, Some(b"bad json".as_slice()), Some(br#"{"ok":true,"data":null,"protocol_version":2}"#)] {
            for command in [CMD_SEND_SEQOVL_TCP, CMD_PROTOCOL_PREFLIGHT] {
                let (reader, writer) = UnixStream::pair().expect("stream pair");
                if let Some(bytes) = bytes {
                    send_message(&writer, bytes, None).expect("write response");
                }
                drop(writer);
                let error = receive_response(&reader, command, Duration::from_secs(1)).expect_err("invalid reply");
                let expected = if command == CMD_SEND_SEQOVL_TCP {
                    io::ErrorKind::Other
                } else if bytes.is_none() {
                    io::ErrorKind::UnexpectedEof
                } else {
                    io::ErrorKind::InvalidData
                };
                assert_eq!(error.kind(), expected);
            }
        }
    }

    #[test]
    fn seqovl_reply_timeout_is_terminal_after_delivery() {
        let (reader, _writer) = UnixStream::pair().expect("stream pair");
        let error = receive_response(&reader, CMD_SEND_SEQOVL_TCP, Duration::ZERO).expect_err("deadline elapsed");
        assert_eq!(error.kind(), io::ErrorKind::Other);
    }
}
