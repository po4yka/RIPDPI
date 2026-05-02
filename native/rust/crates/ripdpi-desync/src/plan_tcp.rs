mod actions;
mod chain;
mod fake_family;
mod fragments;
mod multi_disorder;
mod offset_plan;

use self::actions::{push_disoob_actions, push_disorder_actions, push_fake_rst_actions, push_split_actions};
use self::chain::split_tcp_chain;
use self::fake_family::{push_fake_chunk_actions, push_hostfake_actions, HostfakePlan};
use self::fragments::push_ipfrag2_or_fallback;
use self::multi_disorder::plan_multi_disorder_steps;
use self::offset_plan::{resolve_send_step_offset, seqovl_hard_gate_matches};
use crate::fake::build_seqovl_fake_prefix;
use crate::tls_prelude::apply_tls_prelude_steps;
use crate::types::{
    activation_filter_matches, ActivationContext, DesyncAction, DesyncError, DesyncPlan, PlannedStep, ProtoInfo,
};
use ripdpi_config::{DesyncGroup, TcpChainStepKind};
use ripdpi_packets::OracleRng;

pub fn plan_tcp(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
    default_ttl: u8,
    context: ActivationContext,
) -> Result<DesyncPlan, DesyncError> {
    if !activation_filter_matches(group.activation_filter(), context) {
        return Ok(DesyncPlan {
            tampered: input.to_vec(),
            steps: Vec::new(),
            proto: ProtoInfo::default(),
            actions: vec![DesyncAction::Write(input.to_vec())],
        });
    }
    let chain = group.effective_tcp_chain();
    let (prelude_steps, send_steps) = split_tcp_chain(&chain)?;
    let tampered = apply_tls_prelude_steps(group, &prelude_steps, input, seed, context)?;
    let mut info = tampered.proto;
    let mut rng = OracleRng::seeded(seed);
    let mut steps = Vec::new();
    let mut actions = Vec::new();
    let mut lp = 0i64;
    let fake_ttl = context.resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);

    if send_steps.iter().any(|step| step.kind == TcpChainStepKind::MultiDisorder) {
        let steps = plan_multi_disorder_steps(&send_steps, &tampered.bytes, &mut info, &mut rng, context)?;
        return Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions });
    }

    for step in send_steps {
        if !activation_filter_matches(step.activation_filter, context) {
            continue;
        }
        let Some(pos) = resolve_send_step_offset(&step, &tampered.bytes, lp, &mut info, &mut rng, context)? else {
            continue;
        };
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        let mut planned_kind = step.kind;

        match step.kind {
            TcpChainStepKind::IpFrag2 => {
                push_ipfrag2_or_fallback(&mut actions, &step, &tampered.bytes, lp, pos, context.round);
                steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                lp = tampered.bytes.len() as i64;
                continue;
            }
            TcpChainStepKind::Split | TcpChainStepKind::SynData => {
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::SeqOverlap => {
                if !seqovl_hard_gate_matches(context, pos) {
                    planned_kind = TcpChainStepKind::Split;
                    push_split_actions(&mut actions, chunk);
                } else {
                    let overlap = step.overlap_size.max(1) as usize;
                    let fake_prefix =
                        build_seqovl_fake_prefix(group, &tampered.bytes, seed, overlap, step.seqovl_fake_mode)?;
                    let split = (pos - lp) as usize;
                    let real_chunk = chunk[..split].to_vec();
                    let remainder = tampered.bytes[pos as usize..].to_vec();
                    actions.push(DesyncAction::WriteSeqOverlap { real_chunk, fake_prefix, remainder });
                    steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                    lp = tampered.bytes.len() as i64;
                    continue;
                }
            }
            TcpChainStepKind::Oob => {
                actions.push(DesyncAction::WriteUrgent {
                    prefix: chunk,
                    urgent_byte: group.actions.oob_data.unwrap_or(b'a'),
                });
            }
            TcpChainStepKind::Disorder => {
                push_disorder_actions(&mut actions, chunk, default_ttl, fake_ttl);
            }
            TcpChainStepKind::Disoob => {
                push_disoob_actions(&mut actions, chunk, group.actions.oob_data.unwrap_or(b'a'), default_ttl, fake_ttl);
            }
            TcpChainStepKind::Fake => {
                push_fake_chunk_actions(&mut actions, lp, pos, group, &tampered.bytes, seed, default_ttl, fake_ttl)?;
            }
            TcpChainStepKind::FakeSplit => {
                // Keep the semantic fake step even when the split lands on the
                // terminal boundary. Runtime lowering decides whether the plan
                // can still emit a true fake split or must degrade to a plain
                // write/await approximation.
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::FakeDisorder => {
                // Keep the semantic fake step even when the split lands on the
                // terminal boundary. Runtime lowering owns the emitter
                // restriction and any fallback behavior.
                push_disorder_actions(&mut actions, chunk, default_ttl, fake_ttl);
            }
            TcpChainStepKind::HostFake => {
                planned_kind = push_hostfake_actions(
                    &mut actions,
                    HostfakePlan {
                        step: &step,
                        group,
                        tampered: &tampered.bytes,
                        step_start: lp,
                        step_end: pos,
                        seed,
                        default_ttl,
                        fake_ttl,
                    },
                );
            }
            TcpChainStepKind::FakeRst => {
                // FakeRst injects a raw TCP RST with fake TTL to clear DPI state.
                // It doesn't consume payload -- the chunk is written normally after.
                push_fake_rst_actions(&mut actions, chunk, fake_ttl);
            }
            TcpChainStepKind::MultiDisorder => return Err(DesyncError),
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => return Err(DesyncError),
            _ => return Err(DesyncError),
        }
        steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
        if matches!(planned_kind, TcpChainStepKind::Oob) {
            actions.push(DesyncAction::AwaitWritable);
        }
        if step.inter_segment_delay_ms > 0 && !matches!(planned_kind, TcpChainStepKind::MultiDisorder) {
            actions.push(DesyncAction::Delay(step.inter_segment_delay_ms.min(500) as u16));
        }
        lp = pos;
    }

    if lp < tampered.bytes.len() as i64 {
        actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
    }

    Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions })
}
