use ripdpi_config::TcpChainStep;
use ripdpi_desync::build_fake_region_bytes;

use crate::emissions::{build_ordered_fake_split_emissions, ordered_segments_from_emissions};
use crate::transport_io::{
    await_writable_action_named, send_fake_tcp_action_named, send_ordered_fake_segments_action_named,
    write_strategy_payload_with_optional_flags_named,
};
use crate::types::OutboundSendError;

use super::options::FakeStepOptions;
use super::{TcpFakeFamilyExecContext, TcpStepControl};

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let second = &ctx.plan.tampered[end..];
    let opts = FakeStepOptions::new(ctx, configured_step);
    if second.is_empty() {
        let bytes_committed = write_strategy_payload_with_optional_flags_named(
            ctx.writer,
            chunk,
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            opts.original_flags,
            ctx.group.actions.ip_id_mode,
            "write_fakesplit",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            opts.wait.0,
            opts.wait.1,
            "await_writable_fakesplit",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    }

    let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
    let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
    let first_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
    let second_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, end, second.len()));

    let bytes_committed = if opts.custom_order {
        let emissions = build_ordered_fake_split_emissions(
            configured_step.fake_order,
            chunk,
            &first_fake,
            second,
            &second_fake,
            opts.fake_ttl,
            opts.fake_ttl,
            opts.fake_flags,
            opts.original_flags,
        );
        let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
        send_ordered_fake_segments_action_named(
            ctx.writer,
            &ordered_segments,
            chunk.len() + second.len(),
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            opts.timestamp_delta_ticks,
            ctx.group.actions.ip_id_mode,
            opts.wait,
            "send_fake_fakesplit",
            step_family,
            step_fallback,
            bytes_committed,
        )?
    } else {
        let bytes_committed = send_fake_tcp_action_named(
            ctx.writer,
            chunk,
            &first_fake,
            opts.fake_ttl,
            ctx.md5sig,
            ctx.config.network.default_ttl,
            opts.tcp_options(ctx.config.process.protect_path.as_deref(), first_secondary_fake.as_deref()),
            ctx.group.actions.ip_id_mode,
            opts.wait,
            "send_fake_fakesplit",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        send_fake_tcp_action_named(
            ctx.writer,
            second,
            &second_fake,
            opts.fake_ttl,
            ctx.md5sig,
            ctx.config.network.default_ttl,
            opts.tcp_options(ctx.config.process.protect_path.as_deref(), second_secondary_fake.as_deref()),
            ctx.group.actions.ip_id_mode,
            opts.wait,
            "send_fake_fakesplit",
            "fakesplit",
            None,
            bytes_committed,
        )?
    };
    Ok((bytes_committed, TcpStepControl::BreakPlan))
}
