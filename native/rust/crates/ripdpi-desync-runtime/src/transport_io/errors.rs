use std::io;

use crate::types::{OutboundSendError, TcpExecutionReceipt, TcpTerminalReason};

pub(crate) fn strategy_execution_error(
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
        payload_commit_uncertain: crate::platform::TcpFragmentSendError::from_error(&source)
            .is_some_and(|phase| phase.payload_commit_uncertain),
        source_errno: source.raw_os_error().or_else(|| {
            crate::platform::TcpFragmentSendError::from_error(&source).and_then(|phase| phase.source.raw_os_error())
        }),
        execution_receipt: Box::new(TcpExecutionReceipt::failed_strategy_execution(
            Some(strategy_family),
            0,
            0,
            0,
            0,
            bytes_committed,
            TcpTerminalReason::StrategyExecution,
        )),
        source,
    }
}

pub(crate) fn log_android_desync_fallback(action: &'static str, fallback: &'static str, error: &OutboundSendError) {
    tracing::warn!("Android desync fallback applied: action={action} fallback={fallback}: {error}");
}

pub(crate) fn strategy_result<T>(
    result: io::Result<T>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<T, OutboundSendError> {
    result.map_err(|source| {
        let written = source
            .get_ref()
            .and_then(|error| error.downcast_ref::<super::WriteProgressError>())
            .map_or(0, |progress| progress.written);
        strategy_execution_error(action, strategy_family, fallback, bytes_committed.saturating_add(written), source)
    })
}

pub(crate) fn transport_result<T>(result: io::Result<T>) -> Result<T, OutboundSendError> {
    result.map_err(OutboundSendError::transport)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};

    #[test]
    fn strategy_error_keeps_known_committed_bytes_without_retry_errno() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
        let mut writer = TcpStream::connect(listener.local_addr().expect("address")).expect("writer");
        let (mut reader, _) = listener.accept().expect("reader");
        writer.write_all(b"abc").expect("partial payload");
        let source = io::Error::new(
            io::ErrorKind::BrokenPipe,
            super::super::WriteProgressError { written: 3, source: io::Error::from_raw_os_error(libc::EPIPE) },
        );
        let error = strategy_result::<()>(Err(source), "send_fake", "fakeddisorder", None, 7)
            .expect_err("send failed after committing bytes")
            .with_possible_payload_commit();
        assert!(error.payload_commit_uncertain());
        let mut received = [0; 3];
        reader.read_exact(&mut received).expect("peer payload");
        assert_eq!(&received, b"abc");
        assert_eq!(error.bytes_committed(), 10);
        assert_eq!(error.source_error().kind(), io::ErrorKind::BrokenPipe);
        assert_eq!(error.source_error().raw_os_error(), None);
    }
}
