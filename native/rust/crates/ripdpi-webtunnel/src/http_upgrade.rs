use std::io::{Read, Write};

use thiserror::Error;

const MAX_RESPONSE_HEADER_BYTES: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpUpgradeRequest {
    pub host: String,
    pub secret_path: String,
}

#[derive(Debug, Error)]
pub enum HttpUpgradeError {
    #[error("HTTP upgrade I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("secret path must start with '/'")]
    InvalidSecretPath,
    #[error("HTTP upgrade response headers exceeded {MAX_RESPONSE_HEADER_BYTES} bytes")]
    ResponseHeadersTooLarge,
    #[error("HTTP upgrade response is malformed")]
    MalformedResponse,
    #[error("HTTP upgrade response status was not 101 Switching Protocols")]
    NotSwitchingProtocols,
    #[error("HTTP upgrade response missing Connection: upgrade")]
    MissingConnectionUpgrade,
    #[error("HTTP upgrade response missing Upgrade: websocket")]
    MissingUpgradeWebsocket,
}

/// Serialize the WebTunnel HTTP/1.1 Upgrade request. Shared by the sync and
/// async paths so both emit byte-identical bytes on the wire.
fn build_request_bytes(request: &HttpUpgradeRequest) -> Result<Vec<u8>, HttpUpgradeError> {
    if !request.secret_path.starts_with('/') {
        return Err(HttpUpgradeError::InvalidSecretPath);
    }
    Ok(format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n",
        request.secret_path, request.host
    )
    .into_bytes())
}

pub fn perform_http_upgrade<S: Read + Write>(
    mut stream: S,
    request: &HttpUpgradeRequest,
) -> Result<S, HttpUpgradeError> {
    let request_bytes = build_request_bytes(request)?;
    stream.write_all(&request_bytes)?;
    stream.flush()?;

    let mut remaining = MAX_RESPONSE_HEADER_BYTES;
    for _ in 0..5 {
        let response = read_response_headers(&mut stream, &mut remaining)?;
        if validate_response(&response)? {
            return Ok(stream);
        }
    }
    Err(HttpUpgradeError::MalformedResponse)
}

/// Async counterpart of [`perform_http_upgrade`]. Reads the response headers one
/// byte at a time and stops exactly at the `\r\n\r\n` boundary so it never
/// consumes any tunnel application data that immediately follows the 101 line.
///
/// # Cancel safety:
/// NOT cancel-safe: bytes can be consumed or sent. If `S` borrows a stream,
/// the caller must discard that stream after cancellation.
pub async fn perform_http_upgrade_async<S>(mut stream: S, request: &HttpUpgradeRequest) -> Result<S, HttpUpgradeError>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let request_bytes = build_request_bytes(request)?;
    stream.write_all(&request_bytes).await?;
    stream.flush().await?;

    let mut remaining = MAX_RESPONSE_HEADER_BYTES;
    for _ in 0..5 {
        let mut bytes = Vec::new();
        let mut byte = [0_u8; 1];
        while !bytes.ends_with(b"\r\n\r\n") {
            if remaining == 0 {
                return Err(HttpUpgradeError::ResponseHeadersTooLarge);
            }
            stream.read_exact(&mut byte).await?;
            remaining -= 1;
            bytes.push(byte[0]);
        }
        if validate_response(&bytes)? {
            return Ok(stream);
        }
    }
    Err(HttpUpgradeError::MalformedResponse)
}

fn read_response_headers<S: Read>(stream: &mut S, remaining: &mut usize) -> Result<Vec<u8>, HttpUpgradeError> {
    let mut bytes = Vec::new();
    let mut byte = [0_u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        if *remaining == 0 {
            return Err(HttpUpgradeError::ResponseHeadersTooLarge);
        }
        stream.read_exact(&mut byte)?;
        *remaining -= 1;
        bytes.push(byte[0]);
    }
    Ok(bytes)
}

fn validate_response(bytes: &[u8]) -> Result<bool, HttpUpgradeError> {
    let mut headers = vec![httparse::EMPTY_HEADER; MAX_RESPONSE_HEADER_BYTES / 4];
    let mut response = httparse::Response::new(&mut headers);
    if !response.parse(bytes).map_err(|_| HttpUpgradeError::MalformedResponse)?.is_complete() {
        return Err(HttpUpgradeError::MalformedResponse);
    }
    if response.version != Some(1) {
        return Err(HttpUpgradeError::NotSwitchingProtocols);
    }
    if response.code.is_some_and(|code| (100..200).contains(&code) && code != 101) {
        return Ok(false);
    }
    if response.code != Some(101) {
        return Err(HttpUpgradeError::NotSwitchingProtocols);
    }
    let has_token = |name: &str, token: &[u8]| {
        response.headers.iter().filter(|header| header.name.eq_ignore_ascii_case(name)).any(|header| {
            header.value.split(|byte| *byte == b',').any(|value| value.trim_ascii().eq_ignore_ascii_case(token))
        })
    };
    if !has_token("connection", b"upgrade") {
        return Err(HttpUpgradeError::MissingConnectionUpgrade);
    }
    if !has_token("upgrade", b"websocket") {
        return Err(HttpUpgradeError::MissingUpgradeWebsocket);
    }
    Ok(true)
}
