pub(crate) struct ParsedHttpResponse {
    pub(crate) status_code: u16,
    pub(crate) headers: Vec<(String, String)>,
    pub(crate) body: Vec<u8>,
}

pub(crate) fn parse_http_response(response: &[u8]) -> Option<ParsedHttpResponse> {
    let headers_end = find_headers_end(response)?;
    let headers_text = std::str::from_utf8(&response[..headers_end]).ok()?;
    let mut lines = headers_text.split("\r\n");
    let status_line = lines.next()?;
    let mut parts = status_line.splitn(3, ' ');
    let http_version = parts.next()?;
    if !matches!(http_version, "HTTP/1.0" | "HTTP/1.1") {
        return None;
    }
    let status_code_raw = parts.next()?;
    if status_code_raw.len() != 3 || !status_code_raw.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let status_code = status_code_raw.parse::<u16>().ok()?;
    let mut headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let (name, value) = line.split_once(':')?;
        let name = name.trim();
        if name.is_empty() {
            return None;
        }
        headers.push((name.to_ascii_lowercase(), value.trim().to_string()));
    }
    Some(ParsedHttpResponse { status_code, headers, body: response[headers_end + 4..].to_vec() })
}

fn find_headers_end(response: &[u8]) -> Option<usize> {
    response.windows(4).position(|window| window == b"\r\n\r\n")
}
