use std::io::{self, ErrorKind};

use serde::{Deserialize, Serialize};

/// TCP fragment failure data. Missing legacy data cannot prove a pre-send rejection.
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct IpFragmentErrorData {
    pub error_kind: String,
    pub raw_os_error: Option<i32>,
    pub payload_commit_uncertain: bool,
}

impl IpFragmentErrorData {
    /// Validate the wire data and preserve normalized kinds with their original errno.
    pub fn into_source_error(self, message: String) -> io::Result<(io::Error, bool)> {
        if self.raw_os_error.is_some_and(|errno| !(1..=4095).contains(&errno)) {
            return Err(invalid_metadata());
        }
        let raw = self.raw_os_error.map(io::Error::from_raw_os_error);
        let kind = match raw.as_ref() {
            Some(error) if format!("{:?}", error.kind()) == self.error_kind => error.kind(),
            _ => parse_kind(&self.error_kind)?,
        };
        let source = match raw {
            Some(error) if error.kind() == kind => error,
            Some(error) => io::Error::new(kind, error),
            None => io::Error::new(kind, message),
        };
        Ok((source, self.payload_commit_uncertain))
    }
}

fn invalid_metadata() -> io::Error {
    io::Error::new(ErrorKind::InvalidData, "invalid IP-fragment helper error metadata")
}

fn parse_kind(value: &str) -> io::Result<ErrorKind> {
    // The pinned stable kinds cover errno-less errors. OS-derived kinds can also
    // round-trip through their validated errno without naming unstable variants.
    const KINDS: &[ErrorKind] = &[
        ErrorKind::NotFound,
        ErrorKind::PermissionDenied,
        ErrorKind::ConnectionRefused,
        ErrorKind::ConnectionReset,
        ErrorKind::HostUnreachable,
        ErrorKind::NetworkUnreachable,
        ErrorKind::ConnectionAborted,
        ErrorKind::NotConnected,
        ErrorKind::AddrInUse,
        ErrorKind::AddrNotAvailable,
        ErrorKind::NetworkDown,
        ErrorKind::BrokenPipe,
        ErrorKind::AlreadyExists,
        ErrorKind::WouldBlock,
        ErrorKind::NotADirectory,
        ErrorKind::IsADirectory,
        ErrorKind::DirectoryNotEmpty,
        ErrorKind::ReadOnlyFilesystem,
        ErrorKind::StaleNetworkFileHandle,
        ErrorKind::InvalidInput,
        ErrorKind::InvalidData,
        ErrorKind::TimedOut,
        ErrorKind::WriteZero,
        ErrorKind::StorageFull,
        ErrorKind::NotSeekable,
        ErrorKind::QuotaExceeded,
        ErrorKind::FileTooLarge,
        ErrorKind::ResourceBusy,
        ErrorKind::ExecutableFileBusy,
        ErrorKind::Deadlock,
        ErrorKind::CrossesDevices,
        ErrorKind::TooManyLinks,
        ErrorKind::InvalidFilename,
        ErrorKind::ArgumentListTooLong,
        ErrorKind::Interrupted,
        ErrorKind::Unsupported,
        ErrorKind::UnexpectedEof,
        ErrorKind::OutOfMemory,
        ErrorKind::Other,
    ];
    KINDS.iter().copied().find(|kind| format!("{kind:?}") == value).ok_or_else(invalid_metadata)
}

#[cfg(test)]
mod tests;
