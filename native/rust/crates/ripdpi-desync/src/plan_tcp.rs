mod actions;
mod chain;
mod fake_family;
mod fragments;
mod multi_disorder;
mod offset_plan;

use self::actions::{push_disoob_actions, push_disorder_actions, push_fake_rst_actions, push_split_actions};
use self::chain::split_tcp_chain;
use self::fake_family::{HostfakePlan, push_fake_chunk_actions, push_hostfake_actions};
use self::fragments::push_ipfrag2_or_fallback;
use self::multi_disorder::plan_multi_disorder_steps;
use self::offset_plan::{resolve_send_step_offset, seqovl_hard_gate_matches};
use crate::fake::build_seqovl_fake_prefix;
use crate::tls_prelude::apply_tls_prelude_steps;
use crate::types::{
    ActivationContext, DesyncAction, DesyncError, DesyncPlan, PlannedStep, ProtoInfo, TlsPreludeApplication,
    activation_filter_matches,
};
use ripdpi_config::{DesyncGroup, TcpChainStepKind};
use ripdpi_packets::OracleRng;
use ripdpi_strategy_trait::{
    DesyncAction as StrategyAction, DesyncPlan as StrategyPlan, DesyncStrategy, HttpDissect, L7Protocol, QuicDissect,
    StrategyContext, StrategyDescriptor, StrategyDescriptorRegistration, StrategyError, StrategyVerdict, TlsDissect,
};

#[derive(Clone, Copy, Debug)]
pub struct TcpDesyncStrategy<'a> {
    group: &'a DesyncGroup,
    seed: u32,
    default_ttl: u8,
    context: ActivationContext,
}

impl<'a> TcpDesyncStrategy<'a> {
    pub fn new(group: &'a DesyncGroup, seed: u32, default_ttl: u8, context: ActivationContext) -> Self {
        Self { group, seed, default_ttl, context }
    }

    pub fn plan(&self, input: &[u8]) -> Result<DesyncPlan, DesyncError> {
        plan_tcp(self.group, input, self.seed, self.default_ttl, self.context)
    }

    /// Plan against `input` (the server-bound buffer, possibly entropy-padded)
    /// while building any injected fake decoy from `fake_reference` (the
    /// unpadded payload). When `fake_reference` equals `input` this is
    /// byte-for-byte identical to [`TcpDesyncStrategy::plan`].
    pub fn plan_with_fake_reference(&self, input: &[u8], fake_reference: &[u8]) -> Result<DesyncPlan, DesyncError> {
        plan_tcp_with_fake_reference(self.group, input, fake_reference, self.seed, self.default_ttl, self.context)
    }
}

impl DesyncStrategy for TcpDesyncStrategy<'_> {
    fn id(&self) -> &str {
        "tcp_desync"
    }

    fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
        activation_filter_matches(self.group.activation_filter(), self.context)
    }

    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut StrategyPlan) -> Result<(), StrategyError> {
        let native_plan = self
            .plan(ctx.payload)
            .map_err(|_error| StrategyError::Execution("tcp desync planning failed".to_owned()))?;
        plan.actions.extend(native_plan.actions.into_iter().map(strategy_action_from_native));
        plan.verdict = StrategyVerdict::Apply;
        Ok(())
    }

    fn describe(&self) -> StrategyDescriptor {
        tcp_desync_descriptor()
    }
}

fn tcp_desync_descriptor() -> StrategyDescriptor {
    StrategyDescriptor {
        id: "tcp_desync".to_owned(),
        label: "TCP desync planner".to_owned(),
        supported_protocols: vec![
            L7Protocol::Http(HttpDissect::default()),
            L7Protocol::Tls(TlsDissect::default()),
            L7Protocol::Quic(QuicDissect::default()),
            L7Protocol::Unknown,
        ],
        ..StrategyDescriptor::default()
    }
}

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_DESCRIPTOR_REGISTRATIONS)]
static TCP_DESYNC_DESCRIPTOR: StrategyDescriptorRegistration =
    StrategyDescriptorRegistration { id: "tcp_desync", describe: tcp_desync_descriptor };

fn strategy_action_from_native(action: DesyncAction) -> StrategyAction {
    match action {
        DesyncAction::Write(bytes) => StrategyAction::Write(bytes),
        DesyncAction::WriteIpFragmentedTcp { bytes, split_offset: _, disorder: _, ipv6_ext: _ }
        | DesyncAction::WriteIpFragmentedUdp { bytes, split_offset: _, disorder: _, ipv6_ext: _ } => {
            StrategyAction::RawSend(bytes)
        }
        DesyncAction::WriteSeqOverlap { real_chunk, fake_prefix, remainder } => {
            let mut bytes = fake_prefix;
            bytes.extend(real_chunk);
            bytes.extend(remainder);
            StrategyAction::Write(bytes)
        }
        DesyncAction::WriteUrgent { prefix, urgent_byte } => {
            let mut bytes = prefix;
            bytes.push(urgent_byte);
            StrategyAction::Write(bytes)
        }
        DesyncAction::SetTtl(ttl) => StrategyAction::SetTtl(ttl),
        DesyncAction::RestoreDefaultTtl => StrategyAction::RestoreDefaultTtl,
        DesyncAction::SetMd5Sig { key_len: _ }
        | DesyncAction::AttachDropSack
        | DesyncAction::DetachDropSack
        | DesyncAction::AwaitWritable => StrategyAction::RawSend(Vec::new()),
        DesyncAction::SetWindowClamp(value) | DesyncAction::SetWsize { window: value } => {
            StrategyAction::SetWindowClamp(value)
        }
        DesyncAction::RestoreWindowClamp | DesyncAction::RestoreWsize => StrategyAction::SetWindowClamp(0),
        DesyncAction::SendFakeRst => StrategyAction::RawSend(Vec::new()),
        DesyncAction::Delay(_delay_ms) => StrategyAction::RawSend(Vec::new()),
    }
}

pub fn plan_tcp(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
    default_ttl: u8,
    context: ActivationContext,
) -> Result<DesyncPlan, DesyncError> {
    plan_tcp_with_fake_reference(group, input, input, seed, default_ttl, context)
}

/// Internal planner that decouples the server-bound buffer (`input`, possibly
/// entropy-padded) from the buffer used to build injected fake decoys
/// (`fake_reference`, the unpadded payload). Passing `fake_reference == input`
/// reproduces the legacy behavior exactly.
fn plan_tcp_with_fake_reference(
    group: &DesyncGroup,
    input: &[u8],
    fake_reference: &[u8],
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
            tls_prelude: TlsPreludeApplication::default(),
        });
    }
    let chain = group.effective_tcp_chain();
    let (prelude_steps, send_steps) = split_tcp_chain(&chain)?;
    let tampered = apply_tls_prelude_steps(group, &prelude_steps, input, seed, context)?;
    // The fake decoy must be detected/sized from the unpadded ClientHello with
    // the same prelude record-fragmentation applied. When `fake_reference`
    // equals `input` (the no-padding common case) the fake is sourced from the
    // already-computed `tampered.bytes`, so the emitted plan is byte-identical
    // to before. Only when padding is present do we run the prelude a second
    // time over the unpadded reference; that buffer is materialized lazily on
    // the first fake step so non-fake chains pay nothing.
    let fake_is_padded = !(std::ptr::eq(fake_reference, input) || fake_reference == input);
    let mut fake_tampered: Option<Vec<u8>> = None;
    let tls_prelude = tampered.tls_prelude.clone();
    let mut info = tampered.proto;
    let mut rng = OracleRng::seeded(seed);
    let mut steps = Vec::new();
    let mut actions = Vec::new();
    let mut lp = 0i64;
    let fake_ttl = context.resolved_fake_ttl.or(group.actions.ttl).unwrap_or(8);

    if send_steps.iter().any(|step| step.kind() == TcpChainStepKind::MultiDisorder) {
        let steps = plan_multi_disorder_steps(&send_steps, &tampered.bytes, &mut info, &mut rng, context)?;
        return Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions, tls_prelude });
    }

    for step in send_steps {
        if !activation_filter_matches(step.activation_filter(), context) {
            continue;
        }
        let Some(pos) = resolve_send_step_offset(&step, &tampered.bytes, lp, &mut info, &mut rng, context)? else {
            continue;
        };
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        let mut planned_kind = step.kind();

        match step.kind() {
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
                    let payload = step.seq_overlap_payload().ok_or(DesyncError)?;
                    let overlap = payload.overlap_size.max(1) as usize;
                    let fake_prefix =
                        build_seqovl_fake_prefix(group, &tampered.bytes, seed, overlap, payload.fake_mode)?;
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
                let fake_reference_bytes = if fake_is_padded {
                    if fake_tampered.is_none() {
                        fake_tampered =
                            Some(apply_tls_prelude_steps(group, &prelude_steps, fake_reference, seed, context)?.bytes);
                    }
                    fake_tampered.as_deref().unwrap_or(&tampered.bytes)
                } else {
                    &tampered.bytes
                };
                push_fake_chunk_actions(
                    &mut actions,
                    lp,
                    pos,
                    group,
                    &tampered.bytes,
                    fake_reference_bytes,
                    seed,
                    default_ttl,
                    fake_ttl,
                )?;
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
        if step.inter_segment_delay_ms() > 0 && !matches!(planned_kind, TcpChainStepKind::MultiDisorder) {
            actions.push(DesyncAction::Delay(step.inter_segment_delay_ms().min(500) as u16));
        }
        lp = pos;
    }

    if lp < tampered.bytes.len() as i64 {
        actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
    }

    Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions, tls_prelude })
}
