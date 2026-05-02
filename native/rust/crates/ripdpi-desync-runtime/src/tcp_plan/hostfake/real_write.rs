use ripdpi_config::TcpChainStep;

use super::pacing::await_hostfake_writable;
use super::TcpHostFakeExecContext;
use crate::tcp_plan::flags::step_original_tcp_flags;
use crate::transport_io::{write_strategy_payload_named, write_strategy_payload_with_optional_flags_named};
use crate::types::OutboundSendError;

const WRITE_HOSTFAKE_ACTION: &str = "write_hostfake";

pub(super) fn write_hostfake_payload(
    ctx: &mut TcpHostFakeExecContext<'_>,
    payload: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    let bytes_committed = write_strategy_payload_named(
        ctx.writer,
        payload,
        WRITE_HOSTFAKE_ACTION,
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    await_hostfake_writable(ctx, step_family, step_fallback, bytes_committed)?;
    Ok(bytes_committed)
}

pub(super) fn write_hostfake_payload_with_optional_flags(
    ctx: &mut TcpHostFakeExecContext<'_>,
    configured_step: &TcpChainStep,
    payload: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    let bytes_committed = write_strategy_payload_with_optional_flags_named(
        ctx.writer,
        payload,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        WRITE_HOSTFAKE_ACTION,
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    await_hostfake_writable(ctx, step_family, step_fallback, bytes_committed)?;
    Ok(bytes_committed)
}
