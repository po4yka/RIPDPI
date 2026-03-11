#![forbid(unsafe_code)]

use ciadpi_config::{
    DesyncGroup, DesyncMode, OffsetBase, OffsetExpr, OffsetProto, TcpChainStep, TcpChainStepKind, UdpChainStepKind,
    FM_ORIG, FM_RAND,
};
use ciadpi_packets::{
    change_tls_sni_seeded_like_c, http_marker_info, is_http, is_tls_client_hello, mod_http_like_c, part_tls_like_c,
    randomize_tls_seeded_like_c, second_level_domain_span, tls_marker_info, HttpMarkerInfo, OracleRng, TlsMarkerInfo,
    DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, DEFAULT_FAKE_UDP, IS_HTTP, IS_HTTPS,
};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ProtoInfo {
    pub kind: u32,
    http: Option<HttpMarkerInfo>,
    tls: Option<TlsMarkerInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedStep {
    pub mode: DesyncMode,
    pub start: i64,
    pub end: i64,
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
    };
    Some(pos)
}

fn apply_tamper_with_tls_records(
    group: &DesyncGroup,
    tls_records: &[OffsetExpr],
    input: &[u8],
    seed: u32,
) -> Result<TamperResult, DesyncError> {
    let mut output = input.to_vec();
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(seed);

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
    if !tls_records.is_empty() && is_tls_client_hello(&output) {
        let mut lp = 0i64;
        let mut rc = 0i32;
        for expr in tls_records {
            let total = expr.repeats.max(1);
            let mut remaining = total;
            while remaining > 0 {
                let mut pos = (rc as i64) * 5;
                let Some(offset) = gen_offset(
                    *expr,
                    &output,
                    output.len().saturating_sub(pos.max(0) as usize),
                    lp,
                    &mut info,
                    &mut rng,
                ) else {
                    break;
                };
                pos += offset;
                if expr.needs_tls_record_adjustment() {
                    pos -= 5;
                }
                pos += (expr.skip as i64) * ((total - remaining) as i64);
                if pos < lp {
                    break;
                }
                let tail = part_tls_like_c(&output[lp as usize..], (pos - lp).try_into().map_err(|_| DesyncError)?);
                if tail.rc <= 0 {
                    break;
                }
                let mut next = Vec::with_capacity(lp as usize + tail.bytes.len());
                next.extend_from_slice(&output[..lp as usize]);
                next.extend_from_slice(&tail.bytes);
                output = next;
                lp = pos + 5;
                rc += 1;
                remaining -= 1;
            }
        }
    }

    Ok(TamperResult { bytes: output, proto: info })
}

pub fn apply_tamper(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<TamperResult, DesyncError> {
    let tls_records = group
        .effective_tcp_chain()
        .into_iter()
        .take_while(|step| matches!(step.kind, TcpChainStepKind::TlsRec))
        .map(|step| step.offset)
        .collect::<Vec<_>>();
    apply_tamper_with_tls_records(group, &tls_records, input, seed)
}

pub fn build_fake_packet(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<FakePacketPlan, DesyncError> {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(seed);
    let sni = if group.fake_sni_list.is_empty() {
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
        DEFAULT_FAKE_HTTP.to_vec()
    } else {
        DEFAULT_FAKE_TLS.to_vec()
    };

    let max_size = input.len().max(base.len());
    let mut output = base;
    let mut built_from_orig = false;

    if (group.fake_mod & FM_ORIG) != 0 && info.kind == IS_HTTPS {
        output = input.to_vec();
        if let Some(sni) = sni {
            let target = normalize_fake_tls_size(group.fake_tls_size, input.len());
            let mutation = change_tls_sni_seeded_like_c(&output, sni, output.len().max(target), seed);
            if mutation.rc == 0 {
                output = mutation.bytes;
                built_from_orig = true;
            }
        } else {
            built_from_orig = true;
        }
    }

    if !built_from_orig {
        if let Some(sni) = sni {
            let mutation = change_tls_sni_seeded_like_c(&output, sni, output.len().max(max_size), seed);
            if mutation.rc == 0 {
                output = mutation.bytes;
            }
        }
    }

    if (group.fake_mod & FM_RAND) != 0 {
        output = randomize_tls_seeded_like_c(&output, seed).bytes;
    }

    let fake_offset =
        group.fake_offset.and_then(|expr| gen_offset(expr, input, input.len(), 0, &mut info, &mut rng)).unwrap_or(0);
    let fake_offset = if fake_offset < 0 || fake_offset as usize > output.len() { 0 } else { fake_offset as usize };

    Ok(FakePacketPlan { bytes: output, fake_offset, proto: info })
}

fn normalize_fake_tls_size(value: i32, input_len: usize) -> usize {
    if value < 0 {
        input_len.saturating_sub((-value) as usize)
    } else if value as usize > input_len || value <= 0 {
        input_len
    } else {
        value as usize
    }
}

pub fn plan_tcp(group: &DesyncGroup, input: &[u8], seed: u32, default_ttl: u8) -> Result<DesyncPlan, DesyncError> {
    let chain = group.effective_tcp_chain();
    let (tls_records, send_steps) = split_tcp_chain(&chain)?;
    let tampered = apply_tamper_with_tls_records(group, &tls_records, input, seed)?;
    let mut info = tampered.proto;
    let mut rng = OracleRng::seeded(seed);
    let mut steps = Vec::new();
    let mut actions = Vec::new();
    let mut lp = 0i64;

    for step in send_steps {
        let mode = step.kind.as_mode().ok_or(DesyncError)?;
        let Some(mut pos) = gen_offset(step.offset, &tampered.bytes, tampered.bytes.len(), lp, &mut info, &mut rng)
        else {
            return Err(DesyncError);
        };
        if pos < 0 || pos < lp {
            return Err(DesyncError);
        }
        if pos > tampered.bytes.len() as i64 {
            pos = tampered.bytes.len() as i64;
        }
        steps.push(PlannedStep { mode, start: lp, end: pos });
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        match mode {
            DesyncMode::Split | DesyncMode::None => {
                actions.push(DesyncAction::Write(chunk));
                actions.push(DesyncAction::AwaitWritable);
            }
            DesyncMode::Oob => {
                actions.push(DesyncAction::WriteUrgent { prefix: chunk, urgent_byte: group.oob_data.unwrap_or(b'a') })
            }
            DesyncMode::Disorder => {
                actions.push(DesyncAction::SetTtl(1));
                actions.push(DesyncAction::Write(chunk));
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
            }
            DesyncMode::Disoob => {
                actions.push(DesyncAction::SetTtl(1));
                actions.push(DesyncAction::WriteUrgent { prefix: chunk, urgent_byte: group.oob_data.unwrap_or(b'a') });
                actions.push(DesyncAction::AwaitWritable);
                actions.push(DesyncAction::RestoreDefaultTtl);
            }
            DesyncMode::Fake => {
                let fake = build_fake_packet(group, &tampered.bytes, seed)?;
                let span = (pos - lp) as usize;
                let fake_end = fake.fake_offset.saturating_add(span).min(fake.bytes.len());
                actions.push(DesyncAction::SetTtl(group.ttl.unwrap_or(8)));
                if group.md5sig {
                    actions.push(DesyncAction::SetMd5Sig { key_len: 5 });
                }
                actions.push(DesyncAction::Write(fake.bytes[fake.fake_offset..fake_end].to_vec()));
                if group.md5sig {
                    actions.push(DesyncAction::SetMd5Sig { key_len: 0 });
                }
                actions.push(DesyncAction::RestoreDefaultTtl);
                if default_ttl != 0 {
                    actions.push(DesyncAction::SetTtl(default_ttl));
                }
            }
        }
        if matches!(mode, DesyncMode::Oob) {
            actions.push(DesyncAction::AwaitWritable);
        }
        lp = pos;
    }

    if lp < tampered.bytes.len() as i64 {
        actions.push(DesyncAction::Write(tampered.bytes[lp as usize..].to_vec()));
    }

    Ok(DesyncPlan { tampered: tampered.bytes, steps, proto: info, actions })
}

pub fn plan_udp(group: &DesyncGroup, payload: &[u8], default_ttl: u8) -> Vec<DesyncAction> {
    let mut actions = Vec::new();
    let chain = group.effective_udp_chain();
    if group.drop_sack {
        actions.push(DesyncAction::AttachDropSack);
    }
    if !chain.is_empty() {
        let fake = udp_fake_payload(group);
        for step in chain {
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

fn split_tcp_chain(chain: &[TcpChainStep]) -> Result<(Vec<OffsetExpr>, Vec<TcpChainStep>), DesyncError> {
    let mut tls_records = Vec::new();
    let mut send_steps = Vec::new();
    let mut saw_send_step = false;

    for step in chain {
        match step.kind {
            TcpChainStepKind::TlsRec if saw_send_step => return Err(DesyncError),
            TcpChainStepKind::TlsRec => tls_records.push(step.offset),
            _ => {
                saw_send_step = true;
                send_steps.push(*step);
            }
        }
    }

    Ok((tls_records, send_steps))
}

fn udp_fake_payload(group: &DesyncGroup) -> Vec<u8> {
    let mut fake = group.fake_data.clone().unwrap_or_else(|| DEFAULT_FAKE_UDP.to_vec());
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
    use ciadpi_config::{OffsetBase, PartSpec, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind};

    fn split_expr(pos: i64) -> OffsetExpr {
        OffsetExpr::absolute(pos).with_repeat_skip(1, 0)
    }

    #[test]
    fn plan_tcp_split_emits_chunk_and_tail_actions() {
        let mut group = DesyncGroup::new(0);
        group.parts.push(PartSpec { mode: DesyncMode::Split, offset: split_expr(5) });
        let payload = b"hello world";

        let plan = plan_tcp(&group, payload, 7, 64).expect("plan split tcp");

        assert_eq!(plan.tampered, payload);
        assert_eq!(plan.steps, vec![PlannedStep { mode: DesyncMode::Split, start: 0, end: 5 }]);
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

        let plan = plan_tcp(&group, payload, 3, 32).expect("plan fake tcp");

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

        let plan = plan_tcp(&group, payload, 1, 0).expect("plan disorder tcp");

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

        let plan = plan_tcp(&group, payload, 2, 0).expect("plan oob tcp");

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

        let plan = plan_tcp(&group, payload, 0, 0).expect("plan disoob tcp");

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
            TcpChainStep { kind: TcpChainStepKind::TlsRec, offset: OffsetExpr::marker(OffsetBase::ExtLen, 0) },
            TcpChainStep { kind: TcpChainStepKind::Fake, offset: split_expr(4) },
            TcpChainStep { kind: TcpChainStepKind::Split, offset: split_expr(7) },
        ];

        let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 9, 32).expect("plan chained tcp");

        assert_eq!(
            plan.steps,
            vec![
                PlannedStep { mode: DesyncMode::Fake, start: 0, end: 4 },
                PlannedStep { mode: DesyncMode::Split, start: 4, end: 7 },
            ]
        );
        assert_eq!(plan.tampered[2], 1);
        assert!(plan.tampered.len() > DEFAULT_FAKE_TLS.len());
    }

    #[test]
    fn plan_tcp_rejects_tlsrec_after_send_step() {
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep { kind: TcpChainStepKind::Split, offset: split_expr(2) },
            TcpChainStep { kind: TcpChainStepKind::TlsRec, offset: OffsetExpr::marker(OffsetBase::ExtLen, 0) },
        ];

        assert!(plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64).is_err());
    }

    #[test]
    fn normalize_fake_tls_size_all_branches() {
        // negative -> saturating_sub
        assert_eq!(normalize_fake_tls_size(-5, 100), 95);
        assert_eq!(normalize_fake_tls_size(-200, 100), 0);
        // zero -> input_len
        assert_eq!(normalize_fake_tls_size(0, 100), 100);
        // positive > input -> input_len
        assert_eq!(normalize_fake_tls_size(200, 100), 100);
        // positive in range -> value
        assert_eq!(normalize_fake_tls_size(50, 100), 50);
    }

    #[test]
    fn plan_udp_no_fake_only_drop_sack() {
        let mut group = DesyncGroup::new(0);
        group.drop_sack = true;
        group.udp_fake_count = 0;

        let actions = plan_udp(&group, b"data", 0);

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
    fn unresolved_markers_fail_planning_safely() {
        let mut group = DesyncGroup::new(0);
        group.parts.push(PartSpec { mode: DesyncMode::Split, offset: OffsetExpr::tls_host(0) });

        assert!(plan_tcp(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7, 64).is_err());
    }

    #[test]
    fn plan_udp_wraps_fake_burst_and_drop_sack_actions() {
        let mut group = DesyncGroup::new(0);
        group.drop_sack = true;
        group.udp_chain = vec![
            UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1 },
            UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2 },
        ];
        group.ttl = Some(7);
        group.fake_data = Some(b"udp-fake".to_vec());

        let actions = plan_udp(&group, b"payload", 64);

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
}
