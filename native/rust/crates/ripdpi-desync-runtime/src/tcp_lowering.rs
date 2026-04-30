use std::io;
use std::io::Write;
use std::net::TcpStream;

use crate::platform;
use crate::sync::{AtomicBool, Ordering};

use super::OutboundSendError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TcpTtlCapabilityState {
    Unknown,
    Unavailable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TcpLoweringCapabilities {
    pub(crate) restore_ttl: u8,
    ttl_write: TcpTtlCapabilityState,
}

impl TcpLoweringCapabilities {
    pub(crate) fn snapshot(default_ttl: u8, session_ttl_unavailable: &AtomicBool) -> Self {
        let restore_ttl = if default_ttl != 0 { default_ttl } else { platform::detect_default_ttl().unwrap_or(64) };
        let ttl_write = if session_ttl_unavailable.load(Ordering::Relaxed) {
            TcpTtlCapabilityState::Unavailable
        } else {
            TcpTtlCapabilityState::Unknown
        };
        Self { restore_ttl, ttl_write }
    }

    pub(crate) fn persist(self, session_ttl_unavailable: &AtomicBool) {
        if self.ttl_actions_unavailable() {
            session_ttl_unavailable.store(true, Ordering::Relaxed);
        }
    }

    pub(crate) fn ttl_actions_unavailable(self) -> bool {
        self.ttl_write == TcpTtlCapabilityState::Unavailable
    }

    pub(crate) fn set_ttl_named(
        &mut self,
        stream: &TcpStream,
        ttl: u8,
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
    ) -> Result<bool, OutboundSendError> {
        if self.ttl_actions_unavailable() {
            return Ok(false);
        }

        match set_stream_ttl(stream, ttl) {
            Ok(()) => Ok(true),
            Err(source) if should_ignore_android_ttl_error(&source) => {
                self.ttl_write = TcpTtlCapabilityState::Unavailable;
                tracing::warn!("TTL desync action unavailable on this Android build: {}", source);
                Ok(false)
            }
            Err(source) => Err(strategy_execution_error(action, strategy_family, fallback, bytes_committed, source)),
        }
    }

    pub(crate) fn restore_default_ttl_named(
        &mut self,
        stream: &TcpStream,
        ttl: u8,
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
    ) -> Result<bool, OutboundSendError> {
        if self.ttl_actions_unavailable() {
            return Ok(false);
        }

        match set_stream_ttl(stream, ttl) {
            Ok(()) => Ok(true),
            Err(source) if should_ignore_android_ttl_error(&source) => {
                self.ttl_write = TcpTtlCapabilityState::Unavailable;
                tracing::warn!("TTL desync action unavailable on this Android build: {}", source);
                Ok(false)
            }
            Err(source) => Err(strategy_execution_error(action, strategy_family, fallback, bytes_committed, source)),
        }
    }
}

#[cfg(any(test, target_os = "android"))]
pub(crate) fn should_ignore_android_ttl_error(err: &io::Error) -> bool {
    matches!(
        err.raw_os_error(),
        Some(libc::EROFS | libc::EINVAL | libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES)
    )
}

#[cfg(not(any(test, target_os = "android")))]
pub(crate) fn should_ignore_android_ttl_error(_err: &io::Error) -> bool {
    false
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn write_payload_with_android_ttl_fallback(
    lowering: &mut TcpLoweringCapabilities,
    writer: &mut TcpStream,
    bytes: &[u8],
    ttl_modified: bool,
    action: &'static str,
    restore_action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    match write_payload_progress(writer, bytes) {
        Ok(()) => Ok((ttl_modified, bytes_committed + bytes.len())),
        Err(progress) if ttl_modified && progress.written == 0 && should_ignore_android_ttl_error(&progress.source) => {
            lowering.ttl_write = TcpTtlCapabilityState::Unavailable;
            lowering.restore_default_ttl_named(
                writer,
                lowering.restore_ttl,
                restore_action,
                strategy_family,
                fallback,
                bytes_committed,
            )?;
            let err = strategy_execution_error(action, strategy_family, fallback, bytes_committed, progress.source);
            log_android_desync_fallback(action, fallback.unwrap_or("none"), &err);
            let committed =
                write_strategy_payload_named(writer, bytes, action, strategy_family, fallback, bytes_committed)?;
            Ok((false, committed))
        }
        Err(progress) => Err(strategy_execution_error(
            action,
            strategy_family,
            fallback,
            bytes_committed + progress.written,
            progress.source,
        )),
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_oob_with_android_ttl_fallback(
    lowering: &mut TcpLoweringCapabilities,
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    ttl_modified: bool,
    action: &'static str,
    restore_action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    match send_oob_action_named(writer, prefix, urgent_byte, action, strategy_family, fallback, bytes_committed) {
        Ok(committed) => Ok((ttl_modified, committed)),
        Err(err) if ttl_modified && should_ignore_android_ttl_error(err.source_error()) => {
            lowering.ttl_write = TcpTtlCapabilityState::Unavailable;
            lowering.restore_default_ttl_named(
                writer,
                lowering.restore_ttl,
                restore_action,
                strategy_family,
                fallback,
                bytes_committed,
            )?;
            log_android_desync_fallback(action, fallback.unwrap_or("none"), &err);
            let committed =
                send_oob_action_named(writer, prefix, urgent_byte, action, strategy_family, fallback, bytes_committed)?;
            Ok((false, committed))
        }
        Err(err) => Err(err),
    }
}

fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = socket2::SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

fn strategy_execution_error(
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
    source: io::Error,
) -> OutboundSendError {
    OutboundSendError::StrategyExecution {
        action,
        strategy_family,
        fallback,
        bytes_committed,
        source_errno: source.raw_os_error(),
        source,
    }
}

fn log_android_desync_fallback(action: &'static str, fallback: &'static str, error: &OutboundSendError) {
    tracing::warn!("Android desync fallback applied: action={action} fallback={fallback}: {error}");
}

fn write_payload_progress(stream: &mut TcpStream, bytes: &[u8]) -> Result<(), WriteProgressError> {
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

fn write_strategy_payload_named(
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

fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = socket2::SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

fn send_oob_action_named(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    send_out_of_band(writer, prefix, urgent_byte)
        .map_err(|source| strategy_execution_error(action, strategy_family, fallback, bytes_committed, source))
        .map(|()| bytes_committed + prefix.len() + 1)
}

#[derive(Debug)]
struct WriteProgressError {
    written: usize,
    source: io::Error,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{TcpListener, TcpStream};
    use std::thread;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("listener addr");
        let handle = thread::spawn(move || TcpStream::connect(addr).expect("client connect"));
        let (server, _) = listener.accept().expect("accept");
        let client = handle.join().expect("client");
        (client, server)
    }

    #[test]
    fn snapshot_uses_configured_default_ttl_and_session_seed() {
        let session = AtomicBool::new(true);
        let caps = TcpLoweringCapabilities::snapshot(42, &session);

        assert_eq!(caps.restore_ttl, 42);
        assert!(caps.ttl_actions_unavailable());
    }

    #[test]
    fn persist_carries_discovered_unavailability_to_session() {
        let session = AtomicBool::new(false);
        TcpLoweringCapabilities { restore_ttl: 64, ttl_write: TcpTtlCapabilityState::Unavailable }.persist(&session);

        assert!(session.load(Ordering::Relaxed));
    }

    #[test]
    fn set_ttl_named_skips_when_marked_unavailable() {
        let (client, _server) = connected_pair();
        let mut caps = TcpLoweringCapabilities { restore_ttl: 64, ttl_write: TcpTtlCapabilityState::Unavailable };

        assert!(!caps.set_ttl_named(&client, 42, "set_ttl", "disorder", None, 0).unwrap());
    }

    #[test]
    fn restore_ttl_named_skips_when_marked_unavailable() {
        let (client, _server) = connected_pair();
        let mut caps = TcpLoweringCapabilities { restore_ttl: 64, ttl_write: TcpTtlCapabilityState::Unavailable };

        assert!(!caps.restore_default_ttl_named(&client, 42, "restore_default_ttl", "disorder", None, 0).unwrap());
    }

    #[test]
    fn write_with_android_fallback_success() {
        let (mut client, _server) = connected_pair();
        let mut caps = TcpLoweringCapabilities::snapshot(64, &AtomicBool::new(false));
        let (ttl_modified, committed) = write_payload_with_android_ttl_fallback(
            &mut caps,
            &mut client,
            b"hello",
            true,
            "write_disorder",
            "restore_default_ttl_disorder",
            "disorder",
            Some("split"),
            50,
        )
        .unwrap();
        assert!(ttl_modified);
        assert_eq!(committed, 55);
        assert!(!caps.ttl_actions_unavailable());
    }

    #[test]
    fn send_oob_with_android_fallback_success() {
        let (client, _server) = connected_pair();
        let mut caps = TcpLoweringCapabilities::snapshot(64, &AtomicBool::new(false));
        let (ttl_modified, committed) = send_oob_with_android_ttl_fallback(
            &mut caps,
            &client,
            b"ab",
            b'!',
            true,
            "send_oob_disoob",
            "restore_default_ttl_disoob",
            "disoob",
            Some("split"),
            10,
        )
        .unwrap();
        assert!(ttl_modified);
        assert_eq!(committed, 13);
    }
}
