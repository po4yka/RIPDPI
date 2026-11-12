use ripdpi_config::{FakeOrder, TcpChainStep};
use ripdpi_desync::build_fake_region_bytes;

use crate::emissions::{FakeEmission, FakeEmissionRole, ordered_segments_from_emissions};
use crate::tcp_lowering::should_ignore_android_ttl_error;
use crate::transport_io::{
    await_writable_action_named, log_android_desync_fallback, send_fake_tcp_action_named,
    send_ordered_fake_segments_action_named, set_stream_ttl, strategy_execution_error,
    write_ttl_sensitive_payload_with_optional_flags_named,
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
        return execute_boundary_fallback(ctx, chunk, step_family, step_fallback, bytes_committed, opts);
    }

    let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
    let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
    let first_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
    let second_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, end, second.len()));

    let bytes_committed = if opts.custom_order {
        let ordering = configured_step.fake_ordering();
        let first_fakes = std::iter::once(first_fake.as_slice())
            .chain(first_secondary_fake.iter().map(Vec::as_slice))
            .collect::<Vec<_>>();
        let second_fakes = std::iter::once(second_fake.as_slice())
            .chain(second_secondary_fake.iter().map(Vec::as_slice))
            .collect::<Vec<_>>();
        let mut emissions = ordered_disorder_emissions(
            ordering.order,
            chunk,
            &first_fakes,
            second,
            &second_fakes,
            ctx.lowering.restore_ttl,
            opts,
        );
        emissions.retain(|emission| !emission.payload.is_empty());
        let ordered_segments = ordered_segments_from_emissions(&emissions, ordering.seq_mode);
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
            "send_fake_fakeddisorder",
            step_family,
            step_fallback,
            bytes_committed,
        )?
    } else {
        let (bytes_committed, lowered_to_fake_split) = if chunk.is_empty() {
            (bytes_committed, false)
        } else {
            send_first_disorder_segment(
                ctx,
                chunk,
                &first_fake,
                first_secondary_fake.as_deref(),
                step_family,
                step_fallback,
                bytes_committed,
                opts,
            )?
        };
        let bytes_committed = send_fake_tcp_action_named(
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
            "fakedsplit",
            None,
            bytes_committed,
        )?;
        if lowered_to_fake_split {
            return Ok((bytes_committed, TcpStepControl::BreakPlanWithFallback(Some("fakedsplit"))));
        }
        bytes_committed
    };
    let control =
        if chunk.is_empty() { TcpStepControl::BreakPlanWithFallback(Some("fake")) } else { TcpStepControl::BreakPlan };
    Ok((bytes_committed, control))
}

fn execute_boundary_fallback(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
    opts: FakeStepOptions,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    if chunk.is_empty() {
        return Ok((bytes_committed, TcpStepControl::BreakPlanWithFallback(None)));
    }
    let ttl_modified = ctx.lowering.set_ttl_named(
        ctx.writer,
        1,
        "set_ttl_fakeddisorder",
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    let result = (|| {
        let (ttl_preserved, bytes_committed) = write_ttl_sensitive_payload_with_optional_flags_named(
            ctx.lowering,
            ctx.writer,
            chunk,
            ttl_modified,
            if ttl_modified { 1 } else { ctx.config.network.default_ttl },
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            opts.original_flags,
            ctx.group.actions.ip_id_mode,
            "write_fakeddisorder",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            opts.wait.0,
            opts.wait.1,
            "await_writable_fakeddisorder",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        let effective_family = ttl_preserved.then_some("disorder");
        Ok((bytes_committed, TcpStepControl::BreakPlanWithFallback(effective_family)))
    })();
    if ttl_modified && let Err(source) = set_stream_ttl(ctx.writer, ctx.lowering.restore_ttl) {
        if let Ok((committed, _)) = &result {
            return Err(strategy_execution_error(
                "restore_default_ttl_fakeddisorder",
                step_family,
                step_fallback,
                *committed,
                source,
            ));
        }
        tracing::warn!("failed to restore boundary TTL after send failure: {source}");
    }
    result
}

#[allow(clippy::too_many_arguments)]
fn send_first_disorder_segment(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    chunk: &[u8],
    first_fake: &[u8],
    first_secondary_fake: Option<&[u8]>,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
    opts: FakeStepOptions,
) -> Result<(usize, bool), OutboundSendError> {
    let mut options = opts.tcp_options(ctx.config.process.protect_path.as_deref(), first_secondary_fake);
    // Keep the original TTL when another modifier already selects raw transport.
    options.force_raw_original = true;
    match send_fake_tcp_action_named(
        ctx.writer,
        chunk,
        first_fake,
        1,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        options,
        ctx.group.actions.ip_id_mode,
        opts.wait,
        "send_fake_fakeddisorder",
        step_family,
        step_fallback,
        bytes_committed,
    ) {
        Ok(bytes_committed) => Ok((bytes_committed, false)),
        Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
            log_android_desync_fallback("send_fake_fakeddisorder", "fakedsplit", &err);
            send_fake_tcp_action_named(
                ctx.writer,
                chunk,
                first_fake,
                opts.fake_ttl,
                ctx.md5sig,
                ctx.config.network.default_ttl,
                opts.tcp_options(ctx.config.process.protect_path.as_deref(), first_secondary_fake),
                ctx.group.actions.ip_id_mode,
                opts.wait,
                "send_fake_fakeddisorder",
                step_family,
                step_fallback,
                bytes_committed,
            )
            .map(|bytes_committed| (bytes_committed, true))
        }
        Err(err) => Err(err),
    }
}

fn ordered_disorder_emissions<'a>(
    order: FakeOrder,
    chunk: &'a [u8],
    first_fakes: &[&'a [u8]],
    second: &'a [u8],
    second_fakes: &[&'a [u8]],
    real_ttl: u8,
    opts: FakeStepOptions,
) -> Vec<FakeEmission<'a>> {
    let second_offset = chunk.len();
    let first_fakes = first_fakes.iter().map(|&payload| fake(payload, 1, 0, opts));
    let second_fakes = second_fakes.iter().map(|&payload| fake(payload, opts.fake_ttl, second_offset, opts));
    let first_real = genuine(chunk, 1, 0, opts);
    let second_real = genuine(second, real_ttl, second_offset, opts);
    match order {
        FakeOrder::AllFakesFirst => first_fakes.chain(second_fakes).chain([first_real, second_real]).collect(),
        FakeOrder::RealFakeRealFake => {
            std::iter::once(first_real).chain(first_fakes).chain([second_real]).chain(second_fakes).collect()
        }
        FakeOrder::AllRealsFirst => {
            [first_real, second_real].into_iter().chain(first_fakes).chain(second_fakes).collect()
        }
        _ => first_fakes.chain([first_real]).chain(second_fakes).chain([second_real]).collect(),
    }
}

fn fake(payload: &[u8], ttl: u8, original_offset: usize, opts: FakeStepOptions) -> FakeEmission<'_> {
    FakeEmission { role: FakeEmissionRole::Fake, payload, ttl, flags: opts.fake_flags, original_offset }
}

fn genuine(payload: &[u8], ttl: u8, original_offset: usize, opts: FakeStepOptions) -> FakeEmission<'_> {
    FakeEmission { role: FakeEmissionRole::Genuine, payload, ttl, flags: opts.original_flags, original_offset }
}

#[cfg(test)]
mod tests;
