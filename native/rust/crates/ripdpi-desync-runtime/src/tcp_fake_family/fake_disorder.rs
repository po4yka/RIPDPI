use ripdpi_config::{FakeOrder, TcpChainStep};
use ripdpi_desync::build_fake_region_bytes;

use crate::emissions::{FakeEmission, FakeEmissionRole, ordered_segments_from_emissions};
use crate::tcp_lowering::should_ignore_android_ttl_error;
use crate::transport_io::{
    await_writable_action_named, log_android_desync_fallback, send_fake_tcp_action_named,
    send_ordered_fake_segments_action_named, write_ttl_sensitive_payload_with_optional_flags_named,
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
        return execute_boundary_fallback(ctx, chunk, step_family, step_fallback, bytes_committed, opts, end);
    }

    let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
    let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
    let first_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
    let second_secondary_fake =
        ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, end, second.len()));

    let bytes_committed = if opts.custom_order {
        let ordering = configured_step.fake_ordering();
        let emissions = ordered_disorder_emissions(ordering.order, chunk, &first_fake, second, &second_fake, opts);
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
        let (bytes_committed, lowered_to_fake_split) = send_first_disorder_segment(
            ctx,
            chunk,
            &first_fake,
            first_secondary_fake.as_deref(),
            step_family,
            step_fallback,
            bytes_committed,
            opts,
        )?;
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
            return Ok((bytes_committed, TcpStepControl::BreakPlanWithFallback("fakedsplit")));
        }
        bytes_committed
    };
    Ok((bytes_committed, TcpStepControl::BreakPlan))
}

fn execute_boundary_fallback(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
    opts: FakeStepOptions,
    end: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let ttl_modified = ctx.lowering.set_ttl_named(
        ctx.writer,
        1,
        "set_ttl_fakeddisorder",
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    let (should_restore_ttl, bytes_committed) = write_ttl_sensitive_payload_with_optional_flags_named(
        ctx.lowering,
        ctx.writer,
        chunk,
        ttl_modified,
        ctx.config.network.default_ttl,
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
    if should_restore_ttl {
        let _ = ctx.lowering.restore_default_ttl_named(
            ctx.writer,
            ctx.lowering.restore_ttl,
            "restore_default_ttl_fakeddisorder",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }
    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
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
    match send_fake_tcp_action_named(
        ctx.writer,
        chunk,
        first_fake,
        1,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        opts.tcp_options(ctx.config.process.protect_path.as_deref(), first_secondary_fake),
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
    first_fake: &'a [u8],
    second: &'a [u8],
    second_fake: &'a [u8],
    opts: FakeStepOptions,
) -> Vec<FakeEmission<'a>> {
    let second_offset = chunk.len();
    match order {
        FakeOrder::BeforeEach => vec![
            fake(first_fake, 1, 0, opts),
            genuine(chunk, 1, 0, opts),
            fake(second_fake, opts.fake_ttl, second_offset, opts),
            genuine(second, opts.fake_ttl, second_offset, opts),
        ],
        FakeOrder::AllFakesFirst => vec![
            fake(first_fake, 1, 0, opts),
            fake(second_fake, opts.fake_ttl, second_offset, opts),
            genuine(chunk, 1, 0, opts),
            genuine(second, opts.fake_ttl, second_offset, opts),
        ],
        FakeOrder::RealFakeRealFake => vec![
            genuine(chunk, 1, 0, opts),
            fake(first_fake, 1, 0, opts),
            genuine(second, opts.fake_ttl, second_offset, opts),
            fake(second_fake, opts.fake_ttl, second_offset, opts),
        ],
        FakeOrder::AllRealsFirst => vec![
            genuine(chunk, 1, 0, opts),
            genuine(second, opts.fake_ttl, second_offset, opts),
            fake(first_fake, 1, 0, opts),
            fake(second_fake, opts.fake_ttl, second_offset, opts),
        ],
        _ => vec![
            fake(first_fake, 1, 0, opts),
            genuine(chunk, 1, 0, opts),
            fake(second_fake, opts.fake_ttl, second_offset, opts),
            genuine(second, opts.fake_ttl, second_offset, opts),
        ],
    }
}

fn fake(payload: &[u8], ttl: u8, original_offset: usize, opts: FakeStepOptions) -> FakeEmission<'_> {
    FakeEmission { role: FakeEmissionRole::Fake, payload, ttl, flags: opts.fake_flags, original_offset }
}

fn genuine(payload: &[u8], ttl: u8, original_offset: usize, opts: FakeStepOptions) -> FakeEmission<'_> {
    FakeEmission { role: FakeEmissionRole::Genuine, payload, ttl, flags: opts.original_flags, original_offset }
}
