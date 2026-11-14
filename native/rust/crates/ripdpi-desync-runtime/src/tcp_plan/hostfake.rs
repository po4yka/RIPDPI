mod construction;
mod emission_order;
mod pacing;
mod privileged_send;
mod real_write;

use std::io;
use std::net::TcpStream;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep};
use ripdpi_desync::{DesyncPlan, resolve_hostfake_span};

use self::construction::HostFakePayload;
use self::emission_order::{build_custom_ordered_segments, build_default_ordered_segments, needs_custom_ordering};
use self::privileged_send::send_hostfake_ordered_segments;
use self::real_write::{write_hostfake_payload, write_hostfake_payload_with_optional_flags};
use crate::platform;
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use crate::transport_io::strategy_execution_error;
use crate::types::OutboundSendError;

pub(crate) struct TcpHostFakeExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) plan: &'a DesyncPlan,
    pub(crate) seed: u32,
    pub(crate) resolved_fake_ttl: Option<u8>,
    pub(crate) real_ttl: u8,
    pub(crate) lowering: &'a mut crate::tcp_lowering::TcpLoweringCapabilities,
    pub(crate) md5sig: bool,
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute_tcp_hostfake_step(
    ctx: &mut TcpHostFakeExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let Some(span) = resolve_hostfake_span(configured_step, &ctx.plan.tampered, start, end, ctx.seed) else {
        let bytes_committed = write_hostfake_payload_with_optional_flags(
            ctx,
            configured_step,
            chunk,
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        return Ok((bytes_committed, TcpStepControl::ContinueAtWithFallback(end, Some("split"))));
    };

    let raw_available = platform::seqovl_supported();
    let original_flags = step_original_tcp_flags(configured_step);
    if !raw_available && !original_flags.is_empty() {
        return Err(strategy_execution_error(
            "send_fake_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
            io::Error::new(io::ErrorKind::Unsupported, "original TCP flags require the raw path"),
        ));
    }
    let mut bytes_committed = bytes_committed;
    if start < span.host_start {
        bytes_committed = write_hostfake_payload(
            ctx,
            &ctx.plan.tampered[start..span.host_start],
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    let real_host = &ctx.plan.tampered[span.host_start..span.host_end];
    let host_payload = HostFakePayload::new(configured_step, &ctx.plan.tampered, span, ctx.seed)?;
    let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
    let fake_flags = step_fake_tcp_flags(configured_step);
    let midhost_split = span.midhost.map(|midhost| midhost - span.host_start);

    // The fakes must be injected beside the real host without taking sequence
    // space, so every order goes through the raw ordered-segment sender.
    let ordered_segments = if needs_custom_ordering(configured_step, span.midhost) {
        build_custom_ordered_segments(
            configured_step,
            real_host,
            host_payload.fake_host(),
            midhost_split,
            ctx.real_ttl,
            fake_ttl,
            fake_flags,
            original_flags,
        )
    } else {
        build_default_ordered_segments(
            real_host,
            host_payload.fake_host(),
            midhost_split,
            ctx.real_ttl,
            fake_ttl,
            fake_flags,
            original_flags,
        )
    };
    if !raw_available {
        bytes_committed = write_real_host_parts(
            ctx,
            configured_step,
            span.host_start,
            span.host_end,
            span.midhost,
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = write_hostfake_suffix(ctx, span.host_end, end, step_family, step_fallback, bytes_committed)?;
        return Ok((bytes_committed, TcpStepControl::ContinueAtWithFallback(end, Some("split"))));
    }
    let fake_payloads: Vec<_> = if ctx.group.actions.entropy_mode == ripdpi_config::EntropyMode::Disabled {
        Vec::new()
    } else {
        ordered_segments
            .iter()
            .map(|segment| {
                if segment.use_fake_timestamp {
                    crate::activation::apply_entropy_padding(ctx.group, segment.payload, None)
                } else {
                    std::borrow::Cow::Borrowed(segment.payload)
                }
            })
            .collect()
    };
    let mut fake_offset = 0usize;
    let mut ordered_segments = ordered_segments;
    for (segment, payload) in ordered_segments.iter_mut().zip(&fake_payloads) {
        segment.payload = payload;
        if segment.use_fake_timestamp
            && configured_step.fake_ordering().seq_mode == ripdpi_config::FakeSeqMode::Sequential
        {
            segment.sequence_offset = fake_offset;
            fake_offset = fake_offset.saturating_add(payload.len());
        }
    }
    let mut control = TcpStepControl::ContinueAt(end);
    bytes_committed = match send_hostfake_ordered_segments(
        ctx,
        &ordered_segments,
        real_host.len(),
        step_family,
        step_fallback,
        bytes_committed,
    ) {
        Ok(committed) => committed,
        Err(error) if !error.payload_commit_uncertain() && should_lower_hostfake_to_split(error.kind()) => {
            control = TcpStepControl::ContinueAtWithFallback(end, Some("split"));
            tracing::debug!("hostfake raw path unavailable, sending the host as a plain split: {error}");
            write_real_host_parts(
                ctx,
                configured_step,
                span.host_start,
                span.host_end,
                span.midhost,
                step_family,
                step_fallback,
                bytes_committed,
            )?
        }
        Err(error) => return Err(error),
    };

    bytes_committed = write_hostfake_suffix(ctx, span.host_end, end, step_family, step_fallback, bytes_committed)?;
    Ok((bytes_committed, control))
}

/// Raw-path refusals that happen before any packet leaves, so the host can
/// still go out as ordinary stream bytes.
fn should_lower_hostfake_to_split(kind: io::ErrorKind) -> bool {
    matches!(
        kind,
        io::ErrorKind::PermissionDenied
            | io::ErrorKind::Unsupported
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::InvalidInput
    )
}

#[allow(clippy::too_many_arguments)]
fn write_real_host_parts(
    ctx: &mut TcpHostFakeExecContext<'_>,
    configured_step: &TcpChainStep,
    host_start: usize,
    host_end: usize,
    midhost: Option<usize>,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    let split = midhost.unwrap_or(host_end);
    let mut bytes_committed = bytes_committed;
    for range in [host_start..split, split..host_end] {
        if !range.is_empty() {
            bytes_committed = write_hostfake_payload_with_optional_flags(
                ctx,
                configured_step,
                &ctx.plan.tampered[range],
                step_family,
                step_fallback,
                bytes_committed,
            )?;
        }
    }
    Ok(bytes_committed)
}

fn write_hostfake_suffix(
    ctx: &mut TcpHostFakeExecContext<'_>,
    host_end: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    if host_end >= end {
        return Ok(bytes_committed);
    }

    write_hostfake_payload(ctx, &ctx.plan.tampered[host_end..end], step_family, step_fallback, bytes_committed)
}
