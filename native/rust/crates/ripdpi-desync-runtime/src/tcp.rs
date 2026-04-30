use std::borrow::Cow;
use std::io::{self, Write};
use std::net::TcpStream;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ripdpi_config::{
    DesyncGroup, DesyncGroupActionSettings, FakeOrder, FakeSeqMode, OffsetBase, OffsetExpr, RuntimeConfig,
    TcpChainStep, TcpChainStepKind,
};
use ripdpi_desync::{
    activation_filter_matches, build_fake_packet, build_fake_region_bytes, build_hostfake_bytes,
    build_secondary_fake_packet, plan_tcp, resolve_hostfake_span, ActivationContext, ActivationTransport,
    AdaptivePlannerHints, DesyncAction, DesyncPlan, PlannedStep,
};
use ripdpi_packets::{tls_marker_info, OracleRng};
use ripdpi_proxy_config::ProxyDirectPathCapability;
use ripdpi_session::OutboundProgress;
use socket2::SockRef;

use crate::activation::{activation_context_from_progress, apply_entropy_padding};
use crate::emissions::{
    build_ordered_fake_split_emissions, build_plain_fake_emissions, ordered_segments_from_emissions, FakeEmission,
    FakeEmissionRole,
};
use crate::sync::AtomicBool;
use crate::tcp_lowering::{
    send_oob_with_android_ttl_fallback, should_ignore_android_ttl_error, write_payload_with_android_ttl_fallback,
    TcpLoweringCapabilities,
};
use crate::types::{OutboundSendError, OutboundSendOutcome, PcapHook};
use crate::{platform, DESYNC_SEED_BASE};

const TWO_PHASE_FIRST_WRITE_MIN: usize = 64;
const TWO_PHASE_FIRST_WRITE_MAX: usize = 256;
const TWO_PHASE_GAP_MS_MIN: u16 = 5;
const TWO_PHASE_GAP_MS_MAX: u16 = 15;
const TRANSPARENT_TLS_RUNTIME_INVARIANT_ENABLED: bool = cfg!(debug_assertions);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct TransparentTlsVariant {
    offset_delta: i64,
    first_write_len: Option<usize>,
    phase_gap_ms: Option<u16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TransparentTlsFamilyError {
    UnsupportedPayload,
    InvalidBoundary,
    ByteInvariantViolation,
}

#[derive(Debug)]
struct WriteProgressError {
    written: usize,
    source: io::Error,
}

#[allow(clippy::too_many_arguments)]
pub fn send_prepared_with_group<P: platform::TcpDesyncPlatform + 'static>(
    writer: &mut TcpStream,
    platform_ops: &P,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    payload: &[u8],
    progress: OutboundProgress,
    context: ActivationContext,
    resolved_fake_ttl: Option<u8>,
    strategy_family_override: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
    pcap_hook: Option<&PcapHook>,
) -> Result<OutboundSendOutcome, OutboundSendError> {
    platform::with_tcp_desync_platform(platform_ops, || {
        // Only apply evolver-suggested entropy padding when the group has fake
        // steps; without fakes the padding bytes reach the upstream server and
        // corrupt the application stream.
        let entropy_override = context.adaptive.entropy_mode.filter(|_| group_has_fake_steps(group));
        let effective_payload = apply_entropy_padding(group, payload, entropy_override);
        let strategy_family = strategy_family_override.or_else(|| primary_tcp_strategy_family(group));
        if should_desync_tcp(group, context) {
            let seed = DESYNC_SEED_BASE + progress.round.saturating_sub(1);
            match plan_tcp(group, &effective_payload, seed, config.network.default_ttl, context) {
                Ok(plan) if requires_special_tcp_execution(group, &plan, platform_ops.supports_fake_retransmit()) => {
                    let bytes_committed = execute_tcp_plan(
                        writer,
                        config,
                        group,
                        &plan,
                        seed,
                        resolved_fake_ttl,
                        strategy_family,
                        session_ttl_unavailable,
                    )?;
                    Ok(OutboundSendOutcome { bytes_committed, strategy_family })
                }
                Ok(plan) => {
                    let bytes_committed = execute_tcp_actions(
                        writer,
                        &plan.actions,
                        config.network.default_ttl,
                        config.timeouts.wait_send,
                        Duration::from_millis(config.timeouts.await_interval.max(1) as u64),
                        strategy_family,
                        session_ttl_unavailable,
                        group.actions.md5sig,
                        group.actions.ip_id_mode,
                        pcap_hook,
                    )?;
                    Ok(OutboundSendOutcome { bytes_committed, strategy_family })
                }
                Err(_) => {
                    let bytes_committed = write_transport_payload(writer, &effective_payload)?;
                    Ok(OutboundSendOutcome { bytes_committed, strategy_family: None })
                }
            }
        } else {
            let bytes_committed = write_transport_payload(writer, &effective_payload)?;
            Ok(OutboundSendOutcome { bytes_committed, strategy_family: None })
        }
    })
}

fn group_has_fake_steps(group: &DesyncGroup) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(
            step.kind,
            TcpChainStepKind::Fake
                | TcpChainStepKind::FakeSplit
                | TcpChainStepKind::FakeDisorder
                | TcpChainStepKind::HostFake
        )
    })
}

fn should_desync_tcp(group: &DesyncGroup, context: ActivationContext) -> bool {
    has_tcp_actions(group) && activation_filter_matches(group.activation_filter(), context)
}

fn has_tcp_actions(group: &DesyncGroup) -> bool {
    !group.effective_tcp_chain().is_empty() || group.actions.mod_http != 0 || group.actions.tlsminor.is_some()
}

pub fn primary_tcp_strategy_family(group: &DesyncGroup) -> Option<&'static str> {
    let chain = group.effective_tcp_chain();
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    chain.into_iter().find(|step| !step.kind.is_tls_prelude()).map(|step| match step.kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => "split",
        TcpChainStepKind::SeqOverlap => {
            if has_tls_prelude {
                "tlsrec_seqovl"
            } else {
                "seqovl"
            }
        }
        TcpChainStepKind::MultiDisorder => {
            if has_tls_prelude {
                "tlsrec_multidisorder"
            } else {
                "multidisorder"
            }
        }
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => "tlsrec",
        _ => "unknown",
    })
}

fn strategy_fallback_family(strategy_family: &'static str) -> Option<&'static str> {
    match strategy_family {
        "seg_mid_sni" => Some("seg_pre_sni"),
        "seg_post_sni" => Some("seg_mid_sni"),
        "rec_mid_sni" => Some("rec_pre_sni"),
        "seqovl" => Some("split"),
        "tlsrec_seqovl" => Some("tlsrec_split"),
        "disorder" => Some("split"),
        "disoob" => Some("oob"),
        "fakeddisorder" => Some("fakedsplit"),
        _ => None,
    }
}

fn apply_tcp_capability_fallback<'a>(
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

fn capability_requires_desync_fallback(capability: &ProxyDirectPathCapability) -> bool {
    capability.fallback_required == Some(true)
        || capability.repeated_handshake_failure_class.as_deref().is_some_and(|value| !value.trim().is_empty())
        || (matches!(capability.quic_mode.trim().to_ascii_uppercase().as_str(), "SOFT_DISABLE" | "HARD_DISABLE")
            && capability.reason_code.as_deref() != Some("NO_TCP_FALLBACK"))
        || matches!(capability.outcome.trim().to_ascii_uppercase().as_str(), "OWNED_STACK_ONLY" | "NO_DIRECT_SOLUTION")
}

fn transparent_tls_family_strategy(
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

fn transparent_tls_variant_with_seed(
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

fn transparent_tls_family_chain(
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

fn validate_transparent_tls_family(
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

fn apply_transparent_tls_family(
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

fn tcp_fallback_kind_for_strategy(strategy_family: &'static str) -> Option<TcpChainStepKind> {
    match strategy_family {
        "seqovl" | "tlsrec_seqovl" | "disorder" => Some(TcpChainStepKind::Split),
        "disoob" => Some(TcpChainStepKind::Oob),
        "fakeddisorder" => Some(TcpChainStepKind::FakeSplit),
        _ => None,
    }
}

fn write_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" | "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => "write_split",
        "seqovl" | "tlsrec_seqovl" => "write_seqovl",
        "disorder" => "write_disorder",
        "oob" => "write_oob",
        "disoob" => "write_disoob",
        "fake" => "write_fake",
        "fakedsplit" => "write_fakesplit",
        "fakeddisorder" => "write_fakeddisorder",
        "hostfake" => "write_hostfake",
        "rec_pre_sni" | "rec_mid_sni" => "write_tlsrec",
        _ => "write",
    }
}

fn should_fallback_ipfrag2_tcp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput | io::ErrorKind::WouldBlock | io::ErrorKind::Unsupported)
}

fn log_ipfrag2_flow_fallback(error: &impl std::fmt::Display) {
    tracing::debug!("falling back to normal TCP write for ipfrag2 after per-flow repair downgrade: {error}");
}

fn should_fallback_seqovl_error_kind(kind: io::ErrorKind) -> bool {
    matches!(
        kind,
        io::ErrorKind::InvalidInput
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::Unsupported
            | io::ErrorKind::PermissionDenied
    )
}

fn await_writable_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" | "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => "await_writable_split",
        "seqovl" | "tlsrec_seqovl" => "await_writable_seqovl",
        "disorder" => "await_writable_disorder",
        "oob" => "await_writable_oob",
        "disoob" => "await_writable_disoob",
        "fakedsplit" => "await_writable_fakesplit",
        "fakeddisorder" => "await_writable_fakeddisorder",
        "hostfake" => "await_writable_hostfake",
        "rec_pre_sni" | "rec_mid_sni" => "await_writable_tlsrec",
        _ => "await_writable",
    }
}

fn set_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "set_ttl_disorder",
        "disoob" => "set_ttl_disoob",
        "fakeddisorder" => "set_ttl_fakeddisorder",
        _ => "set_ttl",
    }
}

fn restore_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "restore_default_ttl_disorder",
        "disoob" => "restore_default_ttl_disoob",
        "fakeddisorder" => "restore_default_ttl_fakeddisorder",
        _ => "restore_default_ttl",
    }
}

fn fake_approximation_step_requires_terminal_lowering(step: &PlannedStep, payload_len: usize) -> bool {
    step.end == payload_len as i64
}

pub(crate) fn requires_special_tcp_execution(
    group: &DesyncGroup,
    plan: &DesyncPlan,
    supports_fake_retransmit: bool,
) -> bool {
    group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::MultiDisorder | TcpChainStepKind::Fake | TcpChainStepKind::IpFrag2)
            || tcp_step_has_flag_overrides(step)
    }) || plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
            && (supports_fake_retransmit
                || fake_approximation_step_requires_terminal_lowering(step, plan.tampered.len()))
    })
}

fn tcp_step_has_flag_overrides(step: &TcpChainStep) -> bool {
    step.tcp_flags_set.unwrap_or_default() != 0
        || step.tcp_flags_unset.unwrap_or_default() != 0
        || step.tcp_flags_orig_set.unwrap_or_default() != 0
        || step.tcp_flags_orig_unset.unwrap_or_default() != 0
}

fn step_fake_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_set.unwrap_or_default(),
        unset: step.tcp_flags_unset.unwrap_or_default(),
    }
}

fn step_original_tcp_flags(step: &TcpChainStep) -> platform::TcpFlagOverrides {
    platform::TcpFlagOverrides {
        set: step.tcp_flags_orig_set.unwrap_or_default(),
        unset: step.tcp_flags_orig_unset.unwrap_or_default(),
    }
}

#[allow(clippy::too_many_arguments)]
fn execute_tcp_actions(
    writer: &mut TcpStream,
    actions: &[DesyncAction],
    default_ttl: u8,
    wait_send: bool,
    await_interval: Duration,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
    md5sig: bool,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    pcap_hook: Option<&PcapHook>,
) -> Result<usize, OutboundSendError> {
    // Snapshot the per-connection TTL lowering capabilities once so action
    // execution and plan execution share the same degradation behavior.
    let mut lowering_caps = TcpLoweringCapabilities::snapshot(default_ttl, session_ttl_unavailable);
    let cached_restore_ttl: Option<u8> = Some(lowering_caps.restore_ttl);
    let mut ttl_modified = false;
    let mut bytes_committed = 0usize;
    let fallback = strategy_family.and_then(strategy_fallback_family);

    let result = (|| -> Result<usize, OutboundSendError> {
        for action in actions {
            match action {
                DesyncAction::Write(bytes) => {
                    if let Some(strategy_family) = strategy_family {
                        if fallback.is_some() && ttl_modified {
                            let (should_restore_ttl, committed) = write_payload_with_android_ttl_fallback(
                                &mut lowering_caps,
                                writer,
                                bytes,
                                ttl_modified,
                                write_action_name(strategy_family),
                                restore_ttl_action_name(strategy_family),
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                            ttl_modified = should_restore_ttl;
                            bytes_committed = committed;
                        } else {
                            bytes_committed = write_strategy_payload_named(
                                writer,
                                bytes,
                                write_action_name(strategy_family),
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                        }
                    } else {
                        bytes_committed = write_transport_payload(writer, bytes)?;
                    }
                    if let Some(hook) = pcap_hook {
                        hook(bytes, true);
                    }
                }
                DesyncAction::WriteUrgent { prefix, urgent_byte } => {
                    if let Some(strategy_family) = strategy_family {
                        if fallback.is_some() && ttl_modified {
                            let (should_restore_ttl, committed) = send_oob_with_android_ttl_fallback(
                                &mut lowering_caps,
                                writer,
                                prefix,
                                *urgent_byte,
                                ttl_modified,
                                match strategy_family {
                                    "disoob" => "send_oob_disoob",
                                    _ => "send_oob",
                                },
                                restore_ttl_action_name(strategy_family),
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                            ttl_modified = should_restore_ttl;
                            bytes_committed = committed;
                        } else {
                            bytes_committed = send_oob_action_named(
                                writer,
                                prefix,
                                *urgent_byte,
                                match strategy_family {
                                    "disoob" => "send_oob_disoob",
                                    _ => "send_oob",
                                },
                                strategy_family,
                                fallback,
                                bytes_committed,
                            )?;
                        }
                    } else {
                        bytes_committed = send_transport_oob_payload(writer, prefix, *urgent_byte)?;
                    }
                }
                DesyncAction::SetTtl(ttl) => {
                    if lowering_caps.set_ttl_named(
                        writer,
                        *ttl,
                        strategy_family.map_or("set_ttl", set_ttl_action_name),
                        strategy_family.unwrap_or("split"),
                        fallback,
                        bytes_committed,
                    )? {
                        ttl_modified = true;
                    }
                }
                DesyncAction::RestoreDefaultTtl => {
                    if let Some(restore) = cached_restore_ttl {
                        if lowering_caps.restore_default_ttl_named(
                            writer,
                            restore,
                            strategy_family.map_or("restore_default_ttl", restore_ttl_action_name),
                            strategy_family.unwrap_or("split"),
                            fallback,
                            bytes_committed,
                        )? {
                            ttl_modified = false;
                        }
                    }
                }
                DesyncAction::SetMd5Sig { key_len } => {
                    if let Some(strategy_family) = strategy_family {
                        set_md5sig_action_named(
                            writer,
                            *key_len,
                            "set_md5sig",
                            strategy_family,
                            fallback,
                            bytes_committed,
                        )?;
                    } else {
                        set_md5sig_transport_action(writer, *key_len)?;
                    }
                }
                DesyncAction::AttachDropSack => {}
                DesyncAction::DetachDropSack => {}
                DesyncAction::WriteIpFragmentedTcp { bytes, split_offset, disorder, ipv6_ext } => {
                    if let Some(strategy_family) = strategy_family {
                        match send_ip_fragmented_tcp_action_named(
                            writer,
                            bytes,
                            *split_offset,
                            default_ttl,
                            None,
                            *disorder,
                            *ipv6_ext,
                            platform::TcpFlagOverrides::default(),
                            ip_id_mode,
                            "write_ipfrag2",
                            strategy_family,
                            fallback,
                            bytes_committed,
                        ) {
                            Ok(committed) => {
                                bytes_committed = committed;
                            }
                            Err(err)
                                if strategy_family == "ipfrag2"
                                    && should_fallback_ipfrag2_tcp_error_kind(err.kind()) =>
                            {
                                log_ipfrag2_flow_fallback(&err);
                                bytes_committed = write_strategy_payload_named(
                                    writer,
                                    bytes,
                                    "write_ipfrag2",
                                    strategy_family,
                                    fallback,
                                    bytes_committed,
                                )?;
                            }
                            Err(err) => return Err(err),
                        }
                    } else {
                        match platform::send_ip_fragmented_tcp(
                            writer,
                            bytes,
                            *split_offset,
                            default_ttl,
                            None,
                            *disorder,
                            *ipv6_ext,
                            platform::TcpFlagOverrides::default(),
                            ip_id_mode,
                        ) {
                            Ok(()) => {
                                bytes_committed += bytes.len();
                            }
                            Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
                                log_ipfrag2_flow_fallback(&err);
                                bytes_committed = write_transport_payload(writer, bytes)?;
                            }
                            Err(err) => return Err(OutboundSendError::Transport(err)),
                        }
                    }
                }
                DesyncAction::WriteSeqOverlap { real_chunk, fake_prefix, remainder } => {
                    match platform::send_seqovl_tcp(
                        writer,
                        real_chunk,
                        fake_prefix,
                        default_ttl,
                        None,
                        md5sig,
                        platform::TcpFlagOverrides::default(),
                        ip_id_mode,
                    ) {
                        Ok(()) => {
                            bytes_committed += real_chunk.len();
                            if !remainder.is_empty() {
                                bytes_committed += write_transport_payload(writer, remainder)?;
                            }
                        }
                        Err(err) if should_fallback_seqovl_error_kind(err.kind()) => {
                            tracing::warn!("seqovl fallback to split: {err}");
                            bytes_committed += write_transport_payload(writer, real_chunk)?;
                            if !remainder.is_empty() {
                                bytes_committed += write_transport_payload(writer, remainder)?;
                            }
                        }
                        Err(err) => return Err(OutboundSendError::Transport(err)),
                    }
                }
                DesyncAction::WriteIpFragmentedUdp { .. } => {
                    return Err(OutboundSendError::Transport(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "udp fragmentation action reached tcp executor",
                    )));
                }
                DesyncAction::AwaitWritable => {
                    if let Some(strategy_family) = strategy_family {
                        await_writable_action_named(
                            writer,
                            wait_send,
                            await_interval,
                            await_writable_action_name(strategy_family),
                            strategy_family,
                            fallback,
                            bytes_committed,
                        )?;
                    } else {
                        await_transport_writable_action(writer, wait_send, await_interval)?;
                    }
                }
                DesyncAction::SetWindowClamp(size) => {
                    let _ = platform::set_tcp_window_clamp(writer, *size);
                }
                DesyncAction::RestoreWindowClamp => {
                    let _ = platform::set_tcp_window_clamp(writer, 0);
                }
                DesyncAction::SetWsize { window } => {
                    let _ = platform::set_tcp_window_clamp(writer, *window);
                }
                DesyncAction::RestoreWsize => {
                    let _ = platform::set_tcp_window_clamp(writer, 0);
                }
                DesyncAction::SendFakeRst => {
                    let _ = platform::send_fake_rst(
                        writer,
                        default_ttl,
                        None,
                        platform::TcpFlagOverrides::default(),
                        ip_id_mode,
                    );
                }
                DesyncAction::Delay(ms) => {
                    // std-thread-safe: each connection runs on its own dedicated OS thread
                    // (mio + std::thread, no tokio worker pool). Blocking here is correct.
                    std::thread::sleep(Duration::from_millis(u64::from(*ms)));
                }
            }
        }
        Ok(bytes_committed)
    })();

    // Safety net: restore TTL even on early error return.
    if ttl_modified {
        if let Some(restore) = cached_restore_ttl {
            let _ = set_stream_ttl(writer, restore);
        }
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    lowering_caps.persist(session_ttl_unavailable);

    result
}

#[derive(Debug)]
struct BuiltFakePackets {
    primary: ripdpi_desync::FakePacketPlan,
    secondary: Option<ripdpi_desync::FakePacketPlan>,
}

fn build_tcp_fake_packets(group: &DesyncGroup, tampered: &[u8], seed: u32) -> io::Result<Option<BuiltFakePackets>> {
    let primary = build_fake_packet(group, tampered, seed)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "failed to build fake packet for tcp desync"))?;
    let secondary = build_secondary_fake_packet(group, tampered, seed.wrapping_add(1)).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidData, "failed to build secondary fake packet for tcp desync")
    })?;
    Ok(Some(BuiltFakePackets { primary, secondary }))
}

struct TcpBasicStreamExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    md5sig: bool,
}

fn execute_basic_tcp_stream_step(
    ctx: &mut TcpBasicStreamExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => {
            let bytes_committed = write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                chunk,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                ctx.md5sig,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_split",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::SeqOverlap => {
            let bytes_committed = write_strategy_payload_named(
                ctx.writer,
                chunk,
                "write_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_seqovl",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Oob => {
            let bytes_committed = send_oob_action_named(
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                "send_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_oob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        _ => unreachable!("non-basic tcp step dispatched to basic stream executor"),
    }
}

struct TcpTtlSensitiveExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    lowering: &'a mut TcpLoweringCapabilities,
    md5sig: bool,
}

fn execute_ttl_sensitive_tcp_step(
    ctx: &mut TcpTtlSensitiveExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    match kind {
        TcpChainStepKind::Disorder => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            let (should_restore_ttl, bytes_committed) = write_ttl_sensitive_payload_with_optional_flags_named(
                ctx.lowering,
                ctx.writer,
                chunk,
                ttl_modified,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                ctx.md5sig,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disorder",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok(bytes_committed)
        }
        TcpChainStepKind::Disoob => {
            let ttl_modified = ctx.lowering.set_ttl_named(
                ctx.writer,
                1,
                "set_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            let (should_restore_ttl, bytes_committed) = send_oob_with_android_ttl_fallback(
                ctx.lowering,
                ctx.writer,
                chunk,
                ctx.group.actions.oob_data.unwrap_or(b'a'),
                ttl_modified,
                "send_oob_disoob",
                "restore_default_ttl_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_disoob",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            if should_restore_ttl {
                let _ = ctx.lowering.restore_default_ttl_named(
                    ctx.writer,
                    ctx.lowering.restore_ttl,
                    "restore_default_ttl_disoob",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
            }
            Ok(bytes_committed)
        }
        _ => unreachable!("non-TTL-sensitive step dispatched to TTL-sensitive executor"),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TcpStepControl {
    ContinueAt(usize),
    BreakPlan,
}

struct TcpFakeFamilyExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    plan: &'a DesyncPlan,
    fake_packets: &'a BuiltFakePackets,
    resolved_fake_ttl: Option<u8>,
    lowering: &'a mut TcpLoweringCapabilities,
    md5sig: bool,
}

#[allow(clippy::too_many_arguments)]
fn execute_tcp_fake_family_step(
    ctx: &mut TcpFakeFamilyExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    match kind {
        TcpChainStepKind::Fake => {
            let fake = &ctx.fake_packets.primary;
            let span = chunk.len();
            // Use cyclic wrapping when the fake payload is shorter than the
            // split span.  This matches FakeSplit/FakeDisorder which already
            // use build_fake_region_bytes() for the same purpose.
            let fake_chunk: Vec<u8> =
                (0..span).map(|i| fake.bytes[(fake.fake_offset + i) % fake.bytes.len()]).collect();
            let secondary_fake_chunk =
                ctx.fake_packets.secondary.as_ref().map(|secondary| build_fake_region_bytes(secondary, start, span));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let fake_refs: Vec<&[u8]> = std::iter::once(fake_chunk.as_slice())
                    .chain(secondary_fake_chunk.iter().map(Vec::as_slice))
                    .collect();
                let emissions = build_plain_fake_emissions(
                    configured_step.fake_order,
                    chunk,
                    &fake_refs,
                    fake_ttl,
                    fake_flags,
                    original_flags,
                );
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            } else {
                send_fake_tcp_action_named(
                    ctx.writer,
                    chunk,
                    &fake_chunk,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: secondary_fake_chunk.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::FakeSplit => {
            let second = &ctx.plan.tampered[end..];
            if second.is_empty() {
                let bytes_committed = write_strategy_payload_with_optional_flags_named(
                    ctx.writer,
                    chunk,
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    step_original_tcp_flags(configured_step),
                    ctx.group.actions.ip_id_mode,
                    "write_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    ctx.writer,
                    ctx.config.timeouts.wait_send,
                    Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    "await_writable_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
            }
            let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
            let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
            let first_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
            let second_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let emissions = build_ordered_fake_split_emissions(
                    configured_step.fake_order,
                    chunk,
                    &first_fake,
                    second,
                    &second_fake,
                    fake_ttl,
                    fake_ttl,
                    fake_flags,
                    original_flags,
                );
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len() + second.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            } else {
                let bytes_committed = send_fake_tcp_action_named(
                    ctx.writer,
                    chunk,
                    &first_fake,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: first_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                send_fake_tcp_action_named(
                    ctx.writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: second_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::BreakPlan))
        }
        TcpChainStepKind::FakeDisorder => {
            let second = &ctx.plan.tampered[end..];
            if second.is_empty() {
                let ttl_modified = ctx.lowering.set_ttl_named(
                    ctx.writer,
                    1,
                    "set_ttl_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                let (should_restore_ttl, bytes_committed) = write_ttl_sensitive_payload_with_optional_flags_named(
                    ctx.lowering,
                    ctx.writer,
                    chunk,
                    ttl_modified,
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    step_original_tcp_flags(configured_step),
                    ctx.group.actions.ip_id_mode,
                    "write_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                await_writable_action_named(
                    ctx.writer,
                    ctx.config.timeouts.wait_send,
                    Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    "await_writable_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?;
                if should_restore_ttl {
                    let _ = ctx.lowering.restore_default_ttl_named(
                        ctx.writer,
                        ctx.lowering.restore_ttl,
                        "restore_default_ttl_fakeddisorder",
                        step_family,
                        step_fallback,
                        bytes_committed,
                    )?;
                }
                return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
            }
            let first_fake = build_fake_region_bytes(&ctx.fake_packets.primary, start, chunk.len());
            let second_fake = build_fake_region_bytes(&ctx.fake_packets.primary, end, second.len());
            let first_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, start, chunk.len()));
            let second_secondary_fake = ctx
                .fake_packets
                .secondary
                .as_ref()
                .map(|secondary| build_fake_region_bytes(secondary, end, second.len()));
            let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
            let fake_flags = step_fake_tcp_flags(configured_step);
            let original_flags = step_original_tcp_flags(configured_step);
            let timestamp_delta_ticks = ctx
                .group
                .actions
                .fake_tcp_timestamp_enabled
                .then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
            let custom_order = configured_step.fake_order != FakeOrder::BeforeEach
                || configured_step.fake_seq_mode != FakeSeqMode::Duplicate;
            let bytes_committed = if custom_order {
                let second_offset = chunk.len();
                let emissions = match configured_step.fake_order {
                    FakeOrder::BeforeEach => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::AllFakesFirst => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::RealFakeRealFake => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                    ],
                    FakeOrder::AllRealsFirst => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                    ],
                    _ => vec![
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &first_fake,
                            ttl: 1,
                            flags: fake_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: chunk,
                            ttl: 1,
                            flags: original_flags,
                            original_offset: 0,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Fake,
                            payload: &second_fake,
                            ttl: fake_ttl,
                            flags: fake_flags,
                            original_offset: second_offset,
                        },
                        FakeEmission {
                            role: FakeEmissionRole::Genuine,
                            payload: second,
                            ttl: fake_ttl,
                            flags: original_flags,
                            original_offset: second_offset,
                        },
                    ],
                };
                let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
                send_ordered_fake_segments_action_named(
                    ctx.writer,
                    &ordered_segments,
                    chunk.len() + second.len(),
                    ctx.config.network.default_ttl,
                    ctx.config.process.protect_path.as_deref(),
                    ctx.md5sig,
                    timestamp_delta_ticks,
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                )?
            } else {
                let bytes_committed = match send_fake_tcp_action_named(
                    ctx.writer,
                    chunk,
                    &first_fake,
                    1,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: first_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakeddisorder",
                    step_family,
                    step_fallback,
                    bytes_committed,
                ) {
                    Ok(bytes_committed) => bytes_committed,
                    Err(err) if should_ignore_android_ttl_error(err.source_error()) => {
                        log_android_desync_fallback("send_fake_fakeddisorder", "fakedsplit", &err);
                        send_fake_tcp_action_named(
                            ctx.writer,
                            chunk,
                            &first_fake,
                            fake_ttl,
                            ctx.md5sig,
                            ctx.config.network.default_ttl,
                            platform::FakeTcpOptions {
                                secondary_fake_prefix: first_secondary_fake.as_deref(),
                                timestamp_delta_ticks,
                                protect_path: ctx.config.process.protect_path.as_deref(),
                                fake_flags,
                                orig_flags: original_flags,
                                ..Default::default()
                            },
                            ctx.group.actions.ip_id_mode,
                            (
                                ctx.config.timeouts.wait_send,
                                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                            ),
                            "send_fake_fakeddisorder",
                            step_family,
                            step_fallback,
                            bytes_committed,
                        )?
                    }
                    Err(err) => return Err(err),
                };
                send_fake_tcp_action_named(
                    ctx.writer,
                    second,
                    &second_fake,
                    fake_ttl,
                    ctx.md5sig,
                    ctx.config.network.default_ttl,
                    platform::FakeTcpOptions {
                        secondary_fake_prefix: second_secondary_fake.as_deref(),
                        timestamp_delta_ticks,
                        protect_path: ctx.config.process.protect_path.as_deref(),
                        fake_flags,
                        orig_flags: original_flags,
                        ..Default::default()
                    },
                    ctx.group.actions.ip_id_mode,
                    (
                        ctx.config.timeouts.wait_send,
                        Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                    ),
                    "send_fake_fakesplit",
                    "fakedsplit",
                    None,
                    bytes_committed,
                )?
            };
            Ok((bytes_committed, TcpStepControl::BreakPlan))
        }
        _ => unreachable!("non-fake-family step dispatched to fake-family executor"),
    }
}

struct TcpHostFakeExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    plan: &'a DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    md5sig: bool,
}

fn execute_tcp_hostfake_step(
    ctx: &mut TcpHostFakeExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let Some(span) = resolve_hostfake_span(configured_step, &ctx.plan.tampered, start, end, ctx.seed) else {
        let bytes_committed = write_strategy_payload_with_optional_flags_named(
            ctx.writer,
            chunk,
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            step_original_tcp_flags(configured_step),
            ctx.group.actions.ip_id_mode,
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    };

    let mut bytes_committed = bytes_committed;
    if start < span.host_start {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[start..span.host_start],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    let real_host = &ctx.plan.tampered[span.host_start..span.host_end];
    let fake_host = build_hostfake_bytes(
        real_host,
        configured_step.fake_host_template.as_deref(),
        ctx.seed,
        configured_step.random_fake_host,
    );
    let fake_ttl = ctx.resolved_fake_ttl.or(ctx.group.actions.ttl).unwrap_or(8);
    let fake_flags = step_fake_tcp_flags(configured_step);
    let original_flags = step_original_tcp_flags(configured_step);
    let timestamp_delta_ticks =
        ctx.group.actions.fake_tcp_timestamp_enabled.then_some(ctx.group.actions.fake_tcp_timestamp_delta_ticks);
    let custom_order = configured_step.fake_seq_mode != FakeSeqMode::Duplicate
        || (span.midhost.is_some() && configured_step.fake_order != FakeOrder::BeforeEach);
    if custom_order {
        let emissions = if let Some(midhost) = span.midhost {
            let split = midhost - span.host_start;
            let first_real = &ctx.plan.tampered[span.host_start..midhost];
            let second_real = &ctx.plan.tampered[midhost..span.host_end];
            let first_fake = &fake_host[..split];
            let second_fake = &fake_host[split..];
            build_ordered_fake_split_emissions(
                configured_step.fake_order,
                first_real,
                first_fake,
                second_real,
                second_fake,
                fake_ttl,
                fake_ttl,
                fake_flags,
                original_flags,
            )
        } else {
            vec![
                FakeEmission {
                    role: FakeEmissionRole::Fake,
                    payload: &fake_host,
                    ttl: fake_ttl,
                    flags: fake_flags,
                    original_offset: 0,
                },
                FakeEmission {
                    role: FakeEmissionRole::Genuine,
                    payload: real_host,
                    ttl: fake_ttl,
                    flags: original_flags,
                    original_offset: 0,
                },
                FakeEmission {
                    role: FakeEmissionRole::Fake,
                    payload: &fake_host,
                    ttl: fake_ttl,
                    flags: fake_flags,
                    original_offset: 0,
                },
            ]
        };
        let ordered_segments = ordered_segments_from_emissions(&emissions, configured_step.fake_seq_mode);
        bytes_committed = send_ordered_fake_segments_action_named(
            ctx.writer,
            &ordered_segments,
            real_host.len(),
            ctx.config.network.default_ttl,
            ctx.config.process.protect_path.as_deref(),
            ctx.md5sig,
            timestamp_delta_ticks,
            ctx.group.actions.ip_id_mode,
            (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
            "send_fake_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        if span.host_end < end {
            bytes_committed = write_strategy_payload_named(
                ctx.writer,
                &ctx.plan.tampered[span.host_end..end],
                "write_hostfake",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            await_writable_action_named(
                ctx.writer,
                ctx.config.timeouts.wait_send,
                Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
                "await_writable_hostfake",
                step_family,
                step_fallback,
                bytes_committed,
            )?;
        }
        return Ok((bytes_committed, TcpStepControl::ContinueAt(end)));
    }

    bytes_committed = send_fake_tcp_action_named(
        ctx.writer,
        real_host,
        &fake_host,
        fake_ttl,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        platform::FakeTcpOptions {
            secondary_fake_prefix: None,
            timestamp_delta_ticks: None,
            protect_path: ctx.config.process.protect_path.as_deref(),
            fake_flags,
            orig_flags: original_flags,
            ..Default::default()
        },
        ctx.group.actions.ip_id_mode,
        (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
        "send_fake_hostfake",
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    if let Some(midhost) = span.midhost {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[span.host_start..midhost],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[midhost..span.host_end],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    } else {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            real_host,
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    bytes_committed = send_fake_tcp_action_named(
        ctx.writer,
        real_host,
        &fake_host,
        fake_ttl,
        ctx.md5sig,
        ctx.config.network.default_ttl,
        platform::FakeTcpOptions {
            secondary_fake_prefix: None,
            timestamp_delta_ticks: None,
            protect_path: ctx.config.process.protect_path.as_deref(),
            fake_flags,
            orig_flags: original_flags,
            ..Default::default()
        },
        ctx.group.actions.ip_id_mode,
        (ctx.config.timeouts.wait_send, Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64)),
        "send_fake_hostfake",
        step_family,
        step_fallback,
        bytes_committed,
    )?;

    if span.host_end < end {
        bytes_committed = write_strategy_payload_named(
            ctx.writer,
            &ctx.plan.tampered[span.host_end..end],
            "write_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        await_writable_action_named(
            ctx.writer,
            ctx.config.timeouts.wait_send,
            Duration::from_millis(ctx.config.timeouts.await_interval.max(1) as u64),
            "await_writable_hostfake",
            step_family,
            step_fallback,
            bytes_committed,
        )?;
    }

    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}

fn execute_tcp_ipfrag2_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    end: usize,
    configured_step: &TcpChainStep,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let bytes_committed = match send_ip_fragmented_tcp_action_named(
        ctx.writer,
        &ctx.plan.tampered,
        end,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        false, // disorder not available in legacy plan path
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        "write_ipfrag2",
        step_family,
        step_fallback,
        bytes_committed,
    ) {
        Ok(committed) => committed,
        Err(err) if should_fallback_ipfrag2_tcp_error_kind(err.kind()) => {
            log_ipfrag2_flow_fallback(&err);
            write_strategy_payload_with_optional_flags_named(
                ctx.writer,
                &ctx.plan.tampered,
                ctx.config.network.default_ttl,
                ctx.config.process.protect_path.as_deref(),
                false,
                step_original_tcp_flags(configured_step),
                ctx.group.actions.ip_id_mode,
                "write_ipfrag2",
                step_family,
                step_fallback,
                bytes_committed,
            )?
        }
        Err(err) => return Err(err),
    };
    Ok((bytes_committed, TcpStepControl::BreakPlan))
}

fn execute_tcp_fakerst_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    let _ = platform::send_fake_rst(
        ctx.writer,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        step_fake_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
    );
    let bytes_committed = write_strategy_payload_with_optional_flags_named(
        ctx.writer,
        chunk,
        ctx.config.network.default_ttl,
        ctx.config.process.protect_path.as_deref(),
        ctx.md5sig,
        step_original_tcp_flags(configured_step),
        ctx.group.actions.ip_id_mode,
        "write_fakerst",
        step_family,
        step_fallback,
        bytes_committed,
    )?;
    Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
}

struct TcpPlanStepExecContext<'a> {
    writer: &'a mut TcpStream,
    config: &'a RuntimeConfig,
    group: &'a DesyncGroup,
    plan: &'a DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    lowering: &'a mut TcpLoweringCapabilities,
    md5sig: bool,
    fake_packets: Option<&'a BuiltFakePackets>,
}

enum TcpPlanLoopControl {
    Continue,
    Break,
    AdvanceToStepEnd,
}

fn tcp_step_strategy_family(kind: TcpChainStepKind, strategy_family: Option<&'static str>) -> &'static str {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => strategy_family.unwrap_or("split"),
        TcpChainStepKind::SeqOverlap => strategy_family.unwrap_or("seqovl"),
        TcpChainStepKind::MultiDisorder => strategy_family.unwrap_or("multidisorder"),
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => strategy_family.unwrap_or("tlsrec"),
        _ => strategy_family.unwrap_or("unknown"),
    }
}

#[allow(clippy::too_many_arguments)]
fn execute_tcp_plan_step(
    ctx: &mut TcpPlanStepExecContext<'_>,
    kind: TcpChainStepKind,
    configured_step: &TcpChainStep,
    chunk: &[u8],
    start: usize,
    end: usize,
    step_family: &'static str,
    step_fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(usize, TcpStepControl), OutboundSendError> {
    match kind {
        TcpChainStepKind::Split | TcpChainStepKind::SynData | TcpChainStepKind::SeqOverlap | TcpChainStepKind::Oob => {
            let mut basic_stream_ctx = TcpBasicStreamExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                md5sig: ctx.md5sig,
            };
            let bytes_committed = execute_basic_tcp_stream_step(
                &mut basic_stream_ctx,
                kind,
                configured_step,
                chunk,
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::Disorder | TcpChainStepKind::Disoob => {
            let mut ttl_sensitive_ctx = TcpTtlSensitiveExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                lowering: ctx.lowering,
                md5sig: ctx.md5sig,
            };
            let bytes_committed = execute_ttl_sensitive_tcp_step(
                &mut ttl_sensitive_ctx,
                kind,
                configured_step,
                chunk,
                step_family,
                step_fallback,
                bytes_committed,
            )?;
            Ok((bytes_committed, TcpStepControl::ContinueAt(end)))
        }
        TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder => {
            let fake_packets =
                ctx.fake_packets.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing fake packet"))?;
            let mut fake_family_ctx = TcpFakeFamilyExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                plan: ctx.plan,
                fake_packets,
                resolved_fake_ttl: ctx.resolved_fake_ttl,
                lowering: ctx.lowering,
                md5sig: ctx.md5sig,
            };
            execute_tcp_fake_family_step(
                &mut fake_family_ctx,
                kind,
                configured_step,
                chunk,
                start,
                end,
                step_family,
                step_fallback,
                bytes_committed,
            )
        }
        TcpChainStepKind::IpFrag2 => {
            execute_tcp_ipfrag2_step(ctx, end, configured_step, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::HostFake => {
            let mut hostfake_ctx = TcpHostFakeExecContext {
                writer: ctx.writer,
                config: ctx.config,
                group: ctx.group,
                plan: ctx.plan,
                seed: ctx.seed,
                resolved_fake_ttl: ctx.resolved_fake_ttl,
                md5sig: ctx.md5sig,
            };
            execute_tcp_hostfake_step(
                &mut hostfake_ctx,
                configured_step,
                chunk,
                start,
                end,
                step_family,
                step_fallback,
                bytes_committed,
            )
        }
        TcpChainStepKind::FakeRst => {
            execute_tcp_fakerst_step(ctx, configured_step, chunk, end, step_family, step_fallback, bytes_committed)
        }
        TcpChainStepKind::MultiDisorder => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder must be executed as a grouped tcp plan",
        ))),
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tls prelude step must not appear in tcp send plan",
        ))),
        _ => Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "unknown tcp step kind in tcp send plan",
        ))),
    }
}

fn handle_tcp_plan_step_control(
    kind: TcpChainStepKind,
    control: TcpStepControl,
    configured_step: &TcpChainStep,
    index: usize,
    total_steps: usize,
    break_cursor: usize,
    cursor: &mut usize,
) -> TcpPlanLoopControl {
    match control {
        TcpStepControl::ContinueAt(next_cursor)
            if matches!(
                kind,
                TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder
            ) =>
        {
            *cursor = next_cursor;
            if configured_step.inter_segment_delay_ms > 0 && index + 1 < total_steps {
                // std-thread-safe: each connection runs on its own dedicated OS thread
                // (mio + std::thread, no tokio worker pool). Blocking here is correct.
                std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms.min(500))));
            }
            TcpPlanLoopControl::Continue
        }
        TcpStepControl::ContinueAt(next_cursor)
            if matches!(kind, TcpChainStepKind::HostFake | TcpChainStepKind::FakeRst) =>
        {
            *cursor = next_cursor;
            TcpPlanLoopControl::Continue
        }
        TcpStepControl::ContinueAt(_) => TcpPlanLoopControl::AdvanceToStepEnd,
        TcpStepControl::BreakPlan => {
            *cursor = break_cursor;
            TcpPlanLoopControl::Break
        }
    }
}

fn execute_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    group: &DesyncGroup,
    plan: &DesyncPlan,
    seed: u32,
    resolved_fake_ttl: Option<u8>,
    strategy_family: Option<&'static str>,
    session_ttl_unavailable: &AtomicBool,
) -> Result<usize, OutboundSendError> {
    let has_multi_disorder = plan.steps.iter().any(|step| step.kind == TcpChainStepKind::MultiDisorder);
    let fake_packets = if plan.steps.iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
    }) {
        build_tcp_fake_packets(group, &plan.tampered, seed)?
    } else {
        None
    };
    let mut lowering_caps = TcpLoweringCapabilities::snapshot(config.network.default_ttl, session_ttl_unavailable);
    let md5sig = group.actions.md5sig;
    let send_steps =
        group.effective_tcp_chain().into_iter().filter(|step| !step.kind.is_tls_prelude()).collect::<Vec<_>>();
    if has_multi_disorder {
        return execute_multi_disorder_tcp_plan(
            writer,
            config,
            &send_steps,
            plan,
            strategy_family,
            md5sig,
            group.actions.ip_id_mode,
        );
    }
    if send_steps.len() < plan.steps.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "tcp plan steps exceed configured send steps",
        )));
    }

    let mut cursor = 0usize;
    let mut bytes_committed = 0usize;
    for (index, step) in plan.steps.iter().enumerate() {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start < cursor || end < start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid tcp desync step bounds",
            )));
        }
        let chunk = &plan.tampered[start..end];
        let configured_step = &send_steps[index];
        let step_family = tcp_step_strategy_family(step.kind, strategy_family);
        let step_fallback = strategy_fallback_family(step_family);
        let mut step_ctx = TcpPlanStepExecContext {
            writer,
            config,
            group,
            plan,
            seed,
            resolved_fake_ttl,
            lowering: &mut lowering_caps,
            md5sig,
            fake_packets: fake_packets.as_ref(),
        };
        let (next_bytes_committed, control) = execute_tcp_plan_step(
            &mut step_ctx,
            step.kind,
            configured_step,
            chunk,
            start,
            end,
            step_family,
            step_fallback,
            bytes_committed,
        )?;
        bytes_committed = next_bytes_committed;
        match handle_tcp_plan_step_control(
            step.kind,
            control,
            configured_step,
            index,
            plan.steps.len(),
            plan.tampered.len(),
            &mut cursor,
        ) {
            TcpPlanLoopControl::Continue => continue,
            TcpPlanLoopControl::Break => break,
            TcpPlanLoopControl::AdvanceToStepEnd => {}
        }
        if configured_step.inter_segment_delay_ms > 0 && index + 1 < plan.steps.len() {
            // std-thread-safe: each connection runs on its own dedicated OS thread
            // (mio + std::thread, no tokio worker pool). Blocking here is correct.
            std::thread::sleep(Duration::from_millis(u64::from(configured_step.inter_segment_delay_ms.min(500))));
        }
        cursor = end;
    }

    if cursor < plan.tampered.len() {
        bytes_committed = write_strategy_payload_named(
            writer,
            &plan.tampered[cursor..],
            write_action_name(strategy_family.unwrap_or("split")),
            strategy_family.unwrap_or("split"),
            strategy_family.and_then(strategy_fallback_family),
            bytes_committed,
        )?;
    }

    // Propagate per-connection discovery to the session-level flag so
    // subsequent connections skip TTL actions immediately.
    lowering_caps.persist(session_ttl_unavailable);

    Ok(bytes_committed)
}

fn execute_multi_disorder_tcp_plan(
    writer: &mut TcpStream,
    config: &RuntimeConfig,
    send_steps: &[ripdpi_config::TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
    md5sig: bool,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> Result<usize, OutboundSendError> {
    let prepared = prepare_multi_disorder_tcp_plan(send_steps, plan, strategy_family)?;
    strategy_result(
        platform::send_multi_disorder_tcp(
            writer,
            &plan.tampered,
            &prepared.segments,
            config.network.default_ttl,
            config.process.protect_path.as_deref(),
            prepared.inter_segment_delay_ms,
            md5sig,
            prepared.original_flags,
            ip_id_mode,
        ),
        "write_multidisorder",
        prepared.strategy_family,
        prepared.fallback,
        0,
    )
    .map(|()| plan.tampered.len())
}

struct PreparedMultiDisorderTcpPlan {
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    inter_segment_delay_ms: u32,
    original_flags: platform::TcpFlagOverrides,
    segments: Vec<platform::TcpPayloadSegment>,
}

fn prepare_multi_disorder_tcp_plan(
    send_steps: &[ripdpi_config::TcpChainStep],
    plan: &DesyncPlan,
    strategy_family: Option<&'static str>,
) -> Result<PreparedMultiDisorderTcpPlan, OutboundSendError> {
    if send_steps.len() < 2 || send_steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid multidisorder tcp chain configuration",
        )));
    }
    if plan.steps.len() < 3 || plan.steps.iter().any(|step| step.kind != TcpChainStepKind::MultiDisorder) {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder requires at least three non-empty planned segments",
        )));
    }

    let mut cursor = 0usize;
    let mut segments = Vec::with_capacity(plan.steps.len());
    for step in &plan.steps {
        let start = usize::try_from(step.start).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan start"))
        })?;
        let end = usize::try_from(step.end).map_err(|_| {
            OutboundSendError::Transport(io::Error::new(io::ErrorKind::InvalidData, "negative tcp plan end"))
        })?;
        if start != cursor || end <= start || end > plan.tampered.len() {
            return Err(OutboundSendError::Transport(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid multidisorder tcp segment bounds",
            )));
        }
        segments.push(platform::TcpPayloadSegment { start, end });
        cursor = end;
    }
    if cursor != plan.tampered.len() {
        return Err(OutboundSendError::Transport(io::Error::new(
            io::ErrorKind::InvalidData,
            "multidisorder tcp plan does not cover the full payload",
        )));
    }

    let strategy_family = strategy_family.unwrap_or("multidisorder");
    let fallback = strategy_fallback_family(strategy_family);
    let inter_segment_delay_ms = send_steps.first().map_or(0, |s| s.inter_segment_delay_ms);
    let original_flags = step_original_tcp_flags(send_steps.first().expect("multidisorder send step missing"));
    Ok(PreparedMultiDisorderTcpPlan { strategy_family, fallback, inter_segment_delay_ms, original_flags, segments })
}

fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

pub(crate) fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

fn strategy_execution_error(
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
    source: io::Error,
) -> OutboundSendError {
    OutboundSendError::StrategyExecution {
        action,
        strategy_family,
        fallback,
        bytes_committed,
        source_errno: source.raw_os_error(),
        source,
    }
}

fn log_android_desync_fallback(action: &'static str, fallback: &'static str, error: &OutboundSendError) {
    tracing::warn!("Android desync fallback applied: action={action} fallback={fallback}: {error}");
}

fn write_payload_progress(stream: &mut TcpStream, bytes: &[u8]) -> Result<(), WriteProgressError> {
    let mut written = 0usize;
    while written < bytes.len() {
        match stream.write(&bytes[written..]) {
            Ok(0) => {
                return Err(WriteProgressError {
                    written,
                    source: io::Error::new(io::ErrorKind::WriteZero, "partial tcp payload write"),
                });
            }
            Ok(chunk) => {
                written += chunk;
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => {
                return Err(WriteProgressError { written, source: err });
            }
        }
    }
    Ok(())
}

fn write_transport_payload(stream: &mut TcpStream, bytes: &[u8]) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes)
        .map(|()| bytes.len())
        .map_err(|progress| OutboundSendError::Transport(progress.source))
}

fn write_strategy_payload_named(
    stream: &mut TcpStream,
    bytes: &[u8],
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    write_payload_progress(stream, bytes).map(|()| bytes_committed + bytes.len()).map_err(|progress| {
        strategy_execution_error(action, strategy_family, fallback, bytes_committed + progress.written, progress.source)
    })
}

#[allow(clippy::too_many_arguments)]
fn write_strategy_payload_with_optional_flags_named(
    stream: &mut TcpStream,
    bytes: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    if flags.is_empty() {
        write_strategy_payload_named(stream, bytes, action, strategy_family, fallback, bytes_committed)
    } else {
        send_flagged_tcp_payload_action_named(
            stream,
            bytes,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
            action,
            strategy_family,
            fallback,
            bytes_committed,
        )
    }
}

fn send_transport_oob_payload(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> Result<usize, OutboundSendError> {
    send_out_of_band(writer, prefix, urgent_byte).map(|()| prefix.len() + 1).map_err(OutboundSendError::Transport)
}

fn strategy_result<T>(
    result: io::Result<T>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<T, OutboundSendError> {
    result.map_err(|source| strategy_execution_error(action, strategy_family, fallback, bytes_committed, source))
}

fn transport_result<T>(result: io::Result<T>) -> Result<T, OutboundSendError> {
    result.map_err(OutboundSendError::Transport)
}

#[allow(clippy::too_many_arguments)]
fn write_ttl_sensitive_payload_with_optional_flags_named(
    lowering: &mut TcpLoweringCapabilities,
    writer: &mut TcpStream,
    bytes: &[u8],
    ttl_modified: bool,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(bool, usize), OutboundSendError> {
    if flags.is_empty() {
        write_payload_with_android_ttl_fallback(
            lowering,
            writer,
            bytes,
            ttl_modified,
            action,
            restore_ttl_action_name(strategy_family),
            strategy_family,
            fallback,
            bytes_committed,
        )
    } else {
        let committed = write_strategy_payload_with_optional_flags_named(
            writer,
            bytes,
            default_ttl,
            protect_path,
            md5sig,
            flags,
            ip_id_mode,
            action,
            strategy_family,
            fallback,
            bytes_committed,
        )?;
        Ok((ttl_modified, committed))
    }
}

fn send_oob_action_named(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(send_out_of_band(writer, prefix, urgent_byte), action, strategy_family, fallback, bytes_committed)
        .map(|()| bytes_committed + prefix.len() + 1)
}

#[allow(clippy::too_many_arguments)]
fn send_fake_tcp_action_named(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: platform::FakeTcpOptions<'_>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_fake_tcp(
            stream,
            original_prefix,
            fake_prefix,
            ttl,
            md5sig,
            default_ttl,
            options,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_prefix.len())
}

#[allow(clippy::too_many_arguments)]
fn send_ordered_fake_segments_action_named(
    stream: &TcpStream,
    segments: &[platform::OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: platform::TcpStageWait,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_ordered_tcp_segments(
            stream,
            segments,
            original_payload_len,
            default_ttl,
            protect_path,
            md5sig,
            timestamp_delta_ticks,
            ip_id_mode,
            wait,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + original_payload_len)
}

#[allow(clippy::too_many_arguments)]
fn send_flagged_tcp_payload_action_named(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ip_id_mode),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

#[allow(clippy::too_many_arguments)]
fn send_ip_fragmented_tcp_action_named(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: platform::TcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<usize, OutboundSendError> {
    strategy_result(
        platform::send_ip_fragmented_tcp(
            stream,
            payload,
            split_offset,
            default_ttl,
            protect_path,
            disorder,
            ipv6_ext,
            flags,
            ip_id_mode,
        ),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
    .map(|()| bytes_committed + payload.len())
}

fn set_md5sig_transport_action(stream: &TcpStream, key_len: u16) -> Result<(), OutboundSendError> {
    transport_result(platform::set_tcp_md5sig(stream, key_len))
}

fn set_md5sig_action_named(
    stream: &TcpStream,
    key_len: u16,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(platform::set_tcp_md5sig(stream, key_len), action, strategy_family, fallback, bytes_committed)
}

fn await_writable_action_named(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
    action: &'static str,
    strategy_family: &'static str,
    fallback: Option<&'static str>,
    bytes_committed: usize,
) -> Result<(), OutboundSendError> {
    strategy_result(
        platform::wait_tcp_stage(stream, wait_send, await_interval),
        action,
        strategy_family,
        fallback,
        bytes_committed,
    )
}

fn await_transport_writable_action(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
) -> Result<(), OutboundSendError> {
    transport_result(platform::wait_tcp_stage(stream, wait_send, await_interval))
}

#[cfg(test)]
mod tests;
