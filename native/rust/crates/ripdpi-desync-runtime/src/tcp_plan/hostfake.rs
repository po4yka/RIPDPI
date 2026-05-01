use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, FakeOrder, FakeSeqMode, RuntimeConfig, TcpChainStep};
use ripdpi_desync::{build_hostfake_bytes, resolve_hostfake_span, DesyncPlan};

use super::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use crate::emissions::{
    build_ordered_fake_split_emissions, ordered_segments_from_emissions, FakeEmission, FakeEmissionRole,
};
use crate::platform;
use crate::tcp_fake_family::TcpStepControl;
use crate::transport_io::{
    await_writable_action_named, send_fake_tcp_action_named, send_ordered_fake_segments_action_named,
    write_strategy_payload_named, write_strategy_payload_with_optional_flags_named,
};
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
        let bytes_committed = write_strategy_payload_with_optional_flags_named(
            ctx.writer,
            chunk,
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            step_original_tcp_flags(configured_step),
            ctx.group.actions.ip_id_mode,
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    };

    let mut bytes_committed = bytes_committed;
    if start < span.host_start {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[start..span.host_start],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    let real_host = &ctx.plan.tampered[span.host_start..span.host_end];
    let fake_host = build_hostfake_bytes(
        real_host,
        configured_step.fake_host_template.as_deref(),
        ctx.seed,
        configured_step.random_fake_host,
    );
    let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
    let fake_flags = step_fake_tcp_flags(configured_step);
    let original_flags = step_original_tcp_flags(configured_step);
    let timestamp_delta_ticks =
        ctx.group.actions.fake_tcp_timestamp_enabled.then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
    let custom_order = configured_step.fake_seq_mode != FakeSeqMode::Duplicate
        || (span.midhost.is_some() && configured_step.fake_order != FakeOrder::BeforeEach);
    if custom_order {
        let emissions = if let Some(midhost) = span.midhost {
            let split = midhost - span.host_start;
            let first_real = &ctx.plan.tampered[span.host_start..midhost];
            let second_real = &ctx.plan.tampered[midhost..span.host_end];
            let first_fake = &fake_host[..split];
            let second_fake = &fake_host[split..];
            build_ordered_fake_split_emissions(
                configured_step.fake_order,
                first_real,
                first_fake,
                second_real,
                second_fake,
                fake_ttl,
                fake_ttl,
                fake_flags,
                original_flags,
            )
        } else {
            vec![
                FakeEmission {
                    role: FakeEmissionRole::Fake,
                    payload: &fake_host,
                    ttl: fake_ttl,
                    flags: fake_flags,
                    original_offset: 0,
                },
                FakeEmission {
                    role: FakeEmissionRole::Genuine,
                    payload: real_host,
                    ttl: fake_ttl,
                    flags: original_flags,
                    original_offset: 0,
                },
                FakeEmission {
                    role: FakeEmissionRole::Fake,
                    payload: &fake_host,
                    ttl: fake_ttl,
                    flags: fake_flags,
                    original_offset: 0,
                },
            ]
        };
        let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
        bytes_committed = send_ordered_fake_segments_action_named(
            ctx.writer,
            &ordered_segments,
            real_host.len(),
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            timestamp_delta_ticks,
            ctx.group.actions.ip_id_mode,
            (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
            "send_fake_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        if span.host_end < end {
            bytes_committed = write_strategy_payload_named(
                ctx.writer,
                &ctx.plan.tampered[span.host_end..end],
                "write_hostfake",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_hostfake",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
        }
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    }

    bytes_committed = send_fake_tcp_action_named(
        ctx.writer,
        real_host,
        &fake_host,
        fake_ttl,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        platform::FakeTcpOptions {
            secondary_fake_prefix: None,
            timestamp_delta_ticks: None,
            protect_path: ctx.config.process.protect_path.as_deref(),
            fake_flags,
            orig_flags: original_flags,
            ..Default::default()
        },
        ctx.group.actions.ip_id_mode,
        (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
        "send_fake_hostfake",
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    if let Some(midhost) = span.midhost {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[span.host_start..midhost],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[midhost..span.host_end],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    } else {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            real_host,
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    bytes_committed = send_fake_tcp_action_named(
        ctx.writer,
        real_host,
        &fake_host,
        fake_ttl,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        platform::FakeTcpOptions {
            secondary_fake_prefix: None,
            timestamp_delta_ticks: None,
            protect_path: ctx.config.process.protect_path.as_deref(),
            fake_flags,
            orig_flags: original_flags,
            ..Default::default()
        },
        ctx.group.actions.ip_id_mode,
        (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
        "send_fake_hostfake",
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    if span.host_end < end {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[span.host_end..end],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}
