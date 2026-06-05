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
    #[error("HTTP upgrade response is not valid UTF-8")]
    ResponseNotUtf8,
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

    let response = read_response_headers(&mut stream)?;
    validate_response(&response)?;
    Ok(stream)
}

/// Async counterpart of [`perform_http_upgrade`]. Reads the response headers one
/// byte at a time and stops exactly at the `\r\n\r\n` boundary so it never
/// consumes any tunnel application data that immediately follows the 101 line.
pub async fn perform_http_upgrade_async<S>(mut stream: S, request: &HttpUpgradeRequest) -> Result<S, HttpUpgradeError>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let request_bytes = build_request_bytes(request)?;
    stream.write_all(&request_bytes).await?;
    stream.flush().await?;

    let mut bytes = Vec::new();
    let mut byte = [0_u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        if bytes.len() >= MAX_RESPONSE_HEADER_BYTES {
            return Err(HttpUpgradeError::ResponseHeadersTooLarge);
        }
        stream.read_exact(&mut byte).await?;
        bytes.push(byte[0]);
    }
    let response = String::from_utf8(bytes).map_err(|_| HttpUpgradeError::ResponseNotUtf8)?;
    validate_response(&response)?;
    Ok(stream)
}

fn read_response_headers<S: Read>(stream: &mut S) -> Result<String, HttpUpgradeError> {
    let mut bytes = Vec::new();
    let mut byte = [0_u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        if bytes.len() >= MAX_RESPONSE_HEADER_BYTES {
            return Err(HttpUpgradeError::ResponseHeadersTooLarge);
        }
        stream.read_exact(&mut byte)?;
        bytes.push(byte[0]);
    }
    String::from_utf8(bytes).map_err(|_| HttpUpgradeError::ResponseNotUtf8)
}

fn validate_response(response: &str) -> Result<(), HttpUpgradeError> {
    let mut lines = response.split("\r\n");
    let status_line = lines.next().unwrap_or_default();
    if status_line != "HTTP/1.1 101 Switching Protocols" {
        return Err(HttpUpgradeError::NotSwitchingProtocols);
    }

    let mut has_connection_upgrade = false;
    let mut has_upgrade_websocket = false;
    for line in lines {
        if line.is_empty() {
            break;
        }
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim().to_ascii_lowercase();
        let value = value.trim().to_ascii_lowercase();
        match name.as_str() {
            "connection" => {
                has_connection_upgrade = value.split(',').any(|token| token.trim() == "upgrade");
            }
            "upgrade" => {
                has_upgrade_websocket = value == "websocket";
            }
            _ => {}
        }
    }
    if !has_connection_upgrade {
        return Err(HttpUpgradeError::MissingConnectionUpgrade);
    }
    if !has_upgrade_websocket {
        return Err(HttpUpgradeError::MissingUpgradeWebsocket);
    }
    Ok(())
}
