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
        source_errno: source.raw_os_error(),
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
    result.map_err(|source| strategy_execution_error(action, strategy_family, fallback, bytes_committed, source))
}

pub(crate) fn transport_result<T>(result: io::Result<T>) -> Result<T, OutboundSendError> {
    result.map_err(OutboundSendError::transport)
}
