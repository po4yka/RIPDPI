mod basic_stream;
mod dispatcher;
mod fake_family;
mod fake_rst;
mod hostfake;
mod invalid;
mod ip_fragmentation;
mod ttl_sensitive;

use std::io;
use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::DesyncPlan;

use super::decision::{TcpPlanLoopControl, handle_tcp_plan_step_control, tcp_step_strategy_family};
use super::fake_packets::{BuiltFakePackets, build_tcp_fake_packets};
use super::multi_disorder::execute_multi_disorder_tcp_plan;
use crate::strategy_family::{effective_tcp_strategy_family, strategy_fallback_family, write_action_name};
use crate::sync::AtomicBool;
use crate::tcp_fake_family::TcpStepControl;
use crate::tcp_lowering::TcpLoweringCapabilities;
use crate::transport_io::write_strategy_payload_named;
use crate::types::OutboundSendError;

#[derive(Debug)]
pub(crate) struct TcpPlanExecutionOutcome {
    pub(crate) bytes_committed: usize,
    pub(crate) effective_family: Option<&'static str>,
    pub(crate) used_family_fallback: bool,
    pub(crate) completed_actions: usize,
    pub(crate) real_writes_committed: usize,
    pub(crate) completed_awaits: usize,
}

#[derive(Clone, Copy)]
pub(crate) struct TcpPlanStrategyContext {
    pub(crate) configured_family: Option<&'static str>,
    pub(crate) tls_prelude_applied: bool,
}

#[cfg(test)]
pub(crate) use fake_rst::execute_tcp_fakerst_step;
#[cfg(test)]
pub(crate) use ip_fragmentation::execute_tcp_ipfrag2_step;

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

pub(crate) struct TcpPlanStepInput<'a> {
    pub(crate) kind: TcpChainStepKind,
    pub(crate) configured_step: &'a TcpChainStep,
    pub(crate) chunk: &'a [u8],
    pub(crate) start: usize,
    pub(crate) end: usize,
    pub(crate) step_family: &'static str,
    pub(crate) step_fallback: Option<&'static str>,
    pub(crate) bytes_committed: usize,
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
    let input =
        TcpPlanStepInput { kind, configured_step, chunk, start, end, step_family, step_fallback, bytes_committed };
    dispatcher::execute(ctx, &input)
}

pub(crate) fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    strategy: TcpPlanStrategyContext,
    session_ttl_unavailable: &AtomicBool,
) -> Result<TcpPlanExecutionOutcome, OutboundSendError> {
    let strategy_family = strategy.configured_family;
    let (initial_effective_family, _) =
        effective_tcp_strategy_family(strategy_family, plan, strategy.tls_prelude_applied);
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
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind().is_tls_prelude()).collect::<Vec<_>>();
    if has_multi_disorder {
        let bytes_committed = execute_multi_disorder_tcp_plan(
            writer,
            config,
            &send_steps,
            plan,
            strategy_family,
            md5sig,
            group.actions.ip_id_mode,
        )?;
        return Ok(TcpPlanExecutionOutcome {
            bytes_committed,
            effective_family: initial_effective_family,
            used_family_fallback: false,
            completed_actions: plan.steps.len(),
            real_writes_committed: plan.steps.len(),
            completed_awaits: 0,
        });
    }
    if send_steps.len() < plan.steps.len() {
        return Err(OutboundSendError::transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tcp plan steps exceed configured send steps",
        )));
    }

    let mut cursor = 0usize;
    let mut bytes_committed = 0usize;
    let mut effective_family = initial_effective_family;
    let mut used_family_fallback = false;
    let mut completed_actions = 0usize;
    let mut real_writes_committed = 0usize;
    let mut completed_awaits = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(OutboundSendError::transport(io::Error::new(
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
        if next_bytes_committed > bytes_committed {
            let writes = real_write_count(step.kind, control, end, plan.tampered.len());
            let awaits = completed_await_count(step.kind, control, end, plan.tampered.len());
            real_writes_committed = real_writes_committed.saturating_add(writes);
            completed_awaits = completed_awaits.saturating_add(awaits);
            completed_actions = completed_actions.saturating_add(writes.saturating_add(awaits));
        }
        bytes_committed = next_bytes_committed;
        if let TcpStepControl::BreakPlanWithFallback(fallback) = control {
            effective_family = Some(fallback);
            used_family_fallback = true;
        }
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
        if configured_step.inter_segment_delay_ms() > 0 && index + 1 < plan.steps.len() {
            // std-thread-safe: each connection runs on its own dedicated OS thread
            // (mio + std::thread, no tokio worker pool). Blocking here is correct.
            std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms().min(500))));
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
        real_writes_committed = real_writes_committed.saturating_add(1);
        completed_actions = completed_actions.saturating_add(1);
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    lowering_caps.persist(session_ttl_unavailable);

    Ok(TcpPlanExecutionOutcome {
        bytes_committed,
        effective_family,
        used_family_fallback,
        completed_actions,
        real_writes_committed,
        completed_awaits,
    })
}

fn real_write_count(kind: TcpChainStepKind, control: TcpStepControl, end: usize, payload_len: usize) -> usize {
    match kind {
        TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder if end < payload_len && breaks_plan(control) => 2,
        _ => 1,
    }
}

fn completed_await_count(kind: TcpChainStepKind, control: TcpStepControl, end: usize, payload_len: usize) -> usize {
    match kind {
        TcpChainStepKind::Split
        | TcpChainStepKind::SynData
        | TcpChainStepKind::SeqOverlap
        | TcpChainStepKind::Oob
        | TcpChainStepKind::Disorder
        | TcpChainStepKind::Disoob => 1,
        TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder if end < payload_len && breaks_plan(control) => 2,
        _ => 0,
    }
}

fn breaks_plan(control: TcpStepControl) -> bool {
    matches!(control, TcpStepControl::BreakPlan | TcpStepControl::BreakPlanWithFallback(_))
}
