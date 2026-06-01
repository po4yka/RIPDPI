mod construction;
mod emission_order;
mod pacing;
mod privileged_send;
mod real_write;

use std::net::TcpStream;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep};
use ripdpi_desync::{DesyncPlan, resolve_hostfake_span};

use self::construction::HostFakePayload;
use self::emission_order::{build_custom_ordered_segments, needs_custom_ordering};
use self::privileged_send::{send_hostfake_ordered_segments, send_hostfake_privileged_pair};
use self::real_write::{write_hostfake_payload, write_hostfake_payload_with_optional_flags};
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_plan::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use crate::types::OutboundSendError;

pub(crate) struct TcpHostFakeExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) plan: &'a DesyncPlan,
    pub(crate) seed: u32,
    pub(crate) resolved_fake_ttl: Option<u8>,
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
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    };

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
    let host_payload = HostFakePayload::new(configured_step, real_host, ctx.seed);
    let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
    let fake_flags = step_fake_tcp_flags(configured_step);
    let original_flags = step_original_tcp_flags(configured_step);

    if needs_custom_ordering(configured_step, span.midhost) {
        let ordering = configured_step.fake_ordering();
        let ordered_segments = build_custom_ordered_segments(
            ordering.order,
            ordering.seq_mode,
            real_host,
            host_payload.fake_host(),
            span.midhost.map(|midhost| midhost - span.host_start),
            fake_ttl,
            fake_flags,
            original_flags,
        );
        bytes_committed = send_hostfake_ordered_segments(
            ctx,
            &ordered_segments,
            real_host.len(),
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = write_hostfake_suffix(ctx, span.host_end, end, step_family, step_fallback, bytes_committed)?;
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    }

    bytes_committed = send_hostfake_privileged_pair(
        ctx,
        real_host,
        host_payload.fake_host(),
        fake_ttl,
        fake_flags,
        original_flags,
        None,
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    bytes_committed = write_real_host_parts(
        ctx,
        span.host_start,
        span.host_end,
        span.midhost,
        real_host,
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    bytes_committed = send_hostfake_privileged_pair(
        ctx,
        real_host,
        host_payload.fake_host(),
        fake_ttl,
        fake_flags,
        original_flags,
        None,
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    bytes_committed = write_hostfake_suffix(ctx, span.host_end, end, step_family, step_fallback, bytes_committed)?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}

#[allow(clippy::too_many_arguments)]
fn write_real_host_parts(
    ctx: &mut TcpHostFakeExecContext<'_>,
    host_start: usize,
    host_end: usize,
    midhost: Option<usize>,
    real_host: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    if let Some(midhost) = midhost {
        let bytes_committed = write_hostfake_payload(
            ctx,
            &ctx.plan.tampered[host_start..midhost],
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        return write_hostfake_payload(
            ctx,
            &ctx.plan.tampered[midhost..host_end],
            step_family,
            step_fallback,
            bytes_committed,
        );
    }

    write_hostfake_payload(ctx, real_host, step_family, step_fallback, bytes_committed)
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
