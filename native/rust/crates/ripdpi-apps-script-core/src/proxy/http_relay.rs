use std::io;
use std::sync::Arc;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::telemetry::SharedTelemetryState;

/// Hard ceiling on a client-declared request body size. A client speaks to this
/// loopback proxy over the local SOCKS bridge; an untrusted `Content-Length`
/// header (e.g. `Content-Length: 9999999999`) must never drive a pre-sized
/// allocation, or it OOM-kills the VPN process. Requests larger than this are
/// rejected before any reservation; the read loop still bounds actual reads by
/// the declared length, so this only caps the up-front `Vec::with_capacity`.
const MAX_REQUEST_BODY_BYTES: usize = 16 * 1024 * 1024;

// NOT cancel-safe: loops over handle_request, which can be cancelled mid
// request-read or mid response-write, losing consumed bytes / truncating the
// response. Cancellation aborts the whole keep-alive connection.
pub(crate) async fn relay_raw(
    mut stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    telemetry.record_target(&format!("{host}:{port}"));
    let scheme = if port == 443 { "https" } else { "http" };
    loop {
        match handle_request(&mut stream, host, port, scheme, relay.as_ref()).await? {
            true => continue,
            false => return Ok(()),
        }
    }
}

// NOT cancel-safe: reads the request across multiple awaits then writes the
// response with write_all + flush as separate awaits; cancellation can drop
// bytes already consumed from the stream or leave a truncated response.
pub(crate) async fn handle_request<S>(
    stream: &mut S,
    host: &str,
    port: u16,
    scheme: &str,
    relay: &AppsScriptDomainFronter,
) -> io::Result<bool>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let Some((head, leftover)) = read_http_head(stream).await? else {
        return Ok(false);
    };
    let Some((method, path, _version, headers)) = parse_request_head(&head) else {
        return Ok(false);
    };
    let body = read_body(stream, &leftover, &headers).await?;

    if method.eq_ignore_ascii_case("OPTIONS") {
        let origin = header_value(&headers, "origin").unwrap_or("*");
        let request_method = header_value(&headers, "access-control-request-method")
            .unwrap_or("GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD");
        let request_headers = header_value(&headers, "access-control-request-headers").unwrap_or("*");
        let response = format!(
            "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: {origin}\r\nAccess-Control-Allow-Methods: {request_method}\r\nAccess-Control-Allow-Headers: {request_headers}\r\nAccess-Control-Allow-Credentials: true\r\nAccess-Control-Max-Age: 86400\r\nContent-Length: 0\r\n\r\n"
        );
        stream.write_all(response.as_bytes()).await?;
        stream.flush().await?;
        let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
        return Ok(!close);
    }

    let default_port = if scheme == "https" { 443 } else { 80 };
    let url = if path.starts_with("http://") || path.starts_with("https://") {
        path.clone()
    } else if port == default_port {
        format!("{scheme}://{host}{path}")
    } else {
        format!("{scheme}://{host}:{port}{path}")
    };
    let response = relay.relay(&method, &url, &headers, &body).await;
    stream.write_all(&response).await?;
    stream.flush().await?;
    let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
    Ok(!close)
}

pub(crate) fn looks_like_http(bytes: &[u8]) -> bool {
    ["GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE "]
        .iter()
        .any(|method| bytes.starts_with(method.as_bytes()))
}

// NOT cancel-safe: accumulates header bytes into a local buffer across reads;
// cancellation drops the buffer, permanently losing bytes already read off the
// stream.
async fn read_http_head<S>(stream: &mut S) -> io::Result<Option<(Vec<u8>, Vec<u8>)>>
where
    S: AsyncRead + Unpin,
{
    let mut buffer = Vec::with_capacity(4_096);
    let mut scratch = [0u8; 4_096];
    loop {
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return if buffer.is_empty() {
                Ok(None)
            } else {
                Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-header"))
            };
        }
        buffer.extend_from_slice(&scratch[..read]);
        if let Some(position) = find_headers_end(&buffer) {
            let head = buffer[..position].to_vec();
            let leftover = buffer[position..].to_vec();
            return Ok(Some((head, leftover)));
        }
        if buffer.len() > 1024 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "headers too large"));
        }
    }
}

fn parse_request_head(head: &[u8]) -> Option<(String, String, String, Vec<(String, String)>)> {
    let head = std::str::from_utf8(head).ok()?;
    let mut lines = head.split("\r\n");
    let first_line = lines.next()?;
    let mut parts = first_line.splitn(3, ' ');
    let method = parts.next()?.to_string();
    let target = parts.next()?.to_string();
    let version = parts.next().unwrap_or("HTTP/1.1").to_string();
    if !matches!(
        method.as_str(),
        "GET" | "POST" | "PUT" | "DELETE" | "HEAD" | "OPTIONS" | "PATCH" | "TRACE" | "CONNECT"
    ) {
        return None;
    }
    let headers = lines
        .take_while(|line| !line.is_empty())
        .filter_map(|line| line.split_once(':').map(|(key, value)| (key.trim().to_string(), value.trim().to_string())))
        .collect::<Vec<_>>();
    Some((method, target, version, headers))
}

// NOT cancel-safe: may write a 100-continue line and then accumulates body
// bytes into a local buffer across reads; cancellation loses the partial body
// already consumed from the stream.
async fn read_body<S>(stream: &mut S, leftover: &[u8], headers: &[(String, String)]) -> io::Result<Vec<u8>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let transfer_encoding = header_value(headers, "transfer-encoding");
    let is_chunked =
        transfer_encoding.is_some_and(|value| value.split(',').any(|part| part.trim().eq_ignore_ascii_case("chunked")));
    let content_length = header_value(headers, "content-length")
        .map(str::parse::<usize>)
        .transpose()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid Content-Length"))?;

    if transfer_encoding.is_some() && !is_chunked {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported Transfer-Encoding"));
    }
    if is_chunked && content_length.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "request contains both chunked transfer-encoding and content-length",
        ));
    }

    if expects_100_continue(headers) && (is_chunked || content_length.is_some()) {
        stream.write_all(b"HTTP/1.1 100 Continue\r\n\r\n").await?;
        stream.flush().await?;
    }

    if is_chunked {
        return read_chunked_request_body(stream, leftover.to_vec()).await;
    }

    let Some(content_length) = content_length else {
        return Ok(Vec::new());
    };
    if content_length > MAX_REQUEST_BODY_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Content-Length exceeds maximum request body size"));
    }
    let mut body = Vec::with_capacity(content_length);
    body.extend_from_slice(&leftover[..leftover.len().min(content_length)]);
    let mut scratch = [0u8; 8_192];
    while body.len() < content_length {
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-body"));
        }
        let needed = content_length - body.len();
        body.extend_from_slice(&scratch[..read.min(needed)]);
    }
    Ok(body)
}

// NOT cancel-safe: decodes chunks into an output buffer across many reads;
// cancellation discards partially decoded data already taken from the stream.
async fn read_chunked_request_body<S>(stream: &mut S, mut buffer: Vec<u8>) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    let mut output = Vec::new();
    let mut scratch = [0u8; 8_192];

    loop {
        let line = read_crlf_line(stream, &mut buffer, &mut scratch).await?;
        if line.is_empty() {
            continue;
        }
        let line = std::str::from_utf8(&line)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid chunk size line"))?
            .trim()
            .to_string();
        let size = usize::from_str_radix(line.split(';').next().unwrap_or_default(), 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid chunk size"))?;
        if size == 0 {
            loop {
                if read_crlf_line(stream, &mut buffer, &mut scratch).await?.is_empty() {
                    return Ok(output);
                }
            }
        }
        fill_buffer(stream, &mut buffer, &mut scratch, size + 2).await?;
        output.extend_from_slice(&buffer[..size]);
        buffer.drain(..size + 2);
    }
}

// NOT cancel-safe: appends reads into the caller-owned buffer until a CRLF is
// found; cancellation between the read await and the buffer append loses bytes
// already pulled from the stream.
async fn read_crlf_line<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8]) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    loop {
        if let Some(position) = buffer.windows(2).position(|window| window == b"\r\n") {
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
        }
        let read = stream.read(scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF in chunked body"));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
}

// NOT cancel-safe: reads until the caller-owned buffer holds `wanted` bytes;
// cancellation after a read await completes but before the append loses bytes
// already consumed from the stream.
async fn fill_buffer<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8], wanted: usize) -> io::Result<()>
where
    S: AsyncRead + Unpin,
{
    while buffer.len() < wanted {
        let read = stream.read(scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF in chunked body"));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
    Ok(())
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|position| position + 4)
}

fn header_value<'a>(headers: &'a [(String, String)], name: &str) -> Option<&'a str> {
    headers.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
}

fn expects_100_continue(headers: &[(String, String)]) -> bool {
    header_value(headers, "expect")
        .is_some_and(|value| value.split(',').any(|part| part.trim().eq_ignore_ascii_case("100-continue")))
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncWriteExt, duplex};

    use super::*;

    #[tokio::test(flavor = "current_thread")]
    async fn read_chunked_request_body_decodes_chunks() {
        let (mut writer, mut reader) = duplex(256);
        let task = tokio::spawn(async move {
            writer.write_all(b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").await.expect("write chunked body");
        });

        let body = read_chunked_request_body(&mut reader, Vec::new()).await.expect("decode body");
        task.await.expect("writer task");
        assert_eq!(body, b"hello world");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn read_body_rejects_oversized_content_length() {
        // A client declaring a multi-GB Content-Length must be rejected before
        // the body Vec is pre-sized, or the reservation OOM-kills the process.
        let (_writer, mut reader) = duplex(64);
        let headers = vec![("Content-Length".to_string(), "9999999999".to_string())];

        let error = read_body(&mut reader, b"", &headers).await.expect_err("oversized length must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn read_body_accepts_content_length_at_cap_boundary() {
        // A declared length exactly at the cap is allowed; the read loop bounds
        // actual reads, so an EOF before the full body yields UnexpectedEof
        // rather than the InvalidData rejection.
        let (writer, mut reader) = duplex(64);
        drop(writer);
        let headers = vec![("Content-Length".to_string(), MAX_REQUEST_BODY_BYTES.to_string())];

        let error = read_body(&mut reader, b"", &headers).await.expect_err("EOF before full body");
        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
    }
}
