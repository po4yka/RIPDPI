use std::net::TcpStream;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStepKind};
use ripdpi_desync::{ActivationContext, TcpDesyncStrategy, activation_filter_matches};
use ripdpi_session::OutboundProgress;

use crate::activation::apply_entropy_padding;
use crate::strategy_family::{effective_tcp_strategy_family, primary_tcp_strategy_family};
use crate::sync::AtomicBool;
use crate::tcp_actions::execute_tcp_actions;
use crate::tcp_plan::{TcpPlanStrategyContext, execute_tcp_plan, requires_special_tcp_execution};
use crate::transport_io::write_transport_payload;
use crate::types::{
    OutboundSendError, OutboundSendOutcome, PcapHook, TcpExecutionDisposition, TcpExecutionReceipt, TcpFallbackReason,
};
use crate::{DESYNC_SEED_BASE, platform};

#[allow(clippy::too_many_arguments)]
pub fn send_prepared_with_group<P: platform::TcpDesyncPlatform + 'static>(
    writer: &mut TcpStream,
    platform_ops: &P,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
    pcap_hook: Option<&PcapHook>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    platform::with_tcp_desync_platform(platform_ops, || {
        // Only apply evolver-suggested entropy padding when the group has fake
        // steps; without fakes the padding bytes reach the upstream server and
        // corrupt the application stream.
        let entropy_override = context.adaptive.entropy_mode.filter(|_| group_has_fake_steps(group));
        let effective_payload = apply_entropy_padding(group, payload, entropy_override);
        let strategy_family = strategy_family_override.or_else(|| primary_tcp_strategy_family(group));
        if should_desync_tcp(group, context) {
            let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
            let strategy = TcpDesyncStrategy::new(group, seed, config.network.default_ttl, context);
            // Build any injected fake decoy from the unpadded `payload` so its
            // captured-ClientHello fidelity and sizing survive entropy padding,
            // while the genuine server-bound writes still use `effective_payload`.
            match strategy.plan_with_fake_reference(&effective_payload, payload) {
                Ok(plan) if requires_special_tcp_execution(group, &plan, platform_ops.supports_fake_retransmit()) => {
                    let tls_prelude_applied = plan.tls_prelude.applied_count > 0;
                    let (planned_effective_family, planned_family_fallback) =
                        effective_tcp_strategy_family(strategy_family, &plan, tls_prelude_applied);
                    let execution = execute_tcp_plan(
                        writer,
                        config,
                        group,
                        &plan,
                        seed,
                        resolved_fake_ttl,
                        TcpPlanStrategyContext { configured_family: strategy_family, tls_prelude_applied },
                        session_ttl_unavailable,
                    )
                    .map_err(|error| {
                        let terminal_reason = match &error {
                            OutboundSendError::Transport { .. } if error.kind() == std::io::ErrorKind::InvalidData => {
                                crate::types::TcpTerminalReason::Planning
                            }
                            OutboundSendError::Transport { .. } => crate::types::TcpTerminalReason::Transport,
                            OutboundSendError::StrategyExecution { .. } => {
                                crate::types::TcpTerminalReason::StrategyExecution
                            }
                        };
                        let receipt = TcpExecutionReceipt::failed_with_plan(
                            group,
                            &plan,
                            strategy_family,
                            planned_effective_family,
                            planned_family_fallback.then_some(TcpFallbackReason::StrategyFamilyFallback),
                            error.execution_receipt(),
                            terminal_reason,
                        );
                        error.with_execution_receipt(receipt)
                    })?;
                    Ok(OutboundSendOutcome {
                        bytes_committed: execution.bytes_committed,
                        strategy_family: execution.effective_family,
                        execution_receipt: TcpExecutionReceipt::applied_with_counters(
                            group,
                            &plan,
                            strategy_family,
                            execution.effective_family,
                            (planned_family_fallback || execution.used_family_fallback)
                                .then_some(TcpFallbackReason::StrategyFamilyFallback),
                            execution.completed_actions,
                            execution.real_writes_committed,
                            execution.completed_awaits,
                            execution.bytes_committed,
                            tls_prelude_applied,
                        ),
                    })
                }
                Ok(plan) => {
                    let tls_prelude_applied = plan.tls_prelude.applied_count > 0;
                    let (effective_family, used_family_fallback) =
                        effective_tcp_strategy_family(strategy_family, &plan, tls_prelude_applied);
                    let bytes_committed = execute_tcp_actions(
                        writer,
                        &plan.actions,
                        config.network.default_ttl,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        strategy_family,
                        session_ttl_unavailable,
                        group.actions.md5sig,
                        group.actions.ip_id_mode,
                        pcap_hook,
                    )?;
                    Ok(OutboundSendOutcome {
                        bytes_committed,
                        strategy_family: effective_family,
                        execution_receipt: TcpExecutionReceipt::applied(
                            group,
                            &plan,
                            strategy_family,
                            effective_family,
                            used_family_fallback.then_some(TcpFallbackReason::StrategyFamilyFallback),
                            tls_prelude_applied,
                        ),
                    })
                }
                Err(_) => {
                    let bytes_committed = write_transport_payload(writer, &effective_payload)?;
                    Ok(OutboundSendOutcome {
                        bytes_committed,
                        strategy_family: None,
                        execution_receipt: TcpExecutionReceipt::plain(
                            TcpExecutionDisposition::PlanFailedPlainFallback,
                            group,
                            strategy_family,
                            bytes_committed,
                        ),
                    })
                }
            }
        } else {
            let bytes_committed = write_transport_payload(writer, &effective_payload)?;
            Ok(OutboundSendOutcome {
                bytes_committed,
                strategy_family: None,
                execution_receipt: TcpExecutionReceipt::plain(
                    TcpExecutionDisposition::ActivationSkipped,
                    group,
                    strategy_family,
                    bytes_committed,
                ),
            })
        }
    })
}

fn group_has_fake_steps(group: &DesyncGroup) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(
            step.kind(),
            TcpChainStepKind::Fake
                | TcpChainStepKind::FakeSplit
                | TcpChainStepKind::FakeDisorder
                | TcpChainStepKind::HostFake
        )
    })
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some()
}

#[cfg(all(test, not(feature = "loom")))]
pub(crate) use crate::tcp_fake_family::*;
#[cfg(all(test, not(feature = "loom")))]
pub(crate) use crate::tcp_plan::*;

#[cfg(all(test, not(feature = "loom")))]
mod tests;
