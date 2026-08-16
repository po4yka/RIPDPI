use std::io::{self, Write};
use std::net::TcpStream;

use crate::types::OutboundSendError;

use super::errors::strategy_execution_error;

#[derive(Debug)]
pub(crate) struct WriteProgressError {
    pub(crate) written: usize,
    pub(crate) source: io::Error,
}

pub(crate) fn write_payload_progress(stream: &mut TcpStream, bytes: &[u8]) -> Result<(), WriteProgressError> {
    let mut written = 0usize;
    while written < bytes.len() {
        match stream.write(&bytes[written..]) {
            Ok(0) => {
                return Err(WriteProgressError {
                    written,
                    source: io::Error::new(io::ErrorKind::WriteZero, "partial tcp payload write"),
                });
            }
            Ok(chunk) => {
                written += chunk;
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => {
                return Err(WriteProgressError { written, source: err });
            }
        }
    }
    Ok(())
}

pub(crate) fn write_transport_payload(stream: &mut TcpStream, bytes: &[u8]) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes)
        .map(|()| bytes.len())
        .map_err(|progress| OutboundSendError::transport(progress.source))
}

pub(crate) fn write_strategy_payload_named(
    stream: &mut TcpStream,
    bytes: &[u8],
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes).map(|()| bytes_committed + bytes.len()).map_err(|progress| {
        strategy_execution_error(action, strategy_family, fallback, bytes_committed + progress.written, progress.source)
    })
}
