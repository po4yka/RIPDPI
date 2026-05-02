use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::hostfake::{execute_tcp_hostfake_step, TcpHostFakeExecContext};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let mut hostfake_ctx = TcpHostFakeExecContext {
        writer: ctx.writer,
        config: ctx.config,
        group: ctx.group,
        plan: ctx.plan,
        seed: ctx.seed,
        resolved_fake_ttl: ctx.resolved_fake_ttl,
        md5sig: ctx.md5sig,
    };
    execute_tcp_hostfake_step(
        &mut hostfake_ctx,
        input.configured_step,
        input.chunk,
        input.start,
        input.end,
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )
}
