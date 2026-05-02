use std::io;

use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::tcp_fake_family::{execute_tcp_fake_family_step, TcpFakeFamilyExecContext, TcpStepControl};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let fake_packets =
        ctx.fake_packets.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
    let mut fake_family_ctx = TcpFakeFamilyExecContext {
        writer: ctx.writer,
        config: ctx.config,
        group: ctx.group,
        plan: ctx.plan,
        fake_packets,
        resolved_fake_ttl: ctx.resolved_fake_ttl,
        lowering: ctx.lowering,
        md5sig: ctx.md5sig,
    };
    execute_tcp_fake_family_step(
        &mut fake_family_ctx,
        input.kind,
        input.configured_step,
        input.chunk,
        input.start,
        input.end,
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )
}
