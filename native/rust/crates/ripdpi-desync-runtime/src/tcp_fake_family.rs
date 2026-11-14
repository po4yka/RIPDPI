mod fake;
mod fake_disorder;
mod fake_split;
mod options;

use std::net::TcpStream;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::DesyncPlan;

use crate::tcp_lowering::TcpLoweringCapabilities;
use crate::tcp_plan::BuiltFakePackets;
use crate::types::OutboundSendError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TcpStepControl {
    ContinueAt(usize),
    ContinueAtWithFallback(usize, Option<&'static str>),
    BreakPlan,
    BreakPlanWithFallback(Option<&'static str>),
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
            fake::execute(ctx, configured_step, chunk, start, end, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::FakeSplit => {
            fake_split::execute(ctx, configured_step, chunk, start, end, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::FakeDisorder => {
            fake_disorder::execute(ctx, configured_step, chunk, start, end, step_family, step_fallback, bytes_committed)
        }
        _ => unreachable!("non-fake-family step dispatched to fake-family executor"),
    }
}

fn write_entropy_fallback(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    configured_step: &TcpChainStep,
    first: &[u8],
    second: &[u8],
    step_family: &'static str,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    let mut committed = crate::transport_io::write_strategy_payload_with_optional_flags_named(
        ctx.writer,
        first,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        crate::tcp_plan::step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        "write_entropy_split",
        step_family,
        Some("split"),
        bytes_committed,
    )?;
    if !second.is_empty() {
        crate::transport_io::await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            std::time::Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_entropy_split",
            step_family,
            Some("split"),
            committed,
        )?;
        committed = crate::transport_io::write_strategy_payload_with_optional_flags_named(
            ctx.writer,
            second,
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            crate::tcp_plan::step_original_tcp_flags(configured_step),
            ctx.group.actions.ip_id_mode,
            "write_entropy_split",
            step_family,
            Some("split"),
            committed,
        )?;
    }
    Ok(committed)
}
