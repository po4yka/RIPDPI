#[cfg(test)]
use ripdpi_config::{TcpChainStep, TcpChainStepKind};

use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::strategy_family::{log_ipfrag2_flow_fallback, should_fallback_ipfrag2_tcp_error_kind};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::step_original_tcp_flags;
use crate::transport_io::{send_ip_fragmented_tcp_action_named, write_strategy_payload_with_optional_flags_named};
use crate::types::OutboundSendError;

#[cfg(test)]
#[allow(dead_code)]
pub(crate) fn execute_tcp_ipfrag2_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    end: usize,
    configured_step: &TcpChainStep,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let input = TcpPlanStepInput {
        kind: TcpChainStepKind::IpFrag2,
        configured_step,
        chunk: &[],
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
    let (bytes_committed, control) = match send_ip_fragmented_tcp_action_named(
        ctx.writer,
        &ctx.plan.tampered,
        input.end,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        false, // disorder not available in legacy plan path
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        step_original_tcp_flags(input.configured_step),
        ctx.group.actions.ip_id_mode,
        "write_ipfrag2",
        input.step_family,
        input.step_fallback,
        input.bytes_committed,
    ) {
        Ok(committed) => (committed, TcpStepControl::BreakPlan),
        Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
            log_ipfrag2_flow_fallback(&err);
            let committed = write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                &ctx.plan.tampered,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                false,
                step_original_tcp_flags(input.configured_step),
                ctx.group.actions.ip_id_mode,
                "write_ipfrag2",
                input.step_family,
                input.step_fallback,
                input.bytes_committed,
            )?;
            (committed, TcpStepControl::BreakPlanWithFallback("split"))
        }
        Err(err) => return Err(err),
    };
    Ok((bytes_committed, control))
}
