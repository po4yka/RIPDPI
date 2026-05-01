use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind};

use super::flags::step_original_tcp_flags;
use crate::tcp_lowering::{send_oob_with_android_ttl_fallback, TcpLoweringCapabilities};
use crate::transport_io::{
    await_writable_action_named, send_oob_action_named, write_strategy_payload_named,
    write_strategy_payload_with_optional_flags_named, write_ttl_sensitive_payload_with_optional_flags_named,
};
use crate::types::OutboundSendError;

pub(crate) struct TcpBasicStreamExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) md5sig: bool,
}

pub(crate) fn execute_basic_tcp_stream_step(
    ctx: &mut TcpBasicStreamExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => {
            let bytes_committed = write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                chunk,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                ctx.md5sig,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::SeqOverlap => {
            let bytes_committed = write_strategy_payload_named(
                ctx.writer,
                chunk,
                "write_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Oob => {
            let bytes_committed = send_oob_action_named(
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                "send_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        _ => unreachable!("non-basic tcp step dispatched to basic stream executor"),
    }
}

pub(crate) struct TcpTtlSensitiveExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) lowering: &'a mut TcpLoweringCapabilities,
    pub(crate) md5sig: bool,
}

pub(crate) fn execute_ttl_sensitive_tcp_step(
    ctx: &mut TcpTtlSensitiveExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Disorder => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            let (should_restore_ttl, bytes_committed) = write_ttl_sensitive_payload_with_optional_flags_named(
                ctx.lowering,
                ctx.writer,
                chunk,
                ttl_modified,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                ctx.md5sig,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Disoob => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            let (should_restore_ttl, bytes_committed) = send_oob_with_android_ttl_fallback(
                ctx.lowering,
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                ttl_modified,
                "send_oob_disoob",
                "restore_default_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            Ok(bytes_committed)
        }
        _ => unreachable!("non-TTL-sensitive step dispatched to TTL-sensitive executor"),
    }
}
