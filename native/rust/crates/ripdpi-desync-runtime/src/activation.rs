use std::borrow::Cow;

use ripdpi_config::{DesyncGroup, EntropyMode};
use ripdpi_desync::{ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints};
use ripdpi_packets::{entropy, tls_marker_info};
use ripdpi_session::OutboundProgress;

use crate::platform;

pub fn activation_context_from_progress(
    progress: OutboundProgress,
    transport: ActivationTransport,
    payload: Option<&[u8]>,
    tcp_segment_hint: Option<ripdpi_desync::TcpSegmentHint>,
    tcp_activation_state: Option<platform::TcpActivationState>,
    resolved_fake_ttl: Option<u8>,
    adaptive: AdaptivePlannerHints,
) -> ActivationContext {
    let has_ech = payload.and_then(tls_marker_info).and_then(|markers| markers.ech_ext_start).is_some();
    let tcp_state = tcp_activation_state.map_or(
        ActivationTcpState { has_ech: Some(has_ech), ..ActivationTcpState::default() },
        |state| ActivationTcpState {
            has_timestamp: state.has_timestamp,
            has_ech: Some(has_ech),
            window_size: state.window_size,
            mss: state.mss.or_else(|| tcp_segment_hint.and_then(|hint| hint.snd_mss.or(hint.advmss))),
        },
    );
    ActivationContext {
        round: progress.round as i64,
        payload_size: progress.payload_size as i64,
        stream_start: progress.stream_start as i64,
        stream_end: progress.stream_end as i64,
        seqovl_supported: false,
        transport,
        tcp_segment_hint,
        tcp_state,
        resolved_fake_ttl,
        adaptive,
    }
}

/// Prepend entropy-aware padding to the payload if the group's entropy
/// mode is enabled. An adaptive override (from strategy evolution) takes
/// precedence over the group's configured mode. Returns `Cow::Borrowed`
/// (zero allocation) when no padding is needed.
pub(crate) fn apply_entropy_padding<'a>(
    group: &DesyncGroup,
    payload: &'a [u8],
    adaptive_override: Option<EntropyMode>,
) -> Cow<'a, [u8]> {
    let actions = &group.actions;
    let max_pad = actions.entropy_padding_max as usize;
    let mode = adaptive_override.unwrap_or(actions.entropy_mode);

    let padding = match mode {
        EntropyMode::Disabled => return Cow::Borrowed(payload),
        EntropyMode::Popcount => {
            let target = match actions.entropy_padding_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => entropy::POPCOUNT_EXEMPT_LOW,
            };
            entropy::generate_entropy_padding(payload, target, max_pad)
        }
        EntropyMode::Shannon => {
            let target = match actions.shannon_entropy_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => 7.92,
            };
            entropy::generate_shannon_padding(payload, target, max_pad)
        }
        EntropyMode::Combined => {
            let pc_target = match actions.entropy_padding_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => entropy::POPCOUNT_EXEMPT_LOW,
            };
            let sh_target = match actions.shannon_entropy_target_permil {
                Some(permil) => permil as f32 / 1000.0,
                None => 7.92,
            };
            entropy::generate_combined_padding(payload, pc_target, sh_target, max_pad)
        }
        _ => return Cow::Borrowed(payload),
    };

    if padding.is_empty() {
        Cow::Borrowed(payload)
    } else {
        let mut padded = padding;
        padded.extend_from_slice(payload);
        Cow::Owned(padded)
    }
}
