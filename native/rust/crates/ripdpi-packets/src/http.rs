use crate::types::{HttpHost, HttpMarkerInfo, PacketMutation, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL};
use crate::util::{parse_u16_ascii, strncase_find};

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

fn get_http_code(data: &[u8]) -> Option<u16> {
    if data.len() < 13 || &data[..7] != b"HTTP/1." || !data[12..].contains(&b'\n') {
        return None;
    }
    let digits_end = data[9..].iter().position(u8::is_ascii_whitespace).map(|idx| idx + 9)?;
    let code = parse_u16_ascii(&data[9..digits_end])?;
    if !(100..=511).contains(&code) {
        return None;
    }
    Some(code)
}

pub fn is_http(buffer: &[u8]) -> bool {
    http_method_start(buffer).is_some()
}

pub fn parse_http(buffer: &[u8]) -> Option<HttpHost<'_>> {
    let markers = http_marker_info(buffer)?;
    Some(HttpHost { host: &buffer[markers.host_start..markers.host_end], port: markers.port })
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

#[cfg(test)]
mod tests {
    use super::*;

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
    fn is_http_range_boundaries() {
        let connect = b"CONNECT host:443 HTTP/1.1\r\nHost: host\r\n\r\n";
        assert!(is_http(connect));
        let shifted = b"\nGET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(is_http(shifted));
        let trace = b"TRACE / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(is_http(trace));
        let below = b"BELOW / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        assert!(!is_http(below));
        let above = b"UPDATE / HTTP/1.1\r\nHost: e.com\r\n\r\n";
        assert!(!is_http(above));
    }

    #[test]
    fn get_http_code_range_boundaries() {
        assert_eq!(get_http_code(b"HTTP/1.1 100 Continue\r\n\r\n"), Some(100));
        assert_eq!(get_http_code(b"HTTP/1.1 511 Not Extended\r\n\r\n"), Some(511));
        assert_eq!(get_http_code(b"HTTP/1.1 099 Below\r\n\r\n"), None);
        assert_eq!(get_http_code(b"HTTP/1.1 512 Above\r\n\r\n"), None);
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
    fn is_http_redirect_same_suffix_not_redirect() {
        let req = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
        let same = b"HTTP/1.1 302 Found\r\nLocation: https://other.example.com/page\r\n\r\n";
        assert!(!is_http_redirect(req, same));
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

    proptest::proptest! {
        #[test]
        fn parse_http_never_panics(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..512)) {
            let _ = is_http(&data);
            let _ = parse_http(&data);
            let _ = is_http_redirect(&data, &data);
        }
    }
}
