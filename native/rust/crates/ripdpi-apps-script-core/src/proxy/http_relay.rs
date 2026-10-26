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

/// # Cancel safety
///
/// NOT cancel-safe: loops over handle_request, which can be cancelled mid
/// request-read or mid response-write, losing consumed bytes / truncating the
/// response. Cancellation aborts the whole keep-alive connection.
pub(crate) async fn relay_raw(
    mut stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    telemetry.record_target(&format!("{}:{port}", authority_host(host)));
    let scheme = if port == 443 { "https" } else { "http" };
    let mut pending = Vec::new();
    loop {
        match handle_request(&mut stream, &mut pending, host, port, scheme, relay.as_ref()).await? {
            true => continue,
            false => return Ok(()),
        }
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: reads the request across multiple awaits then writes the
/// response with write_all + flush as separate awaits; cancellation can drop
/// bytes already consumed from the stream or leave a truncated response.
pub(crate) async fn handle_request<S>(
    stream: &mut S,
    pending: &mut Vec<u8>,
    host: &str,
    port: u16,
    scheme: &str,
    relay: &AppsScriptDomainFronter,
) -> io::Result<bool>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let Some(head) = read_http_head(stream, pending).await? else {
        return Ok(false);
    };
    let Some((method, path, _version, headers)) = parse_request_head(&head) else {
        return Ok(false);
    };
    let body = read_body(stream, pending, &headers).await?;

    if method.eq_ignore_ascii_case("OPTIONS") {
        let response = local_options_response(&headers, host);
        stream.write_all(response.as_bytes()).await?;
        stream.flush().await?;
        let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
        return Ok(!close);
    }

    let url = request_url(scheme, host, port, &path);
    let response = relay.relay(&method, &url, &headers, &body).await;
    stream.write_all(&response).await?;
    stream.flush().await?;
    let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
    Ok(!close)
}

/// Host as it appears in a URL authority: RFC 3986 3.2.2 puts an IPv6
/// literal in brackets.
pub(crate) fn authority_host(host: &str) -> std::borrow::Cow<'_, str> {
    if host.contains(':') && !host.starts_with('[') { format!("[{host}]").into() } else { host.into() }
}

/// Absolute URL that Apps Script fetches for a request target.
fn request_url(scheme: &str, host: &str, port: u16, path: &str) -> String {
    if path.starts_with("http://") || path.starts_with("https://") {
        return path.to_string();
    }
    let default_port = if scheme == "https" { 443 } else { 80 };
    let host = authority_host(host);
    if port == default_port { format!("{scheme}://{host}{path}") } else { format!("{scheme}://{host}:{port}{path}") }
}

/// Answers an OPTIONS request locally: Apps Script UrlFetchApp cannot send
/// OPTIONS, so the request never reaches the origin.
///
/// Only a CORS preflight (Origin plus Access-Control-Request-Method) is
/// approved. Credentials are allowed only when the page and the target share
/// a site, so a foreign page cannot use the relay to send credentialed
/// requests that the target's own preflight answer would refuse.
fn local_options_response(headers: &[(String, String)], host: &str) -> String {
    let (Some(origin), Some(request_method)) =
        (header_value(headers, "origin"), header_value(headers, "access-control-request-method"))
    else {
        return "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\n\r\n".to_string();
    };
    let mut response = format!(
        "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: {origin}\r\nAccess-Control-Allow-Methods: {request_method}\r\nVary: Origin\r\nAccess-Control-Max-Age: 86400\r\n"
    );
    if let Some(request_headers) = header_value(headers, "access-control-request-headers") {
        response.push_str(&format!("Access-Control-Allow-Headers: {request_headers}\r\n"));
    }
    if origin_host(origin).is_some_and(|origin_host| site_key(&origin_host) == site_key(host)) {
        response.push_str("Access-Control-Allow-Credentials: true\r\n");
    }
    response.push_str("\r\n");
    response
}

/// Host of a serialized origin such as `https://www.example.com:8443`.
fn origin_host(origin: &str) -> Option<String> {
    let authority = origin.split_once("://")?.1;
    let host = match authority.strip_prefix('[') {
        Some(bracketed) => bracketed.split_once(']')?.0,
        None => authority.split(':').next()?,
    };
    (!host.is_empty()).then(|| host.to_ascii_lowercase())
}

/// Approximate registrable domain: the last two labels, or three under a
/// two-letter country code with a short second level (`example.co.uk`).
// shortcut: no public suffix list, so private suffixes such as github.io count as one site; use a PSL if one is bundled.
fn site_key(host: &str) -> String {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.parse::<std::net::IpAddr>().is_ok() {
        return host;
    }
    let labels: Vec<&str> = host.split('.').collect();
    let country_second_level =
        labels.len() >= 3 && labels[labels.len() - 1].len() == 2 && labels[labels.len() - 2].len() <= 3;
    let keep = if country_second_level { 3 } else { 2 };
    labels[labels.len().saturating_sub(keep)..].join(".")
}

pub(crate) fn looks_like_http(bytes: &[u8]) -> bool {
    ["GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE "]
        .iter()
        .any(|method| bytes.starts_with(method.as_bytes()))
}

/// # Cancel safety
///
/// NOT cancel-safe: the caller owns the pending bytes, but cancellation after a
/// read may still stop the connection with a partial HTTP request.
async fn read_http_head<S>(stream: &mut S, pending: &mut Vec<u8>) -> io::Result<Option<Vec<u8>>>
where
    S: AsyncRead + Unpin,
{
    let mut scratch = [0u8; 4_096];
    loop {
        if let Some(position) = find_headers_end(pending) {
            if position > 1024 * 1024 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "headers too large"));
            }
            return Ok(Some(pending.drain(..position).collect()));
        }
        if pending.len() > 1024 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "headers too large"));
        }
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return if pending.is_empty() {
                Ok(None)
            } else {
                Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-header"))
            };
        }
        pending.extend_from_slice(&scratch[..read]);
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

/// # Cancel safety
///
/// NOT cancel-safe: may write a 100-continue line and then accumulates body
/// bytes into a local buffer across reads; cancellation loses the partial body
/// already consumed from the stream.
async fn read_body<S>(stream: &mut S, pending: &mut Vec<u8>, headers: &[(String, String)]) -> io::Result<Vec<u8>>
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
        return read_chunked_request_body(stream, pending).await;
    }

    let Some(content_length) = content_length else {
        return Ok(Vec::new());
    };
    if content_length > MAX_REQUEST_BODY_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Content-Length exceeds maximum request body size"));
    }
    let mut body = Vec::with_capacity(content_length);
    let buffered = pending.len().min(content_length);
    body.extend(pending.drain(..buffered));
    let mut scratch = [0u8; 8_192];
    while body.len() < content_length {
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-body"));
        }
        let needed = content_length - body.len();
        body.extend_from_slice(&scratch[..read.min(needed)]);
        pending.extend_from_slice(&scratch[read.min(needed)..read]);
    }
    Ok(body)
}

/// # Cancel safety
///
/// NOT cancel-safe: decodes chunks into an output buffer across many reads;
/// cancellation discards partially decoded data already taken from the stream.
async fn read_chunked_request_body<S>(stream: &mut S, buffer: &mut Vec<u8>) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    let mut output = Vec::new();
    let mut scratch = [0u8; 8_192];

    loop {
        let line = read_crlf_line(stream, buffer, &mut scratch).await?;
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
                if read_crlf_line(stream, buffer, &mut scratch).await?.is_empty() {
                    return Ok(output);
                }
            }
        }
        if size > MAX_REQUEST_BODY_BYTES - output.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "chunked request body exceeds maximum size"));
        }
        fill_buffer(stream, buffer, &mut scratch, size + 2).await?;
        if &buffer[size..size + 2] != b"\r\n" {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "chunk is missing CRLF"));
        }
        output.extend_from_slice(&buffer[..size]);
        buffer.drain(..size + 2);
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: appends reads into the caller-owned buffer until a CRLF is
/// found; cancellation between the read await and the buffer append loses bytes
/// already pulled from the stream.
async fn read_crlf_line<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8]) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    loop {
        if let Some(position) = buffer.windows(2).position(|window| window == b"\r\n") {
            if position > 8_192 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "chunked request line too long"));
            }
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
        }
        if buffer.len() > 8_192 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "chunked request line too long"));
        }
        let read = stream.read(scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF in chunked body"));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: reads until the caller-owned buffer holds `wanted` bytes;
/// cancellation after a read await completes but before the append loses bytes
/// already consumed from the stream.
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
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};

    use super::*;

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns both duplex endpoints and keeps no external state.
    async fn pipelined_request_bytes_survive_body_read() {
        let (mut writer, mut reader) = duplex(256);
        writer
            .write_all(b"POST /one HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcGET /two HTTP/1.1\r\n\r\n")
            .await
            .expect("write pipelined requests");
        drop(writer);
        let mut pending = Vec::new();
        let first = read_http_head(&mut reader, &mut pending).await.expect("first head").expect("first request");
        assert!(first.starts_with(b"POST /one"));
        let body = read_body(&mut reader, &mut pending, &[("Content-Length".to_owned(), "3".to_owned())])
            .await
            .expect("first body");
        assert_eq!(body, b"abc");
        let second = read_http_head(&mut reader, &mut pending).await.expect("second head").expect("second request");
        assert!(second.starts_with(b"GET /two"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn read_chunked_request_body_decodes_chunks() {
        let (mut writer, mut reader) = duplex(256);
        let task = tokio::spawn(async move {
            writer.write_all(b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").await.expect("write chunked body");
        });

        let body = read_chunked_request_body(&mut reader, &mut Vec::new()).await.expect("decode body");
        task.await.expect("writer task");
        assert_eq!(body, b"hello world");
    }

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns only an in-memory duplex endpoint.
    async fn chunked_body_rejects_missing_chunk_terminator() {
        let (_writer, mut reader) = duplex(64);
        let error = read_chunked_request_body(&mut reader, &mut b"1\r\nAx0\r\n\r\n".to_vec())
            .await
            .expect_err("chunk must end in CRLF");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns its socket and channel resources.
    async fn chunked_body_rejects_oversized_chunk_and_line_before_reading_payload() {
        let (_writer, mut reader) = duplex(64);
        let error = read_chunked_request_body(&mut reader, &mut b"1000001\r\n".to_vec())
            .await
            .expect_err("oversized chunk must fail before allocation");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);

        let mut long_line = vec![b'A'; 8_193];
        long_line.extend_from_slice(b"\r\n");
        let error = read_chunked_request_body(&mut reader, &mut long_line)
            .await
            .expect_err("oversized chunk line must fail before allocation");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn read_body_rejects_oversized_content_length() {
        // A client declaring a multi-GB Content-Length must be rejected before
        // the body Vec is pre-sized, or the reservation OOM-kills the process.
        let (_writer, mut reader) = duplex(64);
        let headers = vec![("Content-Length".to_string(), "9999999999".to_string())];

        let error =
            read_body(&mut reader, &mut Vec::new(), &headers).await.expect_err("oversized length must be rejected");
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

        let error = read_body(&mut reader, &mut Vec::new(), &headers).await.expect_err("EOF before full body");
        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
    }

    /// Sends one request through `handle_request` and returns the response head.
    async fn local_response(request: &str, host: &str) -> String {
        let config = crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#)
            .expect("config");
        let relay = AppsScriptDomainFronter::new(&config);
        let (mut client, mut server) = duplex(4096);
        client.write_all(request.as_bytes()).await.expect("write request");
        handle_request(&mut server, &mut Vec::new(), host, 443, "https", &relay).await.expect("local answer");
        drop(server);
        let mut response = String::new();
        client.read_to_string(&mut response).await.expect("read response");
        response
    }

    #[tokio::test(flavor = "current_thread")]
    async fn cross_site_preflight_is_not_approved_with_credentials() {
        let response = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.example\r\nAccess-Control-Request-Method: PUT\r\n\r\n",
            "api.victim.example",
        )
        .await;
        assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
        assert!(response.contains("Access-Control-Allow-Origin: https://evil.example\r\n"), "{response}");
        assert!(!response.contains("Access-Control-Allow-Credentials"), "{response}");
        assert!(!response.contains("Content-Length"), "RFC 9110 8.6: no Content-Length on 204: {response}");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn same_site_preflight_keeps_credentials() {
        let response = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://www.example.co.uk\r\nAccess-Control-Request-Method: PUT\r\n\r\n",
            "api.example.co.uk",
        )
        .await;
        assert!(response.contains("Access-Control-Allow-Credentials: true\r\n"), "{response}");

        let other_site = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.co.uk\r\nAccess-Control-Request-Method: PUT\r\n\r\n",
            "victim.co.uk",
        )
        .await;
        assert!(!other_site.contains("Access-Control-Allow-Credentials"), "{other_site}");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn options_without_preflight_headers_is_not_faked() {
        let response = local_response("OPTIONS * HTTP/1.1\r\nHost: dav.example\r\n\r\n", "dav.example").await;
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        assert!(!response.contains("Access-Control-"), "{response}");
    }

    #[test]
    fn ipv6_targets_are_bracketed_in_the_relayed_url() {
        assert_eq!(request_url("http", "2001:db8::1", 80, "/a"), "http://[2001:db8::1]/a");
        assert_eq!(request_url("https", "2001:db8::1", 8443, "/a"), "https://[2001:db8::1]:8443/a");
        assert_eq!(request_url("https", "example.com", 443, "/a"), "https://example.com/a");
        assert_eq!(request_url("http", "192.0.2.1", 8080, "/a"), "http://192.0.2.1:8080/a");
    }
}
