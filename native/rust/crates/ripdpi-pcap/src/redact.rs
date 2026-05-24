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
            // TCP: checksum at bytes 16..18 of TCP header
            bytes[payload_start + 16] = 0;
            bytes[payload_start + 17] = 0;
        }
        17 if bytes.len() >= payload_start + 8 => {
            // UDP: checksum at bytes 6..8 of UDP header
            bytes[payload_start + 6] = 0;
            bytes[payload_start + 7] = 0;
        }
        _ => {}
    }
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
    // IPv6 (RFC 2460) and tools verify them, so zeroing them is a
    // documented tradeoff per the design's "modern tooling tolerates"
    // note. We still zero for consistency with IPv4 behavior.
    let next_header = bytes[6];
    let payload_start = 40;
    match next_header {
        6 if bytes.len() >= payload_start + 18 => {
            bytes[payload_start + 16] = 0;
            bytes[payload_start + 17] = 0;
        }
        17 if bytes.len() >= payload_start + 8 => {
            bytes[payload_start + 6] = 0;
            bytes[payload_start + 7] = 0;
        }
        _ => {}
    }
}
