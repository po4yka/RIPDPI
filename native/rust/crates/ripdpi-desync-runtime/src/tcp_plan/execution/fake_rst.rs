#[cfg(test)]
use ripdpi_config::{TcpChainStep, TcpChainStepKind};

use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::platform;
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use crate::transport_io::write_strategy_payload_with_optional_flags_named;
use crate::types::OutboundSendError;

#[cfg(test)]
#[allow(dead_code)]
pub(crate) fn execute_tcp_fakerst_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let input = TcpPlanStepInput {
        kind: TcpChainStepKind::FakeRst,
        configured_step,
        chunk,
        start: 0,
        end,
        step_family,
        step_fallback,
        bytes_committed,
    };
    execute(ctx, &input)
}

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let _ = platform::send_fake_rst(
        ctx.writer,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        step_fake_tcp_flags(input.configured_step),
        ctx.group.actions.ip_id_mode,
    );
    let bytes_committed = write_strategy_payload_with_optional_flags_named(
        ctx.writer,
        input.chunk,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        step_original_tcp_flags(input.configured_step),
        ctx.group.actions.ip_id_mode,
        "write_fakerst",
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(input.end)))
}
