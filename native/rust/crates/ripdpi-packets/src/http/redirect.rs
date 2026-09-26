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
    let mut location_start = location_marker + b"\nLocation:".len();
    let Some(line_end_rel) = resp_headers.get(location_start..).and_then(|line| line.iter().position(|&b| b == b'\n'))
    else {
        return false;
    };
    let mut line_end = location_start + line_end_rel;
    while line_end > location_start && resp_headers[line_end - 1].is_ascii_whitespace() {
        line_end -= 1;
    }
    while location_start < line_end && resp_headers[location_start].is_ascii_whitespace() {
        location_start += 1;
    }
    let location = &resp_headers[location_start..line_end];
    let authority = if location.get(..7).is_some_and(|prefix| prefix.eq_ignore_ascii_case(b"http://")) {
        &location[7..]
    } else if location.get(..8).is_some_and(|prefix| prefix.eq_ignore_ascii_case(b"https://")) {
        &location[8..]
    } else if location.starts_with(b"//") {
        &location[2..]
    } else {
        return false; // relative URL stays on the request host
    };
    let end = authority.iter().position(|b| matches!(*b, b'/' | b'?' | b'#')).unwrap_or(authority.len());
    let authority = &authority[..end];
    let authority = authority.rsplit(|&b| b == b'@').next().unwrap_or(authority);
    let location_host = if authority.starts_with(b"[") {
        let Some(end) = authority.iter().position(|&b| b == b']') else {
            return false;
        };
        &authority[1..end]
    } else {
        let port_start = authority.iter().rposition(|&b| b == b':');
        match port_start {
            Some(at) if authority[at + 1..].iter().all(u8::is_ascii_digit) => &authority[..at],
            _ => authority,
        }
    };
    if location_host.is_empty() {
        return false;
    }

    let same_host = location_host.eq_ignore_ascii_case(host);
    let child_of_request = location_host.len() > host.len()
        && location_host[location_host.len() - host.len() - 1] == b'.'
        && location_host[location_host.len() - host.len()..].eq_ignore_ascii_case(host);
    let parent_of_request = host.len() > location_host.len()
        && host[host.len() - location_host.len() - 1] == b'.'
        && host[host.len() - location_host.len()..].eq_ignore_ascii_case(location_host);
    !(same_host || child_of_request || parent_of_request)
}
