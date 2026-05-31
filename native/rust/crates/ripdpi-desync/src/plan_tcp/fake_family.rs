use super::actions::{push_fake_actions, push_split_actions};
use crate::fake::{build_fake_packet, build_hostfake_bytes, resolve_hostfake_span};
use crate::types::{DesyncAction, DesyncError};
use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};

pub(super) struct HostfakePlan<'a> {
    pub(super) step: &'a TcpChainStep,
    pub(super) group: &'a DesyncGroup,
    pub(super) tampered: &'a [u8],
    pub(super) step_start: i64,
    pub(super) step_end: i64,
    pub(super) seed: u32,
    pub(super) default_ttl: u8,
    pub(super) fake_ttl: u8,
}

#[allow(clippy::too_many_arguments)]
pub(super) fn push_fake_chunk_actions(
    actions: &mut Vec<DesyncAction>,
    step_start: i64,
    step_end: i64,
    group: &DesyncGroup,
    tampered: &[u8],
    fake_reference: &[u8],
    seed: u32,
    default_ttl: u8,
    fake_ttl: u8,
) -> Result<(), DesyncError> {
    // The fake decoy is built from the unpadded ClientHello reference so its
    // protocol detection and sizing stay faithful even when entropy padding
    // has been prepended to `tampered`. The genuine server-bound chunk is
    // always sourced from `tampered`, preserving the entropy-evasion contract.
    // When no padding is present `fake_reference` is byte-identical to
    // `tampered`, so this is a no-op relative to the previous behavior.
    let fake = build_fake_packet(group, fake_reference, seed)?;
    // `span` is the genuine chunk length in `tampered` coordinates; the fake
    // region is clamped to the fake buffer length so the slice never panics
    // and stays coherent when the fake is shorter than the padded chunk.
    let span = (step_end - step_start) as usize;
    let fake_offset = fake.fake_offset.min(fake.bytes.len());
    let fake_end = fake_offset.saturating_add(span).min(fake.bytes.len());
    push_fake_actions(
        actions,
        &tampered[step_start as usize..step_end as usize],
        fake.bytes[fake_offset..fake_end].to_vec(),
        group,
        default_ttl,
        fake_ttl,
    );
    Ok(())
}

pub(super) fn push_hostfake_actions(actions: &mut Vec<DesyncAction>, plan: HostfakePlan<'_>) -> TcpChainStepKind {
    let Some(span) =
        resolve_hostfake_span(plan.step, plan.tampered, plan.step_start as usize, plan.step_end as usize, plan.seed)
    else {
        push_split_actions(actions, plan.tampered[plan.step_start as usize..plan.step_end as usize].to_vec());
        return TcpChainStepKind::Split;
    };

    if (plan.step_start as usize) < span.host_start {
        push_split_actions(actions, plan.tampered[plan.step_start as usize..span.host_start].to_vec());
    }

    let real_host = &plan.tampered[span.host_start..span.host_end];
    let fake_host =
        build_hostfake_bytes(real_host, plan.step.fake_host_template(), plan.seed, plan.step.random_fake_host());
    push_fake_actions(actions, real_host, fake_host.clone(), plan.group, plan.default_ttl, plan.fake_ttl);

    if let Some(midhost) = span.midhost {
        push_split_actions(actions, plan.tampered[span.host_start..midhost].to_vec());
        push_split_actions(actions, plan.tampered[midhost..span.host_end].to_vec());
    } else {
        push_split_actions(actions, real_host.to_vec());
    }

    push_fake_actions(actions, real_host, fake_host, plan.group, plan.default_ttl, plan.fake_ttl);

    if span.host_end < plan.step_end as usize {
        push_split_actions(actions, plan.tampered[span.host_end..plan.step_end as usize].to_vec());
    }

    plan.step.kind()
}
