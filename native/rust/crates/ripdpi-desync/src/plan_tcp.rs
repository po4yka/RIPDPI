use crate::fake::{build_fake_packet, build_hostfake_bytes, build_seqovl_fake_prefix, resolve_hostfake_span};
use crate::offset::resolve_offset;
use crate::tls_prelude::apply_tls_prelude_steps;
use crate::types::{
    activation_filter_matches, ActivationContext, DesyncAction, DesyncError, DesyncPlan, PlannedStep, ProtoInfo,
};
use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
use ripdpi_ipfrag::Ipv6ExtHeaders;
use ripdpi_packets::OracleRng;

fn ipv6_ext_from_tcp_step(step: &TcpChainStep) -> Ipv6ExtHeaders {
    Ipv6ExtHeaders {
        hop_by_hop: step.ipv6_hop_by_hop,
        dest_opt: step.ipv6_dest_opt,
        dest_opt_fragmentable: step.ipv6_dest_opt2,
        routing: step.ipv6_routing,
        second_frag_next_override: step.ipv6_frag_next_override,
    }
}

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

fn plan_multi_disorder_steps(
    send_steps: &[TcpChainStep],
    tampered: &[u8],
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<Vec<PlannedStep>, DesyncError> {
    let payload_len = tampered.len() as i64;
    let mut resolved_markers = Vec::with_capacity(send_steps.len());

    for step in send_steps {
        if step.kind != TcpChainStepKind::MultiDisorder {
            return Err(DesyncError);
        }
        if !activation_filter_matches(step.activation_filter, context) {
            continue;
        }
        let Some(mut pos) = resolve_offset(
            step.offset,
            tampered,
            tampered.len(),
            0,
            info,
            rng,
            context,
            context.adaptive.split_offset_base,
        ) else {
            if step.offset.base.is_adaptive() || allows_missing_marker_offset(step) {
                continue;
            }
            return Err(DesyncError);
        };
        if pos < 0 {
            return Err(DesyncError);
        }
        if pos > payload_len {
            pos = payload_len;
        }
        resolved_markers.push(pos);
    }
    resolved_markers.sort_unstable();

    let mut boundaries = Vec::with_capacity(resolved_markers.len() + 2);
    boundaries.push(0);
    boundaries.extend(resolved_markers);
    boundaries.push(payload_len);

    let steps = boundaries
        .windows(2)
        .filter_map(|window| {
            let start = window[0];
            let end = window[1];
            (end > start).then_some(PlannedStep { kind: TcpChainStepKind::MultiDisorder, start, end })
        })
        .collect::<Vec<_>>();

    if steps.len() < 3 {
        return Err(DesyncError);
    }

    Ok(steps)
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

    if send_steps.iter().any(|step| step.kind == TcpChainStepKind::MultiDisorder) {
        let steps = plan_multi_disorder_steps(&send_steps, &tampered.bytes, &mut info, &mut rng, context)?;
        return Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions });
    }

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
                        disorder: step.ip_frag_disorder,
                        ipv6_ext: ipv6_ext_from_tcp_step(&step),
                    });
                } else {
                    actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
                }
                steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                lp = tampered.bytes.len() as i64;
                continue;
            }
            TcpChainStepKind::Split | TcpChainStepKind::SynData => {
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::SeqOverlap => {
                if !context.seqovl_supported || !seqovl_hard_gate_matches(context, pos) {
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
                let fake_host =
                    build_hostfake_bytes(real_host, step.fake_host_template.as_deref(), seed, step.random_fake_host);
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
            TcpChainStepKind::FakeRst => {
                // FakeRst injects a raw TCP RST with fake TTL to clear DPI state.
                // It doesn't consume payload -- the chunk is written normally after.
                actions.push(DesyncAction::SetTtl(disorder_ttl(fake_ttl)));
                actions.push(DesyncAction::SendFakeRst);
                actions.push(DesyncAction::RestoreDefaultTtl);
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::MultiDisorder => return Err(DesyncError),
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => return Err(DesyncError),
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
