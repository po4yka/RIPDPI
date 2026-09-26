use std::io::{self, Read, Write};

use crate::constants::SNAPLEN_DEFAULT;
use crate::reader::PcapReader;
use crate::writer::PcapWriter;

/// Stream a PCAP with endpoint addresses, unknown option values, and
/// transport payloads cleared. Packet lengths, timestamps, fixed header
/// fields, and the IPv6 Jumbo Payload length remain for diagnostics.
///
/// The name is kept for existing callers. It does not promise full anonymity:
/// packet timing, sizes, ports, and fixed header fields remain visible.
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

/// Clear packet content in place. Unknown or incomplete IP headers are
/// cleared in full so their bytes cannot pass through the export.
pub fn redact_in_place(bytes: &mut [u8]) {
    match bytes.first().map(|byte| byte >> 4) {
        Some(4) => redact_ipv4(bytes),
        Some(6) => redact_ipv6(bytes),
        Some(_) => bytes.fill(0),
        None => {}
    }
}

fn redact_ipv4(bytes: &mut [u8]) {
    let header_len = usize::from(bytes[0] & 0x0f) * 4;
    if bytes.len() < 20 || header_len < 20 || header_len > bytes.len() {
        bytes.fill(0);
        return;
    }

    bytes[12..20].fill(0); // Source and destination addresses.
    bytes[20..header_len].fill(0); // Every IPv4 option value.
    let fragment_offset = u16::from_be_bytes([bytes[6], bytes[7]]) & 0x1fff;
    if fragment_offset == 0 {
        redact_transport(bytes, header_len, bytes[9]);
    } else {
        bytes[header_len..].fill(0); // Non-initial fragment data.
    }

    bytes[10..12].fill(0);
    let mut sum = 0u32;
    for word in bytes[..header_len].as_chunks::<2>().0 {
        sum += u32::from(u16::from_be_bytes([word[0], word[1]]));
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    bytes[10..12].copy_from_slice(&(!(sum as u16)).to_be_bytes());
}

fn redact_ipv6(bytes: &mut [u8]) {
    if bytes.len() < 40 {
        bytes.fill(0);
        return;
    }
    bytes[8..40].fill(0); // Source and destination addresses.
    match ipv6_transport(bytes) {
        Some((protocol, start)) => redact_transport(bytes, start, protocol),
        None => {
            bytes[6] = 59; // No Next Header: the extension chain is incomplete.
            bytes[40..].fill(0);
        }
    }
}

/// Replace options with Pad1 bytes, except the Jumbo Payload length that
/// analyzers need when the IPv6 base header has Payload Length zero.
fn redact_ipv6_options(bytes: &mut [u8], mut at: usize, end: usize, mut allow_jumbo: bool) {
    while at < end {
        if bytes[at] == 0 {
            at += 1;
            continue;
        }
        if at + 2 > end {
            bytes[at..end].fill(0);
            return;
        }
        let option_end = at + 2 + usize::from(bytes[at + 1]);
        if option_end > end {
            bytes[at..end].fill(0);
            return;
        }
        let is_jumbo =
            allow_jumbo && bytes[at] == 0xc2 && bytes[at + 1] == 4 && (bytes[at + 2] != 0 || bytes[at + 3] != 0);
        if is_jumbo {
            allow_jumbo = false;
        } else {
            bytes[at..option_end].fill(0);
        }
        at = option_end;
    }
}

/// Keep only framing bytes needed to find the transport header. All option
/// values except the Jumbo Payload length, routing data, fragment IDs, and
/// AH data are cleared.
fn ipv6_transport(bytes: &mut [u8]) -> Option<(u8, usize)> {
    let mut next = *bytes.get(6)?;
    let mut offset = 40usize;
    loop {
        let rest = bytes.get(offset..)?;
        let current = next;
        let (following, len) = match current {
            0 | 43 | 60 => {
                let header = rest.get(..2)?;
                (header[0], (usize::from(header[1]) + 1) * 8)
            }
            44 => {
                let header = rest.get(..8)?;
                if u16::from_be_bytes([header[2], header[3]]) & 0xfff8 != 0 {
                    return None;
                }
                (header[0], 8)
            }
            51 => {
                let header = rest.get(..2)?;
                (header[0], (usize::from(header[1]) + 2) * 4)
            }
            _ => return Some((next, offset)),
        };
        let end = offset.checked_add(len)?;
        bytes.get(..end)?;
        let allow_jumbo = current == 0 && offset == 40 && bytes[4..6] == [0, 0];
        match current {
            0 | 60 => redact_ipv6_options(bytes, offset + 2, end, allow_jumbo),
            51 => bytes[offset + 2..end].fill(0),
            43 | 44 => bytes[offset + 4..end].fill(0),
            _ => unreachable!(),
        }
        next = following;
        offset = end;
    }
}

/// Preserve fixed TCP/UDP fields while clearing options and all packet data.
/// Unknown protocols and incomplete transport headers are cleared in full.
fn redact_transport(bytes: &mut [u8], start: usize, protocol: u8) {
    match protocol {
        6 if start.checked_add(20).is_some_and(|end| end <= bytes.len()) => {
            let header_len = usize::from(bytes[start + 12] >> 4) * 4;
            if header_len < 20 || start.checked_add(header_len).is_none_or(|end| end > bytes.len()) {
                bytes[start..].fill(0);
                return;
            }
            bytes[start + 16..start + 18].fill(0); // TCP checksum.
            bytes[start + 20..].fill(0); // TCP options and application data.
        }
        17 if start.checked_add(8).is_some_and(|end| end <= bytes.len()) => {
            bytes[start + 6..start + 8].fill(0); // UDP checksum.
            bytes[start + 8..].fill(0); // UDP application data.
        }
        _ => bytes[start..].fill(0),
    }
}
