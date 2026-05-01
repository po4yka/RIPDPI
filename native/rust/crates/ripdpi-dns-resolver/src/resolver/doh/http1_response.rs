use tokio::io::{AsyncRead, AsyncReadExt};

use super::chunked_body::read_chunked_doh_body;
use super::{MAX_DOH_HEADER_BYTES, MAX_DOH_RESPONSE_BYTES};
use crate::types::EncryptedDnsError;

#[derive(Debug, Clone, Copy)]
pub(super) struct DohHttpResponseHead {
    status: reqwest::StatusCode,
    content_length: Option<usize>,
    chunked: bool,
}

pub(super) async fn read_doh_response<S>(stream: &mut S) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    let (head, body) = read_doh_response_head(stream).await?;
    if !head.status.is_success() {
        return Err(EncryptedDnsError::HttpStatus(head.status));
    }

    if head.chunked {
        return read_chunked_doh_body(stream, body).await;
    }

    if let Some(content_length) = head.content_length {
        return read_doh_body_with_content_length(stream, body, content_length).await;
    }

    read_doh_body_until_eof(stream, body).await
}

pub(super) async fn read_doh_response_head<S>(
    stream: &mut S,
) -> Result<(DohHttpResponseHead, Vec<u8>), EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    let mut response = Vec::new();

    loop {
        if let Some(header_end) = find_http_header_terminator(&response) {
            if header_end > MAX_DOH_HEADER_BYTES {
                return Err(EncryptedDnsError::Request("DoH response headers exceed maximum size".to_string()));
            }

            let head = parse_doh_http_response_head(&response[..header_end])?;
            let body = response[header_end + 4..].to_vec();
            return Ok((head, body));
        }

        if response.len() > MAX_DOH_HEADER_BYTES {
            return Err(EncryptedDnsError::Request("DoH response headers exceed maximum size".to_string()));
        }

        if read_more_doh_bytes(stream, &mut response).await? == 0 {
            return Err(EncryptedDnsError::Request("DoH response missing HTTP header terminator".to_string()));
        }
    }
}

fn parse_doh_http_response_head(header_bytes: &[u8]) -> Result<DohHttpResponseHead, EncryptedDnsError> {
    let mut lines = header_bytes.split(|byte| *byte == b'\n');
    let status_line = lines
        .next()
        .map(trim_ascii)
        .ok_or_else(|| EncryptedDnsError::Request("DoH response missing status line".to_string()))?;
    let status_line = std::str::from_utf8(status_line).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| EncryptedDnsError::Request("DoH response missing status code".to_string()))?
        .parse::<u16>()
        .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    let status = reqwest::StatusCode::from_u16(status).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;

    let mut chunked = false;
    let mut content_length = None;
    for line in lines {
        let line = trim_ascii(line);
        if line.is_empty() {
            continue;
        }
        let Some(separator) = line.iter().position(|byte| *byte == b':') else {
            continue;
        };
        let name = &line[..separator];
        let value = &line[separator + 1..];
        let value = trim_ascii(value);
        if name.eq_ignore_ascii_case(b"transfer-encoding") {
            let encoding = std::str::from_utf8(value).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
            chunked = encoding.split(',').any(|item| item.trim().eq_ignore_ascii_case("chunked"));
        }
        if name.eq_ignore_ascii_case(b"content-length") {
            let parsed = std::str::from_utf8(value)
                .map_err(|err| EncryptedDnsError::Request(err.to_string()))?
                .trim()
                .parse::<usize>()
                .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
            content_length = Some(parsed);
        }
    }

    Ok(DohHttpResponseHead { status, content_length, chunked })
}

pub(super) async fn read_doh_body_with_content_length<S>(
    stream: &mut S,
    mut body: Vec<u8>,
    content_length: usize,
) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    if content_length > MAX_DOH_RESPONSE_BYTES {
        return Err(EncryptedDnsError::Request("DoH response Content-Length exceeds maximum size".to_string()));
    }

    if body.len() >= content_length {
        body.truncate(content_length);
        return Ok(body);
    }

    while body.len() < content_length {
        if read_more_doh_bytes(stream, &mut body).await? == 0 {
            return Err(EncryptedDnsError::Request("DoH response body shorter than Content-Length".to_string()));
        }
    }

    body.truncate(content_length);
    Ok(body)
}

async fn read_doh_body_until_eof<S>(stream: &mut S, mut body: Vec<u8>) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    if body.len() > MAX_DOH_RESPONSE_BYTES {
        return Err(EncryptedDnsError::Request("DoH response body exceeds maximum size".to_string()));
    }

    loop {
        let previous_len = body.len();
        if read_more_doh_bytes(stream, &mut body).await? == 0 {
            return Ok(body);
        }
        if body.len() > MAX_DOH_RESPONSE_BYTES {
            return Err(EncryptedDnsError::Request("DoH response body exceeds maximum size".to_string()));
        }
        if body.len() == previous_len {
            return Ok(body);
        }
    }
}

pub(super) async fn read_more_doh_bytes<S>(stream: &mut S, buffer: &mut Vec<u8>) -> Result<usize, EncryptedDnsError>
where
    S: AsyncRead + Unpin,
{
    let mut chunk = [0u8; 4096];
    match stream.read(&mut chunk).await {
        Ok(0) => Ok(0),
        Ok(read) => {
            buffer.extend_from_slice(&chunk[..read]);
            Ok(read)
        }
        Err(err) if should_ignore_tls_eof(&err) && !buffer.is_empty() => Ok(0),
        Err(err) => Err(EncryptedDnsError::Request(err.to_string())),
    }
}

fn trim_ascii(bytes: &[u8]) -> &[u8] {
    let start = bytes.iter().position(|byte| !byte.is_ascii_whitespace()).unwrap_or(bytes.len());
    let end = bytes.iter().rposition(|byte| !byte.is_ascii_whitespace()).map_or(start, |index| index + 1);
    &bytes[start..end]
}

fn find_http_header_terminator(bytes: &[u8]) -> Option<usize> {
    bytes.windows(4).position(|window| window == b"\r\n\r\n")
}

fn should_ignore_tls_eof(error: &std::io::Error) -> bool {
    error.kind() == std::io::ErrorKind::UnexpectedEof && error.to_string().contains("close_notify")
}
