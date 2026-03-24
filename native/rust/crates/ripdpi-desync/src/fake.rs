use crate::offset::gen_offset;
use crate::proto::resolve_host_range;
use crate::types::{DesyncError, FakePacketPlan, HostFakeSpan, ProtoInfo};
use ripdpi_config::{DesyncGroup, OffsetProto, TcpChainStep, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI};
use ripdpi_packets::{
    change_tls_sni_seeded_like_c, duplicate_tls_session_id_like_c, http_fake_profile_bytes, is_http,
    is_tls_client_hello, padencap_tls_like_c, randomize_tls_seeded_like_c, randomize_tls_sni_seeded_like_c,
    tls_fake_profile_bytes, tls_marker_info, tune_tls_padding_size_like_c, OracleRng, PacketMutation, IS_HTTP,
    IS_HTTPS,
};

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
    if trimmed.bytes().any(|byte| !(byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-' || byte == b'.'))
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

pub fn build_fake_region_bytes(fake: &FakePacketPlan, stream_offset: usize, len: usize) -> Vec<u8> {
    if len == 0 {
        return Vec::new();
    }
    let stream = fake.bytes.get(fake.fake_offset..).unwrap_or(&[]);
    if stream.is_empty() {
        return vec![0; len];
    }

    (0..len)
        .map(|index| {
            let offset = (stream_offset + index) % stream.len();
            stream[offset]
        })
        .collect()
}

fn apply_tls_mutation(output: &mut Vec<u8>, mutation: PacketMutation) {
    if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
        *output = mutation.bytes;
    }
}

fn tls_sni_capacity(current: &[u8], target_size: usize, new_host: &[u8]) -> usize {
    let Some(markers) = tls_marker_info(current) else {
        return current.len().max(target_size);
    };
    let current_host_len = markers.host_end.saturating_sub(markers.host_start);
    current.len().max(target_size).max(current.len().saturating_add(new_host.len().saturating_sub(current_host_len)))
}

pub(crate) fn normalize_fake_tls_size(value: i32, input_len: usize) -> usize {
    if value <= 0 {
        input_len.saturating_sub((-value) as usize)
    } else {
        value as usize
    }
}

pub fn build_fake_packet(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<FakePacketPlan, DesyncError> {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(seed);
    let fixed_sni = if group.actions.fake_sni_list.is_empty() {
        None
    } else {
        Some(group.actions.fake_sni_list[rng.next_mod(group.actions.fake_sni_list.len())].as_bytes())
    };

    if info.kind == 0 {
        if is_tls_client_hello(input) {
            info.kind = IS_HTTPS;
        } else if is_http(input) {
            info.kind = IS_HTTP;
        }
    }

    let base = if let Some(fake) = &group.actions.fake_data {
        fake.clone()
    } else if info.kind == IS_HTTP {
        http_fake_profile_bytes(group.actions.http_fake_profile).to_vec()
    } else {
        tls_fake_profile_bytes(group.actions.tls_fake_profile).to_vec()
    };

    let fake_tls_target = normalize_fake_tls_size(group.actions.fake_tls_size, input.len());
    let mut output =
        if (group.actions.fake_mod & FM_ORIG) != 0 && info.kind == IS_HTTPS { input.to_vec() } else { base };

    if is_tls_client_hello(&output) {
        if let Some(sni) = fixed_sni {
            let mutation =
                change_tls_sni_seeded_like_c(&output, sni, tls_sni_capacity(&output, fake_tls_target, sni), seed);
            apply_tls_mutation(&mut output, mutation);
        } else if (group.actions.fake_mod & FM_RNDSNI) != 0 {
            let mutation = randomize_tls_sni_seeded_like_c(&output, seed);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.actions.fake_mod & FM_RAND) != 0 {
            let mutation = randomize_tls_seeded_like_c(&output, seed);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.actions.fake_mod & FM_DUPSID) != 0 {
            let mutation = duplicate_tls_session_id_like_c(&output, input);
            apply_tls_mutation(&mut output, mutation);
        }
        if fake_tls_target != output.len() {
            let mutation = tune_tls_padding_size_like_c(&output, fake_tls_target);
            apply_tls_mutation(&mut output, mutation);
        }
        if (group.actions.fake_mod & FM_PADENCAP) != 0 {
            let mutation = padencap_tls_like_c(&output, input.len());
            apply_tls_mutation(&mut output, mutation);
        }
    }

    let fake_offset = group
        .actions
        .fake_offset
        .and_then(|expr| gen_offset(expr, input, input.len(), 0, &mut info, &mut rng))
        .unwrap_or(0);
    let fake_offset = if fake_offset < 0 || fake_offset as usize > output.len() { 0 } else { fake_offset as usize };

    Ok(FakePacketPlan { bytes: output, fake_offset, proto: info })
}
