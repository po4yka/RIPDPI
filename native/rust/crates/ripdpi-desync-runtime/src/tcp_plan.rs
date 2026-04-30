use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, FakeOrder, FakeSeqMode, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{
    build_fake_packet, build_hostfake_bytes, build_secondary_fake_packet, resolve_hostfake_span, DesyncPlan,
    PlannedStep,
};

use crate::emissions::{
    build_ordered_fake_split_emissions, ordered_segments_from_emissions, FakeEmission, FakeEmissionRole,
};
use crate::platform;
use crate::strategy_family::{
    log_ipfrag2_flow_fallback, should_fallback_ipfrag2_tcp_error_kind, strategy_fallback_family, write_action_name,
};
use crate::sync::AtomicBool;
use crate::tcp_lowering::{send_oob_with_android_ttl_fallback, TcpLoweringCapabilities};
use crate::transport_io::{
    await_writable_action_named, send_fake_tcp_action_named, send_ip_fragmented_tcp_action_named,
    send_oob_action_named, send_ordered_fake_segments_action_named, strategy_result, write_strategy_payload_named,
    write_strategy_payload_with_optional_flags_named, write_ttl_sensitive_payload_with_optional_flags_named,
};
use crate::types::OutboundSendError;

pub(crate) fn fake_approximation_step_requires_terminal_lowering(step: &PlannedStep, payload_len: usize) -> bool {
    step.end == payload_len as i64
}

pub(crate) fn requires_special_tcp_execution(
    group: &DesyncGroup,
    plan: &DesyncPlan,
    supports_fake_retransmit: bool,
) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::MultiDisorder | TcpChainStepKind::Fake | TcpChainStepKind::IpFrag2)
            || tcp_step_has_flag_overrides(step)
    }) || plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
            && (supports_fake_retransmit
                || fake_approximation_step_requires_terminal_lowering(step, plan.tampered.len()))
    })
}

pub(crate) fn tcp_step_has_flag_overrides(step: &TcpChainStep) -> bool {
    step.tcp_flags_set.unwrap_or_default() != 0
        || step.tcp_flags_unset.unwrap_or_default() != 0
        || step.tcp_flags_orig_set.unwrap_or_default() != 0
        || step.tcp_flags_orig_unset.unwrap_or_default() != 0
}

pub(crate) fn step_fake_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_set.unwrap_or_default(),
        unset: step.tcp_flags_unset.unwrap_or_default(),
    }
}

pub(crate) fn step_original_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_orig_set.unwrap_or_default(),
        unset: step.tcp_flags_orig_unset.unwrap_or_default(),
    }
}

#[derive(Debug)]
pub(crate) struct BuiltFakePackets {
    pub(crate) primary: ripdpi_desync::FakePacketPlan,
    pub(crate) secondary: Option<ripdpi_desync::FakePacketPlan>,
}

pub(crate) fn build_tcp_fake_packets(
    group: &DesyncGroup,
    tampered: &[u8],
    seed: u32,
) -> io::Result<Option<BuiltFakePackets>> {
    let primary = build_fake_packet(group, tampered, seed)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync"))?;
    let secondary = build_secondary_fake_packet(group, tampered, seed.wrapping_add(1)).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidData, "failed to build secondary fake packet for tcp desync")
    })?;
    Ok(Some(BuiltFakePackets { primary, secondary }))
}

pub(crate) struct TcpBasicStreamExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    md5sig: bool,
}

pub(crate) fn execute_basic_tcp_stream_step(
    ctx: &mut TcpBasicStreamExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => {
            let bytes_committed = write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                chunk,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                ctx.md5sig,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::SeqOverlap => {
            let bytes_committed = write_strategy_payload_named(
                ctx.writer,
                chunk,
                "write_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Oob => {
            let bytes_committed = send_oob_action_named(
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                "send_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        _ => unreachable!("non-basic tcp step dispatched to basic stream executor"),
    }
}

pub(crate) struct TcpTtlSensitiveExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    lowering: &'a mut TcpLoweringCapabilities,
    md5sig: bool,
}

pub(crate) fn execute_ttl_sensitive_tcp_step(
    ctx: &mut TcpTtlSensitiveExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Disorder => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disorder",
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
                "write_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Disoob => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            let (should_restore_ttl, bytes_committed) = send_oob_with_android_ttl_fallback(
                ctx.lowering,
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                ttl_modified,
                "send_oob_disoob",
                "restore_default_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            Ok(bytes_committed)
        }
        _ => unreachable!("non-TTL-sensitive step dispatched to TTL-sensitive executor"),
    }
}

use crate::tcp_fake_family::{execute_tcp_fake_family_step, TcpFakeFamilyExecContext, TcpStepControl};

pub(crate) struct TcpHostFakeExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    plan: &'a DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    md5sig: bool,
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

pub(crate) fn execute_tcp_ipfrag2_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    end: usize,
    configured_step: &TcpChainStep,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let bytes_committed = match send_ip_fragmented_tcp_action_named(
        ctx.writer,
        &ctx.plan.tampered,
        end,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        false, // disorder not available in legacy plan path
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        "write_ipfrag2",
        step_family,
        step_fallback,
        bytes_committed,
    ) {
        Ok(committed) => committed,
        Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
            log_ipfrag2_flow_fallback(&err);
            write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                &ctx.plan.tampered,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                false,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_ipfrag2",
                step_family,
                step_fallback,
                bytes_committed,
            )?
        }
        Err(err) => return Err(err),
    };
    Ok((bytes_committed, TcpStepControl::BreakPlan))
}

pub(crate) fn execute_tcp_fakerst_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let _ = platform::send_fake_rst(
        ctx.writer,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        step_fake_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
    );
    let bytes_committed = write_strategy_payload_with_optional_flags_named(
        ctx.writer,
        chunk,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        "write_fakerst",
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}

pub(crate) struct TcpPlanStepExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    plan: &'a DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    lowering: &'a mut TcpLoweringCapabilities,
    md5sig: bool,
    fake_packets: Option<&'a BuiltFakePackets>,
}

pub(crate) enum TcpPlanLoopControl {
    Continue,
    Break,
    AdvanceToStepEnd,
}

pub(crate) fn tcp_step_strategy_family(kind: TcpChainStepKind, strategy_family: Option<&'static str>) -> &'static str {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => strategy_family.unwrap_or("split"),
        TcpChainStepKind::SeqOverlap => strategy_family.unwrap_or("seqovl"),
        TcpChainStepKind::MultiDisorder => strategy_family.unwrap_or("multidisorder"),
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => strategy_family.unwrap_or("tlsrec"),
        _ => strategy_family.unwrap_or("unknown"),
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn execute_tcp_plan_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
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
        TcpChainStepKind::Split | TcpChainStepKind::SynData | TcpChainStepKind::SeqOverlap | TcpChainStepKind::Oob => {
            let mut basic_stream_ctx = TcpBasicStreamExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                md5sig: ctx.md5sig,
            };
            let bytes_committed = execute_basic_tcp_stream_step(
                &mut basic_stream_ctx,
                kind,
                configured_step,
                chunk,
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::Disorder | TcpChainStepKind::Disoob => {
            let mut ttl_sensitive_ctx = TcpTtlSensitiveExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                lowering: ctx.lowering,
                md5sig: ctx.md5sig,
            };
            let bytes_committed = execute_ttl_sensitive_tcp_step(
                &mut ttl_sensitive_ctx,
                kind,
                configured_step,
                chunk,
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => {
            let fake_packets =
                ctx.fake_packets.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
            let mut fake_family_ctx = TcpFakeFamilyExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                plan: ctx.plan,
                fake_packets,
                resolved_fake_ttl: ctx.resolved_fake_ttl,
                lowering: ctx.lowering,
                md5sig: ctx.md5sig,
            };
            execute_tcp_fake_family_step(
                &mut fake_family_ctx,
                kind,
                configured_step,
                chunk,
                start,
                end,
                step_family,
                step_fallback,
                bytes_committed,
            )
        }
        TcpChainStepKind::IpFrag2 => {
            execute_tcp_ipfrag2_step(ctx, end, configured_step, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::HostFake => {
            let mut hostfake_ctx = TcpHostFakeExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                plan: ctx.plan,
                seed: ctx.seed,
                resolved_fake_ttl: ctx.resolved_fake_ttl,
                md5sig: ctx.md5sig,
            };
            execute_tcp_hostfake_step(
                &mut hostfake_ctx,
                configured_step,
                chunk,
                start,
                end,
                step_family,
                step_fallback,
                bytes_committed,
            )
        }
        TcpChainStepKind::FakeRst => {
            execute_tcp_fakerst_step(ctx, configured_step, chunk, end, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::MultiDisorder => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder must be executed as a grouped tcp plan",
        ))),
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tls prelude step must not appear in tcp send plan",
        ))),
        _ => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "unknown tcp step kind in tcp send plan",
        ))),
    }
}

pub(crate) fn handle_tcp_plan_step_control(
    kind: TcpChainStepKind,
    control: TcpStepControl,
    configured_step: &TcpChainStep,
    index: usize,
    total_steps: usize,
    break_cursor: usize,
    cursor: &mut usize,
) -> TcpPlanLoopControl {
    match control {
        TcpStepControl::ContinueAt(next_cursor)
            if matches!(
                kind,
                TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder
            ) =>
        {
            *cursor = next_cursor;
            if configured_step.inter_segment_delay_ms > 0 && index + 1 < total_steps {
                // std-thread-safe: each connection runs on its own dedicated OS thread
                // (mio + std::thread, no tokio worker pool). Blocking here is correct.
                std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms.min(500))));
            }
            TcpPlanLoopControl::Continue
        }
        TcpStepControl::ContinueAt(next_cursor)
            if matches!(kind, TcpChainStepKind::HostFake | TcpChainStepKind::FakeRst) =>
        {
            *cursor = next_cursor;
            TcpPlanLoopControl::Continue
        }
        TcpStepControl::ContinueAt(_) => TcpPlanLoopControl::AdvanceToStepEnd,
        TcpStepControl::BreakPlan => {
            *cursor = break_cursor;
            TcpPlanLoopControl::Break
        }
    }
}

pub(crate) fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
) -> Result<usize, OutboundSendError> {
    let has_multi_disorder = plan.steps.iter().any(|step| step.kind == TcpChainStepKind::MultiDisorder);
    let fake_packets = if plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
    }) {
        build_tcp_fake_packets(group, &plan.tampered, seed)?
    } else {
        None
    };
    let mut lowering_caps = TcpLoweringCapabilities::snapshot(config.network.default_ttl, session_ttl_unavailable);
    let md5sig = group.actions.md5sig;
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if has_multi_disorder {
        return execute_multi_disorder_tcp_plan(
            writer,
            config,
            &send_steps,
            plan,
            strategy_family,
            md5sig,
            group.actions.ip_id_mode,
        );
    }
    if send_steps.len() < plan.steps.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tcp plan steps exceed configured send steps",
        )));
    }

    let mut cursor = 0usize;
    let mut bytes_committed = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid tcp desync step bounds",
            )));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];
        let step_family = tcp_step_strategy_family(step.kind, strategy_family);
        let step_fallback = strategy_fallback_family(step_family);
        let mut step_ctx = TcpPlanStepExecContext {
            writer,
            config,
            group,
            plan,
            seed,
            resolved_fake_ttl,
            lowering: &mut lowering_caps,
            md5sig,
            fake_packets: fake_packets.as_ref(),
        };
        let (next_bytes_committed, control) = execute_tcp_plan_step(
            &mut step_ctx,
            step.kind,
            configured_step,
            chunk,
            start,
            end,
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = next_bytes_committed;
        match handle_tcp_plan_step_control(
            step.kind,
            control,
            configured_step,
            index,
            plan.steps.len(),
            plan.tampered.len(),
            &mut cursor,
        ) {
            TcpPlanLoopControl::Continue => continue,
            TcpPlanLoopControl::Break => break,
            TcpPlanLoopControl::AdvanceToStepEnd => {}
        }
        if configured_step.inter_segment_delay_ms > 0 && index + 1 < plan.steps.len() {
            // std-thread-safe: each connection runs on its own dedicated OS thread
            // (mio + std::thread, no tokio worker pool). Blocking here is correct.
            std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms.min(500))));
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        bytes_committed = write_strategy_payload_named(
            writer,
            &plan.tampered[cursor..],
            write_action_name(strategy_family.unwrap_or("split")),
            strategy_family.unwrap_or("split"),
            strategy_family.and_then(strategy_fallback_family),
            bytes_committed,
        )?;
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    lowering_caps.persist(session_ttl_unavailable);

    Ok(bytes_committed)
}

pub(crate) fn execute_multi_disorder_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    send_steps: &[ripdpi_config::TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
    md5sig: bool,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> Result<usize, OutboundSendError> {
    let prepared = prepare_multi_disorder_tcp_plan(send_steps, plan, strategy_family)?;
    strategy_result(
        platform::send_multi_disorder_tcp(
            writer,
            &plan.tampered,
            &prepared.segments,
            config.network.default_ttl,
            config.process.protect_path.as_deref(),
            prepared.inter_segment_delay_ms,
            md5sig,
            prepared.original_flags,
            ip_id_mode,
        ),
        "write_multidisorder",
        prepared.strategy_family,
        prepared.fallback,
        0,
    )
    .map(|()| plan.tampered.len())
}

pub(crate) struct PreparedMultiDisorderTcpPlan {
    pub(crate) strategy_family: &'static str,
    pub(crate) fallback: Option<&'static str>,
    pub(crate) inter_segment_delay_ms: u32,
    pub(crate) original_flags: platform::TcpFlagOverrides,
    pub(crate) segments: Vec<platform::TcpPayloadSegment>,
}

pub(crate) fn prepare_multi_disorder_tcp_plan(
    send_steps: &[ripdpi_config::TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
) -> Result<PreparedMultiDisorderTcpPlan, OutboundSendError> {
    if send_steps.len() < 2 || send_steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid multidisorder tcp chain configuration",
        )));
    }
    if plan.steps.len() < 3 || plan.steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder requires at least three non-empty planned segments",
        )));
    }

    let mut cursor = 0usize;
    let mut segments = Vec::with_capacity(plan.steps.len());
    for step in &plan.steps {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start != cursor || end <= start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid multidisorder tcp segment bounds",
            )));
        }
        segments.push(platform::TcpPayloadSegment { start, end });
        cursor = end;
    }
    if cursor != plan.tampered.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder tcp plan does not cover the full payload",
        )));
    }

    let strategy_family = strategy_family.unwrap_or("multidisorder");
    let fallback = strategy_fallback_family(strategy_family);
    let inter_segment_delay_ms = send_steps.first().map_or(0, |s| s.inter_segment_delay_ms);
    let original_flags = step_original_tcp_flags(send_steps.first().expect("multidisorder send step missing"));
    Ok(PreparedMultiDisorderTcpPlan { strategy_family, fallback, inter_segment_delay_ms, original_flags, segments })
}
