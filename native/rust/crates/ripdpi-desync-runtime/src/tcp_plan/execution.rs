use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::DesyncPlan;

use super::decision::{handle_tcp_plan_step_control, tcp_step_strategy_family, TcpPlanLoopControl};
use super::fake_packets::{build_tcp_fake_packets, BuiltFakePackets};
use super::flags::{step_fake_tcp_flags, step_original_tcp_flags};
use super::hostfake::{execute_tcp_hostfake_step, TcpHostFakeExecContext};
use super::multi_disorder::execute_multi_disorder_tcp_plan;
use super::stream_steps::{
    execute_basic_tcp_stream_step, execute_ttl_sensitive_tcp_step, TcpBasicStreamExecContext,
    TcpTtlSensitiveExecContext,
};
use crate::platform;
use crate::strategy_family::{
    log_ipfrag2_flow_fallback, should_fallback_ipfrag2_tcp_error_kind, strategy_fallback_family, write_action_name,
};
use crate::sync::AtomicBool;
use crate::tcp_fake_family::{execute_tcp_fake_family_step, TcpFakeFamilyExecContext, TcpStepControl};
use crate::tcp_lowering::TcpLoweringCapabilities;
use crate::transport_io::{
    send_ip_fragmented_tcp_action_named, write_strategy_payload_named, write_strategy_payload_with_optional_flags_named,
};
use crate::types::OutboundSendError;

pub(crate) struct TcpPlanStepExecContext<'a> {
    pub(crate) writer: &'a mut TcpStream,
    pub(crate) config: &'a RuntimeConfig,
    pub(crate) group: &'a DesyncGroup,
    pub(crate) plan: &'a DesyncPlan,
    pub(crate) seed: u32,
    pub(crate) resolved_fake_ttl: Option<u8>,
    pub(crate) lowering: &'a mut TcpLoweringCapabilities,
    pub(crate) md5sig: bool,
    pub(crate) fake_packets: Option<&'a BuiltFakePackets>,
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
