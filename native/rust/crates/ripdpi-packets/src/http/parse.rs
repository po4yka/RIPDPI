use crate::util::{parse_u16_ascii, strncase_find};

/// Return the byte offset just past the last header byte, before the blank
/// line that separates headers from body. Searches for `\r\n\r\n` or `\n\n`.
/// If no terminator is found the entire buffer is treated as headers.
pub(super) fn header_block_end(buffer: &[u8]) -> usize {
    if let Some(pos) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
        return pos + 2;
    }
    if let Some(pos) = buffer.windows(2).position(|w| w == b"\n\n") {
        return pos + 1;
    }
    buffer.len()
}

#[derive(Debug, Clone, Copy)]
pub(super) struct HttpParts {
    pub(super) method_start: usize,
    pub(super) header_name_start: usize,
    pub(super) host_start: usize,
    pub(super) host_end: usize,
    pub(super) port: u16,
}

#[derive(Debug, Clone, Copy)]
pub(super) struct HttpHeaderLine {
    pub(super) start: usize,
    pub(super) end: usize,
    pub(super) value_start: usize,
}

#[derive(Debug, Clone)]
pub(super) struct HttpRequestLayout {
    pub(super) method_start: usize,
    pub(super) request_line_end: usize,
    pub(super) header_lines: Vec<HttpHeaderLine>,
    pub(super) user_agent_index: Option<usize>,
    pub(super) body_start: usize,
}

pub(super) fn http_method_start(buffer: &[u8]) -> Option<usize> {
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

pub(super) fn parse_http_parts(buffer: &[u8]) -> Option<HttpParts> {
    let method_start = http_method_start(buffer)?;
    // Search only within the header block to prevent body content from
    // masquerading as a Host header (F-005).
    let headers = &buffer[..header_block_end(buffer)];
    let marker = strncase_find(headers, b"\nHost:")?;
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

pub(super) fn parse_http_request_layout(buffer: &[u8]) -> Option<HttpRequestLayout> {
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

pub(super) fn get_http_code(data: &[u8]) -> Option<u16> {
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
