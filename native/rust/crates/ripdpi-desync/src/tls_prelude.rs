use crate::offset::{gen_offset, insert_boundary, random_tail_fragment_lengths, resolve_adaptive_offset};
use crate::types::{
    activation_filter_matches, ActivationContext, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
    DesyncError, ProtoInfo, TamperResult, TcpSegmentHint,
};
use ripdpi_config::{DesyncGroup, TcpChainStep, TcpChainStepKind};
use ripdpi_packets::{is_http, is_tls_client_hello, mod_http_like_c, OracleRng};

#[derive(Debug, Clone, PartialEq, Eq)]
struct TlsPreludeState {
    header: [u8; 3],
    payload: Vec<u8>,
    boundaries: Vec<usize>,
}

impl TlsPreludeState {
    fn from_record(buffer: &[u8]) -> Option<Self> {
        if !is_tls_client_hello(buffer) || buffer.len() < 5 {
            return None;
        }
        Some(Self {
            header: [buffer[0], buffer[1], buffer[2]],
            payload: buffer[5..].to_vec(),
            boundaries: vec![buffer.len() - 5],
        })
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
        Ok(output)
    }
}

fn apply_tlsrec_prelude_step(
    step: &TcpChainStep,
    state: &mut TlsPreludeState,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<bool, DesyncError> {
    let Some(record) = state.synthetic_record() else {
        return Ok(false);
    };
    let mut info = ProtoInfo::default();
    let mut lp = 0i64;
    let total = step.offset.repeats.max(1);
    let mut remaining = total;
    let mut changed = false;
    while remaining > 0 {
        let resolved = if step.offset.base.is_adaptive() {
            resolve_adaptive_offset(
                step.offset,
                &record,
                state.payload.len(),
                lp.max(0) as usize,
                &mut info,
                context,
                context.adaptive.tls_record_offset_base,
                5,
            )
        } else {
            gen_offset(step.offset, &record, record.len(), lp, &mut info, rng)
        };
        let Some(mut pos) = resolved else {
            break;
        };
        if step.offset.needs_tls_record_adjustment() {
            pos -= 5;
        }
        pos += (step.offset.skip as i64) * ((total - remaining) as i64);
        if pos < lp {
            break;
        }
        if pos < 0 || pos > state.payload.len() as i64 {
            break;
        }
        insert_boundary(&mut state.boundaries, pos as usize);
        changed = true;
        lp = pos;
        remaining -= 1;
    }
    Ok(changed)
}

fn apply_tlsrandrec_prelude_step(
    step: &TcpChainStep,
    state: &mut TlsPreludeState,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Result<bool, DesyncError> {
    let Some(record) = state.synthetic_record() else {
        return Ok(false);
    };
    let mut info = ProtoInfo::default();
    let resolved = if step.offset.base.is_adaptive() {
        resolve_adaptive_offset(
            step.offset,
            &record,
            state.payload.len(),
            0,
            &mut info,
            context,
            context.adaptive.tls_record_offset_base,
            5,
        )
    } else {
        gen_offset(step.offset, &record, record.len(), 0, &mut info, rng)
    };
    let Some(mut marker) = resolved else {
        return Ok(false);
    };
    if step.offset.needs_tls_record_adjustment() {
        marker -= 5;
    }
    if marker < 0 || marker > state.payload.len() as i64 {
        return Ok(false);
    }

    let marker = marker as usize;
    let fragment_count = step.fragment_count.max(1) as usize;
    let (min_fragment_size, max_fragment_size) = resolve_tlsrandrec_fragment_sizes(step, context, fragment_count);
    let tail_len = state.payload.len().saturating_sub(marker);
    let Some(lengths) =
        random_tail_fragment_lengths(tail_len, fragment_count, min_fragment_size, max_fragment_size, rng)
    else {
        return Ok(false);
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
        return Ok(false);
    }
    let changed = boundaries != state.boundaries;
    state.boundaries = boundaries;
    Ok(changed)
}

fn resolve_tlsrandrec_fragment_sizes(
    step: &TcpChainStep,
    context: ActivationContext,
    fragment_count: usize,
) -> (usize, usize) {
    let min_fragment_size = step.min_fragment_size.max(1) as usize;
    let max_fragment_size = step.max_fragment_size.max(min_fragment_size as i32) as usize;
    let budget = context
        .tcp_segment_hint
        .map_or((max_fragment_size * fragment_count.max(1)) as i64, TcpSegmentHint::adaptive_budget)
        .max(min_fragment_size as i64) as usize;
    match context.adaptive.tlsrandrec_profile.unwrap_or(AdaptiveTlsRandRecProfile::Balanced) {
        AdaptiveTlsRandRecProfile::Balanced => (min_fragment_size, max_fragment_size),
        AdaptiveTlsRandRecProfile::Tight => {
            let adjusted_min = min_fragment_size.clamp(1, 16);
            let adjusted_max = max_fragment_size.min((budget / fragment_count.max(1)).max(adjusted_min));
            (adjusted_min, adjusted_max.max(adjusted_min))
        }
        AdaptiveTlsRandRecProfile::Wide => {
            let adjusted_min = min_fragment_size.max(24);
            let adjusted_max = max_fragment_size.max(adjusted_min).min(budget.max(adjusted_min));
            (adjusted_min, adjusted_max.max(adjusted_min))
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
    let mut output = input.to_vec();
    let info = ProtoInfo::default();

    if group.actions.mod_http != 0 && is_http(&output) {
        let mutation = mod_http_like_c(&output, group.actions.mod_http);
        if mutation.rc == 0 {
            output = mutation.bytes;
        }
    }
    if let Some(tlsminor) = group.actions.tlsminor {
        if is_tls_client_hello(&output) && output.len() > 2 {
            output[2] = tlsminor;
        }
    }
    if !prelude_steps.is_empty() {
        let mut rng = OracleRng::seeded(seed);
        if let Some(mut state) = TlsPreludeState::from_record(&output) {
            let mut changed = false;
            for step in prelude_steps {
                if !activation_filter_matches(step.activation_filter, context) {
                    continue;
                }
                match step.kind {
                    TcpChainStepKind::TlsRec => {
                        changed |= apply_tlsrec_prelude_step(step, &mut state, &mut rng, context)?;
                    }
                    TcpChainStepKind::TlsRandRec => {
                        changed |= apply_tlsrandrec_prelude_step(step, &mut state, &mut rng, context)?;
                    }
                    _ => return Err(DesyncError),
                }
            }
            if changed {
                output = state.serialize()?;
            }
        }
    }

    Ok(TamperResult { bytes: output, proto: info })
}

pub fn apply_tamper(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<TamperResult, DesyncError> {
    let prelude_steps =
        group.effective_tcp_chain().into_iter().take_while(|step| step.kind.is_tls_prelude()).collect::<Vec<_>>();
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
            transport: ActivationTransport::Tcp,
            tcp_segment_hint: None,
            resolved_fake_ttl: None,
            adaptive: AdaptivePlannerHints::default(),
        },
    )
}
