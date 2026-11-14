use crate::activation::pad_fake_payload;
use ripdpi_config::TcpChainStep;
use ripdpi_desync::FakePacketPlan;

use crate::emissions::{build_plain_fake_emissions, ordered_segments_from_emissions};
use crate::transport_io::{send_fake_tcp_action_named, send_ordered_fake_segments_action_named};
use crate::types::OutboundSendError;

use super::options::FakeStepOptions;
use super::{TcpFakeFamilyExecContext, TcpStepControl};

/// A plain fake always starts at its own `fake_offset`, whatever the position of the real chunk.
fn plain_fake_chunks(
    primary: &FakePacketPlan,
    secondary: Option<&FakePacketPlan>,
    span: usize,
) -> (Vec<u8>, Option<Vec<u8>>) {
    (
        ripdpi_desync::build_fake_region_bytes(primary, 0, span),
        secondary.map(|fake| ripdpi_desync::build_fake_region_bytes(fake, 0, span)),
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let fake = &ctx.fake_packets.primary;
    let span = chunk.len();
    let (fake_chunk, secondary_fake_chunk) = plain_fake_chunks(fake, ctx.fake_packets.secondary.as_ref(), span);
    let fake_chunk = pad_fake_payload(ctx.group, fake_chunk);
    let secondary_fake_chunk = secondary_fake_chunk.map(|chunk| pad_fake_payload(ctx.group, chunk));
    let opts = FakeStepOptions::new(ctx, configured_step);

    if (fake_chunk.len() > chunk.len() || secondary_fake_chunk.as_ref().is_some_and(|fake| fake.len() > chunk.len()))
        && !crate::platform::seqovl_supported()
    {
        let committed = super::write_entropy_fallback(ctx, configured_step, chunk, &[], step_family, bytes_committed)?;
        return Ok((committed, TcpStepControl::ContinueAtWithFallback(end, Some("split"))));
    }
    let bytes_committed = if opts.custom_order {
        let ordering = configured_step.fake_ordering();
        let fake_refs: Vec<&[u8]> =
            std::iter::once(fake_chunk.as_slice()).chain(secondary_fake_chunk.iter().map(Vec::as_slice)).collect();
        let emissions = build_plain_fake_emissions(
            ordering.order,
            chunk,
            &fake_refs,
            ctx.lowering.restore_ttl,
            opts.fake_ttl,
            opts.fake_flags,
            opts.original_flags,
        );
        let ordered_segments = ordered_segments_from_emissions(&emissions, ordering.seq_mode);
        ctx.lowering.note_ordered_fake_ttl_attempt(&ordered_segments);
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
        if !chunk.is_empty() && !fake_chunk.is_empty() {
            ctx.lowering.note_fake_ttl_attempt(opts.fake_ttl);
        }
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

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_desync::ProtoInfo;

    fn plan(bytes: &[u8], fake_offset: usize) -> FakePacketPlan {
        FakePacketPlan { bytes: bytes.to_vec(), fake_offset, proto: ProtoInfo::default() }
    }

    #[test]
    fn plain_fake_chunks_start_at_fake_offset_and_wrap_after_it() {
        let primary = plan(b"HEADabcde", 4);
        let secondary = plan(b"XYZ123", 3);

        let (p, s) = plain_fake_chunks(&primary, Some(&secondary), 7);

        assert_eq!(p, b"abcdeab");
        assert_eq!(s.as_deref(), Some(&b"1231231"[..]));
        assert_eq!(plain_fake_chunks(&primary, None, 3), (b"abc".to_vec(), None));
    }
}
