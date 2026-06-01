use ripdpi_config::DesyncGroup;
use ripdpi_desync::{ActivationTransport, AdaptivePlannerHints, DesyncAction, plan_tcp};
use ripdpi_session::OutboundProgress;

use crate::DESYNC_SEED_BASE;
use crate::activation::activation_context_from_progress;

use super::transparent_tls::{TWO_PHASE_GAP_MS_MAX, TWO_PHASE_GAP_MS_MIN, TransparentTlsFamilyError};

pub(crate) fn validate_transparent_tls_family(
    payload: &[u8],
    strategy_family: &'static str,
    group: &DesyncGroup,
) -> Result<(), TransparentTlsFamilyError> {
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let plan = plan_tcp(group, payload, DESYNC_SEED_BASE, 64, context)
        .map_err(|_| TransparentTlsFamilyError::InvalidBoundary)?;

    match strategy_family {
        "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => {
            let chunks =
                collect_transport_write_chunks(&plan.actions).ok_or(TransparentTlsFamilyError::InvalidBoundary)?;
            if chunks.len() < 2 || chunks.iter().any(Vec::is_empty) {
                return Err(TransparentTlsFamilyError::InvalidBoundary);
            }

            let rebuilt = chunks.into_iter().flatten().collect::<Vec<_>>();
            if rebuilt != payload {
                return Err(TransparentTlsFamilyError::ByteInvariantViolation);
            }

            if strategy_family == "two_phase_send" {
                let delay_ms = plan
                    .actions
                    .iter()
                    .find_map(|action| match action {
                        DesyncAction::Delay(value) => Some(*value),
                        _ => None,
                    })
                    .ok_or(TransparentTlsFamilyError::InvalidBoundary)?;
                if !(TWO_PHASE_GAP_MS_MIN..=TWO_PHASE_GAP_MS_MAX).contains(&delay_ms) {
                    return Err(TransparentTlsFamilyError::InvalidBoundary);
                }
            }
        }
        "rec_pre_sni" | "rec_mid_sni" => {
            let original = flatten_tls_record_payload(payload).ok_or(TransparentTlsFamilyError::UnsupportedPayload)?;
            let transformed =
                flatten_tls_record_payload(&plan.tampered).ok_or(TransparentTlsFamilyError::ByteInvariantViolation)?;
            if transformed != original {
                return Err(TransparentTlsFamilyError::ByteInvariantViolation);
            }
        }
        _ => return Err(TransparentTlsFamilyError::UnsupportedPayload),
    }

    Ok(())
}

fn flatten_tls_record_payload(buffer: &[u8]) -> Option<Vec<u8>> {
    let mut cursor = 0usize;
    let mut flattened = Vec::new();
    while cursor < buffer.len() {
        let header = buffer.get(cursor..cursor + 5)?;
        if header.first().copied()? != 0x16 {
            return None;
        }

        let record_len = usize::from(u16::from_be_bytes([header[3], header[4]]));
        let payload_start = cursor + 5;
        let payload_end = payload_start.checked_add(record_len)?;
        flattened.extend_from_slice(buffer.get(payload_start..payload_end)?);
        cursor = payload_end;
    }

    Some(flattened)
}

fn collect_transport_write_chunks(actions: &[DesyncAction]) -> Option<Vec<Vec<u8>>> {
    let mut chunks = Vec::new();
    for action in actions {
        match action {
            DesyncAction::Write(bytes) => chunks.push(bytes.clone()),
            DesyncAction::AwaitWritable => {}
            DesyncAction::Delay(_) => {}
            _ => return None,
        }
    }

    Some(chunks)
}
