use std::collections::HashMap;
use std::io::{self, ErrorKind, Read};

use super::{HttpBodyFraming, HttpResponse, HttpResponseDecoder, HttpResponseEvent};
use crate::transport::ConnectionStream;

pub fn read_http_response(stream: &mut ConnectionStream, max_bytes: usize) -> Result<HttpResponse, String> {
    read_http_response_io(stream, max_bytes).map_err(|error| error.to_string())
}

pub(super) fn read_http_response_io(stream: &mut ConnectionStream, max_bytes: usize) -> io::Result<HttpResponse> {
    let mut decoder = HttpResponseDecoder::new("GET", max_bytes).map_err(invalid_response)?;
    let mut response = None;
    let mut input = [0u8; 8192];
    loop {
        match decoder.next_event().map_err(invalid_response)? {
            HttpResponseEvent::NeedInput => {
                let count = stream.read(&mut input[..decoder.input_capacity()])?;
                if count == 0 {
                    decoder.finish_input();
                } else {
                    decoder.feed(&input[..count]).map_err(invalid_response)?;
                }
            }
            HttpResponseEvent::Head(head, framing) => {
                if matches!(framing, HttpBodyFraming::Length(length) if length > max_bytes as u64) {
                    return Err(invalid_response("response_too_large"));
                }
                if !matches!(head.status_code, 204 | 205 | 304)
                    && head.headers.get("content-encoding").is_some_and(|coding| {
                        coding
                            .split(',')
                            .map(|value| value.trim_matches([' ', '\t']))
                            .filter(|value| !value.is_empty())
                            .any(|value| !value.eq_ignore_ascii_case("identity"))
                    })
                {
                    return Err("unsupported_content_encoding".to_string());
                }
                response = Some(head);
            }
            HttpResponseEvent::Data(data) => {
                let response = response.as_mut().ok_or_else(|| invalid_response("response_missing_headers"))?;
                if data.len() > max_bytes.saturating_sub(response.body.len()) {
                    return Err(invalid_response("response_too_large"));
                }
                response.body.extend_from_slice(&data);
            }
            HttpResponseEvent::Complete => return response.ok_or_else(|| invalid_response("response_missing_headers")),
        }
    }
}

fn invalid_response(message: &'static str) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, message)
}

pub fn read_http_headers(stream: &mut ConnectionStream, max_bytes: usize) -> Result<Vec<u8>, String> {
    read_http_headers_io(stream, max_bytes).map_err(|error| error.to_string())
}

/// Read the final response head without consuming following body bytes.
/// IO errors retain their kind for timeout and reset classification.
pub fn read_http_headers_io(stream: &mut ConnectionStream, max_bytes: usize) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    let mut byte = [0u8; 1];
    let mut informational = 0;
    for _ in 0..max_bytes {
        if stream.read(&mut byte)? == 0 {
            return Err(io::Error::new(
                ErrorKind::UnexpectedEof,
                if bytes.is_empty() { "unexpected eof" } else { "response_missing_headers" },
            ));
        }
        bytes.push(byte[0]);
        if bytes.ends_with(b"\r\n\r\n") {
            let (_, response) =
                parse_http_response_head(&bytes).map_err(|error| io::Error::new(ErrorKind::InvalidData, error))?;
            if (100..200).contains(&response.status_code) {
                if response.status_code == 101 || informational == super::response_decoder::MAX_INFORMATIONAL {
                    return Err(io::Error::new(ErrorKind::InvalidData, "invalid_framing"));
                }
                informational += 1;
                bytes.clear();
                continue;
            }
            return Ok(bytes);
        }
    }
    Err(io::Error::new(ErrorKind::InvalidData, "response_too_large"))
}

#[cfg(test)]
pub(super) fn response_content_length(headers: &[u8]) -> Result<Option<usize>, String> {
    let (headers, version, _) = parsed_head(headers)?;
    match super::response_decoder::response_framing(&headers, version, 200, &http::Method::GET)
        .map_err(str::to_string)?
    {
        HttpBodyFraming::Length(length) => usize::try_from(length).map(Some).map_err(|_| "invalid_framing".to_string()),
        _ => Ok(None),
    }
}

pub fn parse_http_response(headers: &[u8], body: Vec<u8>) -> Result<HttpResponse, String> {
    let (_, _, mut response) = parsed_head(headers)?;
    response.body = body;
    Ok(response)
}

pub fn parse_http_response_head(headers: &[u8]) -> Result<(http::Version, HttpResponse), String> {
    let (headers, version, response) = parsed_head(headers)?;
    super::response_decoder::response_framing(&headers, version, response.status_code, &http::Method::HEAD)
        .map_err(str::to_string)?;
    Ok((version, response))
}

pub(super) fn parsed_head(bytes: &[u8]) -> Result<(http::HeaderMap, http::Version, HttpResponse), String> {
    let mut terminated = bytes.to_vec();
    if !terminated.ends_with(b"\r\n") {
        terminated.extend_from_slice(b"\r\n");
    }
    if !terminated.ends_with(b"\r\n\r\n") {
        terminated.extend_from_slice(b"\r\n");
    }
    let mut fields = [httparse::EMPTY_HEADER; 128];
    let mut parsed = httparse::Response::new(&mut fields);
    if !parsed.parse(&terminated).map_err(|_| "invalid_framing")?.is_complete() {
        return Err("response_missing_headers".to_string());
    }
    let status_code = parsed.code.filter(|status| (100..=599).contains(status)).ok_or("invalid_framing")?;
    let mut headers = http::HeaderMap::new();
    let mut display = HashMap::<String, String>::new();
    for field in parsed.headers.iter() {
        let name = http::HeaderName::from_bytes(field.name.as_bytes()).map_err(|_| "invalid_framing")?;
        let value = http::HeaderValue::from_bytes(field.value).map_err(|_| "invalid_framing")?;
        let text = String::from_utf8_lossy(field.value);
        display
            .entry(name.to_string())
            .and_modify(|old| {
                old.push_str(", ");
                old.push_str(&text);
            })
            .or_insert_with(|| text.into_owned());
        headers.append(name, value);
    }
    Ok((
        headers,
        if parsed.version == Some(0) { http::Version::HTTP_10 } else { http::Version::HTTP_11 },
        HttpResponse {
            status_code,
            reason: parsed.reason.unwrap_or_default().to_string(),
            headers: display,
            body: Vec::new(),
        },
    ))
}
