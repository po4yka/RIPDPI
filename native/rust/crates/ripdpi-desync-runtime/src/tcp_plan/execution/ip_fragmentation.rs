use super::{TcpPlanStepExecContext, TcpPlanStepInput};
use crate::strategy_family::{log_ipfrag2_flow_fallback, should_fallback_ipfrag2_tcp_error_kind};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::step_original_tcp_flags;
use crate::transport_io::{send_ip_fragmented_tcp_action_named, write_strategy_payload_with_optional_flags_named};
use crate::types::OutboundSendError;

pub(super) fn execute(
    ctx: &mut TcpPlanStepExecContext<'_>,
    input: &TcpPlanStepInput<'_>,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let payload = input.configured_step.ip_frag_payload();
    let extensions = input.configured_step.ipv6_extension_payload();
    let (bytes_committed, control) = match send_ip_fragmented_tcp_action_named(
        ctx.writer,
        &ctx.plan.tampered,
        input.end,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        payload.is_some_and(|payload| payload.disorder),
        ripdpi_ipfrag::Ipv6ExtHeaders {
            hop_by_hop: extensions.hop_by_hop,
            dest_opt: extensions.dest_opt,
            dest_opt_fragmentable: extensions.dest_opt2,
            routing: extensions.routing,
            second_frag_next_override: extensions.second_frag_next_override,
        },
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
            (committed, TcpStepControl::BreakPlanWithFallback(Some("split")))
        }
        Err(err) => return Err(err),
    };
    Ok((bytes_committed, control))
}
