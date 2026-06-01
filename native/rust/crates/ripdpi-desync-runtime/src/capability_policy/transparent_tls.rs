use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{DesyncGroup, DesyncGroupActionSettings, OffsetBase, OffsetExpr, TcpChainStep, TcpChainStepKind};
use ripdpi_packets::{OracleRng, tls_marker_info};
use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;

use super::invariant::validate_transparent_tls_family;

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

    if TRANSPARENT_TLS_RUNTIME_INVARIANT_ENABLED
        && let Err(original_error) = validate_transparent_tls_family(payload, strategy_family, &adjusted)
    {
        variant = transparent_tls_canonical_variant(strategy_family, payload)?;
        adjusted.actions = DesyncGroupActionSettings {
            tcp_chain: transparent_tls_family_chain(strategy_family, variant)?,
            ..DesyncGroupActionSettings::default()
        };
        validate_transparent_tls_family(payload, strategy_family, &adjusted).map_err(|_| original_error)?;
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
        "two_phase_send" => two_phase_variant(payload, &mut rng),
        _ => Err(TransparentTlsFamilyError::UnsupportedPayload),
    }
}

fn transparent_tls_variant(
    strategy_family: &'static str,
    payload: &[u8],
) -> Result<TransparentTlsVariant, TransparentTlsFamilyError> {
    transparent_tls_variant_with_seed(strategy_family, payload, transparent_tls_variant_seed(strategy_family, payload))
}

fn transparent_tls_canonical_variant(
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
            let upper = two_phase_upper_bound(payload)?;
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

fn transparent_tls_family_chain(
    strategy_family: &'static str,
    variant: TransparentTlsVariant,
) -> Result<Vec<TcpChainStep>, TransparentTlsFamilyError> {
    match strategy_family {
        "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "rec_pre_sni" | "rec_mid_sni" => {
            Ok(vec![tls_marker_step(strategy_family, variant.offset_delta)])
        }
        "two_phase_send" => {
            let step = TcpChainStep::new(
                TcpChainStepKind::Split,
                OffsetExpr::absolute(variant.first_write_len.ok_or(TransparentTlsFamilyError::InvalidBoundary)? as i64),
            )
            .with_inter_segment_delay_ms(u32::from(
                variant.phase_gap_ms.ok_or(TransparentTlsFamilyError::InvalidBoundary)?,
            ));
            Ok(vec![step])
        }
        _ => Err(TransparentTlsFamilyError::UnsupportedPayload),
    }
}

fn transparent_tls_variant_seed(strategy_family: &'static str, payload: &[u8]) -> u32 {
    let epoch_nanos =
        SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_nanos() as u64).unwrap_or_default();
    let mut mix = epoch_nanos ^ ((payload.len() as u64) << 16);
    for byte in strategy_family.bytes() {
        mix = mix.rotate_left(5) ^ u64::from(byte);
    }

    (mix as u32).wrapping_add(((mix >> 32) as u32).wrapping_mul(31))
}

fn weighted_family_delta(strategy_family: &'static str, rng: &mut OracleRng) -> i64 {
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

fn tls_marker_step(strategy_family: &'static str, offset_delta: i64) -> TcpChainStep {
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

    TcpChainStep::new(kind, OffsetExpr::tls_marker(offset_base, offset_delta))
}

fn two_phase_variant(payload: &[u8], rng: &mut OracleRng) -> Result<TransparentTlsVariant, TransparentTlsFamilyError> {
    let upper = two_phase_upper_bound(payload)?;
    if tls_marker_info(payload).is_none() {
        return Err(TransparentTlsFamilyError::UnsupportedPayload);
    }

    let first_write_len = TWO_PHASE_FIRST_WRITE_MIN + rng.next_mod(upper.saturating_sub(TWO_PHASE_FIRST_WRITE_MIN) + 1);
    let phase_gap_ms = TWO_PHASE_GAP_MS_MIN
        + u16::try_from(rng.next_mod(usize::from(TWO_PHASE_GAP_MS_MAX - TWO_PHASE_GAP_MS_MIN + 1))).unwrap_or_default();
    Ok(TransparentTlsVariant {
        offset_delta: 0,
        first_write_len: Some(first_write_len),
        phase_gap_ms: Some(phase_gap_ms),
    })
}

fn two_phase_upper_bound(payload: &[u8]) -> Result<usize, TransparentTlsFamilyError> {
    if payload.len() <= TWO_PHASE_FIRST_WRITE_MIN {
        return Err(TransparentTlsFamilyError::InvalidBoundary);
    }

    let upper = TWO_PHASE_FIRST_WRITE_MAX.min(payload.len().saturating_sub(1));
    if upper < TWO_PHASE_FIRST_WRITE_MIN {
        return Err(TransparentTlsFamilyError::InvalidBoundary);
    }

    Ok(upper)
}
