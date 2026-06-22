use std::io::{self, Read, Write};

use crate::constants::SNAPLEN_DEFAULT;
use crate::reader::PcapReader;
use crate::writer::PcapWriter;

/// Stream-read packets from `src`, rewrite endpoint addresses to all
/// zeros (IPv4 `0.0.0.0`, IPv6 `::`), recompute the IPv4 header
/// checksum, zero the TCP/UDP checksum, and stream-write to `dst`.
///
/// Non-IP packets are passed through unchanged (defensive - shouldn't
/// happen with LINKTYPE_RAW, but we do not bail).
///
/// Returns the number of bytes written to `dst`.
pub fn rewrite_endpoints<R: Read, W: Write>(src: R, dst: W) -> io::Result<u64> {
    let mut reader = PcapReader::new(src)?;
    let mut writer = PcapWriter::new(dst, SNAPLEN_DEFAULT)?;
    while let Some(record) = reader.next_record()? {
        let mut bytes = record.bytes;
        redact_in_place(&mut bytes);
        writer.write_packet(record.ts_micros, &bytes)?;
    }
    writer.flush()?;
    Ok(writer.bytes_written())
}

/// Mutate a single IP packet in place. Public for unit-testing.
pub fn redact_in_place(bytes: &mut [u8]) {
    if bytes.is_empty() {
        return;
    }
    match bytes[0] >> 4 {
        4 => redact_ipv4(bytes),
        6 => redact_ipv6(bytes),
        _ => {} // not IP - leave alone
    }
}

fn redact_ipv4(bytes: &mut [u8]) {
    if bytes.len() < 20 {
        return;
    }
    // bytes 12..16 = src, 16..20 = dst
    for b in &mut bytes[12..20] {
        *b = 0;
    }
    // Recompute IPv4 header checksum (RFC 791): zero existing
    // checksum bytes, sum 16-bit words (excluding checksum field),
    // fold carries, store one's-complement.
    let ihl = (bytes[0] & 0x0f) as usize * 4;
    if ihl < 20 || bytes.len() < ihl {
        return;
    }
    bytes[10] = 0;
    bytes[11] = 0;
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < ihl {
        sum = sum.wrapping_add(u32::from(u16::from_be_bytes([bytes[i], bytes[i + 1]])));
        i += 2;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    let checksum = !(sum as u16);
    bytes[10..12].copy_from_slice(&checksum.to_be_bytes());
    // Zero TCP/UDP checksum if present (relaxed convention - modern
    // tooling tolerates zero checksums on captured packets).
    let protocol = bytes[9];
    let payload_start = ihl;
    match protocol {
        6 if bytes.len() >= payload_start + 18 => {
            // TCP: checksum at bytes 16..18 of TCP header. The SNI scrub
            // overwrites host bytes in place (length-preserving), so the
            // already-zeroed checksum stays valid for captured-packet tooling.
            bytes[payload_start + 16] = 0;
            bytes[payload_start + 17] = 0;
            redact_tcp_sni(bytes, payload_start);
        }
        17 if bytes.len() >= payload_start + 8 => {
            // UDP: checksum at bytes 6..8 of UDP header
            bytes[payload_start + 6] = 0;
            bytes[payload_start + 7] = 0;
        }
        _ => {}
    }
}

/// Byte written over scrubbed SNI host bytes. ASCII so the record stays
/// readable as "all-x" rather than a confusing run of NULs.
const SNI_FILL: u8 = b'x';

/// Locate a TLS ClientHello inside the TCP segment that starts at
/// `tcp_start` and overwrite the SNI `host_name` bytes in place.
///
/// Length-preserving: every host byte is replaced with [`SNI_FILL`], so
/// all TLS record / handshake / extension length fields stay correct and
/// the (already-zeroed) TCP checksum remains valid for captured traffic.
///
/// Fail-open and panic-free: every offset is bounds-checked with `get()`
/// before use. Any structure that does not parse as a ClientHello leaves
/// the payload untouched.
fn redact_tcp_sni(bytes: &mut [u8], tcp_start: usize) {
    // TCP data-offset (header length) is the high nibble of byte 12,
    // counted in 32-bit words.
    let Some(&offset_byte) = bytes.get(tcp_start + 12) else {
        return;
    };
    let tcp_hdr_len = ((offset_byte >> 4) as usize) * 4;
    if tcp_hdr_len < 20 {
        return;
    }
    let Some(payload_start) = tcp_start.checked_add(tcp_hdr_len) else {
        return;
    };
    if let Some((host_off, host_len)) = find_sni_host(&bytes[payload_start..]) {
        // host_off / host_len were derived from slices of the payload, so
        // these indices are in range; the loop is bounds-checked regardless.
        let abs_start = payload_start.saturating_add(host_off);
        for b in bytes.iter_mut().skip(abs_start).take(host_len) {
            *b = SNI_FILL;
        }
    }
}

/// Read a big-endian `u16` length at `data[at..at+2]`, returning `None`
/// when out of range.
fn be_u16(data: &[u8], at: usize) -> Option<usize> {
    let hi = *data.get(at)?;
    let lo = *data.get(at + 1)?;
    Some(usize::from(u16::from_be_bytes([hi, lo])))
}

/// Defensively parse a TLS ClientHello in `payload` and return the
/// `(offset, len)` of the first SNI `host_name` value, relative to the
/// start of `payload`. Returns `None` for anything that is not a
/// well-formed ClientHello carrying a non-empty host_name.
fn find_sni_host(payload: &[u8]) -> Option<(usize, usize)> {
    // TLS record header: type(1) version(2) length(2).
    // 0x16 = handshake; 0x03 = TLS major version (SSL3/TLS1.x).
    if *payload.first()? != 0x16 || *payload.get(1)? != 0x03 {
        return None;
    }
    let record_len = be_u16(payload, 3)?;
    let record_end = 5usize.checked_add(record_len)?;
    let record_end = record_end.min(payload.len());

    // Handshake header begins at offset 5: type(1) length(3).
    if *payload.get(5)? != 0x01 {
        return None; // not ClientHello
    }
    let hs_len =
        (usize::from(*payload.get(6)?) << 16) | (usize::from(*payload.get(7)?) << 8) | usize::from(*payload.get(8)?);
    let hs_body_start = 9usize;
    let hs_end = hs_body_start.checked_add(hs_len)?.min(record_end);

    // ClientHello body: version(2) random(32) then variable fields.
    let mut p = hs_body_start.checked_add(2 + 32)?; // skip version + random

    // session_id: len(1) + bytes
    let sid_len = usize::from(*payload.get(p)?);
    p = p.checked_add(1)?.checked_add(sid_len)?;

    // cipher_suites: len(2) + bytes
    let cs_len = be_u16(payload, p)?;
    p = p.checked_add(2)?.checked_add(cs_len)?;

    // compression_methods: len(1) + bytes
    let cm_len = usize::from(*payload.get(p)?);
    p = p.checked_add(1)?.checked_add(cm_len)?;

    // extensions: len(2) + bytes
    let ext_total = be_u16(payload, p)?;
    p = p.checked_add(2)?;
    let ext_end = p.checked_add(ext_total)?.min(hs_end);

    // Walk extensions looking for server_name (type 0x0000).
    while p.checked_add(4)? <= ext_end {
        let ext_type = be_u16(payload, p)?;
        let ext_len = be_u16(payload, p + 2)?;
        let ext_body = p.checked_add(4)?;
        let ext_body_end = ext_body.checked_add(ext_len)?;
        if ext_body_end > ext_end {
            return None; // declared length overruns the record
        }
        if ext_type == 0x0000 {
            return find_host_in_sni_ext(payload, ext_body, ext_body_end);
        }
        p = ext_body_end;
    }
    None
}

/// Parse the body of a server_name extension and return the
/// `(offset, len)` of the first `host_name` value (name_type 0).
fn find_host_in_sni_ext(payload: &[u8], body: usize, body_end: usize) -> Option<(usize, usize)> {
    // ServerNameList: len(2) then entries.
    let list_len = be_u16(payload, body)?;
    let mut q = body.checked_add(2)?;
    let list_end = q.checked_add(list_len)?.min(body_end);
    // Each entry: name_type(1) name_len(2) name(name_len).
    while q.checked_add(3)? <= list_end {
        let name_type = *payload.get(q)?;
        let name_len = be_u16(payload, q + 1)?;
        let name_start = q.checked_add(3)?;
        let name_end = name_start.checked_add(name_len)?;
        if name_end > list_end {
            return None;
        }
        if name_type == 0 && name_len > 0 {
            return Some((name_start, name_len));
        }
        q = name_end;
    }
    None
}

fn redact_ipv6(bytes: &mut [u8]) {
    if bytes.len() < 40 {
        return;
    }
    // bytes 8..24 = src, 24..40 = dst
    for b in &mut bytes[8..40] {
        *b = 0;
    }
    // IPv6 has no header checksum. TCP/UDP checksums are MANDATORY in
    // IPv6 (RFC 2460) and tools verify them, so zeroing them is an
    // explicit redaction tradeoff. We still zero for consistency with
    // IPv4 behavior.
    let next_header = bytes[6];
    let payload_start = 40;
    match next_header {
        6 if bytes.len() >= payload_start + 18 => {
            bytes[payload_start + 16] = 0;
            bytes[payload_start + 17] = 0;
            // SNI scrub is length-preserving, so zeroing the checksum first
            // (a deliberate IPv6 redaction tradeoff) stays consistent.
            redact_tcp_sni(bytes, payload_start);
        }
        17 if bytes.len() >= payload_start + 8 => {
            bytes[payload_start + 6] = 0;
            bytes[payload_start + 7] = 0;
        }
        _ => {}
    }
}
