use std::collections::HashMap;
use std::io::{ErrorKind, Read};
use std::time::Instant;

use crate::transport::ConnectionStream;
use crate::util::{find_headers_end, parse_content_length};

use super::types::HttpResponse;

pub fn read_http_response(stream: &mut ConnectionStream, max_bytes: usize) -> Result<HttpResponse, String> {
    read_http_response_with_ttfb(stream, max_bytes).map(|(response, _)| response)
}

pub(crate) fn read_http_response_with_ttfb(
    stream: &mut ConnectionStream,
    max_bytes: usize,
) -> Result<(HttpResponse, u64), String> {
    let (buf, ttfb_ms) = read_http_headers_with_ttfb(stream, max_bytes)?;
    let header_end = find_headers_end(&buf).ok_or_else(|| "response_missing_headers".to_string())?;
    let header_bytes = buf[..header_end].to_vec();
    let mut body = buf[header_end + 4..].to_vec();
    let content_length = parse_content_length(&header_bytes);
    if let Some(expected_length) = content_length {
        if expected_length > max_bytes {
            return Err("response_too_large".to_string());
        }
        while body.len() < expected_length {
            let remaining = expected_length - body.len();
            let mut chunk = vec![0u8; remaining.min(4096)];
            let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read]);
        }
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

    parse_http_response(&header_bytes, body).map(|response| (response, ttfb_ms))
}

pub fn read_http_headers(stream: &mut ConnectionStream, max_bytes: usize) -> Result<Vec<u8>, String> {
    read_http_headers_with_ttfb(stream, max_bytes).map(|(headers, _)| headers)
}

fn read_http_headers_with_ttfb(stream: &mut ConnectionStream, max_bytes: usize) -> Result<(Vec<u8>, u64), String> {
    let started = Instant::now();
    let mut ttfb_ms = None;
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1024];
    loop {
        let read = stream.read(&mut chunk).map_err(|err| err.to_string())?;
        if read == 0 {
            if buf.is_empty() {
                return Err("unexpected eof".to_string());
            }
            break;
        }
        ttfb_ms.get_or_insert_with(|| started.elapsed().as_millis() as u64);
        buf.extend_from_slice(&chunk[..read]);
        if buf.len() > max_bytes {
            return Err("response_too_large".to_string());
        }
        if find_headers_end(&buf).is_some() {
            break;
        }
    }
    Ok((buf, ttfb_ms.unwrap_or_default()))
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
