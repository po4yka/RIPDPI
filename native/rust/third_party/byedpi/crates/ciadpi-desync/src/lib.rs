#![forbid(unsafe_code)]

use ciadpi_config::{
    DesyncGroup, DesyncMode, OffsetExpr, FM_ORIG, FM_RAND, OFFSET_END, OFFSET_HOST, OFFSET_MID, OFFSET_RAND, OFFSET_SNI,
};
use ciadpi_packets::{
    change_tls_sni_seeded_like_c, is_http, is_tls_client_hello, mod_http_like_c, parse_http, parse_tls,
    part_tls_like_c, randomize_tls_seeded_like_c, OracleRng, DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, DEFAULT_FAKE_UDP,
    IS_HTTP, IS_HTTPS,
};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ProtoInfo {
    pub kind: u32,
    pub host_len: usize,
    pub host_pos: usize,
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
    if info.host_len != 0 || info.host_pos != 0 {
        return;
    }
    if let Some(host) = parse_tls(buffer) {
        info.kind = IS_HTTPS;
        info.host_len = host.len();
        info.host_pos = buffer.windows(host.len()).position(|window| window == host).unwrap_or(0);
    } else if let Some(host) = parse_http(buffer) {
        if info.kind == 0 {
            info.kind = IS_HTTP;
        }
        info.host_len = host.host.len();
        info.host_pos = buffer.windows(host.host.len()).position(|window| window == host.host).unwrap_or(0);
    }
}

fn gen_offset(expr: OffsetExpr, buffer: &[u8], n: usize, lp: i64, info: &mut ProtoInfo, rng: &mut OracleRng) -> i64 {
    let mut pos = expr.pos;
    if expr.flag & (OFFSET_SNI | OFFSET_HOST) != 0 {
        init_proto_info(buffer, info);
        if info.host_pos == 0 || ((expr.flag & OFFSET_SNI) != 0 && info.kind != IS_HTTPS) {
            return -1;
        }
        pos += info.host_pos as i64;
        if expr.flag & OFFSET_END != 0 {
            pos += info.host_len as i64;
        } else if expr.flag & OFFSET_MID != 0 {
            pos += (info.host_len / 2) as i64;
        } else if expr.flag & OFFSET_RAND != 0 && info.host_len != 0 {
            pos += rng.next_mod(info.host_len) as i64;
        }
    } else if expr.flag & OFFSET_RAND != 0 {
        let available = n.saturating_sub(lp.max(0) as usize);
        pos += lp + rng.next_mod(available.max(1)) as i64;
    } else if expr.flag & OFFSET_MID != 0 {
        pos += (n / 2) as i64;
    } else if pos < 0 || expr.flag & OFFSET_END != 0 {
        pos += n as i64;
    }
    pos
}

pub fn apply_tamper(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<TamperResult, DesyncError> {
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
    if !group.tls_records.is_empty() && is_tls_client_hello(&output) {
        let mut lp = 0i64;
        let mut rc = 0i32;
        for expr in &group.tls_records {
            let total = expr.repeats.max(1);
            let mut remaining = total;
            while remaining > 0 {
                let mut pos = (rc as i64) * 5;
                pos += gen_offset(
                    *expr,
                    &output,
                    output.len().saturating_sub(pos.max(0) as usize),
                    lp,
                    &mut info,
                    &mut rng,
                );
                if expr.pos < 0 || expr.flag != 0 {
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
        group.fake_offset.map(|expr| gen_offset(expr, input, input.len(), 0, &mut info, &mut rng)).unwrap_or(0);
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
    let tampered = apply_tamper(group, input, seed)?;
    let mut info = tampered.proto;
    let mut rng = OracleRng::seeded(seed);
    let mut steps = Vec::new();
    let mut actions = Vec::new();
    let mut lp = 0i64;

    for part in &group.parts {
        let mut pos = gen_offset(part.offset, &tampered.bytes, tampered.bytes.len(), lp, &mut info, &mut rng);
        if pos < 0 || pos < lp {
            return Err(DesyncError);
        }
        if pos > tampered.bytes.len() as i64 {
            pos = tampered.bytes.len() as i64;
        }
        steps.push(PlannedStep { mode: part.mode, start: lp, end: pos });
        let chunk = tampered.bytes[lp as usize..pos as usize].to_vec();
        match part.mode {
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
        if matches!(part.mode, DesyncMode::Oob) {
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
    if group.drop_sack {
        actions.push(DesyncAction::AttachDropSack);
    }
    if group.udp_fake_count > 0 {
        let mut fake = group.fake_data.clone().unwrap_or_else(|| DEFAULT_FAKE_UDP.to_vec());
        if let Some(offset) = group.fake_offset {
            if offset.pos >= 0 && (offset.pos as usize) < fake.len() {
                fake = fake[offset.pos as usize..].to_vec();
            } else {
                fake.clear();
            }
        }
        actions.push(DesyncAction::SetTtl(group.ttl.unwrap_or(8)));
        for _ in 0..group.udp_fake_count {
            actions.push(DesyncAction::Write(fake.clone()));
        }
        actions.push(DesyncAction::RestoreDefaultTtl);
        if default_ttl != 0 {
            actions.push(DesyncAction::SetTtl(default_ttl));
        }
    }
    actions.push(DesyncAction::Write(payload.to_vec()));
    if group.drop_sack {
        actions.push(DesyncAction::DetachDropSack);
    }
    actions
}

#[cfg(test)]
mod tests {
    use super::*;
    use ciadpi_config::PartSpec;

    fn split_expr(pos: i64) -> OffsetExpr {
        OffsetExpr { pos, flag: 0, repeats: 1, skip: 0 }
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

        // OFFSET_END: pos += n
        let expr_end = OffsetExpr { pos: -3, flag: OFFSET_END, repeats: 0, skip: 0 };
        assert_eq!(gen_offset(expr_end, buf, buf.len(), 0, &mut info, &mut rng), 7);

        // OFFSET_MID: pos += n/2
        let expr_mid = OffsetExpr { pos: 0, flag: OFFSET_MID, repeats: 0, skip: 0 };
        assert_eq!(gen_offset(expr_mid, buf, buf.len(), 0, &mut info, &mut rng), 5);

        // OFFSET_RAND: pos in range [lp, n)
        let expr_rand = OffsetExpr { pos: 0, flag: OFFSET_RAND, repeats: 0, skip: 0 };
        let result = gen_offset(expr_rand, buf, buf.len(), 0, &mut info, &mut rng);
        assert!(result >= 0 && result <= buf.len() as i64);
    }

    #[test]
    fn plan_udp_wraps_fake_burst_and_drop_sack_actions() {
        let mut group = DesyncGroup::new(0);
        group.drop_sack = true;
        group.udp_fake_count = 2;
        group.ttl = Some(7);
        group.fake_data = Some(b"udp-fake".to_vec());

        let actions = plan_udp(&group, b"payload", 64);

        assert_eq!(
            actions,
            vec![
                DesyncAction::AttachDropSack,
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
