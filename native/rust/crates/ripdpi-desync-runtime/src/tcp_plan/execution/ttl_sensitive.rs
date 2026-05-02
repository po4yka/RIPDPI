use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::stream_steps::{execute_ttl_sensitive_tcp_step, TcpTtlSensitiveExecContext};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let mut ttl_sensitive_ctx = TcpTtlSensitiveExecContext {
        writer: ctx.writer,
        config: ctx.config,
        group: ctx.group,
        lowering: ctx.lowering,
        md5sig: ctx.md5sig,
    };
    let bytes_committed = execute_ttl_sensitive_tcp_step(
        &mut ttl_sensitive_ctx,
        input.kind,
        input.configured_step,
        input.chunk,
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(input.end)))
}
