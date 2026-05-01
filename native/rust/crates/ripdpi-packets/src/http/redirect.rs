use super::detect::parse_http;
use super::parse::{get_http_code, header_block_end};
use crate::util::strncase_find;

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
    // Search only within the header block to prevent body content from
    // being misinterpreted as a Location header (F-006).
    let resp_headers = &resp[..header_block_end(resp)];
    let Some(location_marker) = strncase_find(resp_headers, b"\nLocation:") else {
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
    let location_end =
        resp[location_start..line_end].iter().position(|&b| b == b'/').map_or(line_end, |idx| idx + location_start);

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
