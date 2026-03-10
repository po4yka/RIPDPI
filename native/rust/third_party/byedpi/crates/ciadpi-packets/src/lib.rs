#![forbid(unsafe_code)]

pub const IS_TCP: u32 = 1;
pub const IS_UDP: u32 = 2;
pub const IS_HTTP: u32 = 4;
pub const IS_HTTPS: u32 = 8;
pub const IS_IPV4: u32 = 16;

pub const MH_HMIX: u32 = 1;
pub const MH_SPACE: u32 = 2;
pub const MH_DMIX: u32 = 4;

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HttpHost<'a> {
    pub host: &'a [u8],
    pub port: u16,
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
    header_name_start: usize,
    host_start: usize,
    host_end: usize,
    port: u16,
}

fn read_u16(data: &[u8], offset: usize) -> Option<usize> {
    if offset + 1 >= data.len() {
        return None;
    }
    Some(((data[offset] as usize) << 8) | data[offset + 1] as usize)
}

fn write_u16(data: &mut [u8], offset: usize, value: usize) -> bool {
    if offset + 1 >= data.len() || value > u16::MAX as usize {
        return false;
    }
    data[offset] = ((value >> 8) & 0xff) as u8;
    data[offset + 1] = (value & 0xff) as u8;
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

fn find_ext_block(data: &[u8]) -> Option<usize> {
    if data.len() < 44 {
        return None;
    }
    let sid_len = data[43] as usize;
    if data.len() < 44 + sid_len + 2 {
        return None;
    }
    let cip_len = read_u16(data, 44 + sid_len)?;
    let skip = 44 + sid_len + 2 + cip_len + 2;
    if skip > data.len() {
        None
    } else {
        Some(skip)
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

fn parse_http_parts(buffer: &[u8]) -> Option<HttpParts> {
    if !is_http(buffer) {
        return None;
    }
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

    Some(HttpParts { header_name_start, host_start, host_end, port })
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
    if !is_tls_client_hello(buffer) {
        return None;
    }
    let skip = find_ext_block(buffer)?;
    let sni_offs = find_tls_ext_offset(0x0000, buffer, skip)?;
    if sni_offs + 12 >= buffer.len() {
        return None;
    }
    let len = read_u16(buffer, sni_offs + 7)?;
    if sni_offs + 9 + len > buffer.len() {
        return None;
    }
    Some(&buffer[sni_offs + 9..sni_offs + 9 + len])
}

pub fn is_http(buffer: &[u8]) -> bool {
    if buffer.len() < 16 {
        return false;
    }
    let first = buffer[0];
    if !(b'C'..=b'T').contains(&first) {
        return false;
    }
    const METHODS: &[&[u8]] =
        &[b"HEAD", b"GET", b"POST", b"PUT", b"DELETE", b"OPTIONS", b"CONNECT", b"TRACE", b"PATCH"];
    METHODS.iter().any(|method| buffer.starts_with(method))
}

pub fn parse_http(buffer: &[u8]) -> Option<HttpHost<'_>> {
    let parts = parse_http_parts(buffer)?;
    Some(HttpHost { host: &buffer[parts.host_start..parts.host_end], port: parts.port })
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
    let Some(parts) = parse_http_parts(input) else {
        return PacketMutation { rc: -1, bytes: input.to_vec() };
    };

    let mut output = input.to_vec();
    if flags & MH_HMIX != 0 && parts.header_name_start + 3 < output.len() {
        output[parts.header_name_start] = output[parts.header_name_start].to_ascii_lowercase();
        output[parts.header_name_start + 1] = output[parts.header_name_start + 1].to_ascii_uppercase();
        output[parts.header_name_start + 3] = output[parts.header_name_start + 3].to_ascii_uppercase();
    }
    if flags & MH_DMIX != 0 {
        for idx in (parts.host_start..parts.host_end).step_by(2) {
            output[idx] = output[idx].to_ascii_uppercase();
        }
    }
    if flags & MH_SPACE != 0 {
        let mut hlen = parts.host_end - parts.host_start;
        while parts.host_start + hlen < output.len() && !output[parts.host_start + hlen].is_ascii_whitespace() {
            hlen += 1;
        }
        if parts.host_start + hlen >= output.len() {
            return PacketMutation { rc: -1, bytes: input.to_vec() };
        }
        let header_value_start = parts.header_name_start + 5;
        let space_count = parts.host_start.saturating_sub(header_value_start);
        output.copy_within(parts.host_start..parts.host_start + hlen, header_value_start);
        for byte in &mut output[header_value_start + hlen..header_value_start + hlen + space_count] {
            *byte = b'\t';
        }
    }

    PacketMutation { rc: 0, bytes: output }
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
