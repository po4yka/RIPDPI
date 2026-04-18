use crate::offset::gen_offset;
use crate::proto::{init_proto_info, resolve_host_range};
use crate::types::{DesyncError, FakePacketPlan, HostFakeSpan, ProtoInfo};
use ripdpi_config::{
    DesyncGroup, FakePacketSource, OffsetProto, SeqOverlapFakeMode, TcpChainStep, FM_DUPSID, FM_ORIG, FM_PADENCAP,
    FM_RAND, FM_RNDSNI,
};
use ripdpi_packets::{
    change_tls_sni_seeded_like_c, duplicate_tls_session_id_inplace, http_fake_profile_bytes, is_tls_client_hello,
    padencap_tls_into, randomize_tls_seeded_inplace, randomize_tls_sni_seeded_inplace,
    remove_tls_key_share_group_like_c, tls_fake_profile_bytes, tune_tls_padding_size_into, OracleRng, PacketMutation,
    TlsFakeProfile, IS_HTTP, IS_HTTPS,
};

pub fn resolve_hostfake_span(
    step: &TcpChainStep,
    buffer: &[u8],
    step_start: usize,
    step_end: usize,
    seed: u32,
) -> Option<HostFakeSpan> {
    let mut info = ProtoInfo::default();
    let host = resolve_host_range(buffer, &mut info, OffsetProto::Any)?;
    let host_start = host.start;
    let host_end = host.end;
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

pub fn build_hostfake_bytes(real_host: &[u8], template: Option<&str>, seed: u32, random: bool) -> Vec<u8> {
    let mut rng = if random { OracleRng::seeded(rand::random::<u32>()) } else { OracleRng::seeded(seed) };
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

pub fn build_seqovl_fake_prefix(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
    overlap_size: usize,
    fake_mode: SeqOverlapFakeMode,
) -> Result<Vec<u8>, DesyncError> {
    match fake_mode {
        SeqOverlapFakeMode::Profile => {
            let fake = build_fake_packet(group, input, seed)?;
            Ok(build_fake_region_bytes(&fake, 0, overlap_size))
        }
        SeqOverlapFakeMode::Rand => {
            let mut rng = OracleRng::seeded(seed);
            let mut output = vec![0; overlap_size];
            for byte in &mut output {
                *byte = rng.next_u8();
            }
            Ok(output)
        }
    }
}

fn apply_tls_mutation(output: &mut Vec<u8>, mutation: PacketMutation) {
    if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
        *output = mutation.bytes;
    }
}

fn apply_tls_profile_specialization(output: &mut Vec<u8>, tls_fake_profile: TlsFakeProfile) {
    if tls_fake_profile == TlsFakeProfile::GoogleChromeHrr {
        // Controlled HRR-oriented tactic: preserve the browser-family group list
        // but withhold the preferred x25519 share so compliant servers can
        // request a retry instead of treating the fake as generic noise.
        let mutation = remove_tls_key_share_group_like_c(output, 0x001d);
        apply_tls_mutation(output, mutation);
    }
}

fn tls_sni_capacity(current: &[u8], target_size: usize, new_host: &[u8]) -> usize {
    let mut info = ProtoInfo::default();
    let Some(host) = resolve_host_range(current, &mut info, OffsetProto::TlsOnly) else {
        return current.len().max(target_size);
    };
    let current_host_len = host.host.len();
    current.len().max(target_size).max(current.len().saturating_add(new_host.len().saturating_sub(current_host_len)))
}

pub(crate) fn normalize_fake_tls_size(value: i32, input_len: usize) -> usize {
    if value <= 0 {
        input_len.saturating_sub(value.unsigned_abs() as usize)
    } else {
        value as usize
    }
}

fn avoid_tls_517_size(output: &mut Vec<u8>) {
    if output.len() == 517 && is_tls_client_hello(output) {
        let _ = tune_tls_padding_size_into(output, 518);
    }
}

fn build_fake_packet_internal(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
    fake_tls_source: FakePacketSource,
    tls_fake_profile: ripdpi_packets::TlsFakeProfile,
    allow_raw_fake_data: bool,
    allow_orig_override: bool,
) -> Result<FakePacketPlan, DesyncError> {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(seed);
    let fixed_sni = if group.actions.fake_sni_list.is_empty() {
        None
    } else {
        Some(group.actions.fake_sni_list[rng.next_mod(group.actions.fake_sni_list.len())].as_bytes())
    };

    init_proto_info(input, &mut info);

    let base =
        if allow_raw_fake_data { group.actions.fake_data.as_ref().cloned() } else { None }.unwrap_or_else(|| {
            if matches!(fake_tls_source, FakePacketSource::CapturedClientHello) && info.kind == IS_HTTPS {
                input.to_vec()
            } else if info.kind == IS_HTTP {
                http_fake_profile_bytes(group.actions.http_fake_profile).to_vec()
            } else {
                tls_fake_profile_bytes(tls_fake_profile).to_vec()
            }
        });

    let fake_tls_target = normalize_fake_tls_size(group.actions.fake_tls_size, input.len());
    let mut output = if allow_orig_override && (group.actions.fake_mod & FM_ORIG) != 0 && info.kind == IS_HTTPS {
        input.to_vec()
    } else {
        base
    };

    if is_tls_client_hello(&output) {
        apply_tls_profile_specialization(&mut output, tls_fake_profile);
        if let Some(sni) = fixed_sni {
            let mutation =
                change_tls_sni_seeded_like_c(&output, sni, tls_sni_capacity(&output, fake_tls_target, sni), seed);
            apply_tls_mutation(&mut output, mutation);
        } else if (group.actions.fake_mod & FM_RNDSNI) != 0 {
            randomize_tls_sni_seeded_inplace(&mut output, seed);
        }
        if (group.actions.fake_mod & FM_RAND) != 0 {
            randomize_tls_seeded_inplace(&mut output, seed);
        }
        if (group.actions.fake_mod & FM_DUPSID) != 0 {
            duplicate_tls_session_id_inplace(&mut output, input);
        }
        if fake_tls_target != output.len() {
            tune_tls_padding_size_into(&mut output, fake_tls_target);
        }
        avoid_tls_517_size(&mut output);
        if (group.actions.fake_mod & FM_PADENCAP) != 0 {
            padencap_tls_into(&mut output, input.len());
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

pub fn build_fake_packet(group: &DesyncGroup, input: &[u8], seed: u32) -> Result<FakePacketPlan, DesyncError> {
    build_fake_packet_internal(
        group,
        input,
        seed,
        group.actions.fake_tls_source,
        group.actions.tls_fake_profile,
        true,
        true,
    )
}

pub fn build_secondary_fake_packet(
    group: &DesyncGroup,
    input: &[u8],
    seed: u32,
) -> Result<Option<FakePacketPlan>, DesyncError> {
    let Some(secondary_profile) = group.actions.fake_tls_secondary_profile else {
        return Ok(None);
    };
    let mut info = ProtoInfo::default();
    init_proto_info(input, &mut info);
    if info.kind != IS_HTTPS {
        return Ok(None);
    }
    build_fake_packet_internal(group, input, seed, FakePacketSource::Profile, secondary_profile, false, false).map(Some)
}
