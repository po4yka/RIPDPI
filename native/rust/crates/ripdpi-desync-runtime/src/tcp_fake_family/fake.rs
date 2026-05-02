use ripdpi_config::TcpChainStep;

use crate::emissions::{build_plain_fake_emissions, ordered_segments_from_emissions};
use crate::transport_io::{send_fake_tcp_action_named, send_ordered_fake_segments_action_named};
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
    let fake = &ctx.fake_packets.primary;
    let span = chunk.len();
    let fake_chunk: Vec<u8> = (0..span).map(|i| fake.bytes[(fake.fake_offset + i) % fake.bytes.len()]).collect();
    let secondary_fake_chunk = ctx
        .fake_packets
        .secondary
        .as_ref()
        .map(|secondary| ripdpi_desync::build_fake_region_bytes(secondary, start, span));
    let opts = FakeStepOptions::new(ctx, configured_step);

    let bytes_committed = if opts.custom_order {
        let ordering = configured_step.fake_ordering();
        let fake_refs: Vec<&[u8]> =
            std::iter::once(fake_chunk.as_slice()).chain(secondary_fake_chunk.iter().map(Vec::as_slice)).collect();
        let emissions = build_plain_fake_emissions(
            ordering.order,
            chunk,
            &fake_refs,
            opts.fake_ttl,
            opts.fake_flags,
            opts.original_flags,
        );
        let ordered_segments = ordered_segments_from_emissions(&emissions, ordering.seq_mode);
        send_ordered_fake_segments_action_named(
            ctx.writer,
            &ordered_segments,
            chunk.len(),
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            opts.timestamp_delta_ticks,
            ctx.group.actions.ip_id_mode,
            opts.wait,
            "send_fake",
            step_family,
            step_fallback,
            bytes_committed,
        )?
    } else {
        send_fake_tcp_action_named(
            ctx.writer,
            chunk,
            &fake_chunk,
            opts.fake_ttl,
            ctx.md5sig,
            ctx.config.network.default_ttl,
            opts.tcp_options(ctx.config.process.protect_path.as_deref(), secondary_fake_chunk.as_deref()),
            ctx.group.actions.ip_id_mode,
            opts.wait,
            "send_fake",
            step_family,
            step_fallback,
            bytes_committed,
        )?
    };
    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}
