use std::time::Duration;

use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{DesyncPlan, PlannedStep};

use super::flags::tcp_step_has_flag_overrides;
use crate::tcp_fake_family::TcpStepControl;

pub(crate) fn fake_approximation_step_requires_terminal_lowering(step: &PlannedStep, payload_len: usize) -> bool {
    step.end == payload_len as i64
}

pub(crate) fn requires_special_tcp_execution(
    group: &DesyncGroup,
    plan: &DesyncPlan,
    supports_fake_retransmit: bool,
) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind(), TcpChainStepKind::MultiDisorder | TcpChainStepKind::Fake | TcpChainStepKind::IpFrag2)
            || tcp_step_has_flag_overrides(step)
    }) || plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
            && (supports_fake_retransmit
                || fake_approximation_step_requires_terminal_lowering(step, plan.tampered.len()))
    })
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
            if configured_step.inter_segment_delay_ms() > 0 && index + 1 < total_steps {
                // std-thread-safe: each connection runs on its own dedicated OS thread
                // (mio + std::thread, no tokio worker pool). Blocking here is correct.
                std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms().min(500))));
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
        TcpStepControl::BreakPlan | TcpStepControl::BreakPlanWithFallback(_) => {
            *cursor = break_cursor;
            TcpPlanLoopControl::Break
        }
    }
}
