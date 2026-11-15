use std::net::Ipv6Addr;

use super::constants::FAT_HEADER_THRESHOLD_BYTES;

pub fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
}

pub fn parse_content_length(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.split("\r\n") {
        let Some((name, value)) = line.split_once(':') else { continue };
        if name.trim().eq_ignore_ascii_case("content-length") {
            return value.trim().parse::<usize>().ok();
        }
    }
    None
}

pub fn fat_threshold_reached(bytes_sent: usize) -> bool {
    bytes_sent >= FAT_HEADER_THRESHOLD_BYTES.saturating_sub(2 * 1024)
}

pub fn late_stage_cutoff(bytes_sent: usize, responses_seen: usize) -> bool {
    fat_threshold_reached(bytes_sent) || (responses_seen >= 1 && bytes_sent >= 8 * 1024)
}

/// Validates an ASCII DNS name or IP literal with an optional TCP port for a fat-header Host.
pub fn is_valid_fat_header_authority(value: &str) -> bool {
    if value.len() > 259 {
        return false;
    }
    let suffix = if let Some(literal) = value.strip_prefix('[') {
        let Some((ip, suffix)) = literal.split_once(']') else { return false };
        if ip.parse::<Ipv6Addr>().is_err() {
            return false;
        }
        suffix
    } else {
        let (host, suffix) = value.split_at(value.find(':').unwrap_or(value.len()));
        let name = host.strip_suffix('.').unwrap_or(host);
        if host.len() > 253
            || !name.split('.').all(|label| {
                (1..=63).contains(&label.len())
                    && label.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
            })
        {
            return false;
        }
        suffix
    };
    suffix.is_empty()
        || suffix.strip_prefix(':').is_some_and(|port| {
            port.bytes().all(|byte| byte.is_ascii_digit()) && port.parse::<u16>().is_ok_and(|port| port != 0)
        })
}
