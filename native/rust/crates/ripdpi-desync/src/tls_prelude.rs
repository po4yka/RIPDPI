use crate::offset::{gen_offset, insert_boundary, random_tail_fragment_lengths, resolve_adaptive_offset};
use crate::proto::init_proto_info;
use crate::types::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
    DesyncError, ProtoInfo, TamperResult, TcpSegmentHint, TlsPreludeApplication, activation_filter_matches,
};
use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind, TcpTlsRandRecPayload};
use ripdpi_packets::{
    IS_HTTP, OracleRng, is_complete_tls_client_hello, is_tls_client_hello, mod_http_inplace,
    parse_tls_client_hello_layout,
};
use ripdpi_tls_profiles::{TlsTemplateFirstFlightPlan, apply_record_choreography, plan_first_flight};

const MAX_TLSREC_ITERATIONS: i32 = 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
struct TlsPreludeState {
    header: [u8; 3],
    payload: Vec<u8>,
    boundaries: Vec<usize>,
    /// Wire bytes after the last record that contains the ClientHello,
    /// passed through unchanged after the re-framed records.
    tail: Vec<u8>,
}

impl TlsPreludeState {
    /// `require_complete` is true for the real stream payload, where more bytes
    /// of the record may follow; a standalone fake has nothing after it.
    fn from_record(buffer: &[u8], require_complete: bool) -> Option<Self> {
        let layout = parse_tls_client_hello_layout(buffer)?;
        let header = [*buffer.first()?, *buffer.get(1)?, *buffer.get(2)?];
        if header[..2] != [0x16, 0x03] {
            return None;
        }
        let handshake_end = layout.handshake_payload_len.checked_add(4)?;
        let mut payload = Vec::new();
        let mut boundaries = Vec::new();
        let mut tail_start = 0;
        for span in &layout.record_payload_spans {
            let length = buffer.get(span.start.checked_sub(2)?..span.start)?;
            let declared = usize::from(u16::from_be_bytes([length[0], length[1]]));
            if declared == 0 || (require_complete && (span.len() != declared || declared > 16_384)) {
                return None;
            }
            payload.extend_from_slice(buffer.get(span.clone())?);
            u16::try_from(payload.len()).ok()?;
            boundaries.push(payload.len());
            tail_start = span.end;
            if payload.len() >= handshake_end {
                break;
            }
        }
        // Single-record templates retain the parser's existing tolerance for a cut handshake.
        if payload.is_empty() || (require_complete && boundaries.len() > 1 && payload.len() < handshake_end) {
            return None;
        }
        let tail = buffer.get(tail_start..)?.to_vec();

        Some(Self { header, payload, boundaries, tail })
    }

    fn synthetic_record(&self) -> Option<Vec<u8>> {
        let payload_len = u16::try_from(self.payload.len()).ok()?;
        let mut record = Vec::with_capacity(self.payload.len() + 5);
        record.extend_from_slice(&self.header);
        record.extend_from_slice(&payload_len.to_be_bytes());
        record.extend_from_slice(&self.payload);
        Some(record)
    }

    fn serialize(&self) -> Result<Vec<u8>, DesyncError> {
        let mut output = Vec::with_capacity(self.payload.len() + self.boundaries.len() * 5);
        let mut start = 0usize;
        for &end in &self.boundaries {
            if end < start || end > self.payload.len() {
                return Err(DesyncError);
            }
            let len = end - start;
            let len = u16::try_from(len).map_err(|_| DesyncError)?;
            output.extend_from_slice(&self.header);
            output.extend_from_slice(&len.to_be_bytes());
            output.extend_from_slice(&self.payload[start..end]);
            start = end;
        }
        if start != self.payload.len() {
            return Err(DesyncError);
        }
        output.extend_from_slice(&self.tail);
        Ok(output)
    }
}

fn apply_tlsrec_prelude_step(
    step: &TcpChainStep,
    state: &mut TlsPreludeState,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<Option<usize>, DesyncError> {
    let offset = step.offset();
    let total = offset.repeats.max(1);
    // Bound work even when repeated offsets add no record boundary.
    if total > MAX_TLSREC_ITERATIONS {
        return Err(DesyncError);
    }
    let Some(record) = state.synthetic_record() else {
        return Ok(None);
    };
    let mut info = ProtoInfo::default();
    let mut lp = 0i64;
    let mut remaining = total;
    let mut resolved_offset = None;
    while remaining > 0 {
        let resolved = if offset.base.is_adaptive() {
            resolve_adaptive_offset(
                offset,
                &record,
                state.payload.len(),
                lp.max(0) as usize,
                &mut info,
                context,
                context.adaptive.tls_record_offset_base,
                5,
            )
        } else {
            gen_offset(offset, &record, record.len(), lp, &mut info, rng)
        };
        let Some(mut pos) = resolved else {
            break;
        };
        if !offset.base.is_adaptive() && offset.needs_tls_record_adjustment() {
            pos = pos.checked_sub(5).ok_or(DesyncError)?;
        }
        pos = pos.checked_add(i64::from(offset.skip) * i64::from(total - remaining)).ok_or(DesyncError)?;
        if pos < lp {
            break;
        }
        if pos < 0 || pos > state.payload.len() as i64 {
            break;
        }
        let pos = pos as usize;
        // A boundary at 0 would emit a zero-length handshake record (RFC 8446 5.1).
        if pos == 0 {
            remaining -= 1;
            continue;
        }
        let previous_count = state.boundaries.len();
        insert_boundary(&mut state.boundaries, pos);
        if state.boundaries.len() > previous_count {
            resolved_offset.get_or_insert(pos);
        }
        lp = pos as i64;
        remaining -= 1;
    }
    Ok(resolved_offset)
}

fn apply_tlsrandrec_prelude_step(
    step: &TcpChainStep,
    state: &mut TlsPreludeState,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<Option<usize>, DesyncError> {
    let Some(record) = state.synthetic_record() else {
        return Ok(None);
    };
    let offset = step.offset();
    let mut info = ProtoInfo::default();
    let resolved = if offset.base.is_adaptive() {
        resolve_adaptive_offset(
            offset,
            &record,
            state.payload.len(),
            0,
            &mut info,
            context,
            context.adaptive.tls_record_offset_base,
            5,
        )
    } else {
        gen_offset(offset, &record, record.len(), 0, &mut info, rng)
    };
    let Some(mut marker) = resolved else {
        return Ok(None);
    };
    if !offset.base.is_adaptive() && offset.needs_tls_record_adjustment() {
        marker = marker.checked_sub(5).ok_or(DesyncError)?;
    }
    if marker < 0 || marker > state.payload.len() as i64 {
        return Ok(None);
    }

    let marker = marker as usize;
    let payload = step.tls_randrec_payload().ok_or(DesyncError)?;
    let fragment_count = payload.fragment_count.max(1) as usize;
    let (min_fragment_size, max_fragment_size) = resolve_tlsrandrec_fragment_sizes(step, context, fragment_count);
    let tail_len = state.payload.len().saturating_sub(marker);
    let Some(lengths) =
        random_tail_fragment_lengths(tail_len, fragment_count, min_fragment_size, max_fragment_size, rng)
    else {
        return Ok(None);
    };

    let mut boundaries = Vec::with_capacity(lengths.len() + usize::from(marker > 0));
    if marker > 0 {
        boundaries.push(marker);
    }
    let mut cursor = marker;
    for len in lengths {
        cursor += len;
        boundaries.push(cursor);
    }
    if boundaries.last().copied() != Some(state.payload.len()) {
        return Ok(None);
    }
    let changed = boundaries != state.boundaries;
    state.boundaries = boundaries;
    Ok(changed.then_some(marker))
}

fn resolve_tlsrandrec_fragment_sizes(
    step: &TcpChainStep,
    context: ActivationContext,
    fragment_count: usize,
) -> (usize, usize) {
    let payload = step.tls_randrec_payload().unwrap_or(TcpTlsRandRecPayload {
        fragment_count: fragment_count as i32,
        min_fragment_size: 1,
        max_fragment_size: 1,
    });
    let min_fragment_size = payload.min_fragment_size.max(1) as usize;
    let max_fragment_size = payload.max_fragment_size.max(min_fragment_size as i32) as usize;
    let budget = context
        .tcp_segment_hint
        .map_or(max_fragment_size.saturating_mul(fragment_count.max(1)) as i64, TcpSegmentHint::adaptive_budget)
        .max(min_fragment_size as i64) as usize;
    match context.adaptive.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Balanced => (min_fragment_size, max_fragment_size),
        AdaptiveTlsRandRecProfile::Tight => {
            let adjusted_max = max_fragment_size.min((budget / fragment_count.max(1)).max(min_fragment_size));
            (min_fragment_size, adjusted_max)
        }
        AdaptiveTlsRandRecProfile::Wide => {
            let adjusted_min = min_fragment_size.max(24).min(max_fragment_size);
            let adjusted_max = max_fragment_size.min(budget.max(adjusted_min));
            (adjusted_min, adjusted_max)
        }
    }
}

pub(crate) fn apply_tls_prelude_steps(
    group: &DesyncGroup,
    prelude_steps: &[TcpChainStep],
    input: &[u8],
    seed: u32,
    context: ActivationContext,
) -> Result<TamperResult, DesyncError> {
    apply_prelude(group, prelude_steps, input, seed, context, true)
}

/// Like `apply_tls_prelude_steps` for a standalone fake packet, which may carry
/// a truncated record because nothing follows it.
pub(crate) fn apply_tls_prelude_steps_to_fake(
    group: &DesyncGroup,
    prelude_steps: &[TcpChainStep],
    fake: &[u8],
    seed: u32,
    context: ActivationContext,
) -> Result<TamperResult, DesyncError> {
    apply_prelude(group, prelude_steps, fake, seed, context, false)
}

fn apply_prelude(
    group: &DesyncGroup,
    prelude_steps: &[TcpChainStep],
    input: &[u8],
    seed: u32,
    context: ActivationContext,
    require_complete: bool,
) -> Result<TamperResult, DesyncError> {
    let mut output = input.to_vec();
    let mut info = ProtoInfo::default();
    init_proto_info(&output, &mut info);

    let mut http_mutated = (group.actions.mod_http != 0).then_some(false);
    if group.actions.mod_http != 0
        && info.kind == IS_HTTP
        && context.stream_start == 0
        && ripdpi_packets::http_request_head_end(&output).is_some()
    {
        http_mutated = Some(mod_http_inplace(&mut output, group.actions.mod_http) == 0);
        info = ProtoInfo::default();
        init_proto_info(&output, &mut info);
    }
    // Keep the original record layout when the override changes only the first header.
    let prelude_state =
        if prelude_steps.is_empty() { None } else { TlsPreludeState::from_record(&output, require_complete) };
    if let Some(tlsminor) = group.actions.tlsminor
        && (info.tls.is_some() || is_complete_tls_client_hello(&output))
        && is_tls_client_hello(&output)
        && output.len() > 2
    {
        output[2] = tlsminor;
        info = ProtoInfo::default();
        init_proto_info(&output, &mut info);
    }
    let mut applied_steps = Vec::new();
    if !prelude_steps.is_empty() {
        let mut rng = OracleRng::seeded(seed);
        if let Some(mut state) = prelude_state {
            if let Some(tlsminor) = group.actions.tlsminor {
                state.header[2] = tlsminor;
            }
            for step in prelude_steps {
                if !activation_filter_matches(step.activation_filter(), context) {
                    continue;
                }
                let resolved_offset = match step.kind() {
                    TcpChainStepKind::TlsRec => apply_tlsrec_prelude_step(step, &mut state, &mut rng, context)?,
                    TcpChainStepKind::TlsRandRec => apply_tlsrandrec_prelude_step(step, &mut state, &mut rng, context)?,
                    _ => return Err(DesyncError),
                };
                if let Some(resolved_offset) = resolved_offset {
                    applied_steps.push((step, resolved_offset));
                }
            }
            if !applied_steps.is_empty() {
                output = state.serialize()?;
                info = ProtoInfo::default();
                init_proto_info(&output, &mut info);
            }
        }
    }

    let exact = (applied_steps.len() == 1).then(|| applied_steps[0]);
    Ok(TamperResult {
        http_mutated,
        bytes: output,
        proto: info,
        tls_prelude: TlsPreludeApplication {
            configured_count: prelude_steps.len(),
            applied_count: applied_steps.len(),
            kind: exact.map(|(step, _)| step.kind()),
            marker_base: exact.map(|(step, _)| step.offset().base),
            marker_delta: exact.map(|(step, _)| step.offset().delta.clamp(i16::MIN as i64, i16::MAX as i64) as i16),
            resolved_offset: exact.map(|(_, resolved_offset)| resolved_offset),
        },
    })
}

pub fn apply_tamper(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<TamperResult, DesyncError> {
    let prelude_steps =
        group.effective_tcp_chain().into_iter().take_while(|step| step.kind().is_tls_prelude()).collect::<Vec<_>>();
    apply_tls_prelude_steps(
        group,
        &prelude_steps,
        input,
        seed,
        ActivationContext {
            round: 1,
            payload_size: input.len() as i64,
            stream_start: 0,
            stream_end: input.len().saturating_sub(1) as i64,
            seqovl_supported: false,
            transport: ActivationTransport::Tcp,
            tcp_segment_hint: None,
            tcp_state: ActivationTcpState {
                has_ech: ripdpi_packets::is_tls_client_hello(input)
                    .then_some(input)
                    .and_then(parse_tls_client_hello_layout)
                    .map(|layout| layout.markers.ech_ext_start.is_some()),
                ..ActivationTcpState::default()
            },
            resolved_fake_ttl: None,
            adaptive: AdaptivePlannerHints::default(),
        },
    )
}

pub fn apply_tls_template_record_choreography(profile: &str, input: &[u8]) -> Result<TamperResult, DesyncError> {
    let _ = plan_first_flight(profile, input).ok_or(DesyncError)?;
    let output = apply_record_choreography(profile, input).ok_or(DesyncError)?;
    let mut info = ProtoInfo::default();
    init_proto_info(&output, &mut info);
    Ok(TamperResult { http_mutated: None, bytes: output, proto: info, tls_prelude: TlsPreludeApplication::default() })
}

pub fn plan_tls_template_first_flight(profile: &str, input: &[u8]) -> Result<TlsTemplateFirstFlightPlan, DesyncError> {
    plan_first_flight(profile, input).ok_or(DesyncError)
}
