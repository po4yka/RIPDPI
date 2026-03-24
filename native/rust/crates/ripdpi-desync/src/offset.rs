use crate::proto::{init_proto_info, resolve_host_range};
use crate::types::{ActivationContext, ProtoInfo, TcpSegmentHint};
use ripdpi_config::{OffsetBase, OffsetExpr};
use ripdpi_packets::{second_level_domain_span, OracleRng};

pub(crate) fn gen_offset(
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
        OffsetBase::AutoBalanced => &[OffsetBase::Method, OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost],
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
    let budget = context.tcp_segment_hint.map_or(1448, TcpSegmentHint::adaptive_budget);
    let target_end = if budget < (remaining as i64 - 1) {
        cursor_start as i64 + budget
    } else {
        cursor_start as i64 + ((remaining / 2).max(1) as i64)
    };
    Some(target_end.min(payload_len.saturating_sub(1) as i64))
}

pub(crate) fn resolve_adaptive_offset(
    expr: OffsetExpr,
    buffer: &[u8],
    payload_len: usize,
    cursor_start: usize,
    info: &mut ProtoInfo,
    context: ActivationContext,
    preferred_base: Option<OffsetBase>,
    coordinate_adjustment: i64,
) -> Option<i64> {
    let target_end = adaptive_target_end(payload_len, cursor_start, context)?;
    let candidate_bases = adaptive_candidate_bases(expr, info, buffer);
    let mut below_or_equal = None;
    let mut above = None;

    let mut evaluate_base = |base: OffsetBase| {
        let candidate_expr = OffsetExpr::marker(base, 0);
        let Some(candidate) =
            gen_offset(candidate_expr, buffer, buffer.len(), cursor_start as i64, info, &mut OracleRng::seeded(0))
        else {
            return;
        };
        let candidate = candidate.saturating_sub(coordinate_adjustment);
        if candidate <= cursor_start as i64 || candidate >= payload_len as i64 {
            return;
        }
        if candidate <= target_end {
            if below_or_equal.is_none_or(|current| candidate > current) {
                below_or_equal = Some(candidate);
            }
        } else if above.is_none_or(|current| candidate < current) {
            above = Some(candidate);
        }
    };

    if let Some(base) = preferred_base.filter(|value| candidate_bases.contains(value)) {
        evaluate_base(base);
    }
    for base in candidate_bases {
        if Some(*base) == preferred_base {
            continue;
        }
        evaluate_base(*base);
    }

    below_or_equal.or(above).or(Some(target_end))
}

pub(crate) fn resolve_offset(
    expr: OffsetExpr,
    buffer: &[u8],
    n: usize,
    lp: i64,
    info: &mut ProtoInfo,
    rng: &mut OracleRng,
    context: ActivationContext,
    preferred_base: Option<OffsetBase>,
) -> Option<i64> {
    if expr.base.is_adaptive() {
        return resolve_adaptive_offset(expr, buffer, n, lp.max(0) as usize, info, context, preferred_base, 0);
    }
    gen_offset(expr, buffer, n, lp, info, rng)
}

pub(crate) fn insert_boundary(boundaries: &mut Vec<usize>, pos: usize) {
    if boundaries.contains(&pos) {
        return;
    }
    let idx = boundaries.iter().position(|&end| end > pos).unwrap_or(boundaries.len());
    boundaries.insert(idx, pos);
}

pub(crate) fn random_tail_fragment_lengths(
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
