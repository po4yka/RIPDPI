use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::stream_steps::{execute_basic_tcp_stream_step, TcpBasicStreamExecContext};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let mut basic_stream_ctx =
        TcpBasicStreamExecContext { writer: ctx.writer, config: ctx.config, group: ctx.group, md5sig: ctx.md5sig };
    let bytes_committed = execute_basic_tcp_stream_step(
        &mut basic_stream_ctx,
        input.kind,
        input.configured_step,
        input.chunk,
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(input.end)))
}
