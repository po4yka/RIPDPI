use std::borrow::Cow;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{DesyncGroup, DesyncGroupActionSettings, OffsetBase, OffsetExpr, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{plan_tcp, ActivationTransport, AdaptivePlannerHints, DesyncAction};
use ripdpi_packets::{tls_marker_info, OracleRng};
use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;

use crate::activation::activation_context_from_progress;
use crate::strategy_family::{primary_tcp_strategy_family, tcp_fallback_kind_for_strategy};
use crate::DESYNC_SEED_BASE;

pub(crate) const TWO_PHASE_FIRST_WRITE_MIN: usize = 64;
pub(crate) const TWO_PHASE_FIRST_WRITE_MAX: usize = 256;
pub(crate) const TWO_PHASE_GAP_MS_MIN: u16 = 5;
pub(crate) const TWO_PHASE_GAP_MS_MAX: u16 = 15;
const TRANSPARENT_TLS_RUNTIME_INVARIANT_ENABLED: bool = cfg!(debug_assertions);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct TransparentTlsVariant {
    pub(crate) offset_delta: i64,
    pub(crate) first_write_len: Option<usize>,
    pub(crate) phase_gap_ms: Option<u16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TransparentTlsFamilyError {
    UnsupportedPayload,
    InvalidBoundary,
    ByteInvariantViolation,
}

pub(crate) fn apply_tcp_capability_fallback<'a>(
    group: &'a DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
) -> Cow<'a, DesyncGroup> {
    let Some(capability) = capability else {
        return Cow::Borrowed(group);
    };
    if !capability_requires_desync_fallback(capability) {
        return Cow::Borrowed(group);
    }
    let Some(strategy_family) = primary_tcp_strategy_family(group) else {
        if group.actions.fake_tcp_timestamp_enabled {
            let mut adjusted = group.clone();
            adjusted.actions.fake_tcp_timestamp_enabled = false;
            return Cow::Owned(adjusted);
        }
        return Cow::Borrowed(group);
    };
    let mut adjusted = group.clone();
    let mut changed = false;
    if let Some(fallback_kind) = tcp_fallback_kind_for_strategy(strategy_family) {
        if let Some(step) = adjusted.actions.tcp_chain.iter_mut().find(|step| !step.kind.is_tls_prelude()) {
            if step.kind != fallback_kind {
                step.kind = fallback_kind;
                changed = true;
            }
        }
    }
    if adjusted.actions.fake_tcp_timestamp_enabled {
        adjusted.actions.fake_tcp_timestamp_enabled = false;
        changed = true;
    }
    if changed {
        Cow::Owned(adjusted)
    } else {
        Cow::Borrowed(group)
    }
}

pub(crate) fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && capability.reason_code.as_deref() != Some("NO_TCP_FALLBACK"))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}

pub(crate) fn transparent_tls_family_strategy(
    capability: &ProxyDirectPathCapability,
    payload: &[u8],
    progress: OutboundProgress,
) -> Option<&'static str> {
    if progress.round != 1 || progress.stream_start != 0 || tls_marker_info(payload).is_none() {
        return None;
    }
    if !capability.outcome.trim().eq_ignore_ascii_case("TRANSPARENT_OK") {
        return None;
    }
    match capability.tcp_family.trim().to_ascii_uppercase().as_str() {
        "SEG_PRE_SNI" => Some("seg_pre_sni"),
        "SEG_MID_SNI" => Some("seg_mid_sni"),
        "SEG_POST_SNI" => Some("seg_post_sni"),
        "REC_PRE_SNI" => Some("rec_pre_sni"),
        "REC_MID_SNI" => Some("rec_mid_sni"),
        "TWO_PHASE_SEND" => Some("two_phase_send"),
        _ => None,
    }
}

pub(crate) fn transparent_tls_variant_seed(strategy_family: &'static str, payload: &[u8]) -> u32 {
    let epoch_nanos =
        SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_nanos() as u64).unwrap_or_default();
    let mut mix = epoch_nanos ^ ((payload.len() as u64) << 16);
    for byte in strategy_family.bytes() {
        mix = mix.rotate_left(5) ^ u64::from(byte);
    }
    (mix as u32).wrapping_add(((mix >> 32) as u32).wrapping_mul(31))
}

pub(crate) fn weighted_family_delta(strategy_family: &'static str, rng: &mut OracleRng) -> i64 {
    let bucket = rng.next_mod(10);
    match strategy_family {
        "seg_pre_sni" | "rec_pre_sni" => match bucket {
            0 => -2,
            1..=3 => -1,
            _ => 0,
        },
        "seg_mid_sni" | "rec_mid_sni" => match bucket {
            0 => -2,
            1..=2 => -1,
            3..=6 => 0,
            7..=8 => 1,
            _ => 2,
        },
        "seg_post_sni" => match bucket {
            0..=5 => 0,
            6..=8 => 1,
            _ => 2,
        },
        _ => 0,
    }
}

pub(crate) fn transparent_tls_variant_with_seed(
    strategy_family: &'static str,
    payload: &[u8],
    seed: u32,
) -> Result<TransparentTlsVariant, TransparentTlsFamilyError> {
    let mut rng = OracleRng::seeded(seed.max(1));
    match strategy_family {
        "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "rec_pre_sni" | "rec_mid_sni" => {
            if tls_marker_info(payload).is_none() {
                return Err(TransparentTlsFamilyError::UnsupportedPayload);
            }
            Ok(TransparentTlsVariant {
                offset_delta: weighted_family_delta(strategy_family, &mut rng),
                first_write_len: None,
                phase_gap_ms: None,
            })
        }
        "two_phase_send" => {
            if payload.len() <= TWO_PHASE_FIRST_WRITE_MIN {
                return Err(TransparentTlsFamilyError::InvalidBoundary);
            }
            if tls_marker_info(payload).is_none() {
                return Err(TransparentTlsFamilyError::UnsupportedPayload);
            }
            let upper = TWO_PHASE_FIRST_WRITE_MAX.min(payload.len().saturating_sub(1));
            if upper < TWO_PHASE_FIRST_WRITE_MIN {
                return Err(TransparentTlsFamilyError::InvalidBoundary);
            }
            let first_write_len =
                TWO_PHASE_FIRST_WRITE_MIN + rng.next_mod(upper.saturating_sub(TWO_PHASE_FIRST_WRITE_MIN) + 1);
            let phase_gap_ms = TWO_PHASE_GAP_MS_MIN
                + u16::try_from(rng.next_mod(usize::from(TWO_PHASE_GAP_MS_MAX - TWO_PHASE_GAP_MS_MIN + 1)))
                    .unwrap_or_default();
            Ok(TransparentTlsVariant {
                offset_delta: 0,
                first_write_len: Some(first_write_len),
                phase_gap_ms: Some(phase_gap_ms),
            })
        }
        _ => Err(TransparentTlsFamilyError::UnsupportedPayload),
    }
}

pub(crate) fn transparent_tls_variant(
    strategy_family: &'static str,
    payload: &[u8],
) -> Result<TransparentTlsVariant, TransparentTlsFamilyError> {
    transparent_tls_variant_with_seed(strategy_family, payload, transparent_tls_variant_seed(strategy_family, payload))
}

pub(crate) fn transparent_tls_canonical_variant(
    strategy_family: &'static str,
    payload: &[u8],
) -> Result<TransparentTlsVariant, TransparentTlsFamilyError> {
    match strategy_family {
        "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "rec_pre_sni" | "rec_mid_sni" => {
            if tls_marker_info(payload).is_none() {
                return Err(TransparentTlsFamilyError::UnsupportedPayload);
            }
            Ok(TransparentTlsVariant { offset_delta: 0, first_write_len: None, phase_gap_ms: None })
        }
        "two_phase_send" => {
            let upper = TWO_PHASE_FIRST_WRITE_MAX.min(payload.len().saturating_sub(1));
            if upper < TWO_PHASE_FIRST_WRITE_MIN {
                return Err(TransparentTlsFamilyError::InvalidBoundary);
            }
            if tls_marker_info(payload).is_none() {
                return Err(TransparentTlsFamilyError::UnsupportedPayload);
            }
            Ok(TransparentTlsVariant {
                offset_delta: 0,
                first_write_len: Some((payload.len() / 2).clamp(TWO_PHASE_FIRST_WRITE_MIN, upper)),
                phase_gap_ms: Some(TWO_PHASE_GAP_MS_MIN),
            })
        }
        _ => Err(TransparentTlsFamilyError::UnsupportedPayload),
    }
}

pub(crate) fn transparent_tls_family_chain(
    strategy_family: &'static str,
    variant: TransparentTlsVariant,
) -> Result<Vec<TcpChainStep>, TransparentTlsFamilyError> {
    match strategy_family {
        "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "rec_pre_sni" | "rec_mid_sni" => {
            let offset_base = match strategy_family {
                "seg_pre_sni" | "rec_pre_sni" => OffsetBase::SniExt,
                "seg_mid_sni" | "rec_mid_sni" => OffsetBase::MidSld,
                "seg_post_sni" => OffsetBase::EndHost,
                _ => OffsetBase::SniExt,
            };
            let kind = match strategy_family {
                "rec_pre_sni" | "rec_mid_sni" => TcpChainStepKind::TlsRec,
                _ => TcpChainStepKind::Split,
            };
            Ok(vec![TcpChainStep::new(kind, OffsetExpr::tls_marker(offset_base, variant.offset_delta))])
        }
        "two_phase_send" => {
            let mut step = TcpChainStep::new(
                TcpChainStepKind::Split,
                OffsetExpr::absolute(variant.first_write_len.ok_or(TransparentTlsFamilyError::InvalidBoundary)? as i64),
            );
            step.inter_segment_delay_ms =
                u32::from(variant.phase_gap_ms.ok_or(TransparentTlsFamilyError::InvalidBoundary)?);
            Ok(vec![step])
        }
        _ => Err(TransparentTlsFamilyError::UnsupportedPayload),
    }
}

pub(crate) fn flatten_tls_record_payload(buffer: &[u8]) -> Option<Vec<u8>> {
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

pub(crate) fn collect_transport_write_chunks(actions: &[DesyncAction]) -> Option<Vec<Vec<u8>>> {
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

pub(crate) fn apply_transparent_tls_family(
    group: &DesyncGroup,
    strategy_family: &'static str,
    payload: &[u8],
) -> Result<DesyncGroup, TransparentTlsFamilyError> {
    let mut variant = transparent_tls_variant(strategy_family, payload)?;
    let mut adjusted = group.clone();
    adjusted.actions = DesyncGroupActionSettings {
        tcp_chain: transparent_tls_family_chain(strategy_family, variant)?,
        ..DesyncGroupActionSettings::default()
    };
    if TRANSPARENT_TLS_RUNTIME_INVARIANT_ENABLED {
        if let Err(original_error) = validate_transparent_tls_family(payload, strategy_family, &adjusted) {
            variant = transparent_tls_canonical_variant(strategy_family, payload)?;
            adjusted.actions = DesyncGroupActionSettings {
                tcp_chain: transparent_tls_family_chain(strategy_family, variant)?,
                ..DesyncGroupActionSettings::default()
            };
            validate_transparent_tls_family(payload, strategy_family, &adjusted).map_err(|_| original_error)?;
        }
    }
    tracing::debug!(
        strategy_family,
        offset_delta = variant.offset_delta,
        first_write_len = variant.first_write_len.unwrap_or_default(),
        phase_gap_ms = variant.phase_gap_ms.unwrap_or_default(),
        "selected transparent tls family variant"
    );
    Ok(adjusted)
}

pub fn apply_tcp_capability_policy<'a>(
    group: &'a DesyncGroup,
    capability: Option<&ProxyDirectPathCapability>,
    payload: &[u8],
    progress: OutboundProgress,
) -> (Cow<'a, DesyncGroup>, Option<&'static str>) {
    if !group.actions.tcp_chain.is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some() {
        return (apply_tcp_capability_fallback(group, capability), None);
    }
    if let Some(strategy_family) =
        capability.and_then(|value| transparent_tls_family_strategy(value, payload, progress))
    {
        match apply_transparent_tls_family(group, strategy_family, payload) {
            Ok(adjusted) => return (Cow::Owned(adjusted), Some(strategy_family)),
            Err(error) => {
                tracing::warn!(strategy_family, ?error, "skipping transparent tls family due to invalid plan");
            }
        }
    }
    (apply_tcp_capability_fallback(group, capability), None)
}
