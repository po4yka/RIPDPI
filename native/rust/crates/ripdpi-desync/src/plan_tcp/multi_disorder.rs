use super::offset_plan::resolve_send_step_offset;
use crate::types::{ActivationContext, DesyncError, PlannedStep, ProtoInfo, activation_filter_matches};
use ripdpi_config::{TcpChainStep, TcpChainStepKind};
use ripdpi_packets::OracleRng;

pub(super) fn plan_multi_disorder_steps(
    send_steps: &[TcpChainStep],
    tampered: &[u8],
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<Vec<PlannedStep>, DesyncError> {
    let payload_len = tampered.len() as i64;
    let mut resolved_markers = Vec::with_capacity(send_steps.len());

    for (source_send_step_index, step) in send_steps.iter().enumerate() {
        if step.kind() != TcpChainStepKind::MultiDisorder {
            return Err(DesyncError);
        }
        if !activation_filter_matches(step.activation_filter(), context) {
            continue;
        }
        let Some(pos) = resolve_send_step_offset(step, tampered, 0, info, rng, context)? else {
            continue;
        };
        resolved_markers.push((pos, source_send_step_index));
    }
    resolved_markers.sort_unstable_by_key(|(pos, source_send_step_index)| (*pos, *source_send_step_index));
    if resolved_markers.is_empty() {
        return Ok(Vec::new());
    }

    let mut boundaries = Vec::with_capacity(resolved_markers.len() + 2);
    boundaries.push((0, None));
    boundaries.extend(resolved_markers.into_iter().map(|(pos, source)| (pos, Some(source))));
    boundaries.push((payload_len, None));

    let steps = boundaries
        .windows(2)
        .filter_map(|window| {
            let (start, _) = window[0];
            let (end, source_send_step_index) = window[1];
            (end > start).then_some(PlannedStep {
                kind: TcpChainStepKind::MultiDisorder,
                start,
                end,
                source_send_step_index,
            })
        })
        .collect::<Vec<_>>();

    if steps.len() < 3 {
        return Err(DesyncError);
    }

    Ok(steps)
}
