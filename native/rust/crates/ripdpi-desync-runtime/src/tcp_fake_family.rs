use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, FakeOrder, FakeSeqMode, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{build_fake_region_bytes, DesyncPlan};

use crate::emissions::{
    build_ordered_fake_split_emissions, build_plain_fake_emissions, ordered_segments_from_emissions, FakeEmission,
    FakeEmissionRole,
};
use crate::platform;
use crate::tcp_lowering::{should_ignore_android_ttl_error, TcpLoweringCapabilities};
use crate::tcp_plan::{step_fake_tcp_flags, step_original_tcp_flags, BuiltFakePackets};
use crate::transport_io::{
    await_writable_action_named, log_android_desync_fallback, send_fake_tcp_action_named,
    send_ordered_fake_segments_action_named, write_strategy_payload_with_optional_flags_named,
    write_ttl_sensitive_payload_with_optional_flags_named,
};
use crate::types::OutboundSendError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TcpStepControl {
    ContinueAt(usize),
    BreakPlan,
}

pub(crate) struct TcpFakeFamilyExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) plan: &'a DesyncPlan,
    pub(crate) fake_packets: &'a BuiltFakePackets,
    pub(crate) resolved_fake_ttl: Option<u8>,
    pub(crate) lowering: &'a mut TcpLoweringCapabilities,
    pub(crate) md5sig: bool,
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute_tcp_fake_family_step(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    match kind {
        TcpChainStepKind::Fake => {
            let fake = &ctx.fake_packets.primary;
            let span = chunk.len();
            // Use cyclic wrapping when the fake payload is shorter than the
            // split span.  This matches FakeSplit/FakeDisorder which already
            // use build_fake_region_bytes() for the same purpose.
            let fake_chunk: Vec<u8> =
                (0..span).map(|i| fake.bytes[(fake.fake_offset + i) % fake.bytes.len()]).collect();
            let secondary_fake_chunk =
                ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, span));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let fake_refs: Vec<&[u8]> = std::iter::once(fake_chunk.as_slice())
                    .chain(secondary_fake_chunk.iter().map(Vec::as_slice))
                    .collect();
                let emissions = build_plain_fake_emissions(
                    configured_step.fake_order,
                    chunk,
                    &fake_refs,
                    fake_ttl,
                    fake_flags,
                    original_flags,
                );
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
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
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: secondary_fake_chunk.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::FakeSplit => {
            let second = &ctx.plan.tampered[end..];
            if second.is_empty() {
                let bytes_committed = write_strategy_payload_with_optional_flags_named(
                    ctx.writer,
                    chunk,
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    step_original_tcp_flags(configured_step),
                    ctx.group.actions.ip_id_mode,
                    "write_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    ctx.writer,
                    ctx.config.timeouts.wait_send,
                    Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    "await_writable_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
            }
            let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
            let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
            let first_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
            let second_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let emissions = build_ordered_fake_split_emissions(
                    configured_step.fake_order,
                    chunk,
                    &first_fake,
                    second,
                    &second_fake,
                    fake_ttl,
                    fake_ttl,
                    fake_flags,
                    original_flags,
                );
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len() + second.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
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
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: first_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                send_fake_tcp_action_named(
                    ctx.writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: second_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::BreakPlan))
        }
        TcpChainStepKind::FakeDisorder => {
            let second = &ctx.plan.tampered[end..];
            if second.is_empty() {
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
                    step_original_tcp_flags(configured_step),
                    ctx.group.actions.ip_id_mode,
                    "write_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    ctx.writer,
                    ctx.config.timeouts.wait_send,
                    Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
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
                return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
            }
            let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
            let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
            let first_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
            let second_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let second_offset = chunk.len();
                let emissions = match configured_step.fake_order {
                    FakeOrder::BeforeEach => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::AllFakesFirst => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::RealFakeRealFake => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::AllRealsFirst => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                    ],
                    _ => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                };
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len() + second.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            } else {
                let bytes_committed = match send_fake_tcp_action_named(
                    ctx.writer,
                    chunk,
                    &first_fake,
                    1,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: first_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                ) {
                    Ok(bytes_committed) => bytes_committed,
                    Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
                        log_android_desync_fallback("send_fake_fakeddisorder", "fakedsplit", &err);
                        send_fake_tcp_action_named(
                            ctx.writer,
                            chunk,
                            &first_fake,
                            fake_ttl,
                            ctx.md5sig,
                            ctx.config.network.default_ttl,
                            platform::FakeTcpOptions {
                                secondary_fake_prefix: first_secondary_fake.as_deref(),
                                timestamp_delta_ticks,
                                protect_path: ctx.config.process.protect_path.as_deref(),
                                fake_flags,
                                orig_flags: original_flags,
                                ..Default::default()
                            },
                            ctx.group.actions.ip_id_mode,
                            (
                                ctx.config.timeouts.wait_send,
                                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                            ),
                            "send_fake_fakeddisorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?
                    }
                    Err(err) => return Err(err),
                };
                send_fake_tcp_action_named(
                    ctx.writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: second_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    "fakedsplit",
                    None,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::BreakPlan))
        }
        _ => unreachable!("non-fake-family step dispatched to fake-family executor"),
    }
}
