use std::collections::HashMap;
use std::io::{ErrorKind, Read};

use crate::transport::ConnectionStream;
use crate::util::find_headers_end;

use super::types::HttpResponse;

pub fn read_http_response(stream: &mut ConnectionStream, max_bytes: usize) -> Result<HttpResponse, String> {
    let mut buf = read_http_headers(stream, max_bytes)?;
    let mut consumed = 0;
    let (header_bytes, mut body, content_length) = loop {
        let header_end = find_headers_end(&buf).ok_or_else(|| "response_missing_headers".to_string())?;
        let header_bytes = buf[..header_end].to_vec();
        let response = parse_http_response(&header_bytes, Vec::new())?;
        if (100..=199).contains(&response.status_code) {
            if response.status_code == 101 {
                return Err("unsupported_switching_protocols".to_string());
            }
            let next = header_end + 4;
            consumed += next;
            if consumed >= max_bytes {
                return Err("response_too_large".to_string());
            }
            buf = read_http_headers_with_initial(stream, max_bytes - consumed, buf[next..].to_vec())?;
            continue;
        }
        if matches!(response.status_code, 204 | 205 | 304) {
            return Ok(response);
        }
        let body = buf[header_end + 4..].to_vec();
        let content_length = response_content_length(&header_bytes)?;
        break (header_bytes, body, content_length);
    };
    if let Some(expected_length) = content_length {
        if expected_length > max_bytes {
            return Err("response_too_large".to_string());
        }
        while body.len() < expected_length {
            let remaining = expected_length - body.len();
            let mut chunk = vec![0u8; remaining.min(4096)];
            let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
            if read == 0 {
                return Err("response_truncated".to_string());
            }
            body.extend_from_slice(&chunk[..read]);
        }
        body.truncate(expected_length);
    } else {
        loop {
            let mut chunk = [0u8; 4096];
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(read) => {
                    body.extend_from_slice(&chunk[..read]);
                    if body.len() > max_bytes {
                        return Err("response_too_large".to_string());
                    }
                }
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    break;
                }
                Err(err) => return Err(err.to_string()),
            }
        }
    }

    parse_http_response(&header_bytes, body)
}

pub(super) fn response_content_length(headers: &[u8]) -> Result<Option<usize>, String> {
    let text = String::from_utf8_lossy(headers);
    let mut length = None;
    let mut transfer_encoding = false;
    for line in text.split("\r\n").skip(1) {
        let Some((name, value)) = line.split_once(':') else { continue };
        if name.trim().eq_ignore_ascii_case("transfer-encoding") {
            transfer_encoding = true;
        }
        if name.trim().eq_ignore_ascii_case("content-length") {
            for value in value.split(',') {
                let parsed = value.trim().parse::<usize>().map_err(|_| "invalid_content_length".to_string())?;
                if length.is_some_and(|previous| previous != parsed) {
                    return Err("invalid_content_length".to_string());
                }
                length = Some(parsed);
            }
        }
    }
    if transfer_encoding && length.is_some() {
        return Err("ambiguous_response_framing".to_string());
    }
    Ok(length)
}

pub fn read_http_headers(stream: &mut ConnectionStream, max_bytes: usize) -> Result<Vec<u8>, String> {
    read_http_headers_with_initial(stream, max_bytes, Vec::new())
}

fn read_http_headers_with_initial(
    stream: &mut ConnectionStream,
    max_bytes: usize,
    mut buf: Vec<u8>,
) -> Result<Vec<u8>, String> {
    let mut chunk = [0u8; 1024];
    loop {
        if buf.len() > max_bytes {
            return Err("response_too_large".to_string());
        }
        if find_headers_end(&buf).is_some() {
            return Ok(buf);
        }
        let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
        if read == 0 {
            if buf.is_empty() {
                return Err("unexpected eof".to_string());
            }
            return Err("response_missing_headers".to_string());
        }
        buf.extend_from_slice(&chunk[..read]);
        if buf.len() > max_bytes {
            return Err("response_too_large".to_string());
        }
        if find_headers_end(&buf).is_some() {
            break;
        }
    }
    Ok(buf)
}

pub fn parse_http_response(headers: &[u8], body: Vec<u8>) -> Result<HttpResponse, String> {
    let text = String::from_utf8_lossy(headers);
    let mut lines = text.split("\r\n");
    let status_line = lines.next().ok_or_else(|| "missing_status_line".to_string())?;
    let mut status_parts = status_line.splitn(3, ' ');
    let _http_version = status_parts.next();
    let status_code = status_parts
        .next()
        .ok_or_else(|| "missing_status_code".to_string())?
        .parse::<u16>()
        .map_err(|err| err.to_string())?;
    let reason = status_parts.next().unwrap_or_default().to_string();
    let mut parsed_headers = HashMap::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((name, value)) = line.split_once(':') {
            parsed_headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }
    Ok(HttpResponse { status_code, reason, headers: parsed_headers, body })
}
