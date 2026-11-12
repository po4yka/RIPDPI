use std::io::{self, Write};
use std::net::TcpStream;

use crate::types::OutboundSendError;

use super::errors::strategy_execution_error;

#[derive(Debug)]
/// A write failed after the socket accepted the reported bytes.
pub struct WriteProgressError {
    pub written: usize,
    pub source: io::Error,
}

impl std::fmt::Display for WriteProgressError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{} after {} committed bytes", self.source, self.written)
    }
}

impl std::error::Error for WriteProgressError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
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
        .map_err(|progress| OutboundSendError::transport_with_progress(progress.source, progress.written))
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
