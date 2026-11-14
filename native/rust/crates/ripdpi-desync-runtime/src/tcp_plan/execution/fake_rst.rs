use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::platform;
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use crate::transport_io::{strategy_execution_error, write_strategy_payload_with_optional_flags_named};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    // The RST carries the live sequence numbers, so it may leave only at the
    // lowered TTL; without a TTL write the step sends no RST. A step whose RST
    // did not leave still writes its chunk and reports the plain split family.
    // Every `fakerst` step runs here, flagged or not.
    let mut rst_sent = false;
    let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8).max(1);
    if ctx.lowering.set_ttl_named(
        ctx.writer,
        fake_ttl,
        "set_ttl_fakerst",
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    )? {
        let reset = platform::send_fake_rst(
            ctx.writer,
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            step_fake_tcp_flags(input.configured_step),
            ctx.group.actions.ip_id_mode,
        );
        let restoration = ctx.lowering.restore_default_ttl_named(
            ctx.writer,
            ctx.lowering.restore_ttl,
            "restore_default_ttl_fakerst",
            input.step_family,
            input.step_fallback,
            input.bytes_committed,
        );
        rst_sent = match reset {
            Ok(()) => true,
            Err(error) if platform::TcpFragmentSendError::before_send(&error) => false,
            Err(error) => {
                return Err(strategy_execution_error(
                    "send_fakerst",
                    input.step_family,
                    input.step_fallback,
                    input.bytes_committed,
                    error,
                ));
            }
        };
        if rst_sent {
            ctx.lowering.note_fake_ttl_attempt(fake_ttl);
        }
        restoration?;
    }
    if !rst_sent {
        ctx.lowering.mark_fake_rst_unsent();
    }
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
