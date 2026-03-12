#![forbid(unsafe_code)]

use ciadpi_config::{
    ActivationFilter, DesyncGroup, NumericRange, OffsetBase, OffsetExpr, OffsetProto, QuicFakeProfile, TcpChainStep,
    TcpChainStepKind, UdpChainStepKind,
    FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
};
use ciadpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat,
    change_tls_sni_seeded_like_c, duplicate_tls_session_id_like_c, http_marker_info, is_http, is_tls_client_hello,
    mod_http_like_c, padencap_tls_like_c, parse_quic_initial, randomize_tls_seeded_like_c,
    randomize_tls_sni_seeded_like_c, second_level_domain_span, tls_marker_info, tune_tls_padding_size_like_c,
    udp_fake_profile_bytes, HttpMarkerInfo, OracleRng, TlsMarkerInfo, IS_HTTP, IS_HTTPS, http_fake_profile_bytes,
    tls_fake_profile_bytes,
};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ProtoInfo {
    pub kind: u32,
    http: Option<HttpMarkerInfo>,
    tls: Option<TlsMarkerInfo>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActivationTransport {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpSegmentHint {
    pub snd_mss: Option<i64>,
    pub advmss: Option<i64>,
    pub pmtu: Option<i64>,
    pub ip_header_overhead: i64,
}

impl TcpSegmentHint {
    pub fn adaptive_budget(self) -> i64 {
        if self.snd_mss.is_some_and(|value| value >= 64) {
            return self.snd_mss.unwrap_or(1448);
        }
        if self.advmss.is_some_and(|value| value >= 64) {
            return self.advmss.unwrap_or(1448);
        }
        if let Some(value) = self.pmtu {
            let adjusted = value.saturating_sub(self.ip_header_overhead);
            if adjusted > 0 {
                return adjusted;
            }
        }
        1448
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ActivationContext {
    pub round: i64,
    pub payload_size: i64,
    pub stream_start: i64,
    pub stream_end: i64,
    pub transport: ActivationTransport,
    pub tcp_segment_hint: Option<TcpSegmentHint>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedStep {
    pub kind: TcpChainStepKind,
    pub start: i64,
    pub end: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HostFakeSpan {
    pub host_start: usize,
    pub host_end: usize,
    pub midhost: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesyncAction {
    Write(Vec<u8>),
    WriteUrgent { prefix: Vec<u8>, urgent_byte: u8 },
    SetTtl(u8),
    RestoreDefaultTtl,
    SetMd5Sig { key_len: u16 },
    AttachDropSack,
    DetachDropSack,
    AwaitWritable,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TamperResult {
    pub bytes: Vec<u8>,
    pub proto: ProtoInfo,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FakePacketPlan {
    pub bytes: Vec<u8>,
    pub fake_offset: usize,
    pub proto: ProtoInfo,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncPlan {
    pub tampered: Vec<u8>,
    pub steps: Vec<PlannedStep>,
    pub proto: ProtoInfo,
    pub actions: Vec<DesyncAction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncError;

#[derive(Debug, Clone, PartialEq, Eq)]
struct TlsPreludeState {
    header: [u8; 3],
    payload: Vec<u8>,
    boundaries: Vec<usize>,
}

fn range_contains(range: NumericRange<i64>, value: i64) -> bool {
    value >= range.start && value <= range.end
}

fn range_overlaps(range: NumericRange<i64>, start: i64, end: i64) -> bool {
    range.end >= start && range.start <= end
}

pub fn activation_filter_matches(filter: Option<ActivationFilter>, context: ActivationContext) -> bool {
    let Some(filter) = filter else {
        return true;
    };
    filter.round.is_none_or(|range| range_contains(range, context.round))
        && filter.payload_size.is_none_or(|range| range_contains(range, context.payload_size))
        && filter.stream_bytes.is_none_or(|range| range_overlaps(range, context.stream_start, context.stream_end))
}

fn init_proto_info(buffer: &[u8], info: &mut ProtoInfo) {
    if info.http.is_some() || info.tls.is_some() {
        return;
    }
    if let Some(tls) = tls_marker_info(buffer) {
        info.kind = IS_HTTPS;
        info.tls = Some(tls);
    } else if let Some(http) = http_marker_info(buffer) {
        if info.kind == 0 {
            info.kind = IS_HTTP;
        }
        info.http = Some(http);
    }
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

fn resolve_host_range<'a>(
    buffer: &'a [u8],
    info: &mut ProtoInfo,
    proto: OffsetProto,
) -> Option<(usize, usize, &'a [u8])> {
    init_proto_info(buffer, info);
    match proto {
        OffsetProto::TlsOnly => {
            let tls = info.tls?;
            Some((tls.host_start, tls.host_end, &buffer[tls.host_start..tls.host_end]))
        }
        OffsetProto::Any => {
            if let Some(tls) = info.tls {
                Some((tls.host_start, tls.host_end, &buffer[tls.host_start..tls.host_end]))
            } else {
                let http = info.http?;
                Some((http.host_start, http.host_end, &buffer[http.host_start..http.host_end]))
            }
        }
    }
}

pub fn resolve_hostfake_span(
    step: &TcpChainStep,
    buffer: &[u8],
    step_start: usize,
    step_end: usize,
    seed: u32,
) -> Option<HostFakeSpan> {
    let mut info = ProtoInfo::default();
    let (host_start, host_end, _) = resolve_host_range(buffer, &mut info, OffsetProto::Any)?;
    if host_start < step_start || host_end > step_end || host_start >= host_end {
        return None;
    }

    let midhost = step.midhost_offset.and_then(|expr| {
        let mut rng = OracleRng::seeded(seed);
        let value = gen_offset(expr, buffer, buffer.len(), 0, &mut info, &mut rng)?;
        let mid = usize::try_from(value).ok()?;
        ((host_start + 1)..host_end).contains(&mid).then_some(mid)
    });

    Some(HostFakeSpan { host_start, host_end, midhost })
}

fn fill_random_lower(byte: &mut u8, rng: &mut OracleRng) {
    *byte = b'a' + (rng.next_u8() % 26);
}

fn fill_random_alnum(byte: &mut u8, rng: &mut OracleRng) {
    let roll = rng.next_mod(36);
    *byte = if roll < 10 { b'0' + roll as u8 } else { b'a' + (roll as u8 - 10) };
}

fn fill_random_host_like(output: &mut [u8], rng: &mut OracleRng) {
    const RANDOM_TLDS: [&[u8; 3]; 8] = [b"com", b"net", b"org", b"edu", b"gov", b"mil", b"int", b"biz"];
    if output.is_empty() {
        return;
    }
    fill_random_lower(&mut output[0], rng);
    if output.len() >= 7 {
        let len = output.len();
        for byte in &mut output[1..len - 4] {
            fill_random_alnum(byte, rng);
        }
        output[len - 4] = b'.';
        output[len - 3..].copy_from_slice(RANDOM_TLDS[rng.next_mod(RANDOM_TLDS.len())]);
    } else {
        for byte in &mut output[1..] {
            fill_random_alnum(byte, rng);
        }
    }
}

fn looks_like_ip_literal(value: &str) -> bool {
    value.parse::<std::net::IpAddr>().is_ok()
}

fn normalize_fake_host_template(value: &str) -> Option<String> {
    let trimmed = value.trim().trim_end_matches('.').to_ascii_lowercase();
    if trimmed.is_empty() || trimmed.contains(':') || looks_like_ip_literal(&trimmed) {
        return None;
    }
    if trimmed.starts_with('.') || trimmed.ends_with('.') || trimmed.contains("..") {
        return None;
    }
    if trimmed
        .bytes()
        .any(|byte| !(byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-' || byte == b'.'))
    {
        return None;
    }
    if trimmed.split('.').any(|label| label.is_empty() || label.starts_with('-') || label.ends_with('-')) {
        return None;
    }
    Some(trimmed)
}

pub fn build_hostfake_bytes(real_host: &[u8], template: Option<&str>, seed: u32) -> Vec<u8> {
    let mut rng = OracleRng::seeded(seed);
    let mut output = vec![0; real_host.len()];
    fill_random_host_like(&mut output, &mut rng);

    let Some(template) = template.and_then(normalize_fake_host_template) else {
        return output;
    };
    let suffix = template.as_bytes();
    if suffix.len() >= output.len() {
        let output_len = output.len();
        output.copy_from_slice(&suffix[suffix.len() - output_len..]);
        return output;
    }

    let anchor = output.len() - suffix.len();
    output[anchor..].copy_from_slice(suffix);
    if anchor > 1 {
        output[anchor - 1] = b'.';
        fill_random_lower(&mut output[0], &mut rng);
        for byte in &mut output[1..anchor - 1] {
            fill_random_alnum(byte, &mut rng);
        }
    }
    output
}

fn push_split_actions(actions: &mut Vec<DesyncAction>, bytes: Vec<u8>) {
    actions.push(DesyncAction::Write(bytes));
    actions.push(DesyncAction::AwaitWritable);
}

fn push_fake_actions(actions: &mut Vec<DesyncAction>, original: &[u8], fake: Vec<u8>, group: &DesyncGroup, default_ttl: u8) {
    actions.push(DesyncAction::SetTtl(group.ttl.unwrap_or(8)));
    if group.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 5 });
    }
    if !original.is_empty() {
        actions.push(DesyncAction::Write(fake));
    }
    if group.md5sig {
        actions.push(DesyncAction::SetMd5Sig { key_len: 0 });
    }
    actions.push(DesyncAction::RestoreDefaultTtl);
    if default_ttl != 0 {
        actions.push(DesyncAction::SetTtl(default_ttl));
    }
}

fn gen_offset(
    expr: OffsetExpr,
    buffer: &[u8],
    n: usize,
    lp: i64,
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
) -> Option<i64> {
    let pos = match expr.base {
        OffsetBase::Abs => {
            if expr.delta < 0 {
                n as i64 + expr.delta
            } else {
                expr.delta
            }
        }
        OffsetBase::PayloadEnd => n as i64 + expr.delta,
        OffsetBase::PayloadMid => (n / 2) as i64 + expr.delta,
        OffsetBase::PayloadRand => {
            let available = n.saturating_sub(lp.max(0) as usize);
            expr.delta + lp + rng.next_mod(available.max(1)) as i64
        }
        OffsetBase::Host => {
            let (host_start, _, _) = resolve_host_range(buffer, info, expr.proto)?;
            host_start as i64 + expr.delta
        }
        OffsetBase::EndHost => {
            let (_, host_end, _) = resolve_host_range(buffer, info, expr.proto)?;
            host_end as i64 + expr.delta
        }
        OffsetBase::HostMid => {
            let (host_start, host_end, _) = resolve_host_range(buffer, info, expr.proto)?;
            host_start as i64 + ((host_end - host_start) / 2) as i64 + expr.delta
        }
        OffsetBase::HostRand => {
            let (host_start, host_end, _) = resolve_host_range(buffer, info, expr.proto)?;
            let host_len = host_end.saturating_sub(host_start);
            host_start as i64 + rng.next_mod(host_len.max(1)) as i64 + expr.delta
        }
        OffsetBase::Sld | OffsetBase::MidSld | OffsetBase::EndSld => {
            let (host_start, _, host) = resolve_host_range(buffer, info, expr.proto)?;
            let (sld_start, sld_end) = second_level_domain_span(host)?;
            let anchor = match expr.base {
                OffsetBase::Sld => sld_start,
                OffsetBase::MidSld => sld_start + ((sld_end - sld_start) / 2),
                OffsetBase::EndSld => sld_end,
                _ => unreachable!(),
            };
            (host_start + anchor) as i64 + expr.delta
        }
        OffsetBase::Method => {
            init_proto_info(buffer, info);
            info.http.map(|http| http.method_start as i64 + expr.delta)?
        }
        OffsetBase::ExtLen => {
            init_proto_info(buffer, info);
            info.tls.map(|tls| tls.ext_len_start as i64 + expr.delta)?
        }
        OffsetBase::SniExt => {
            init_proto_info(buffer, info);
            info.tls.map(|tls| tls.sni_ext_start as i64 + expr.delta)?
        }
        OffsetBase::AutoBalanced
        | OffsetBase::AutoHost
        | OffsetBase::AutoMidSld
        | OffsetBase::AutoEndHost
        | OffsetBase::AutoMethod
        | OffsetBase::AutoSniExt
        | OffsetBase::AutoExtLen => return None,
    };
    Some(pos)
}

fn adaptive_candidate_bases(expr: OffsetExpr, info: &mut ProtoInfo, buffer: &[u8]) -> &'static [OffsetBase] {
    init_proto_info(buffer, info);
    match expr.base {
        OffsetBase::AutoBalanced if info.tls.is_some() => {
            &[OffsetBase::ExtLen, OffsetBase::SniExt, OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost]
        }
        OffsetBase::AutoBalanced => {
            &[OffsetBase::Method, OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost]
        }
        OffsetBase::AutoHost => &[OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost],
        OffsetBase::AutoMidSld => &[OffsetBase::MidSld, OffsetBase::Host, OffsetBase::EndHost],
        OffsetBase::AutoEndHost => &[OffsetBase::EndHost, OffsetBase::MidSld, OffsetBase::Host],
        OffsetBase::AutoMethod => &[OffsetBase::Method, OffsetBase::Host],
        OffsetBase::AutoSniExt => &[OffsetBase::SniExt, OffsetBase::ExtLen, OffsetBase::Host],
        OffsetBase::AutoExtLen => &[OffsetBase::ExtLen, OffsetBase::SniExt, OffsetBase::Host],
        _ => &[],
    }
}

fn adaptive_target_end(payload_len: usize, cursor_start: usize, context: ActivationContext) -> Option<i64> {
    let remaining = payload_len.saturating_sub(cursor_start);
    if remaining <= 1 {
        return None;
    }
    let budget = context.tcp_segment_hint.map(TcpSegmentHint::adaptive_budget).unwrap_or(1448);
    let target_end = if budget < (remaining as i64 - 1) {
        cursor_start as i64 + budget
    } else {
        cursor_start as i64 + ((remaining / 2).max(1) as i64)
    };
    Some(target_end.min(payload_len.saturating_sub(1) as i64))
}

fn resolve_adaptive_offset(
    expr: OffsetExpr,
    buffer: &[u8],
    payload_len: usize,
    cursor_start: usize,
    info: &mut ProtoInfo,
    context: ActivationContext,
    coordinate_adjustment: i64,
) -> Option<i64> {
    let target_end = adaptive_target_end(payload_len, cursor_start, context)?;
    let mut below_or_equal = None;
    let mut above = None;

    for base in adaptive_candidate_bases(expr, info, buffer) {
        let candidate_expr = OffsetExpr::marker(*base, 0);
        let Some(candidate) = gen_offset(
            candidate_expr,
            buffer,
            buffer.len(),
            cursor_start as i64,
            info,
            &mut OracleRng::seeded(0),
        ) else {
            continue;
        };
        let candidate = candidate.saturating_sub(coordinate_adjustment);
        if candidate <= cursor_start as i64 || candidate >= payload_len as i64 {
            continue;
        }
        if candidate <= target_end {
            if below_or_equal.is_none_or(|current| candidate > current) {
                below_or_equal = Some(candidate);
            }
        } else if above.is_none_or(|current| candidate < current) {
            above = Some(candidate);
        }
    }

    below_or_equal.or(above).or(Some(target_end))
}

fn resolve_offset(
    expr: OffsetExpr,
    buffer: &[u8],
    n: usize,
    lp: i64,
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
    context: ActivationContext,
) -> Option<i64> {
    if expr.base.is_adaptive() {
        return resolve_adaptive_offset(expr, buffer, n, lp.max(0) as usize, info, context, 0);
    }
    gen_offset(expr, buffer, n, lp, info, rng)
}

fn insert_boundary(boundaries: &mut Vec<usize>, pos: usize) {
    let idx = boundaries.iter().position(|&end| end > pos).unwrap_or(boundaries.len());
    boundaries.insert(idx, pos);
}

fn random_tail_fragment_lengths(
    total_len: usize,
    count: usize,
    min_size: usize,
    max_size: usize,
    rng: &mut OracleRng,
) -> Option<Vec<usize>> {
    if count == 0 || min_size == 0 || min_size > max_size {
        return None;
    }
    if total_len < count.saturating_mul(min_size) || total_len > count.saturating_mul(max_size) {
        return None;
    }

    let mut remaining_len = total_len;
    let mut remaining_count = count;
    let mut lengths = Vec::with_capacity(count);
    while remaining_count > 0 {
        if remaining_count == 1 {
            lengths.push(remaining_len);
            break;
        }
        let min_here = min_size.max(remaining_len.saturating_sub(max_size * (remaining_count - 1)));
        let max_here = max_size.min(remaining_len.saturating_sub(min_size * (remaining_count - 1)));
        if min_here > max_here {
            return None;
        }
        let span = max_here - min_here + 1;
        let next = min_here + rng.next_mod(span.max(1));
        lengths.push(next);
        remaining_len -= next;
        remaining_count -= 1;
    }
    Some(lengths)
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
            resolve_adaptive_offset(step.offset, &record, state.payload.len(), lp.max(0) as usize, &mut info, context, 5)
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
        resolve_adaptive_offset(step.offset, &record, state.payload.len(), 0, &mut info, context, 5)
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
    let min_fragment_size = step.min_fragment_size.max(1) as usize;
    let max_fragment_size = step.max_fragment_size.max(min_fragment_size as i32) as usize;
    let tail_len = state.payload.len().saturating_sub(marker);
    let Some(lengths) = random_tail_fragment_lengths(tail_len, fragment_count, min_fragment_size, max_fragment_size, rng)
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

fn apply_tls_prelude_steps(
    group: &DesyncGroup,
    prelude_steps: &[TcpChainStep],
    input: &[u8],
    seed: u32,
    context: ActivationContext,
) -> Result<TamperResult, DesyncError> {
    let mut output = input.to_vec();
    let info = ProtoInfo::default();

    if group.mod_http != 0 && is_http(&output) {
        let mutation = mod_http_like_c(&output, group.mod_http);
        if mutation.rc == 0 {
            output = mutation.bytes;
        }
    }
    if let Some(tlsminor) = group.tlsminor {
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
    let prelude_steps = group
        .effective_tcp_chain()
        .into_iter()
        .take_while(|step| step.kind.is_tls_prelude())
        .collect::<Vec<_>>();
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
        },
    )
}

fn apply_tls_mutation(output: &mut Vec<u8>, mutation: ciadpi_packets::PacketMutation) {
    if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
        *output = mutation.bytes;
    }
}

fn tls_sni_capacity(current: &[u8], target_size: usize, new_host: &[u8]) -> usize {
    let Some(markers) = tls_marker_info(current) else {
        return current.len().max(target_size);
    };
    let current_host_len = markers.host_end.saturating_sub(markers.host_start);
    current
        .len()
        .max(target_size)
        .max(current.len().saturating_add(new_host.len().saturating_sub(current_host_len)))
}

pub fn build_fake_packet(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<FakePacketPlan, DesyncError> {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(seed);
    let fixed_sni = if group.fake_sni_list.is_empty() {
        None
    } else {
        Some(group.fake_sni_list[rng.next_mod(group.fake_sni_list.len())].as_bytes())
    };

    if info.kind == 0 {
        if is_tls_client_hello(input) {
            info.kind = IS_HTTPS;
        } else if is_http(input) {
            info.kind = IS_HTTP;
        }
    }

    let base = if let Some(fake) = &group.fake_data {
        fake.clone()
    } else if info.kind == IS_HTTP {
        http_fake_profile_bytes(group.http_fake_profile).to_vec()
    } else {
        tls_fake_profile_bytes(group.tls_fake_profile).to_vec()
    };

    let fake_tls_target = normalize_fake_tls_size(group.fake_tls_size, input.len());
    let mut output = if (group.fake_mod & FM_ORIG) != 0 && info.kind == IS_HTTPS { input.to_vec() } else { base };

    if is_tls_client_hello(&output) {
        if let Some(sni) = fixed_sni {
            let mutation =
                change_tls_sni_seeded_like_c(&output, sni, tls_sni_capacity(&output, fake_tls_target, sni), seed);
            apply_tls_mutation(&mut output, mutation);
        } else if (group.fake_mod & FM_RNDSNI) != 0 {
            let mutation = randomize_tls_sni_seeded_like_c(&output, seed);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.fake_mod & FM_RAND) != 0 {
            let mutation = randomize_tls_seeded_like_c(&output, seed);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.fake_mod & FM_DUPSID) != 0 {
            let mutation = duplicate_tls_session_id_like_c(&output, input);
            apply_tls_mutation(&mut output, mutation);
        }
        if fake_tls_target != output.len() {
            let mutation = tune_tls_padding_size_like_c(&output, fake_tls_target);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.fake_mod & FM_PADENCAP) != 0 {
            let mutation = padencap_tls_like_c(&output, input.len());
            apply_tls_mutation(&mut output, mutation);
        }
    }

    let fake_offset =
        group.fake_offset.and_then(|expr| gen_offset(expr, input, input.len(), 0, &mut info, &mut rng)).unwrap_or(0);
    let fake_offset = if fake_offset < 0 || fake_offset as usize > output.len() { 0 } else { fake_offset as usize };

    Ok(FakePacketPlan { bytes: output, fake_offset, proto: info })
}

fn normalize_fake_tls_size(value: i32, input_len: usize) -> usize {
    if value < 0 {
        input_len.saturating_sub((-value) as usize)
    } else if value <= 0 {
        input_len
    } else {
        value as usize
    }
}

pub fn plan_tcp(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
    default_ttl: u8,
    context: ActivationContext,
) -> Result<DesyncPlan, DesyncError> {
    if !activation_filter_matches(group.activation_filter(), context) {
        return Ok(DesyncPlan {
            tampered: input.to_vec(),
            steps: Vec::new(),
            proto: ProtoInfo::default(),
            actions: vec![DesyncAction::Write(input.to_vec())],
        });
    }
    let chain = group.effective_tcp_chain();
    let (prelude_steps, send_steps) = split_tcp_chain(&chain)?;
    let tampered = apply_tls_prelude_steps(group, &prelude_steps, input, seed, context)?;
    let mut info = tampered.proto;
    let mut rng = OracleRng::seeded(seed);
    let mut steps = Vec::new();
    let mut actions = Vec::new();
    let mut lp = 0i64;

    for step in send_steps {
        if !activation_filter_matches(step.activation_filter, context) {
            continue;
        }
        let Some(mut pos) =
            resolve_offset(step.offset, &tampered.bytes, tampered.bytes.len(), lp, &mut info, &mut rng, context)
        else {
            if step.offset.base.is_adaptive() {
                continue;
            }
            return Err(DesyncError);
        };
        if pos < 0 || pos < lp {
            return Err(DesyncError);
        }
        if pos > tampered.bytes.len() as i64 {
            pos = tampered.bytes.len() as i64;
        }
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        let mut planned_kind = step.kind;

        match step.kind {
            TcpChainStepKind::Split => {
                push_split_actions(&mut actions, chunk);
            }
            TcpChainStepKind::Oob => {
                actions.push(DesyncAction::WriteUrgent { prefix: chunk, urgent_byte: group.oob_data.unwrap_or(b'a') })
            }
            TcpChainStepKind::Disorder => {
                actions.push(DesyncAction::SetTtl(1));
                actions.push(DesyncAction::Write(chunk));
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
            }
            TcpChainStepKind::Disoob => {
                actions.push(DesyncAction::SetTtl(1));
                actions.push(DesyncAction::WriteUrgent { prefix: chunk, urgent_byte: group.oob_data.unwrap_or(b'a') });
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
            }
            TcpChainStepKind::Fake => {
                let fake = build_fake_packet(group, &tampered.bytes, seed)?;
                let span = (pos - lp) as usize;
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                push_fake_actions(
                    &mut actions,
                    &tampered.bytes[lp as usize..pos as usize],
                    fake.bytes[fake.fake_offset..fake_end].to_vec(),
                    group,
                    default_ttl,
                );
            }
            TcpChainStepKind::HostFake => {
                let Some(span) = resolve_hostfake_span(&step, &tampered.bytes, lp as usize, pos as usize, seed) else {
                    planned_kind = TcpChainStepKind::Split;
                    push_split_actions(&mut actions, chunk);
                    steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
                    if matches!(planned_kind, TcpChainStepKind::Oob) {
                        actions.push(DesyncAction::AwaitWritable);
                    }
                    lp = pos;
                    continue;
                };

                if (lp as usize) < span.host_start {
                    push_split_actions(&mut actions, tampered.bytes[lp as usize..span.host_start].to_vec());
                }

                let real_host = &tampered.bytes[span.host_start..span.host_end];
                let fake_host = build_hostfake_bytes(real_host, step.fake_host_template.as_deref(), seed);
                push_fake_actions(&mut actions, real_host, fake_host.clone(), group, default_ttl);

                if let Some(midhost) = span.midhost {
                    push_split_actions(&mut actions, tampered.bytes[span.host_start..midhost].to_vec());
                    push_split_actions(&mut actions, tampered.bytes[midhost..span.host_end].to_vec());
                } else {
                    push_split_actions(&mut actions, real_host.to_vec());
                }

                push_fake_actions(&mut actions, real_host, fake_host, group, default_ttl);

                if span.host_end < pos as usize {
                    push_split_actions(&mut actions, tampered.bytes[span.host_end..pos as usize].to_vec());
                }
            }
            TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => return Err(DesyncError),
        }
        steps.push(PlannedStep { kind: planned_kind, start: lp, end: pos });
        if matches!(planned_kind, TcpChainStepKind::Oob) {
            actions.push(DesyncAction::AwaitWritable);
        }
        lp = pos;
    }

    if lp < tampered.bytes.len() as i64 {
        actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
    }

    Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions })
}

pub fn plan_udp(
    group: &DesyncGroup,
    payload: &[u8],
    default_ttl: u8,
    context: ActivationContext,
) -> Vec<DesyncAction> {
    if !activation_filter_matches(group.activation_filter(), context) {
        return vec![DesyncAction::Write(payload.to_vec())];
    }
    let mut actions = Vec::new();
    let chain = group.effective_udp_chain();
    if group.drop_sack {
        actions.push(DesyncAction::AttachDropSack);
    }
    if !chain.is_empty() {
        let fake = udp_fake_payload(group, payload);
        for step in chain {
            if !activation_filter_matches(step.activation_filter, context) {
                continue;
            }
            if !matches!(step.kind, UdpChainStepKind::FakeBurst) || step.count <= 0 {
                continue;
            }
            actions.push(DesyncAction::SetTtl(group.ttl.unwrap_or(8)));
            for _ in 0..step.count {
                actions.push(DesyncAction::Write(fake.clone()));
            }
            actions.push(DesyncAction::RestoreDefaultTtl);
            if default_ttl != 0 {
                actions.push(DesyncAction::SetTtl(default_ttl));
            }
        }
    }
    actions.push(DesyncAction::Write(payload.to_vec()));
    if group.drop_sack {
        actions.push(DesyncAction::DetachDropSack);
    }
    actions
}

fn split_tcp_chain(chain: &[TcpChainStep]) -> Result<(Vec<TcpChainStep>, Vec<TcpChainStep>), DesyncError> {
    let mut prelude_steps = Vec::new();
    let mut send_steps = Vec::new();
    let mut saw_send_step = false;

    for step in chain {
        if step.kind.is_tls_prelude() {
            if saw_send_step {
                return Err(DesyncError);
            }
            prelude_steps.push(step.clone());
        } else {
            saw_send_step = true;
            send_steps.push(step.clone());
        }
    }

    Ok((prelude_steps, send_steps))
}

fn udp_fake_payload(group: &DesyncGroup, payload: &[u8]) -> Vec<u8> {
    if group.quic_fake_profile != QuicFakeProfile::Disabled {
        if let Some(quic) = parse_quic_initial(payload) {
            match group.quic_fake_profile {
                QuicFakeProfile::Disabled => {}
                QuicFakeProfile::CompatDefault => return default_fake_quic_compat(),
                QuicFakeProfile::RealisticInitial => {
                    if let Some(fake) = build_realistic_quic_initial(quic.version, group.quic_fake_host.as_deref()) {
                        return fake;
                    }
                }
            }
        }
    }

    let mut fake = group
        .fake_data
        .clone()
        .unwrap_or_else(|| udp_fake_profile_bytes(group.udp_fake_profile).to_vec());
    if let Some(offset) = group.fake_offset {
        if let Some(pos) = offset.absolute_positive().filter(|pos| (*pos as usize) < fake.len()) {
            fake = fake[pos as usize..].to_vec();
        } else {
            fake.clear();
        }
    }
    fake
}

#[cfg(test)]
mod tests {
    use super::*;
    use ciadpi_config::{
        DesyncMode, OffsetBase, PartSpec, QuicFakeProfile, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
    };
    use ciadpi_packets::{
        build_realistic_quic_initial, http_marker_info, parse_http, parse_quic_initial, parse_tls,
        second_level_domain_span, tls_marker_info, HttpFakeProfile, TlsFakeProfile, UdpFakeProfile,
        DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, QUIC_V2_VERSION,
    };

    fn split_expr(pos: i64) -> OffsetExpr {
        OffsetExpr::absolute(pos).with_repeat_skip(1, 0)
    }

    fn tls_record_lengths(buffer: &[u8]) -> Vec<usize> {
        let mut cursor = 0usize;
        let mut lengths = Vec::new();
        while cursor + 5 <= buffer.len() {
            let len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
            lengths.push(len);
            cursor += 5 + len;
        }
        assert_eq!(cursor, buffer.len());
        lengths
    }

    fn tcp_context(payload: &[u8]) -> ActivationContext {
        ActivationContext {
            round: 1,
            payload_size: payload.len() as i64,
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1) as i64,
            transport: ActivationTransport::Tcp,
            tcp_segment_hint: None,
        }
    }

    fn udp_context(payload: &[u8]) -> ActivationContext {
        ActivationContext {
            round: 1,
            payload_size: payload.len() as i64,
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1) as i64,
            transport: ActivationTransport::Udp,
            tcp_segment_hint: None,
        }
    }

    fn tcp_context_with_hint(payload: &[u8], tcp_segment_hint: TcpSegmentHint) -> ActivationContext {
        ActivationContext { tcp_segment_hint: Some(tcp_segment_hint), ..tcp_context(payload) }
    }

    fn tlsrandrec_step(marker: i64, count: i32, min_size: i32, max_size: i32) -> TcpChainStep {
        TcpChainStep {
            kind: TcpChainStepKind::TlsRandRec,
            offset: OffsetExpr::absolute(marker),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: count,
            min_fragment_size: min_size,
            max_fragment_size: max_size,
        }
    }

    #[test]
    fn tcp_segment_hint_budget_uses_fallback_order() {
        assert_eq!(
            TcpSegmentHint { snd_mss: Some(96), advmss: Some(120), pmtu: Some(1500), ip_header_overhead: 40 }
                .adaptive_budget(),
            96,
        );
        assert_eq!(
            TcpSegmentHint { snd_mss: Some(63), advmss: Some(120), pmtu: Some(1500), ip_header_overhead: 40 }
                .adaptive_budget(),
            120,
        );
        assert_eq!(
            TcpSegmentHint { snd_mss: None, advmss: Some(63), pmtu: Some(1500), ip_header_overhead: 40 }
                .adaptive_budget(),
            1460,
        );
        assert_eq!(
            TcpSegmentHint { snd_mss: None, advmss: None, pmtu: None, ip_header_overhead: 40 }.adaptive_budget(),
            1448,
        );
    }

    #[test]
    fn plan_tcp_auto_host_uses_hint_budget_and_semantic_markers() {
        let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

        let plan = plan_tcp(
            &group,
            DEFAULT_FAKE_HTTP,
            7,
            64,
            tcp_context_with_hint(
                DEFAULT_FAKE_HTTP,
                TcpSegmentHint {
                    snd_mss: None,
                    advmss: None,
                    pmtu: Some(markers.host_end as i64 + 40),
                    ip_header_overhead: 40,
                },
            ),
        )
        .expect("adaptive host plan");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_end as i64 }]);
    }

    #[test]
    fn plan_tcp_auto_marker_is_cursor_aware_across_chain_steps() {
        let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
        let host = &DEFAULT_FAKE_HTTP[markers.host_start..markers.host_end];
        let (sld_start, sld_end) = second_level_domain_span(host).expect("sld span");
        let midsld = (markers.host_start + sld_start + ((sld_end - sld_start) / 2)) as i64;
        let remaining = DEFAULT_FAKE_HTTP.len().saturating_sub(markers.host_start);
        let target_end = markers.host_start as i64 + ((remaining.max(1) / 2) as i64);
        let expected_second_end = if markers.host_end as i64 <= target_end { markers.host_end as i64 } else { midsld };

        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::Host, 0)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
        ];

        let plan = plan_tcp(&group, DEFAULT_FAKE_HTTP, 7, 64, tcp_context(DEFAULT_FAKE_HTTP)).expect("cursor-aware plan");

        assert_eq!(plan.steps[0], PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 });
        assert_eq!(
            plan.steps[1],
            PlannedStep {
                kind: TcpChainStepKind::Split,
                start: markers.host_start as i64,
                end: expected_second_end,
            },
        );
    }

    #[test]
    fn plan_tcp_auto_marker_falls_back_to_payload_target_without_semantics() {
        let payload = b"plain payload without semantic markers";
        let expected = (payload.len() / 2).max(1) as i64;
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoBalanced))];

        let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("fallback plan");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: expected }]);
    }

    #[test]
    fn plan_tcp_tlsrec_supports_adaptive_marker_resolution() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");
        let mut auto_group = DesyncGroup::new(0);
        auto_group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::adaptive(OffsetBase::AutoSniExt))];
        let mut explicit_group = DesyncGroup::new(0);
        explicit_group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];

        let auto_plan = plan_tcp(
            &auto_group,
            DEFAULT_FAKE_TLS,
            7,
            64,
            tcp_context_with_hint(
                DEFAULT_FAKE_TLS,
                TcpSegmentHint {
                    snd_mss: None,
                    advmss: None,
                    pmtu: Some(markers.sni_ext_start as i64 + 41),
                    ip_header_overhead: 40,
                },
            ),
        )
        .expect("auto tlsrec plan");
        let explicit_plan = plan_tcp(&explicit_group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS))
            .expect("explicit tlsrec plan");

        assert_eq!(auto_plan.tampered, explicit_plan.tampered);
    }

    #[test]
    fn plan_tcp_tlsrandrec_supports_adaptive_marker_resolution() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");
        let mut auto_group = DesyncGroup::new(0);
        auto_group.tcp_chain = vec![TcpChainStep {
            kind: TcpChainStepKind::TlsRandRec,
            offset: OffsetExpr::adaptive(OffsetBase::AutoSniExt),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 4,
            min_fragment_size: 16,
            max_fragment_size: 32,
        }];
        let mut explicit_group = DesyncGroup::new(0);
        explicit_group.tcp_chain = vec![TcpChainStep {
            kind: TcpChainStepKind::TlsRandRec,
            offset: OffsetExpr::marker(OffsetBase::SniExt, 0),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 4,
            min_fragment_size: 16,
            max_fragment_size: 32,
        }];

        let auto_plan = plan_tcp(
            &auto_group,
            DEFAULT_FAKE_TLS,
            7,
            64,
            tcp_context_with_hint(
                DEFAULT_FAKE_TLS,
                TcpSegmentHint {
                    snd_mss: None,
                    advmss: None,
                    pmtu: Some(markers.sni_ext_start as i64 + 41),
                    ip_header_overhead: 40,
                },
            ),
        )
        .expect("auto tlsrandrec plan");
        let explicit_plan = plan_tcp(&explicit_group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS))
            .expect("explicit tlsrandrec plan");

        assert_eq!(auto_plan.tampered, explicit_plan.tampered);
    }

    #[test]
    fn plan_tcp_split_emits_chunk_and_tail_actions() {
        let mut group = DesyncGroup::new(0);
        group.parts.push(PartSpec { mode: DesyncMode::Split, offset: split_expr(5) });
        let payload = b"hello world";

        let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan split tcp");

        assert_eq!(plan.tampered, payload);
        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 5 }]);
        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::Write(b"hello".to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::Write(b" world".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_tcp_fake_uses_fake_chunk_then_original_tail() {
        let mut group = DesyncGroup::new(0);
        group.ttl = Some(9);
        group.fake_data = Some(b"FAKEPAYLOAD".to_vec());
        group.parts.push(PartSpec { mode: DesyncMode::Fake, offset: split_expr(4) });
        let payload = b"hello world";

        let plan = plan_tcp(&group, payload, 3, 32, tcp_context(payload)).expect("plan fake tcp");

        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::SetTtl(9),
                DesyncAction::Write(b"FAKE".to_vec()),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(32),
                DesyncAction::Write(b"o world".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_tcp_disorder_emits_ttl_write_await_restore() {
        let mut group = DesyncGroup::new(0);
        group.parts.push(PartSpec { mode: DesyncMode::Disorder, offset: split_expr(3) });
        let payload = b"abcdef";

        let plan = plan_tcp(&group, payload, 1, 0, tcp_context(payload)).expect("plan disorder tcp");

        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::SetTtl(1),
                DesyncAction::Write(b"abc".to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::Write(b"def".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_tcp_oob_emits_write_urgent() {
        let mut group = DesyncGroup::new(0);
        group.oob_data = Some(b'Z');
        group.parts.push(PartSpec { mode: DesyncMode::Oob, offset: split_expr(4) });
        let payload = b"abcdefgh";

        let plan = plan_tcp(&group, payload, 2, 0, tcp_context(payload)).expect("plan oob tcp");

        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::WriteUrgent { prefix: b"abcd".to_vec(), urgent_byte: b'Z' },
                DesyncAction::AwaitWritable,
                DesyncAction::Write(b"efgh".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_tcp_disoob_combines_ttl_and_urgent() {
        let mut group = DesyncGroup::new(0);
        group.oob_data = Some(b'X');
        group.parts.push(PartSpec { mode: DesyncMode::Disoob, offset: split_expr(2) });
        let payload = b"abcdef";

        let plan = plan_tcp(&group, payload, 0, 0, tcp_context(payload)).expect("plan disoob tcp");

        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::SetTtl(1),
                DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'X' },
                DesyncAction::AwaitWritable,
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::Write(b"cdef".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_tcp_chain_preserves_tlsrec_prelude_and_send_step_order() {
        let mut group = DesyncGroup::new(0);
        group.fake_data = Some(b"FAKEPAYLOAD".to_vec());
        group.tlsminor = Some(1);
        group.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(TcpChainStepKind::Fake, split_expr(4)),
            TcpChainStep::new(TcpChainStepKind::Split, split_expr(7)),
        ];

        let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 9, 32, tcp_context(DEFAULT_FAKE_TLS)).expect("plan chained tcp");

        assert_eq!(
            plan.steps,
            vec![
                PlannedStep { kind: TcpChainStepKind::Fake, start: 0, end: 4 },
                PlannedStep { kind: TcpChainStepKind::Split, start: 4, end: 7 },
            ]
        );
        assert_eq!(plan.tampered[2], 1);
        assert!(plan.tampered.len() > DEFAULT_FAKE_TLS.len());
    }

    #[test]
    fn plan_tcp_group_activation_filter_can_disable_desync() {
        let mut group = DesyncGroup::new(0);
        group.set_activation_filter(ActivationFilter {
            round: Some(NumericRange::new(2, 4)),
            payload_size: None,
            stream_bytes: None,
        });
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, split_expr(5))];
        let payload = b"hello world";

        let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan tcp");

        assert!(plan.steps.is_empty());
        assert_eq!(plan.tampered, payload);
        assert_eq!(plan.actions, vec![DesyncAction::Write(payload.to_vec())]);
    }

    #[test]
    fn plan_tcp_step_activation_filter_skips_tls_prelude_only() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep {
                kind: TcpChainStepKind::TlsRec,
                offset: OffsetExpr::marker(OffsetBase::ExtLen, 0),
                activation_filter: Some(ActivationFilter {
                    round: Some(NumericRange::new(2, 3)),
                    payload_size: None,
                    stream_bytes: None,
                }),
                midhost_offset: None,
                fake_host_template: None,
                fragment_count: 0,
                min_fragment_size: 0,
                max_fragment_size: 0,
            },
            TcpChainStep::new(TcpChainStepKind::Split, split_expr(4)),
        ];

        let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("plan tcp");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 4 }]);
        assert_eq!(plan.tampered, DEFAULT_FAKE_TLS);
    }

    #[test]
    fn plan_tcp_rejects_tlsrec_after_send_step() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, split_expr(2)),
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
        ];

        assert!(plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).is_err());
    }

    #[test]
    fn apply_tamper_tlsrandrec_is_noop_for_non_tls_payloads() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![tlsrandrec_step(0, 4, 16, 32)];

        let tampered = apply_tamper(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7).expect("tamper http");

        assert_eq!(tampered.bytes, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
    }

    #[test]
    fn apply_tamper_tlsrandrec_is_noop_when_marker_cannot_be_resolved() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep {
            kind: TcpChainStepKind::TlsRandRec,
            offset: OffsetExpr::marker(OffsetBase::Method, 0),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 4,
            min_fragment_size: 16,
            max_fragment_size: 32,
        }];

        let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper tls");

        assert_eq!(tampered.bytes, DEFAULT_FAKE_TLS);
    }

    #[test]
    fn apply_tamper_tlsrandrec_is_noop_when_layout_is_impossible() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![tlsrandrec_step(0, 16, 4096, 4096)];

        let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper impossible");

        assert_eq!(tampered.bytes, DEFAULT_FAKE_TLS);
    }

    #[test]
    fn apply_tamper_tlsrandrec_rewrites_clienthello_into_expected_record_lengths() {
        let payload_len = DEFAULT_FAKE_TLS.len() - 5;
        let marker = (payload_len - 96) as i64;
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![tlsrandrec_step(marker, 3, 32, 32)];

        let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper tlsrandrec");

        assert_eq!(tls_record_lengths(&tampered.bytes), vec![payload_len - 96, 32, 32, 32]);
        assert_eq!(tampered.bytes[0], DEFAULT_FAKE_TLS[0]);
        assert_eq!(tampered.bytes[1], DEFAULT_FAKE_TLS[1]);
        assert_eq!(tampered.bytes[2], DEFAULT_FAKE_TLS[2]);
    }

    #[test]
    fn apply_tamper_tlsrandrec_seed_changes_randomized_layout() {
        let payload_len = DEFAULT_FAKE_TLS.len() - 5;
        let marker = (payload_len - 96) as i64;
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![tlsrandrec_step(marker, 3, 24, 40)];

        let seed_a = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("seed a");
        let seed_b = apply_tamper(&group, DEFAULT_FAKE_TLS, 8).expect("seed b");

        assert_ne!(seed_a.bytes, seed_b.bytes);
    }

    #[test]
    fn plan_tcp_supports_mixed_tls_preludes_before_send_steps() {
        let payload_len = DEFAULT_FAKE_TLS.len() - 5;
        let marker = (payload_len - 96) as i64;
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
            tlsrandrec_step(marker, 3, 32, 32),
            TcpChainStep::new(TcpChainStepKind::Split, split_expr(4)),
        ];

        let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("mixed tls preludes");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 4 }]);
        assert_eq!(tls_record_lengths(&plan.tampered), vec![payload_len - 96, 32, 32, 32]);
    }

    #[test]
    fn normalize_fake_tls_size_all_branches() {
        // negative -> saturating_sub
        assert_eq!(normalize_fake_tls_size(-5, 100), 95);
        assert_eq!(normalize_fake_tls_size(-200, 100), 0);
        // zero -> input_len
        assert_eq!(normalize_fake_tls_size(0, 100), 100);
        // positive -> explicit target size
        assert_eq!(normalize_fake_tls_size(200, 100), 200);
        // positive in range -> value
        assert_eq!(normalize_fake_tls_size(50, 100), 50);
    }

    #[test]
    fn build_fake_packet_applies_rndsni_and_dupsid_in_order() {
        let mut group = DesyncGroup::new(0);
        group.fake_mod = FM_ORIG | FM_RAND | FM_RNDSNI | FM_DUPSID;
        let payload = DEFAULT_FAKE_TLS;
        let expected_sid = payload[44..44 + payload[43] as usize].to_vec();
        let original_host = ciadpi_packets::parse_tls(payload).expect("original tls host").to_vec();

        let fake = build_fake_packet(&group, payload, 19).expect("fake packet");
        let fake_host = ciadpi_packets::parse_tls(&fake.bytes).expect("fake tls host").to_vec();

        assert_ne!(fake_host, original_host);
        assert_eq!(&fake.bytes[44..44 + fake.bytes[43] as usize], expected_sid.as_slice());
    }

    #[test]
    fn build_fake_packet_applies_fake_tls_size_to_default_and_orig_bases() {
        let mut default_group = DesyncGroup::new(0);
        default_group.fake_mod = FM_RNDSNI;
        default_group.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 12) as i32;

        let default_fake = build_fake_packet(&default_group, DEFAULT_FAKE_TLS, 3).expect("default fake tls");
        assert_eq!(default_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 12);

        let mut orig_group = DesyncGroup::new(0);
        orig_group.fake_mod = FM_ORIG | FM_RNDSNI;
        orig_group.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 8) as i32;

        let orig_fake = build_fake_packet(&orig_group, DEFAULT_FAKE_TLS, 5).expect("orig fake tls");
        assert_eq!(orig_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 8);
    }

    #[test]
    fn build_fake_packet_ignores_tls_mods_for_http_payloads() {
        let mut group = DesyncGroup::new(0);
        group.fake_mod = FM_RAND | FM_RNDSNI | FM_DUPSID | FM_PADENCAP;

        let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 11).expect("http fake");

        assert_eq!(fake.bytes, DEFAULT_FAKE_HTTP);
        assert_eq!(fake.proto.kind, IS_HTTP);
    }

    #[test]
    fn build_fake_packet_padencap_keeps_valid_tls() {
        let mut group = DesyncGroup::new(0);
        group.fake_mod = FM_PADENCAP;

        let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("padencap fake");

        assert!(ciadpi_packets::parse_tls(&fake.bytes).is_some());
        assert_eq!(fake.bytes.len(), DEFAULT_FAKE_TLS.len());
        assert!(fake.fake_offset <= fake.bytes.len());
    }

    #[test]
    fn plan_udp_no_fake_only_drop_sack() {
        let mut group = DesyncGroup::new(0);
        group.drop_sack = true;
        group.udp_fake_count = 0;

        let actions = plan_udp(&group, b"data", 0, udp_context(b"data"));

        assert_eq!(
            actions,
            vec![DesyncAction::AttachDropSack, DesyncAction::Write(b"data".to_vec()), DesyncAction::DetachDropSack,]
        );
    }

    #[test]
    fn gen_offset_end_mid_rand_flags() {
        let mut info = ProtoInfo::default();
        let mut rng = OracleRng::seeded(42);
        let buf = b"0123456789";

        let expr_end = OffsetExpr::marker(OffsetBase::PayloadEnd, -3);
        assert_eq!(gen_offset(expr_end, buf, buf.len(), 0, &mut info, &mut rng), Some(7));

        let expr_mid = OffsetExpr::marker(OffsetBase::PayloadMid, 0);
        assert_eq!(gen_offset(expr_mid, buf, buf.len(), 0, &mut info, &mut rng), Some(5));

        let expr_rand = OffsetExpr::marker(OffsetBase::PayloadRand, 0);
        let result = gen_offset(expr_rand, buf, buf.len(), 0, &mut info, &mut rng).expect("payload rand");
        assert!(result >= 0 && result <= buf.len() as i64);
    }

    #[test]
    fn gen_offset_resolves_named_markers() {
        let mut info = ProtoInfo::default();
        let mut rng = OracleRng::seeded(7);
        let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
        let tls = DEFAULT_FAKE_TLS;
        let expected_http_mid = 24 + 4 + ((11 - 4) / 2);
        let tls_markers = tls_marker_info(tls).expect("tls markers");

        assert_eq!(
            gen_offset(OffsetExpr::marker(OffsetBase::Method, 0), http, http.len(), 0, &mut info, &mut rng),
            Some(2)
        );
        assert_eq!(
            gen_offset(OffsetExpr::marker(OffsetBase::MidSld, 0), http, http.len(), 0, &mut info, &mut rng),
            Some(expected_http_mid as i64)
        );

        let mut tls_info = ProtoInfo::default();
        let mut tls_rng = OracleRng::seeded(7);
        assert_eq!(
            gen_offset(OffsetExpr::marker(OffsetBase::SniExt, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
            Some(tls_markers.sni_ext_start as i64)
        );
        assert_eq!(
            gen_offset(OffsetExpr::marker(OffsetBase::ExtLen, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
            Some(tls_markers.ext_len_start as i64)
        );
    }

    #[test]
    fn build_hostfake_bytes_preserves_length_and_template_suffix() {
        let fake = build_hostfake_bytes(b"video.example.com", Some("googlevideo.com"), 17);

        assert_eq!(fake.len(), b"video.example.com".len());
        assert!(fake.iter().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'-')));
        assert!(std::str::from_utf8(&fake).unwrap().ends_with("video.com"));
    }

    #[test]
    fn plan_tcp_hostfake_emits_fake_real_fake_sequence_for_http_host() {
        let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
        let markers = http_marker_info(payload).expect("http markers");
        let mut group = DesyncGroup::new(0);
        group.ttl = Some(9);
        group.tcp_chain = vec![TcpChainStep {
            kind: TcpChainStepKind::HostFake,
            offset: OffsetExpr::marker(OffsetBase::PayloadEnd, 0),
            activation_filter: None,
            midhost_offset: Some(OffsetExpr::marker(OffsetBase::MidSld, 0)),
            fake_host_template: Some("googlevideo.com".to_string()),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
        }];

        let plan = plan_tcp(&group, payload, 23, 32, tcp_context(payload)).expect("plan hostfake");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::HostFake, start: 0, end: payload.len() as i64 }]);
        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::Write(payload[..markers.host_start].to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::SetTtl(9),
                DesyncAction::Write(build_hostfake_bytes(&payload[markers.host_start..markers.host_end], Some("googlevideo.com"), 23)),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(32),
                DesyncAction::Write(payload[markers.host_start..markers.host_start + 7].to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::Write(payload[markers.host_start + 7..markers.host_end].to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::SetTtl(9),
                DesyncAction::Write(build_hostfake_bytes(&payload[markers.host_start..markers.host_end], Some("googlevideo.com"), 23)),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(32),
                DesyncAction::Write(payload[markers.host_end..].to_vec()),
                DesyncAction::AwaitWritable,
            ]
        );
    }

    #[test]
    fn hostfake_degrades_to_split_when_step_ends_before_endhost() {
        let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
        let markers = http_marker_info(payload).expect("http markers");
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep {
            kind: TcpChainStepKind::HostFake,
            offset: OffsetExpr::marker(OffsetBase::Host, 0),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
        }];

        let plan = plan_tcp(&group, payload, 9, 32, tcp_context(payload)).expect("plan degraded hostfake");

        assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 }]);
        assert_eq!(
            plan.actions,
            vec![
                DesyncAction::Write(payload[..markers.host_start].to_vec()),
                DesyncAction::AwaitWritable,
                DesyncAction::Write(payload[markers.host_start..].to_vec()),
            ]
        );
    }

    #[test]
    fn unresolved_markers_fail_planning_safely() {
        let mut group = DesyncGroup::new(0);
        group.parts.push(PartSpec { mode: DesyncMode::Split, offset: OffsetExpr::tls_host(0) });

        let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(plan_tcp(&group, payload, 7, 64, tcp_context(payload)).is_err());
    }

    #[test]
    fn plan_udp_wraps_fake_burst_and_drop_sack_actions() {
        let mut group = DesyncGroup::new(0);
        group.drop_sack = true;
        group.udp_chain = vec![
            UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None },
            UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
        ];
        group.ttl = Some(7);
        group.fake_data = Some(b"udp-fake".to_vec());

        let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

        assert_eq!(
            actions,
            vec![
                DesyncAction::AttachDropSack,
                DesyncAction::SetTtl(7),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(64),
                DesyncAction::SetTtl(7),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(64),
                DesyncAction::Write(b"payload".to_vec()),
                DesyncAction::DetachDropSack,
            ]
        );
    }

    #[test]
    fn plan_udp_step_activation_filter_skips_filtered_fake_bursts() {
        let mut group = DesyncGroup::new(0);
        group.udp_chain = vec![
            UdpChainStep {
                kind: UdpChainStepKind::FakeBurst,
                count: 1,
                activation_filter: Some(ActivationFilter {
                    round: Some(NumericRange::new(2, 4)),
                    payload_size: None,
                    stream_bytes: None,
                }),
            },
            UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
        ];
        group.fake_data = Some(b"udp-fake".to_vec());

        let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

        assert_eq!(
            actions,
            vec![
                DesyncAction::SetTtl(8),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(64),
                DesyncAction::Write(b"payload".to_vec()),
            ]
        );
    }

    #[test]
    fn plan_udp_uses_generated_quic_fake_initial_when_profile_is_active() {
        let mut group = DesyncGroup::new(0);
        group.ttl = Some(7);
        group.quic_fake_profile = QuicFakeProfile::RealisticInitial;
        group.quic_fake_host = Some("video.example.test".to_string());
        group.udp_chain = vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
        let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

        let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
        let DesyncAction::Write(fake_packet) = &actions[1] else {
            panic!("expected first fake write");
        };
        let parsed = parse_quic_initial(fake_packet).expect("parse generated fake");

        assert_eq!(parsed.version, QUIC_V2_VERSION);
        assert_eq!(parsed.host(), b"video.example.test");
        assert_eq!(actions.last(), Some(&DesyncAction::Write(payload)));
    }

    #[test]
    fn plan_udp_falls_back_to_raw_fake_payload_for_non_quic_input() {
        let mut group = DesyncGroup::new(0);
        group.quic_fake_profile = QuicFakeProfile::CompatDefault;
        group.udp_chain = vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
        group.fake_data = Some(b"udp-fake".to_vec());

        let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

        assert_eq!(
            actions,
            vec![
                DesyncAction::SetTtl(8),
                DesyncAction::Write(b"udp-fake".to_vec()),
                DesyncAction::RestoreDefaultTtl,
                DesyncAction::SetTtl(64),
                DesyncAction::Write(b"payload".to_vec()),
            ]
        );
    }

    #[test]
    fn build_fake_packet_uses_selected_http_profile_when_no_raw_fake_is_set() {
        let mut group = DesyncGroup::new(0);
        group.http_fake_profile = HttpFakeProfile::CloudflareGet;

        let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7).expect("http fake");
        let parsed = parse_http(&fake.bytes).expect("parse fake http");

        assert_eq!(parsed.host, b"www.cloudflare.com");
    }

    #[test]
    fn build_fake_packet_uses_selected_tls_profile_when_no_raw_fake_is_set() {
        let mut group = DesyncGroup::new(0);
        group.tls_fake_profile = TlsFakeProfile::GoogleChrome;

        let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("tls fake");
        let parsed = parse_tls(&fake.bytes).expect("parse fake tls");

        assert_eq!(parsed, b"www.google.com");
    }

    #[test]
    fn plan_udp_uses_selected_udp_profile_when_no_raw_fake_is_set() {
        let mut group = DesyncGroup::new(0);
        group.udp_fake_profile = UdpFakeProfile::DnsQuery;
        group.udp_chain = vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];

        let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

        let DesyncAction::Write(fake_packet) = &actions[1] else {
            panic!("expected fake packet write");
        };
        assert_eq!(fake_packet.len(), 38);
        assert_eq!(&fake_packet[12..19], b"\x06update");
    }
}
