#![forbid(unsafe_code)]

use aes::cipher::{generic_array::GenericArray, BlockEncrypt, KeyInit as BlockKeyInit};
use aes::Aes128;
use aes_gcm::aead::AeadInPlace;
use aes_gcm::{Aes128Gcm, Nonce, Tag};
use hkdf::Hkdf;
use sha2::Sha256;

mod fake_profiles;

pub use fake_profiles::{
    http_fake_profile_bytes, tls_fake_profile_bytes, udp_fake_profile_bytes, HttpFakeProfile, TlsFakeProfile,
    UdpFakeProfile,
};

pub const IS_TCP: u32 = 1;
pub const IS_UDP: u32 = 2;
pub const IS_HTTP: u32 = 4;
pub const IS_HTTPS: u32 = 8;
pub const IS_IPV4: u32 = 16;

pub const MH_HMIX: u32 = 1;
pub const MH_SPACE: u32 = 2;
pub const MH_DMIX: u32 = 4;
pub const MH_UNIXEOL: u32 = 8;
pub const MH_METHODEOL: u32 = 16;

const TLS_RECORD_HEADER_LEN: usize = 5;
const QUIC_INITIAL_MIN_LEN: usize = 128;
const QUIC_HP_SAMPLE_LEN: usize = 16;
const QUIC_TAG_LEN: usize = 16;
const QUIC_MAX_CID_LEN: usize = 20;
const QUIC_MAX_CRYPTO_LEN: usize = 64 * 1024;
pub const QUIC_V1_VERSION: u32 = 0x0000_0001;
pub const QUIC_V2_VERSION: u32 = 0x6b33_43cf;
const QUIC_FAKE_INITIAL_TARGET_LEN: usize = 1200;
const QUIC_FAKE_DCID: [u8; 8] = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
const QUIC_FAKE_SCID: [u8; 4] = [0x11, 0x22, 0x33, 0x44];
const QUIC_V1_SALT: [u8; 20] = [
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f,
    0x0a,
];
const QUIC_V2_SALT: [u8; 20] = [
    0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93, 0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e,
    0xd9,
];

pub const DEFAULT_FAKE_TLS: &[u8] = &[
    0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc, 0x03, 0x03, 0x03, 0x5f, 0x6f, 0x2c, 0xed, 0x13, 0x22, 0xf8,
    0xdc, 0xb2, 0xf2, 0x60, 0x48, 0x2d, 0x72, 0x66, 0x6f, 0x57, 0xdd, 0x13, 0x9d, 0x1b, 0x37, 0xdc, 0xfa, 0x36, 0x2e,
    0xba, 0xf9, 0x92, 0x99, 0x3a, 0x20, 0xf9, 0xdf, 0x0c, 0x2e, 0x8a, 0x55, 0x89, 0x82, 0x31, 0x63, 0x1a, 0xef, 0xa8,
    0xbe, 0x08, 0x58, 0xa7, 0xa3, 0x5a, 0x18, 0xd3, 0x96, 0x5f, 0x04, 0x5c, 0xb4, 0x62, 0xaf, 0x89, 0xd7, 0x0f, 0x8b,
    0x00, 0x3e, 0x13, 0x02, 0x13, 0x03, 0x13, 0x01, 0xc0, 0x2c, 0xc0, 0x30, 0x00, 0x9f, 0xcc, 0xa9, 0xcc, 0xa8, 0xcc,
    0xaa, 0xc0, 0x2b, 0xc0, 0x2f, 0x00, 0x9e, 0xc0, 0x24, 0xc0, 0x28, 0x00, 0x6b, 0xc0, 0x23, 0xc0, 0x27, 0x00, 0x67,
    0xc0, 0x0a, 0xc0, 0x14, 0x00, 0x39, 0xc0, 0x09, 0xc0, 0x13, 0x00, 0x33, 0x00, 0x9d, 0x00, 0x9c, 0x00, 0x3d, 0x00,
    0x3c, 0x00, 0x35, 0x00, 0x2f, 0x00, 0xff, 0x01, 0x00, 0x01, 0x75, 0x00, 0x00, 0x00, 0x16, 0x00, 0x14, 0x00, 0x00,
    0x11, 0x77, 0x77, 0x77, 0x2e, 0x77, 0x69, 0x6b, 0x69, 0x70, 0x65, 0x64, 0x69, 0x61, 0x2e, 0x6f, 0x72, 0x67, 0x00,
    0x0b, 0x00, 0x04, 0x03, 0x00, 0x01, 0x02, 0x00, 0x0a, 0x00, 0x16, 0x00, 0x14, 0x00, 0x1d, 0x00, 0x17, 0x00, 0x1e,
    0x00, 0x19, 0x00, 0x18, 0x01, 0x00, 0x01, 0x01, 0x01, 0x02, 0x01, 0x03, 0x01, 0x04, 0x00, 0x10, 0x00, 0x0e, 0x00,
    0x0c, 0x02, 0x68, 0x32, 0x08, 0x68, 0x74, 0x74, 0x70, 0x2f, 0x31, 0x2e, 0x31, 0x00, 0x16, 0x00, 0x00, 0x00, 0x17,
    0x00, 0x00, 0x00, 0x31, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x2a, 0x00, 0x28, 0x04, 0x03, 0x05, 0x03, 0x06, 0x03, 0x08,
    0x07, 0x08, 0x08, 0x08, 0x09, 0x08, 0x0a, 0x08, 0x0b, 0x08, 0x04, 0x08, 0x05, 0x08, 0x06, 0x04, 0x01, 0x05, 0x01,
    0x06, 0x01, 0x03, 0x03, 0x03, 0x01, 0x03, 0x02, 0x04, 0x02, 0x05, 0x02, 0x06, 0x02, 0x00, 0x2b, 0x00, 0x09, 0x08,
    0x03, 0x04, 0x03, 0x03, 0x03, 0x02, 0x03, 0x01, 0x00, 0x2d, 0x00, 0x02, 0x01, 0x01, 0x00, 0x33, 0x00, 0x26, 0x00,
    0x24, 0x00, 0x1d, 0x00, 0x20, 0x11, 0x8c, 0xb8, 0x8c, 0xe8, 0x8a, 0x08, 0x90, 0x1e, 0xee, 0x19, 0xd9, 0xdd, 0xe8,
    0xd4, 0x06, 0xb1, 0xd1, 0xe2, 0xab, 0xe0, 0x16, 0x63, 0xd6, 0xdc, 0xda, 0x84, 0xa4, 0xb8, 0x4b, 0xfb, 0x0e, 0x00,
    0x15, 0x00, 0xac, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
];

pub const DEFAULT_FAKE_HTTP: &[u8] = b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n";
pub const DEFAULT_FAKE_UDP: &[u8] = &[0; 64];
pub const DEFAULT_FAKE_QUIC_COMPAT_LEN: usize = 620;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HttpHost<'a> {
    pub host: &'a [u8],
    pub port: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HttpMarkerInfo {
    pub method_start: usize,
    pub host_start: usize,
    pub host_end: usize,
    pub port: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TlsMarkerInfo {
    pub ext_len_start: usize,
    pub sni_ext_start: usize,
    pub host_start: usize,
    pub host_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QuicInitialInfo {
    pub version: u32,
    pub client_hello: Vec<u8>,
    pub tls_info: TlsMarkerInfo,
    pub is_crypto_complete: bool,
}

impl QuicInitialInfo {
    pub fn host(&self) -> &[u8] {
        &self.client_hello[self.tls_info.host_start..self.tls_info.host_end]
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PacketMutation {
    pub rc: isize,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OracleRng {
    state: u32,
}

impl OracleRng {
    pub const fn seeded(seed: u32) -> Self {
        Self { state: seed }
    }

    pub fn next_raw(&mut self) -> u32 {
        self.state = self.state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        (self.state >> 16) & 0x7fff
    }

    pub fn next_u8(&mut self) -> u8 {
        (self.next_raw() & 0xff) as u8
    }

    pub fn next_mod(&mut self, modulus: usize) -> usize {
        if modulus == 0 {
            return 0;
        }
        (self.next_raw() as usize) % modulus
    }
}

#[derive(Debug, Clone, Copy)]
struct HttpParts {
    method_start: usize,
    header_name_start: usize,
    host_start: usize,
    host_end: usize,
    port: u16,
}

#[derive(Debug, Clone, Copy)]
struct HttpHeaderLine {
    start: usize,
    end: usize,
    value_start: usize,
}

#[derive(Debug, Clone)]
struct HttpRequestLayout {
    method_start: usize,
    request_line_end: usize,
    header_lines: Vec<HttpHeaderLine>,
    user_agent_index: Option<usize>,
    body_start: usize,
}

#[derive(Debug, Clone, Copy)]
struct QuicInitialHeader<'a> {
    version: u32,
    dcid: &'a [u8],
    payload_len: usize,
    pn_offset: usize,
}

fn read_u16(data: &[u8], offset: usize) -> Option<usize> {
    if offset + 1 >= data.len() {
        return None;
    }
    Some(((data[offset] as usize) << 8) | data[offset + 1] as usize)
}

fn read_u24(data: &[u8], offset: usize) -> Option<usize> {
    if offset + 2 >= data.len() {
        return None;
    }
    Some(((data[offset] as usize) << 16) | ((data[offset + 1] as usize) << 8) | data[offset + 2] as usize)
}

fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    let bytes = data.get(offset..offset + 4)?;
    Some(u32::from_be_bytes(bytes.try_into().ok()?))
}

fn write_u16(data: &mut [u8], offset: usize, value: usize) -> bool {
    if offset + 1 >= data.len() || value > u16::MAX as usize {
        return false;
    }
    data[offset] = ((value >> 8) & 0xff) as u8;
    data[offset + 1] = (value & 0xff) as u8;
    true
}

fn write_u24(data: &mut [u8], offset: usize, value: usize) -> bool {
    if offset + 2 >= data.len() || value > 0x00ff_ffff {
        return false;
    }
    data[offset] = ((value >> 16) & 0xff) as u8;
    data[offset + 1] = ((value >> 8) & 0xff) as u8;
    data[offset + 2] = (value & 0xff) as u8;
    true
}

fn find_tls_ext_offset(kind: u16, data: &[u8], mut skip: usize) -> Option<usize> {
    if data.len() <= skip + 2 {
        return None;
    }
    let ext_len = read_u16(data, skip)?;
    skip += 2;
    let mut size = data.len();
    if ext_len < size.saturating_sub(skip) {
        size = ext_len + skip;
    }
    while skip + 4 < size {
        let curr = read_u16(data, skip)? as u16;
        if curr == kind {
            return Some(skip);
        }
        skip += read_u16(data, skip + 2)? + 4;
    }
    None
}

fn find_tls_ext_len_offset_in_handshake(data: &[u8]) -> Option<usize> {
    let mut offset = 1 + 3 + 2 + 32;
    let sid_len = *data.get(offset)? as usize;
    offset += 1 + sid_len;
    let cipher_len = read_u16(data, offset)?;
    offset += 2 + cipher_len;
    let compression_len = *data.get(offset)? as usize;
    offset += 1 + compression_len;
    if offset + 1 >= data.len() {
        return None;
    }
    Some(offset)
}

fn find_tls_ext_len_offset(data: &[u8]) -> Option<usize> {
    Some(find_tls_ext_len_offset_in_handshake(data.get(TLS_RECORD_HEADER_LEN..)?)? + TLS_RECORD_HEADER_LEN)
}

fn find_ext_block(data: &[u8]) -> Option<usize> {
    find_tls_ext_len_offset(data)
}

fn adjust_tls_lengths(buffer: &mut [u8], ext_len_start: usize, delta: isize) -> bool {
    let Some(record_len) = read_u16(buffer, 3).map(|value| value as isize) else {
        return false;
    };
    let Some(handshake_len) = read_u24(buffer, 6).map(|value| value as isize) else {
        return false;
    };
    let Some(ext_len) = read_u16(buffer, ext_len_start).map(|value| value as isize) else {
        return false;
    };

    let record_len = record_len + delta;
    let handshake_len = handshake_len + delta;
    let ext_len = ext_len + delta;
    if record_len < 0 || handshake_len < 0 || ext_len < 0 {
        return false;
    }
    write_u16(buffer, 3, record_len as usize)
        && write_u24(buffer, 6, handshake_len as usize)
        && write_u16(buffer, ext_len_start, ext_len as usize)
}

fn fill_random_alnum(byte: &mut u8, rng: &mut OracleRng) {
    let roll = rng.next_mod(36);
    *byte = if roll < 10 { b'0' + roll as u8 } else { b'a' + (roll as u8 - 10) };
}

fn fill_random_lower(byte: &mut u8, rng: &mut OracleRng) {
    *byte = b'a' + (rng.next_u8() % 26);
}

fn fill_random_tls_host_like_c(host: &mut [u8], rng: &mut OracleRng) {
    const RANDOM_TLDS: [&[u8; 3]; 8] = [b"com", b"net", b"org", b"edu", b"gov", b"mil", b"int", b"biz"];
    if host.is_empty() {
        return;
    }
    fill_random_lower(&mut host[0], rng);
    let len = host.len();
    if len >= 7 {
        for byte in &mut host[1..len - 4] {
            fill_random_alnum(byte, rng);
        }
        host[len - 4] = b'.';
        host[len - 3..].copy_from_slice(RANDOM_TLDS[rng.next_mod(RANDOM_TLDS.len())]);
    } else {
        for byte in &mut host[1..] {
            fill_random_alnum(byte, rng);
        }
    }
}

fn ascii_case_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b.iter()).all(|(left, right)| left.eq_ignore_ascii_case(right))
}

fn strncase_find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|window| ascii_case_eq(window, needle))
}

fn parse_u16_ascii(data: &[u8]) -> Option<u16> {
    std::str::from_utf8(data).ok()?.parse().ok()
}

fn tls_client_hello_marker_info_in_handshake(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    if buffer.first().copied() != Some(0x01) {
        return None;
    }
    let handshake_len = read_u24(buffer, 1)?;
    let client_hello = buffer.get(..4 + handshake_len)?;
    let ext_len_start = find_tls_ext_len_offset_in_handshake(client_hello)?;
    let sni_ext_offset = find_tls_ext_offset(0x0000, client_hello, ext_len_start)?;
    if sni_ext_offset + 12 >= client_hello.len() {
        return None;
    }
    let host_len = read_u16(client_hello, sni_ext_offset + 7)?;
    let host_start = sni_ext_offset + 9;
    let host_end = host_start + host_len;
    if host_end > client_hello.len() {
        return None;
    }
    Some(TlsMarkerInfo { ext_len_start, sni_ext_start: sni_ext_offset + 4, host_start, host_end })
}

fn tls_client_hello_marker_info_in_record(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    if !is_tls_client_hello(buffer) {
        return None;
    }
    let ext_len_start = find_tls_ext_len_offset(buffer)?;
    let sni_ext_offset = find_tls_ext_offset(0x0000, buffer, ext_len_start)?;
    if sni_ext_offset + 12 >= buffer.len() {
        return None;
    }
    let host_len = read_u16(buffer, sni_ext_offset + 7)?;
    let host_start = sni_ext_offset + 9;
    let host_end = host_start + host_len;
    if host_end > buffer.len() {
        return None;
    }
    Some(TlsMarkerInfo { ext_len_start, sni_ext_start: sni_ext_offset + 4, host_start, host_end })
}

fn http_method_start(buffer: &[u8]) -> Option<usize> {
    if buffer.len() < 16 {
        return None;
    }
    let mut start = 0usize;
    for _ in 0..2 {
        match buffer.get(start) {
            Some(b'\r' | b'\n') => start += 1,
            _ => break,
        }
    }
    const METHODS: &[&[u8]] =
        &[b"HEAD ", b"GET ", b"POST ", b"PUT ", b"DELETE ", b"OPTIONS ", b"CONNECT ", b"TRACE ", b"PATCH "];
    METHODS.iter().any(|method| buffer[start..].starts_with(method)).then_some(start)
}

fn parse_http_parts(buffer: &[u8]) -> Option<HttpParts> {
    let method_start = http_method_start(buffer)?;
    let marker = strncase_find(buffer, b"\nHost:")?;
    let header_name_start = marker + 1;
    let mut host_start = marker + 6;
    while host_start < buffer.len() && buffer[host_start] == b' ' {
        host_start += 1;
    }
    let line_end = host_start + buffer[host_start..].iter().position(|&byte| byte == b'\n')?;
    let mut trimmed_end = line_end;
    while trimmed_end > host_start && buffer[trimmed_end - 1].is_ascii_whitespace() {
        trimmed_end -= 1;
    }
    if trimmed_end <= host_start {
        return None;
    }

    let mut host_end = trimmed_end;
    let mut digit_start = trimmed_end;
    while digit_start > host_start && buffer[digit_start - 1].is_ascii_digit() {
        digit_start -= 1;
    }
    let port = if digit_start < trimmed_end && digit_start > host_start && buffer[digit_start - 1] == b':' {
        host_end = digit_start - 1;
        parse_u16_ascii(&buffer[digit_start..trimmed_end])?
    } else {
        80
    };

    if buffer.get(host_start) == Some(&b'[') {
        if host_end <= host_start + 1 || buffer[host_end - 1] != b']' {
            return None;
        }
        host_start += 1;
        host_end -= 1;
    }
    if host_end <= host_start {
        return None;
    }

    Some(HttpParts { method_start, header_name_start, host_start, host_end, port })
}

fn next_http_line_bounds(buffer: &[u8], start: usize) -> Option<(usize, usize)> {
    let line_feed = buffer[start..].iter().position(|&byte| byte == b'\n')? + start;
    let line_end = if line_feed > start && buffer[line_feed - 1] == b'\r' { line_feed - 1 } else { line_feed };
    Some((line_end, line_feed + 1))
}

fn parse_http_request_layout(buffer: &[u8]) -> Option<HttpRequestLayout> {
    let method_start = http_method_start(buffer)?;
    let (request_line_end, mut cursor) = next_http_line_bounds(buffer, method_start)?;
    let mut header_lines = Vec::new();
    let mut user_agent_index = None;

    loop {
        let (line_end, next_start) = next_http_line_bounds(buffer, cursor)?;
        if line_end == cursor {
            return Some(HttpRequestLayout {
                method_start,
                request_line_end,
                header_lines,
                user_agent_index,
                body_start: next_start,
            });
        }

        let raw_line = &buffer[cursor..line_end];
        let colon = raw_line.iter().position(|&byte| byte == b':')?;
        let mut value_start = cursor + colon + 1;
        while value_start < line_end && matches!(buffer[value_start], b' ' | b'\t') {
            value_start += 1;
        }
        if raw_line[..colon].eq_ignore_ascii_case(b"user-agent") {
            user_agent_index = Some(header_lines.len());
        }
        header_lines.push(HttpHeaderLine { start: cursor, end: line_end, value_start });
        cursor = next_start;
    }
}

pub fn second_level_domain_span(host: &[u8]) -> Option<(usize, usize)> {
    if host.is_empty() {
        return None;
    }
    let mut end = host.len();
    for _ in 1..2 {
        while end > 0 && host[end - 1] != b'.' {
            end -= 1;
        }
        if end == 0 {
            return None;
        }
        end -= 1;
    }
    let mut start = end;
    while start > 0 && host[start - 1] != b'.' {
        start -= 1;
    }
    Some((start, end))
}

pub fn http_marker_info(buffer: &[u8]) -> Option<HttpMarkerInfo> {
    let parts = parse_http_parts(buffer)?;
    Some(HttpMarkerInfo {
        method_start: parts.method_start,
        host_start: parts.host_start,
        host_end: parts.host_end,
        port: parts.port,
    })
}

pub fn tls_marker_info(buffer: &[u8]) -> Option<TlsMarkerInfo> {
    tls_client_hello_marker_info_in_record(buffer)
}

fn get_http_code(data: &[u8]) -> Option<u16> {
    if data.len() < 13 || &data[..7] != b"HTTP/1." || !data[12..].contains(&b'\n') {
        return None;
    }
    let digits_end = data[9..].iter().position(|byte| byte.is_ascii_whitespace()).map(|idx| idx + 9)?;
    let code = parse_u16_ascii(&data[9..digits_end])?;
    if !(100..=511).contains(&code) {
        return None;
    }
    Some(code)
}

fn copy_name_seeded(out: &mut [u8], pattern: &[u8], rng: &mut OracleRng) {
    for (dst, src) in out.iter_mut().zip(pattern.iter().copied()) {
        *dst = match src {
            b'*' => {
                let roll = (rng.next_u8() as usize) % (10 + (b'z' - b'a' + 1) as usize);
                if roll < 10 {
                    b'0' + roll as u8
                } else {
                    b'a' + (roll as u8 - 10)
                }
            }
            b'?' => b'a' + (rng.next_u8() % (b'z' - b'a' + 1)),
            b'#' => b'0' + (rng.next_u8() % 10),
            other => other,
        };
    }
}

fn merge_tls_records(buffer: &mut [u8], n: usize) -> usize {
    if n < 5 {
        return 0;
    }
    let Some(mut record_size) = read_u16(buffer, 3) else {
        return 0;
    };
    let mut full_size = 0usize;
    let mut removed = 0usize;

    loop {
        full_size += record_size;
        if 5 + full_size > n.saturating_sub(5) || buffer[5 + full_size] != buffer[0] {
            break;
        }
        let Some(next_record_size) = read_u16(buffer, 5 + full_size + 3) else {
            break;
        };
        if full_size + 10 + next_record_size > n {
            break;
        }
        buffer.copy_within(10 + full_size..n, 5 + full_size);
        removed += 5;
        record_size = next_record_size;
    }

    let _ = write_u16(buffer, 3, full_size);
    let _ = write_u16(buffer, 7, full_size.saturating_sub(4));
    removed
}

fn remove_ks_group(buffer: &mut [u8], n: usize, skip: usize, group: u16) -> usize {
    let Some(ks_offs) = find_tls_ext_offset(0x0033, &buffer[..n], skip) else {
        return 0;
    };
    if ks_offs + 6 >= n {
        return 0;
    }
    let Some(ks_size) = read_u16(buffer, ks_offs + 2) else {
        return 0;
    };
    if ks_offs + 4 + ks_size > n {
        return 0;
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(buffer, group_offs + 2) else {
            return 0;
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > n {
            return 0;
        }
        let Some(group_type) = read_u16(buffer, group_offs).map(|value| value as u16) else {
            return 0;
        };
        if group_type == group {
            buffer.copy_within(group_end..n, group_offs);
            let new_size = ks_size.saturating_sub(4 + group_size);
            let _ = write_u16(buffer, ks_offs + 2, new_size);
            let _ = write_u16(buffer, ks_offs + 4, new_size.saturating_sub(2));
            return 4 + group_size;
        }
        group_offs += 4 + group_size;
    }
    0
}

fn remove_tls_ext(buffer: &mut [u8], n: usize, skip: usize, kind: u16) -> usize {
    let Some(ext_offs) = find_tls_ext_offset(kind, &buffer[..n], skip) else {
        return 0;
    };
    let Some(ext_size) = read_u16(buffer, ext_offs + 2) else {
        return 0;
    };
    let ext_end = ext_offs + 4 + ext_size;
    if ext_end > n {
        return 0;
    }
    buffer.copy_within(ext_end..n, ext_offs);
    ext_size + 4
}

fn resize_ech_ext(buffer: &mut [u8], n: usize, skip: usize, mut inc: isize) -> isize {
    let Some(ech_offs) = find_tls_ext_offset(0xfe0d, &buffer[..n], skip) else {
        return 0;
    };
    let Some(ech_size) = read_u16(buffer, ech_offs + 2).map(|value| value as isize) else {
        return 0;
    };
    let ech_end = ech_offs as isize + 4 + ech_size;
    if ech_size < 12 || ech_end as usize > n {
        return 0;
    }
    let Some(enc_size) = read_u16(buffer, ech_offs + 10).map(|value| value as isize) else {
        return 0;
    };
    let payload_offs = ech_offs as isize + 12 + enc_size;
    let payload_size = ech_size - (8 + enc_size + 2);
    if payload_offs + 2 > n as isize {
        return 0;
    }
    if payload_size < -inc {
        inc = -payload_size;
    }
    if ech_size + inc < 0 || payload_size + inc < 0 {
        return 0;
    }
    let dest = ech_end + inc;
    let tail_len = n.saturating_sub(ech_end as usize);
    if dest < 0 || dest as usize > buffer.len().saturating_sub(tail_len) {
        return 0;
    }
    let _ = write_u16(buffer, ech_offs + 2, (ech_size + inc) as usize);
    let _ = write_u16(buffer, payload_offs as usize, (payload_size + inc) as usize);
    buffer.copy_within(ech_end as usize..n, dest as usize);
    inc
}

fn resize_sni(buffer: &mut [u8], n: usize, sni_offs: usize, sni_size: usize, new_size: usize) -> bool {
    let delta = new_size as isize - (sni_size as isize - 5);
    let sni_end = sni_offs + 4 + sni_size;
    if sni_end > n {
        return false;
    }
    let dest = sni_end as isize + delta;
    let tail_len = n.saturating_sub(sni_end);
    if dest < 0 || dest as usize > buffer.len().saturating_sub(tail_len) {
        return false;
    }
    if !write_u16(buffer, sni_offs + 2, new_size + 5)
        || !write_u16(buffer, sni_offs + 4, new_size + 3)
        || !write_u16(buffer, sni_offs + 7, new_size)
    {
        return false;
    }
    buffer.copy_within(sni_end..n, dest as usize);
    true
}

pub fn is_tls_client_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x01
}

pub fn is_tls_server_hello(buffer: &[u8]) -> bool {
    buffer.len() > 5 && read_u16(buffer, 0) == Some(0x1603) && buffer[5] == 0x02
}

pub fn parse_tls(buffer: &[u8]) -> Option<&[u8]> {
    let markers = tls_client_hello_marker_info_in_record(buffer)?;
    Some(&buffer[markers.host_start..markers.host_end])
}

fn is_quic_v2(version: u32) -> bool {
    version == QUIC_V2_VERSION
}

fn supported_quic_version(version: u32) -> bool {
    matches!(version, QUIC_V1_VERSION | QUIC_V2_VERSION)
}

fn quic_hkdf_label(label: &str, out_len: usize) -> Option<Vec<u8>> {
    if out_len > u16::MAX as usize || label.len() > u8::MAX as usize {
        return None;
    }
    let mut info = Vec::with_capacity(2 + 1 + label.len() + 1);
    info.extend_from_slice(&(out_len as u16).to_be_bytes());
    info.push(label.len() as u8);
    info.extend_from_slice(label.as_bytes());
    info.push(0);
    Some(info)
}

fn quic_expand_label(secret: &[u8], label: &str, out: &mut [u8]) -> Option<()> {
    let info = quic_hkdf_label(label, out.len())?;
    let hkdf = Hkdf::<Sha256>::from_prk(secret).ok()?;
    hkdf.expand(&info, out).ok()?;
    Some(())
}

fn quic_derive_client_initial_secret(dcid: &[u8], version: u32) -> Option<[u8; 32]> {
    let salt = match version {
        QUIC_V1_VERSION => &QUIC_V1_SALT,
        QUIC_V2_VERSION => &QUIC_V2_SALT,
        _ => return None,
    };
    let hkdf = Hkdf::<Sha256>::new(Some(salt), dcid);
    let mut secret = [0u8; 32];
    let info = quic_hkdf_label("tls13 client in", secret.len())?;
    hkdf.expand(&info, &mut secret).ok()?;
    Some(secret)
}

fn read_quic_varint(data: &[u8], offset: usize) -> Option<(u64, usize)> {
    let first = *data.get(offset)?;
    let len = 1usize << ((first >> 6) as usize);
    let bytes = data.get(offset..offset + len)?;
    let mut value = (bytes[0] & 0x3f) as u64;
    for byte in &bytes[1..] {
        value = (value << 8) | u64::from(*byte);
    }
    Some((value, len))
}

fn encode_quic_varint(value: u64) -> Vec<u8> {
    match value {
        0..=63 => vec![value as u8],
        64..=16_383 => ((0x4000 | value as u16).to_be_bytes()).to_vec(),
        16_384..=1_073_741_823 => ((0x8000_0000 | value as u32).to_be_bytes()).to_vec(),
        _ => {
            let mut bytes = value.to_be_bytes();
            bytes[0] |= 0xc0;
            bytes.to_vec()
        }
    }
}

fn append_quic_crypto_frame(out: &mut Vec<u8>, offset: u64, data: &[u8]) {
    out.push(0x06);
    out.extend_from_slice(&encode_quic_varint(offset));
    out.extend_from_slice(&encode_quic_varint(data.len() as u64));
    out.extend_from_slice(data);
}

pub fn default_fake_quic_compat() -> Vec<u8> {
    let mut packet = vec![0; DEFAULT_FAKE_QUIC_COMPAT_LEN];
    packet[0] = 0x40;
    packet
}

pub fn build_quic_initial_from_tls(version: u32, tls_client_hello: &[u8], gap_after_split: usize) -> Option<Vec<u8>> {
    let version = if supported_quic_version(version) { version } else { QUIC_V1_VERSION };
    if !is_tls_client_hello(tls_client_hello) || tls_client_hello.len() <= TLS_RECORD_HEADER_LEN {
        return None;
    }

    let crypto = tls_client_hello.get(TLS_RECORD_HEADER_LEN..)?.to_vec();
    let split = crypto.len() / 2;
    let mut plaintext = Vec::new();
    append_quic_crypto_frame(&mut plaintext, 0, &crypto[..split]);
    append_quic_crypto_frame(&mut plaintext, (split + gap_after_split) as u64, &crypto[split..]);

    loop {
        let payload_len = 4 + plaintext.len() + QUIC_TAG_LEN;
        let payload_len_varint = encode_quic_varint(payload_len as u64);
        let header_len = 1 + 4 + 1 + QUIC_FAKE_DCID.len() + 1 + QUIC_FAKE_SCID.len() + 1 + payload_len_varint.len();
        let total_len = header_len + payload_len;
        if total_len >= QUIC_FAKE_INITIAL_TARGET_LEN {
            break;
        }
        plaintext.extend(std::iter::repeat_n(0u8, QUIC_FAKE_INITIAL_TARGET_LEN - total_len));
    }

    let payload_len = 4 + plaintext.len() + QUIC_TAG_LEN;
    let payload_len_varint = encode_quic_varint(payload_len as u64);
    let first_byte = if version == QUIC_V2_VERSION { 0xd3 } else { 0xc3 };

    let mut header = Vec::new();
    header.push(first_byte);
    header.extend_from_slice(&version.to_be_bytes());
    header.push(QUIC_FAKE_DCID.len() as u8);
    header.extend_from_slice(&QUIC_FAKE_DCID);
    header.push(QUIC_FAKE_SCID.len() as u8);
    header.extend_from_slice(&QUIC_FAKE_SCID);
    header.push(0);
    header.extend_from_slice(&payload_len_varint);

    let packet_number = [0u8; 4];
    let mut aad = header.clone();
    aad.extend_from_slice(&packet_number);

    let secret = quic_derive_client_initial_secret(&QUIC_FAKE_DCID, version)?;
    let (key_label, iv_label, hp_label) = if version == QUIC_V2_VERSION {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    quic_expand_label(&secret, key_label, &mut key)?;
    quic_expand_label(&secret, iv_label, &mut iv)?;
    quic_expand_label(&secret, hp_label, &mut hp)?;

    let cipher = Aes128Gcm::new_from_slice(&key).ok()?;
    let mut ciphertext = plaintext;
    let tag = cipher.encrypt_in_place_detached(Nonce::from_slice(&iv), &aad, &mut ciphertext).ok()?;

    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample = GenericArray::clone_from_slice(ciphertext.get(..QUIC_HP_SAMPLE_LEN)?);
    hp_cipher.encrypt_block(&mut sample);

    let mut packet = header;
    packet.extend((0..4).map(|idx| packet_number[idx] ^ sample[1 + idx]));
    packet.extend_from_slice(&ciphertext);
    packet.extend_from_slice(&tag);
    packet[0] ^= sample[0] & 0x0f;
    Some(packet)
}

fn padded_default_fake_tls_client_hello() -> Vec<u8> {
    let mut client_hello = DEFAULT_FAKE_TLS.to_vec();
    let target_len = read_u16(DEFAULT_FAKE_TLS, 3)
        .map(|record_len| record_len + TLS_RECORD_HEADER_LEN)
        .unwrap_or(client_hello.len());
    if client_hello.len() < target_len {
        client_hello.resize(target_len, 0);
    }
    client_hello
}

pub fn build_realistic_quic_initial(version: u32, host_override: Option<&str>) -> Option<Vec<u8>> {
    let mut client_hello = padded_default_fake_tls_client_hello();
    if let Some(host) = host_override {
        let capacity = client_hello.len().saturating_add(host.len()).saturating_add(64);
        let mutation = change_tls_sni_seeded_like_c(&client_hello, host.as_bytes(), capacity, 7);
        if mutation.rc == 0 && is_tls_client_hello(&mutation.bytes) {
            client_hello = mutation.bytes;
        }
    }
    build_quic_initial_from_tls(version, &client_hello, 0)
}

fn parse_quic_initial_header(buffer: &[u8]) -> Option<QuicInitialHeader<'_>> {
    if buffer.len() < QUIC_INITIAL_MIN_LEN || (buffer[0] & 0x80) == 0 || (buffer[0] & 0x40) == 0 {
        return None;
    }
    let version = read_u32(buffer, 1)?;
    if !supported_quic_version(version) {
        return None;
    }
    let expected_prefix = if is_quic_v2(version) { 0xd0 } else { 0xc0 };
    if (buffer[0] & 0xf0) != expected_prefix {
        return None;
    }

    let dcid_len = *buffer.get(5)? as usize;
    if dcid_len == 0 || dcid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    let dcid = buffer.get(6..6 + dcid_len)?;

    let mut offset = 6 + dcid_len;
    let scid_len = *buffer.get(offset)? as usize;
    if scid_len > QUIC_MAX_CID_LEN {
        return None;
    }
    offset += 1;
    buffer.get(offset..offset + scid_len)?;
    offset += scid_len;

    let (token_len, token_varint_len) = read_quic_varint(buffer, offset)?;
    offset += token_varint_len;
    let token_len: usize = token_len.try_into().ok()?;
    buffer.get(offset..offset + token_len)?;
    offset += token_len;

    let (payload_len, payload_varint_len) = read_quic_varint(buffer, offset)?;
    offset += payload_varint_len;
    let payload_len: usize = payload_len.try_into().ok()?;
    buffer.get(offset..offset + payload_len)?;

    Some(QuicInitialHeader { version, dcid, payload_len, pn_offset: offset })
}

fn decrypt_quic_initial_payload(buffer: &[u8], header: QuicInitialHeader<'_>) -> Option<Vec<u8>> {
    let secret = quic_derive_client_initial_secret(header.dcid, header.version)?;
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    let (key_label, iv_label, hp_label) = if is_quic_v2(header.version) {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    quic_expand_label(&secret, key_label, &mut key)?;
    quic_expand_label(&secret, iv_label, &mut iv)?;
    quic_expand_label(&secret, hp_label, &mut hp)?;

    let sample = buffer.get(header.pn_offset + 4..header.pn_offset + 4 + QUIC_HP_SAMPLE_LEN)?;
    let hp_cipher = Aes128::new_from_slice(&hp).ok()?;
    let mut sample_block = GenericArray::clone_from_slice(sample);
    hp_cipher.encrypt_block(&mut sample_block);

    let unprotected_first = buffer[0] ^ (sample_block[0] & 0x0f);
    let pn_len = ((unprotected_first & 0x03) + 1) as usize;
    let protected_pn = buffer.get(header.pn_offset..header.pn_offset + pn_len)?;
    let mut packet_number_bytes = [0u8; 4];
    let mut unprotected_pn = [0u8; 4];
    for idx in 0..pn_len {
        let value = protected_pn[idx] ^ sample_block[1 + idx];
        packet_number_bytes[4 - pn_len + idx] = value;
        unprotected_pn[4 - pn_len + idx] = value;
    }
    let packet_number = u32::from_be_bytes(packet_number_bytes);

    let ciphertext_len = header.payload_len.checked_sub(pn_len + QUIC_TAG_LEN)?;
    let ciphertext = buffer.get(header.pn_offset + pn_len..header.pn_offset + pn_len + ciphertext_len)?.to_vec();
    let tag = buffer.get(header.pn_offset + pn_len + ciphertext_len..header.pn_offset + header.payload_len)?;

    let mut aad = buffer.get(..header.pn_offset + pn_len)?.to_vec();
    aad[0] = unprotected_first;
    aad[header.pn_offset..header.pn_offset + pn_len].copy_from_slice(&unprotected_pn[4 - pn_len..]);

    let mut nonce_bytes = iv;
    let packet_number = u64::from(packet_number).to_be_bytes();
    for (slot, byte) in nonce_bytes[4..].iter_mut().zip(packet_number) {
        *slot ^= byte;
    }

    let cipher = Aes128Gcm::new_from_slice(&key).ok()?;
    let mut plaintext = ciphertext;
    cipher
        .decrypt_in_place_detached(Nonce::from_slice(&nonce_bytes), &aad, &mut plaintext, Tag::from_slice(tag))
        .ok()?;
    Some(plaintext)
}

fn defrag_quic_crypto_frames(payload: &[u8]) -> Option<(Vec<u8>, bool)> {
    let mut pieces = Vec::new();
    let mut cursor = 0usize;
    let mut max_end = 0usize;

    while cursor < payload.len() {
        match payload[cursor] {
            0x00 | 0x01 => {
                cursor += 1;
            }
            0x06 => {
                cursor += 1;
                let (offset, offset_len) = read_quic_varint(payload, cursor)?;
                cursor += offset_len;
                let (frame_len, frame_len_len) = read_quic_varint(payload, cursor)?;
                cursor += frame_len_len;
                let offset: usize = offset.try_into().ok()?;
                let frame_len: usize = frame_len.try_into().ok()?;
                let end = cursor.checked_add(frame_len)?;
                let chunk = payload.get(cursor..end)?;
                cursor = end;
                let piece_end = offset.checked_add(frame_len)?;
                if piece_end > QUIC_MAX_CRYPTO_LEN {
                    return None;
                }
                max_end = max_end.max(piece_end);
                pieces.push((offset, chunk.to_vec()));
            }
            _ => return None,
        }
    }

    if pieces.is_empty() || max_end == 0 {
        return None;
    }

    let mut data = vec![0u8; max_end];
    let mut covered = vec![false; max_end];
    for (offset, chunk) in pieces {
        let end = offset + chunk.len();
        data[offset..end].copy_from_slice(&chunk);
        covered[offset..end].fill(true);
    }

    Some((data, covered.iter().all(|covered| *covered)))
}

pub fn is_quic_initial(buffer: &[u8]) -> bool {
    parse_quic_initial_header(buffer).is_some()
}

pub fn parse_quic_initial(buffer: &[u8]) -> Option<QuicInitialInfo> {
    let header = parse_quic_initial_header(buffer)?;
    let payload = decrypt_quic_initial_payload(buffer, header)?;
    let (client_hello, is_crypto_complete) = defrag_quic_crypto_frames(&payload)?;
    if !is_crypto_complete {
        return None;
    }
    let tls_info = tls_client_hello_marker_info_in_handshake(&client_hello)?;
    Some(QuicInitialInfo { version: header.version, client_hello, tls_info, is_crypto_complete })
}

pub fn is_http(buffer: &[u8]) -> bool {
    http_method_start(buffer).is_some()
}

pub fn parse_http(buffer: &[u8]) -> Option<HttpHost<'_>> {
    let markers = http_marker_info(buffer)?;
    Some(HttpHost { host: &buffer[markers.host_start..markers.host_end], port: markers.port })
}

pub fn is_http_redirect(req: &[u8], resp: &[u8]) -> bool {
    let Some(host) = parse_http(req).map(|parsed| parsed.host) else {
        return false;
    };
    if resp.len() < 29 {
        return false;
    }
    let Some(code) = get_http_code(resp) else {
        return false;
    };
    if !(300..=308).contains(&code) {
        return false;
    }
    let Some(location_marker) = strncase_find(resp, b"\nLocation:") else {
        return false;
    };
    let mut location_start = location_marker + 11;
    if location_start + 8 >= resp.len() {
        return false;
    }
    let Some(line_end_rel) = resp[location_start..].iter().position(|&byte| byte == b'\n') else {
        return false;
    };
    let mut line_end = location_start + line_end_rel;
    while line_end > location_start && resp[line_end - 1].is_ascii_whitespace() {
        line_end -= 1;
    }
    if line_end.saturating_sub(location_start) > 7 {
        if resp[location_start..line_end].starts_with(b"http://") {
            location_start += 7;
        } else if resp[location_start..line_end].starts_with(b"https://") {
            location_start += 8;
        }
    }
    let location_end = resp[location_start..line_end]
        .iter()
        .position(|&byte| byte == b'/')
        .map(|idx| idx + location_start)
        .unwrap_or(line_end);

    let mut suffix_start = host.len();
    while suffix_start > 0 && host[suffix_start - 1] != b'.' {
        suffix_start -= 1;
    }
    while suffix_start > 0 && host[suffix_start - 1] != b'.' {
        suffix_start -= 1;
    }
    let suffix = &host[suffix_start..];
    let location_host = &resp[location_start..location_end];

    location_host.len() < suffix.len() || &location_host[location_host.len() - suffix.len()..] != suffix
}

pub fn tls_session_id_mismatch(req: &[u8], resp: &[u8]) -> bool {
    if req.len() < 75 || resp.len() < 75 {
        return false;
    }
    if !is_tls_client_hello(req) || read_u16(resp, 0) != Some(0x1603) {
        return false;
    }
    let sid_len = req[43] as usize;
    let skip = 44 + sid_len + 3;
    if find_tls_ext_offset(0x002b, resp, skip).is_none() {
        return false;
    }
    if req[43] != resp[43] {
        return true;
    }
    req.get(44..44 + sid_len) != resp.get(44..44 + sid_len)
}

pub fn mod_http_like_c(input: &[u8], flags: u32) -> PacketMutation {
    fn apply_host_mixed_case(input: &[u8]) -> Option<Vec<u8>> {
        let parts = parse_http_parts(input)?;
        if parts.header_name_start + 3 >= input.len() {
            return None;
        }
        let mut output = input.to_vec();
        output[parts.header_name_start] = output[parts.header_name_start].to_ascii_lowercase();
        output[parts.header_name_start + 1] = output[parts.header_name_start + 1].to_ascii_uppercase();
        output[parts.header_name_start + 3] = output[parts.header_name_start + 3].to_ascii_uppercase();
        Some(output)
    }

    fn apply_domain_mixed_case(input: &[u8]) -> Option<Vec<u8>> {
        let parts = parse_http_parts(input)?;
        let mut output = input.to_vec();
        for idx in (parts.host_start..parts.host_end).step_by(2) {
            output[idx] = output[idx].to_ascii_uppercase();
        }
        Some(output)
    }

    fn apply_host_remove_spaces(input: &[u8]) -> Option<Vec<u8>> {
        let parts = parse_http_parts(input)?;
        let mut output = input.to_vec();
        let mut hlen = parts.host_end - parts.host_start;
        while parts.host_start + hlen < output.len() && !output[parts.host_start + hlen].is_ascii_whitespace() {
            hlen += 1;
        }
        if parts.host_start + hlen >= output.len() {
            return None;
        }
        let header_value_start = parts.header_name_start + 5;
        let space_count = parts.host_start.saturating_sub(header_value_start);
        output.copy_within(parts.host_start..parts.host_start + hlen, header_value_start);
        for byte in &mut output[header_value_start + hlen..header_value_start + hlen + space_count] {
            *byte = b'\t';
        }
        Some(output)
    }

    fn reconstruct_http_request(
        input: &[u8],
        layout: &HttpRequestLayout,
        line_ending: &[u8],
        user_agent_padding: usize,
    ) -> Vec<u8> {
        let mut output = Vec::with_capacity(input.len() + user_agent_padding);
        output.extend_from_slice(&input[layout.method_start..layout.request_line_end]);
        output.extend_from_slice(line_ending);
        for (index, line) in layout.header_lines.iter().enumerate() {
            output.extend_from_slice(&input[line.start..line.end]);
            if layout.user_agent_index == Some(index) && user_agent_padding > 0 {
                output.extend(std::iter::repeat_n(b' ', user_agent_padding));
            }
            output.extend_from_slice(line_ending);
        }
        output.extend_from_slice(line_ending);
        output.extend_from_slice(&input[layout.body_start..]);
        output
    }

    fn apply_http_unix_eol(input: &[u8]) -> Option<Vec<u8>> {
        let layout = parse_http_request_layout(input)?;
        let candidate = reconstruct_http_request(input, &layout, b"\n", 0);
        if candidate.len() > input.len() {
            return None;
        }
        let padding = input.len().saturating_sub(candidate.len());
        let output = if padding == 0 {
            candidate
        } else if layout.user_agent_index.is_some() {
            reconstruct_http_request(input, &layout, b"\n", padding)
        } else {
            return None;
        };
        (output.len() == input.len() && output != input).then_some(output)
    }

    fn apply_http_method_eol(input: &[u8]) -> Option<Vec<u8>> {
        let layout = parse_http_request_layout(input)?;
        let user_agent = layout.user_agent_index.and_then(|index| layout.header_lines.get(index)).copied()?;
        if user_agent.end < user_agent.value_start + 2 {
            return None;
        }

        let mut output = Vec::with_capacity(input.len() + 2);
        output.extend_from_slice(b"\r\n");
        output.extend_from_slice(input);
        output.drain(user_agent.end..user_agent.end + 2);
        Some(output)
    }

    let mut output = input.to_vec();
    let mut modified = false;

    if flags & MH_HMIX != 0 {
        if let Some(next) = apply_host_mixed_case(&output) {
            modified |= next != output;
            output = next;
        }
    }
    if flags & MH_DMIX != 0 {
        if let Some(next) = apply_domain_mixed_case(&output) {
            modified |= next != output;
            output = next;
        }
    }
    if flags & MH_SPACE != 0 {
        if let Some(next) = apply_host_remove_spaces(&output) {
            modified |= next != output;
            output = next;
        }
    }
    if flags & MH_UNIXEOL != 0 {
        if let Some(next) = apply_http_unix_eol(&output) {
            modified |= next != output;
            output = next;
        }
    }
    if flags & MH_METHODEOL != 0 {
        if let Some(next) = apply_http_method_eol(&output) {
            modified |= next != output;
            output = next;
        }
    }

    PacketMutation { rc: if modified { 0 } else { -1 }, bytes: if modified { output } else { input.to_vec() } }
}

pub fn part_tls_like_c(input: &[u8], pos: isize) -> PacketMutation {
    let n = input.len();
    if n < 3 || pos < 0 || pos as usize + 5 > n {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    }
    let mut output = vec![0; n + 5];
    output[..n].copy_from_slice(input);

    let Some(record_size) = read_u16(&output, 3) else {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    };
    if record_size < pos as usize {
        return PacketMutation { rc: n as isize, bytes: input.to_vec() };
    }

    let pos = pos as usize;
    output.copy_within(5 + pos..n, 10 + pos);
    output[5 + pos..5 + pos + 3].copy_from_slice(&input[..3]);
    let _ = write_u16(&mut output, 3, pos);
    let _ = write_u16(&mut output, 8 + pos, record_size.saturating_sub(pos));

    PacketMutation { rc: 5, bytes: output }
}

pub fn randomize_tls_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    if output.len() < 44 {
        return PacketMutation { rc: 0, bytes: output };
    }
    let sid_len = output[43] as usize;
    if output.len() < 44 + sid_len + 2 {
        return PacketMutation { rc: 0, bytes: output };
    }
    let mut rng = OracleRng::seeded(seed);
    for byte in &mut output[11..43] {
        *byte = rng.next_u8();
    }
    for byte in &mut output[44..44 + sid_len] {
        *byte = rng.next_u8();
    }

    let Some(skip) = find_ext_block(&output) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    let Some(ks_offs) = find_tls_ext_offset(0x0033, &output, skip) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    if ks_offs + 6 >= output.len() {
        return PacketMutation { rc: 0, bytes: output };
    }
    let Some(ks_size) = read_u16(&output, ks_offs + 2) else {
        return PacketMutation { rc: 0, bytes: output };
    };
    if ks_offs + 4 + ks_size > output.len() {
        return PacketMutation { rc: 0, bytes: output };
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(&output, group_offs + 2) else {
            return PacketMutation { rc: 0, bytes: output };
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > output.len() {
            return PacketMutation { rc: 0, bytes: output };
        }
        for byte in &mut output[group_offs + 4..group_end] {
            *byte = rng.next_u8();
        }
        group_offs += 4 + group_size;
    }

    PacketMutation { rc: 0, bytes: output }
}

pub fn randomize_tls_sni_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let Some(markers) = tls_marker_info(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let mut output = input.to_vec();
    let mut rng = OracleRng::seeded(seed);
    fill_random_tls_host_like_c(&mut output[markers.host_start..markers.host_end], &mut rng);
    PacketMutation { rc: 0, bytes: output }
}

pub fn duplicate_tls_session_id_like_c(fake_input: &[u8], original_input: &[u8]) -> PacketMutation {
    let mut output = fake_input.to_vec();
    if !is_tls_client_hello(fake_input)
        || !is_tls_client_hello(original_input)
        || output.len() < 44
        || original_input.len() < 44
    {
        return PacketMutation { rc: -1, bytes: output };
    }
    let sid_len = output[43] as usize;
    if output.len() < 44 + sid_len || original_input[43] as usize != sid_len || original_input.len() < 44 + sid_len {
        return PacketMutation { rc: -1, bytes: output };
    }
    output[44..44 + sid_len].copy_from_slice(&original_input[44..44 + sid_len]);
    PacketMutation { rc: 0, bytes: output }
}

pub fn tune_tls_padding_size_like_c(input: &[u8], target_size: usize) -> PacketMutation {
    if target_size == input.len() {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    }
    let Some(ext_len_start) = find_tls_ext_len_offset(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let mut output = input.to_vec();
    let original_len = output.len();
    let pad_offs = find_tls_ext_offset(0x0015, &output, ext_len_start);

    match target_size.cmp(&original_len) {
        std::cmp::Ordering::Equal => PacketMutation { rc: 0, bytes: output },
        std::cmp::Ordering::Greater => {
            output.resize(target_size, 0);
            let grow = target_size - original_len;
            if let Some(pad_offs) = pad_offs {
                if pad_offs + 4 <= output.len() {
                    if let Some(pad_len) = read_u16(input, pad_offs + 2) {
                        let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_add(grow));
                    }
                }
            } else if grow >= 4 {
                let pad_offs = original_len;
                let _ = write_u16(&mut output, pad_offs, 0x0015);
                let _ = write_u16(&mut output, pad_offs + 2, grow - 4);
            }
            if !adjust_tls_lengths(&mut output, ext_len_start, grow as isize) {
                return PacketMutation { rc: -1, bytes: input.to_vec() };
            }
            PacketMutation { rc: 0, bytes: output }
        }
        std::cmp::Ordering::Less => {
            let shrink = original_len - target_size;
            output.truncate(target_size);
            if let Some(pad_offs) = pad_offs {
                if pad_offs + 4 <= output.len() {
                    if let Some(pad_len) = read_u16(input, pad_offs + 2) {
                        let _ = write_u16(&mut output, pad_offs + 2, pad_len.saturating_sub(shrink));
                    }
                }
            }
            if !adjust_tls_lengths(&mut output, ext_len_start, -(shrink as isize)) {
                return PacketMutation { rc: -1, bytes: input.to_vec() };
            }
            PacketMutation { rc: 0, bytes: output }
        }
    }
}

pub fn padencap_tls_like_c(input: &[u8], payload_len: usize) -> PacketMutation {
    let Some(ext_len_start) = find_tls_ext_len_offset(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let mut output = input.to_vec();
    let pad_len_offs = if let Some(pad_offs) = find_tls_ext_offset(0x0015, &output, ext_len_start) {
        pad_offs + 2
    } else {
        let pad_offs = output.len();
        output.extend_from_slice(&[0x00, 0x15, 0x00, 0x00]);
        if !adjust_tls_lengths(&mut output, ext_len_start, 4) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        pad_offs + 2
    };
    let Some(pad_len) = read_u16(&output, pad_len_offs) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if !write_u16(&mut output, pad_len_offs, pad_len + payload_len)
        || !adjust_tls_lengths(&mut output, ext_len_start, payload_len as isize)
    {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    PacketMutation { rc: 0, bytes: output }
}

pub fn change_tls_sni_seeded_like_c(input: &[u8], host: &[u8], capacity: usize, seed: u32) -> PacketMutation {
    if capacity < input.len() || host.len() > u16::MAX as usize {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut output = vec![0; capacity];
    output[..input.len()].copy_from_slice(input);
    let n = input.len();
    let mut avail = merge_tls_records(&mut output, n) as isize + (capacity - n) as isize;
    let Some(mut record_size) = read_u16(&output, 3).map(|value| value as isize) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    record_size += avail;

    let Some(skip) = find_ext_block(&output[..n]) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let Some(mut sni_offs) = find_tls_ext_offset(0x0000, &output[..n], skip) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    let Some(sni_size) = read_u16(&output, sni_offs + 2) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    if sni_offs + 4 + sni_size > n {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut diff = host.len() as isize - (sni_size as isize - 5);
    avail -= diff;
    if diff < 0 && avail > 0 {
        if !resize_sni(&mut output, n, sni_offs, sni_size, host.len()) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        diff = 0;
    }
    if avail != 0 {
        avail -= resize_ech_ext(&mut output, n, skip, avail);
    }
    if avail < -50 {
        avail += remove_ks_group(&mut output, n, skip, 0x11ec) as isize;
    }
    for kind in [0x0015u16, 0x0031, 0x0010, 0x001c, 0x0023, 0x0005, 0x0022, 0x0012, 0x001b] {
        if avail == 0 || avail >= 4 {
            break;
        }
        avail += remove_tls_ext(&mut output, n, skip, kind) as isize;
    }
    if avail != 0 && avail < 4 {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let Some(new_sni_offs) = find_tls_ext_offset(0x0000, &output[..n], skip) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };
    sni_offs = new_sni_offs;
    if diff != 0 {
        let curr_n = capacity as isize - avail - diff;
        if curr_n < 0 || curr_n > capacity as isize {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        if !resize_sni(&mut output, curr_n as usize, sni_offs, sni_size, host.len()) {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
    }
    if sni_offs + 9 + host.len() > capacity {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let mut rng = OracleRng::seeded(seed);
    copy_name_seeded(&mut output[sni_offs + 9..sni_offs + 9 + host.len()], host, &mut rng);

    if avail > 0 {
        avail -= resize_ech_ext(&mut output, n, skip, avail);
    }
    if avail >= 4 {
        let record_end = 5 + record_size;
        let pad_offs = record_end - avail;
        if record_end > capacity as isize || pad_offs < 0 || pad_offs + avail > capacity as isize {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        let pad_offs = pad_offs as usize;
        let avail = avail as usize;
        let _ = write_u16(&mut output, pad_offs, 0x0015);
        let _ = write_u16(&mut output, pad_offs + 2, avail.saturating_sub(4));
        output[pad_offs + 4..pad_offs + avail].fill(0);
    }

    if record_size < 4
        || !write_u16(&mut output, 3, record_size as usize)
        || !write_u16(&mut output, 7, (record_size - 4) as usize)
        || !write_u16(&mut output, skip, (5 + record_size - skip as isize - 2).max(0) as usize)
    {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }

    let out_len = (5 + record_size) as usize;
    if out_len > output.len() {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    }
    PacketMutation { rc: 0, bytes: output[..out_len].to_vec() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[allow(dead_code)]
    mod rust_packet_seeds {
        use crate as ripdpi_packets;

        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/rust_packet_seeds.rs"));
    }

    #[test]
    fn parse_tls_extracts_default_fake_sni() {
        assert!(is_tls_client_hello(DEFAULT_FAKE_TLS));
        assert_eq!(parse_tls(DEFAULT_FAKE_TLS), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn parse_http_extracts_host_and_port() {
        let request = b"GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n";
        let parsed = parse_http(request).expect("parse http host header");

        assert_eq!(parsed.host, b"example.com");
        assert_eq!(parsed.port, 8080);
    }

    #[test]
    fn http_marker_info_tracks_method_host_and_port() {
        let request = b"\r\nGET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n";
        let markers = http_marker_info(request).expect("parse http markers");

        assert_eq!(markers.method_start, 2);
        assert_eq!(&request[markers.host_start..markers.host_end], b"example.com");
        assert_eq!(markers.port, 8080);
    }

    #[test]
    fn http_marker_info_handles_ipv6_host_literals() {
        let request = b"GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n";
        let markers = http_marker_info(request).expect("parse ipv6 http markers");

        assert_eq!(&request[markers.host_start..markers.host_end], b"::1");
        assert_eq!(markers.port, 8080);
    }

    #[test]
    fn second_level_domain_span_matches_structural_labels() {
        assert_eq!(second_level_domain_span(b"sub.example.com"), Some((4, 11)));
        assert_eq!(second_level_domain_span(b"example.com"), Some((0, 7)));
        assert_eq!(second_level_domain_span(b"localhost"), None);
    }

    #[test]
    fn tls_marker_info_tracks_sni_and_extensions_offsets() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("parse tls markers");

        assert_eq!(&DEFAULT_FAKE_TLS[markers.host_start..markers.host_end], b"www.wikipedia.org");
        assert_eq!(markers.host_start, markers.sni_ext_start + 5);
        assert!(markers.ext_len_start < markers.sni_ext_start);
    }

    #[test]
    fn tls_marker_info_rejects_truncated_sni_payload() {
        let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("original tls markers");
        let mut truncated = DEFAULT_FAKE_TLS.to_vec();
        truncated.truncate(markers.host_start + 4);

        assert!(tls_marker_info(&truncated).is_none());
    }

    #[test]
    fn randomize_tls_sni_preserves_valid_host_length() {
        let original = parse_tls(DEFAULT_FAKE_TLS).expect("original tls sni").to_vec();
        let mutation = randomize_tls_sni_seeded_like_c(DEFAULT_FAKE_TLS, 7);
        let randomized = parse_tls(&mutation.bytes).expect("randomized tls sni").to_vec();

        assert_eq!(mutation.rc, 0);
        assert_eq!(randomized.len(), original.len());
        assert_ne!(randomized, original);
        assert!(randomized.iter().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || *byte == b'.'));
    }

    #[test]
    fn duplicate_tls_session_id_uses_original_when_compatible() {
        let source = DEFAULT_FAKE_TLS.to_vec();
        let mut fake = DEFAULT_FAKE_TLS.to_vec();
        let sid_len = fake[43] as usize;
        fake[44..44 + sid_len].fill(b'Z');

        let mutation = duplicate_tls_session_id_like_c(&fake, &source);

        assert_eq!(mutation.rc, 0);
        assert_eq!(&mutation.bytes[44..44 + mutation.bytes[43] as usize], &source[44..44 + source[43] as usize]);
    }

    #[test]
    fn duplicate_tls_session_id_rejects_incompatible_lengths() {
        let mut source = DEFAULT_FAKE_TLS.to_vec();
        source[43] = source[43].saturating_sub(1);

        let mutation = duplicate_tls_session_id_like_c(DEFAULT_FAKE_TLS, &source);

        assert_eq!(mutation.rc, -1);
        assert_eq!(mutation.bytes, DEFAULT_FAKE_TLS);
    }

    #[test]
    fn tune_tls_padding_size_can_grow_and_shrink_default_fake() {
        let grown = tune_tls_padding_size_like_c(DEFAULT_FAKE_TLS, DEFAULT_FAKE_TLS.len() + 12);
        let shrunk = tune_tls_padding_size_like_c(DEFAULT_FAKE_TLS, DEFAULT_FAKE_TLS.len() - 12);

        assert_eq!(grown.rc, 0);
        assert_eq!(grown.bytes.len(), DEFAULT_FAKE_TLS.len() + 12);
        assert_eq!(parse_tls(&grown.bytes), Some(&b"www.wikipedia.org"[..]));

        assert_eq!(shrunk.rc, 0);
        assert_eq!(shrunk.bytes.len(), DEFAULT_FAKE_TLS.len() - 12);
        assert_eq!(parse_tls(&shrunk.bytes), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn padencap_tls_updates_padding_and_lengths() {
        let mutation = padencap_tls_like_c(DEFAULT_FAKE_TLS, 24);
        let ext_len_start = find_tls_ext_len_offset(&mutation.bytes).expect("ext len offset");
        let pad_offs = find_tls_ext_offset(0x0015, &mutation.bytes, ext_len_start).expect("padding ext");
        let pad_len = read_u16(&mutation.bytes, pad_offs + 2).expect("pad len");

        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes.len(), DEFAULT_FAKE_TLS.len());
        assert_eq!(read_u16(DEFAULT_FAKE_TLS, 3).unwrap() + 24, read_u16(&mutation.bytes, 3).unwrap());
        assert_eq!(read_u16(DEFAULT_FAKE_TLS, pad_offs + 2).unwrap() + 24, pad_len);
        assert_eq!(parse_tls(&mutation.bytes), Some(&b"www.wikipedia.org"[..]));
    }

    #[test]
    fn parse_quic_initial_extracts_v1_sni() {
        let packet =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("docs.example.test")).expect("build quic initial v1");
        let parsed = parse_quic_initial(&packet).expect("parse quic initial v1");

        assert!(is_quic_initial(&packet));
        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert!(parsed.is_crypto_complete);
        assert_eq!(parsed.host(), b"docs.example.test");
    }

    #[test]
    fn parse_quic_initial_extracts_v2_sni() {
        let packet =
            build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("build quic initial v2");
        let parsed = parse_quic_initial(&packet).expect("parse quic initial v2");

        assert!(is_quic_initial(&packet));
        assert_eq!(parsed.version, QUIC_V2_VERSION);
        assert!(parsed.is_crypto_complete);
        assert_eq!(parsed.host(), b"media.example.test");
    }

    #[test]
    fn realistic_quic_fake_builder_round_trips_and_uses_default_tls_base() {
        let packet = build_realistic_quic_initial(QUIC_V1_VERSION, None).expect("build default realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert_eq!(parsed.host(), b"www.wikipedia.org");
        assert_eq!(packet.len(), QUIC_FAKE_INITIAL_TARGET_LEN);
    }

    #[test]
    fn realistic_quic_fake_builder_applies_host_override() {
        let packet =
            build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.host(), b"video.example.test");
    }

    #[test]
    fn realistic_quic_fake_builder_defaults_to_v1_for_unknown_versions() {
        let packet =
            build_realistic_quic_initial(0xface_feed, Some("video.example.test")).expect("build realistic fake");
        let parsed = parse_quic_initial(&packet).expect("parse realistic fake");

        assert_eq!(parsed.version, QUIC_V1_VERSION);
        assert_eq!(parsed.host(), b"video.example.test");
    }

    #[test]
    fn compat_default_quic_fake_matches_zapret_layout() {
        let packet = default_fake_quic_compat();

        assert_eq!(packet.len(), DEFAULT_FAKE_QUIC_COMPAT_LEN);
        assert_eq!(packet[0], 0x40);
        assert!(packet[1..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn parse_quic_initial_rejects_unsupported_versions() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        packet[1..5].copy_from_slice(&0x0000_0002u32.to_be_bytes());

        assert!(!is_quic_initial(&packet));
        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_bad_tags() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        let last = packet.len() - 1;
        packet[last] ^= 0xff;

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_truncated_packets() {
        let mut packet = rust_packet_seeds::quic_initial_v1();
        packet.truncate(packet.len() - 32);

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_incomplete_crypto_frames() {
        let packet = rust_packet_seeds::quic_initial_with_crypto_gap(QUIC_V1_VERSION, "docs.example.test");

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn parse_quic_initial_rejects_missing_sni() {
        let packet = rust_packet_seeds::quic_initial_missing_sni(QUIC_V1_VERSION);

        assert!(parse_quic_initial(&packet).is_none());
    }

    #[test]
    fn http_redirect_detection_uses_host_suffix() {
        let request = b"GET / HTTP/1.1\r\nHost: api.example.com\r\n\r\n";
        let redirect = b"HTTP/1.1 302 Found\r\nLocation: https://login.other.net/path\r\n\r\n";
        let same_site = b"HTTP/1.1 302 Found\r\nLocation: https://cdn.example.com/path\r\n\r\n";

        assert!(is_http_redirect(request, redirect));
        assert!(!is_http_redirect(request, same_site));
    }

    #[test]
    fn mod_http_like_c_applies_header_and_domain_mixing() {
        let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let mutation = mod_http_like_c(input, MH_HMIX | MH_DMIX);
        let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

        assert_eq!(mutation.rc, 0);
        assert!(output.contains("\r\nhOsT: ExAmPlE.CoM\r\n"));
    }

    #[test]
    fn mod_http_like_c_applies_unix_eol_with_user_agent_padding() {
        let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
        let mutation = mod_http_like_c(input, MH_UNIXEOL);
        let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes.len(), input.len());
        assert_eq!(output, "GET / HTTP/1.1\nHost: example.com\nUser-Agent: agent    \n\n");
    }

    #[test]
    fn mod_http_like_c_applies_method_eol_and_trims_user_agent() {
        let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
        let mutation = mod_http_like_c(input, MH_METHODEOL);
        let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

        assert_eq!(mutation.rc, 0);
        assert_eq!(mutation.bytes.len(), input.len());
        assert_eq!(output, "\r\nGET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: age\r\n\r\n");
    }

    #[test]
    fn mod_http_like_c_best_effort_skips_eol_mutations_without_user_agent() {
        let input = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let mutation = mod_http_like_c(input, MH_UNIXEOL | MH_METHODEOL);

        assert_eq!(mutation.rc, -1);
        assert_eq!(mutation.bytes, input);
    }

    #[test]
    fn mod_http_like_c_keeps_pipeline_order_for_safe_and_aggressive_mutations() {
        let input = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";
        let mutation = mod_http_like_c(input, MH_HMIX | MH_DMIX | MH_SPACE | MH_UNIXEOL | MH_METHODEOL);
        let output = std::str::from_utf8(&mutation.bytes).expect("http mutation utf8");

        assert_eq!(mutation.rc, 0);
        assert!(output.starts_with("\r\nGET / HTTP/1.1\n"));
        assert!(output.contains("\nhOsT:ExAmPlE.CoM\t\n"));
        assert!(output.contains("\nUser-Agent: agent  \n\n"));
    }

    #[test]
    fn read_u16_boundary_conditions() {
        // Exact two-byte buffer at offset 0
        assert_eq!(read_u16(&[0xAB, 0xCD], 0), Some(0xABCD));
        // Single-byte buffer: offset + 1 >= len
        assert_eq!(read_u16(&[0xAB], 0), None);
        // Empty buffer
        assert_eq!(read_u16(&[], 0), None);
        // Offset at last byte
        assert_eq!(read_u16(&[0x01, 0x02, 0x03], 2), None);
    }

    #[test]
    fn write_u16_rejects_overflow() {
        let mut buf = [0u8; 4];
        assert!(write_u16(&mut buf, 0, 0xFFFF));
        assert_eq!(buf[0], 0xFF);
        assert_eq!(buf[1], 0xFF);
        // value > u16::MAX
        assert!(!write_u16(&mut buf, 0, 65536));
    }

    #[test]
    fn is_http_range_boundaries() {
        // CONNECT starts with 'C' (low bound)
        let connect = b"CONNECT host:443 HTTP/1.1\r\nHost: host\r\n\r\n";
        assert!(is_http(connect));
        let shifted = b"\nGET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(is_http(shifted));
        // TRACE starts with 'T' (high bound)
        let trace = b"TRACE / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(is_http(trace));
        // 'B' is below range
        let below = b"BELOW / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(!is_http(below));
        // 'U' is above range
        let above = b"UPDATE / HTTP/1.1\r\nHost: e.com\r\n\r\n";
        assert!(!is_http(above));
    }

    #[test]
    fn get_http_code_range_boundaries() {
        // Code 100 (low boundary)
        assert_eq!(get_http_code(b"HTTP/1.1 100 Continue\r\n\r\n"), Some(100));
        // Code 511 (high boundary)
        assert_eq!(get_http_code(b"HTTP/1.1 511 Not Extended\r\n\r\n"), Some(511));
        // Code 99 (below range)
        assert_eq!(get_http_code(b"HTTP/1.1 099 Below\r\n\r\n"), None);
        // Code 512 (above range)
        assert_eq!(get_http_code(b"HTTP/1.1 512 Above\r\n\r\n"), None);
    }

    #[test]
    fn oracle_rng_next_mod_zero_returns_zero() {
        let mut rng = OracleRng::seeded(42);
        assert_eq!(rng.next_mod(0), 0);
    }

    #[test]
    fn copy_name_seeded_pattern_chars() {
        let mut rng = OracleRng::seeded(0);
        let mut out = [0u8; 5];

        // '*' produces alphanumeric
        copy_name_seeded(&mut out[..1], b"*", &mut rng);
        assert!(out[0].is_ascii_alphanumeric());

        // '?' produces lowercase letter
        copy_name_seeded(&mut out[..1], b"?", &mut rng);
        assert!(out[0].is_ascii_lowercase());

        // '#' produces digit
        copy_name_seeded(&mut out[..1], b"#", &mut rng);
        assert!(out[0].is_ascii_digit());

        // literal passes through
        copy_name_seeded(&mut out[..1], b"X", &mut rng);
        assert_eq!(out[0], b'X');
    }

    #[test]
    fn is_http_redirect_same_suffix_not_redirect() {
        let req = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
        // Same 2-level suffix in location -> not a redirect
        let same = b"HTTP/1.1 302 Found\r\nLocation: https://other.example.com/page\r\n\r\n";
        assert!(!is_http_redirect(req, same));
        // Different suffix -> redirect
        let diff = b"HTTP/1.1 302 Found\r\nLocation: https://sub.other.net/page\r\n\r\n";
        assert!(is_http_redirect(req, diff));
    }

    #[test]
    fn parse_http_ipv6_host_bracket() {
        let request = b"GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n";
        let parsed = parse_http(request).expect("parse ipv6 host");
        assert_eq!(parsed.host, b"::1");
        assert_eq!(parsed.port, 8080);
    }

    #[test]
    fn ascii_case_eq_different_lengths() {
        assert!(!ascii_case_eq(b"abc", b"abcd"));
        assert!(!ascii_case_eq(b"abcd", b"abc"));
        assert!(ascii_case_eq(b"AbC", b"abc"));
    }

    proptest! {
        #[test]
        fn parse_http_never_panics(data in proptest::collection::vec(any::<u8>(), 0..512)) {
            let _ = is_http(&data);
            let _ = parse_http(&data);
            let _ = is_http_redirect(&data, &data);
        }

        #[test]
        fn parse_tls_never_panics(data in proptest::collection::vec(any::<u8>(), 0..1024)) {
            let _ = is_tls_client_hello(&data);
            let _ = is_tls_server_hello(&data);
            let _ = parse_tls(&data);
            let _ = tls_session_id_mismatch(&data, &data);
        }
    }
}
