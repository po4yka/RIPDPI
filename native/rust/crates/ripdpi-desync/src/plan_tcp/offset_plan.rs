use crate::offset::resolve_offset;
use crate::types::{ActivationContext, DesyncError, ProtoInfo};
use ripdpi_config::TcpChainStep;
use ripdpi_packets::OracleRng;

fn allows_missing_marker_offset(step: &TcpChainStep) -> bool {
    matches!(step.offset.base, ripdpi_config::OffsetBase::EchExt)
}

pub(super) fn resolve_send_step_offset(
    step: &TcpChainStep,
    tampered: &[u8],
    lp: i64,
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<Option<i64>, DesyncError> {
    let Some(mut pos) = resolve_offset(
        step.offset,
        tampered,
        tampered.len(),
        lp,
        info,
        rng,
        context,
        context.adaptive.split_offset_base,
    ) else {
        if step.offset.base.is_adaptive() || allows_missing_marker_offset(step) {
            return Ok(None);
        }
        return Err(DesyncError);
    };
    if pos < 0 || pos < lp {
        return Err(DesyncError);
    }
    if pos > tampered.len() as i64 {
        pos = tampered.len() as i64;
    }
    Ok(Some(pos))
}

pub(super) fn seqovl_hard_gate_matches(context: ActivationContext, split_end: i64) -> bool {
    if context.round != 1 || context.stream_start < 0 || split_end <= 0 {
        return false;
    }
    context.stream_start.saturating_add(split_end) <= 1500
}
