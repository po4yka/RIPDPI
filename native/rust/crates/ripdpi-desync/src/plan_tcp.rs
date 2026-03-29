use crate::fake::{build_fake_packet, build_hostfake_bytes, resolve_hostfake_span};
use crate::offset::resolve_offset;
use crate::tls_prelude::apply_tls_prelude_steps;
use crate::types::{
    activation_filter_matches, ActivationContext, DesyncAction, DesyncError, DesyncPlan, PlannedStep, ProtoInfo,
};
use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
use ripdpi_packets::OracleRng;

fn allows_missing_marker_offset(step: &TcpChainStep) -> bool {
    matches!(step.offset.base, ripdpi_config::OffsetBase::EchExt)
}

fn push_split_actions(actions: &mut Vec<DesyncAction>, bytes: Vec<u8>) {
    actions.push(DesyncAction::Write(bytes));
    actions.push(DesyncAction::AwaitWritable);
}

fn push_fake_actions(
    actions: &mut Vec<DesyncAction>,
    original: &[u8],
    fake: Vec<u8>,
    group: &DesyncGroup,
    default_ttl: u8,
    fake_ttl: u8,
) {
    if original.is_empty() {
        return;
    }
    actions.push(DesyncAction::SetTtl(fake_ttl));
    if group.actions.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 5 });
    }
    actions.push(DesyncAction::Write(fake));
    actions.push(DesyncAction::AwaitWritable);
    if group.actions.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 0 });
    }
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}

fn split_tcp_chain(chain: &[TcpChainStep]) -> Result<(Vec<TcpChainStep>, Vec<TcpChainStep>), DesyncError> {
    let mut prelude_steps = Vec::new();
    let mut send_steps = Vec::new();
    let mut saw_send_step = false;

    for (index, step) in chain.iter().enumerate() {
        if step.kind.is_tls_prelude() {
            if saw_send_step {
                return Err(DesyncError);
            }
            prelude_steps.push(step.clone());
        } else {
            saw_send_step = true;
            if matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
                && index + 1 != chain.len()
            {
                return Err(DesyncError);
            }
            send_steps.push(step.clone());
        }
    }

    Ok((prelude_steps, send_steps))
}

/// Effective TTL for disorder-family steps: use the configured fake_ttl,
/// falling back to 1 when fake_ttl is zero (unconfigured).
fn disorder_ttl(fake_ttl: u8) -> u8 {
    if fake_ttl == 0 {
        1
    } else {
        fake_ttl
    }
}

fn seqovl_hard_gate_matches(context: ActivationContext, split_end: i64) -> bool {
    if context.round != 1 || context.stream_start < 0 || split_end <= 0 {
        return false;
    }
    context.stream_start.saturating_add(split_end) <= 1500
}

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

    for step in send_steps {
        if !activation_filter_matches(step.activation_filter, context) {
            continue;
        }
        let Some(mut pos) = resolve_offset(
            step.offset,
            &tampered.bytes,
            tampered.bytes.len(),
            lp,
            &mut info,
            &mut rng,
            context,
            context.adaptive.split_offset_base,
        ) else {
            if step.offset.base.is_adaptive() || allows_missing_marker_offset(&step) {
                continue;
            }
            return Err(DesyncError);
        };
        if pos < 0 || pos < lp {
            return Err(DesyncError);
        }
        if pos > tampered.bytes.len() as i64 {
            pos = tampered.bytes.len() as i64;
        }
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        let mut planned_kind = step.kind;

        match step.kind {
            TcpChainStepKind::IpFrag2 => {
                if context.round == 1 && pos > 0 && pos < tampered.bytes.len() as i64 {
                    actions.push(DesyncAction::WriteIpFragmentedTcp {
                        bytes: tampered.bytes.clone(),
                        split_offset: pos as usize,
                    });
                } else {
                    actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
                }
                steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                lp = tampered.bytes.len() as i64;
                continue;
            }
            TcpChainStepKind::Split => {
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::SeqOverlap => {
                if !context.seqovl_supported || !seqovl_hard_gate_matches(context, pos) {
                    planned_kind = TcpChainStepKind::Split;
                }
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::Oob => {
                actions.push(DesyncAction::WriteUrgent {
                    prefix: chunk,
                    urgent_byte: group.actions.oob_data.unwrap_or(b'a'),
                });
            }
            TcpChainStepKind::Disorder => {
                actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
                actions.push(DesyncAction::Write(chunk));
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
                if default_ttl != 0 {
                    actions.push(DesyncAction::SetTtl(default_ttl));
                }
            }
            TcpChainStepKind::Disoob => {
                actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
                actions.push(DesyncAction::WriteUrgent {
                    prefix: chunk,
                    urgent_byte: group.actions.oob_data.unwrap_or(b'a'),
                });
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
                if default_ttl != 0 {
                    actions.push(DesyncAction::SetTtl(default_ttl));
                }
            }
            TcpChainStepKind::Fake => {
                let fake = build_fake_packet(group, &tampered.bytes, seed)?;
                let span = (pos - lp) as usize;
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                push_fake_actions(
                    &mut actions,
                    &tampered.bytes[lp as usize..pos as usize],
                    fake.bytes[fake.fake_offset..fake_end].to_vec(),
                    group,
                    default_ttl,
                    fake_ttl,
                );
            }
            TcpChainStepKind::FakeSplit => {
                // Graceful degradation: when the offset falls at a boundary
                // (pos <= lp or pos >= total), FakeSplit cannot inject a fake
                // copy of the second segment. Fall back to plain Split which
                // still provides packet-level evasion without the fake
                // injection property.
                if pos <= lp || pos >= tampered.bytes.len() as i64 {
                    planned_kind = TcpChainStepKind::Split;
                }
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::FakeDisorder => {
                // Graceful degradation: when the offset falls at a boundary
                // (pos <= lp or pos >= total), FakeDisorder cannot inject a
                // fake copy of the second segment. Fall back to plain Disorder
                // which still provides packet-level evasion without the fake
                // injection property.
                if pos <= lp || pos >= tampered.bytes.len() as i64 {
                    planned_kind = TcpChainStepKind::Disorder;
                }
                actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
                actions.push(DesyncAction::Write(chunk));
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
                if default_ttl != 0 {
                    actions.push(DesyncAction::SetTtl(default_ttl));
                }
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(&step, &tampered.bytes, lp as usize, pos as usize, seed) else {
                    planned_kind = TcpChainStepKind::Split;
                    push_split_actions(&mut actions, chunk);
                    steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                    lp = pos;
                    continue;
                };

                if (lp as usize) < span.host_start {
                    push_split_actions(&mut actions, tampered.bytes[lp as usize..span.host_start].to_vec());
                }

                let real_host = &tampered.bytes[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, step.fake_host_template.as_deref(), seed);
                push_fake_actions(&mut actions, real_host, fake_host.clone(), group, default_ttl, fake_ttl);

                if let Some(midhost) = span.midhost {
                    push_split_actions(&mut actions, tampered.bytes[span.host_start..midhost].to_vec());
                    push_split_actions(&mut actions, tampered.bytes[midhost..span.host_end].to_vec());
                } else {
                    push_split_actions(&mut actions, real_host.to_vec());
                }

                push_fake_actions(&mut actions, real_host, fake_host, group, default_ttl, fake_ttl);

                if span.host_end < pos as usize {
                    push_split_actions(&mut actions, tampered.bytes[span.host_end..pos as usize].to_vec());
                }
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => return Err(DesyncError),
        }
        steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
        if matches!(planned_kind, TcpChainStepKind::Oob) {
            actions.push(DesyncAction::AwaitWritable);
        }
        lp = pos;
    }

    if lp < tampered.bytes.len() as i64 {
        actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
    }

    Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions })
}
