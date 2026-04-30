use reqwest::header::{ACCEPT, CONTENT_TYPE};
use rustls::pki_types::ServerName;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use url::Url;

use super::EncryptedDnsResolver;
use crate::transport::{format_error_chain, DNS_MESSAGE_MEDIA_TYPE};
use crate::types::EncryptedDnsError;

const MAX_DOH_RESPONSE_BYTES: usize = 65_535;
const MAX_DOH_HEADER_BYTES: usize = 8 * 1024;

#[derive(Debug, Clone, Copy)]
struct DohHttpResponseHead {
    status: reqwest::StatusCode,
    content_length: Option<usize>,
    chunked: bool,
}

impl EncryptedDnsResolver {
    pub(super) async fn exchange_doh(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        if self.uses_direct_tcp_connector() {
            return self.exchange_doh_manually(query_bytes).await;
        }

        let client = self
            .inner
            .doh_client
            .as_ref()
            .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH client not initialized".to_string()))?;
        let base_url = self.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;

        let response = client
            .post(base_url)
            .header(CONTENT_TYPE, DNS_MESSAGE_MEDIA_TYPE)
            .header(ACCEPT, DNS_MESSAGE_MEDIA_TYPE)
            .body(query_bytes.to_vec())
            .send()
            .await
            .map_err(|err| EncryptedDnsError::Request(format_error_chain(&err)))?;

        if !response.status().is_success() {
            return Err(EncryptedDnsError::HttpStatus(response.status()));
        }

        response.bytes().await.map(|value| value.to_vec()).map_err(|err| EncryptedDnsError::Request(err.to_string()))
    }

    async fn exchange_doh_manually(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let base_url = self.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;
        let url = Url::parse(base_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
        let mut tcp_stream = self.connect_plain_tcp().await?;

        if url.scheme().eq_ignore_ascii_case("https") {
            let tls_name =
                self.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| self.inner.endpoint.host.clone());
            let server_name =
                ServerName::try_from(tls_name.clone()).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
            let connector = TlsConnector::from(self.inner.dot_tls_config.clone());
            let mut tls_stream = match timeout(self.inner.timeout, connector.connect(server_name, tcp_stream)).await {
                Ok(Ok(stream)) => stream,
                Ok(Err(err)) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name}: {err}"))),
                Err(_) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name} timed out"))),
            };
            self.exchange_doh_over_stream(&mut tls_stream, &url, query_bytes).await
        } else {
            self.exchange_doh_over_stream(&mut tcp_stream, &url, query_bytes).await
        }
    }

    async fn exchange_doh_over_stream<S>(
        &self,
        stream: &mut S,
        url: &Url,
        query_bytes: &[u8],
    ) -> Result<Vec<u8>, EncryptedDnsError>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let request_target = doh_request_target(url);
        let host_header = doh_host_header(url)?;
        let request = format!(
            "POST {request_target} HTTP/1.1\r\nHost: {host_header}\r\n{}: {}\r\n{}: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            CONTENT_TYPE.as_str(),
            DNS_MESSAGE_MEDIA_TYPE,
            ACCEPT.as_str(),
            DNS_MESSAGE_MEDIA_TYPE,
            query_bytes.len(),
        );

        let mut response = Vec::new();
        match timeout(self.inner.timeout, async {
            stream
                .write_all(request.as_bytes())
                .await
                .map_err(|err| EncryptedDnsError::Request(format!("DoH write request headers: {err}")))?;
            stream
                .write_all(query_bytes)
                .await
                .map_err(|err| EncryptedDnsError::Request(format!("DoH write query body: {err}")))?;
            stream.flush().await.map_err(|err| EncryptedDnsError::Request(format!("DoH flush stream: {err}")))?;
            response = read_doh_response(stream).await?;
            Ok::<(), EncryptedDnsError>(())
        })
        .await
        {
            Ok(result) => result?,
            Err(_) => return Err(EncryptedDnsError::Request("DoH exchange timed out".to_string())),
        }

        Ok(response)
    }
}

/// Append a random cache-busting query parameter to a DoH URL.
fn doh_request_target(url: &Url) -> String {
    let mut target = if url.path().is_empty() { "/".to_string() } else { url.path().to_string() };
    if let Some(query) = url.query() {
        target.push('?');
        target.push_str(query);
    }
    target
}

fn doh_host_header(url: &Url) -> Result<String, EncryptedDnsError> {
    let host = url.host_str().ok_or(EncryptedDnsError::MissingHost)?;
    let host_header = match url.port() {
        Some(port) if Some(port) != url.port_or_known_default() => format!("{host}:{port}"),
        _ => host.to_string(),
    };
    Ok(host_header)
}

async fn read_doh_response<S>(stream: &mut S) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: tokio::io::AsyncRead + Unpin,
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

async fn read_doh_response_head<S>(stream: &mut S) -> Result<(DohHttpResponseHead, Vec<u8>), EncryptedDnsError>
where
    S: tokio::io::AsyncRead + Unpin,
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

async fn read_doh_body_with_content_length<S>(
    stream: &mut S,
    mut body: Vec<u8>,
    content_length: usize,
) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: tokio::io::AsyncRead + Unpin,
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
    S: tokio::io::AsyncRead + Unpin,
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

async fn read_chunked_doh_body<S>(stream: &mut S, mut buffer: Vec<u8>) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: tokio::io::AsyncRead + Unpin,
{
    let mut decoded = Vec::new();
    let mut cursor = 0usize;

    loop {
        let size_end = loop {
            if let Some(offset) = find_crlf(&buffer[cursor..]) {
                break cursor + offset;
            }
            if read_more_doh_bytes(stream, &mut buffer).await? == 0 {
                return Err(EncryptedDnsError::Request("chunked DoH response missing size delimiter".to_string()));
            }
        };
        let size_line = std::str::from_utf8(&buffer[cursor..size_end])
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        let size = usize::from_str_radix(size_line.split(';').next().unwrap_or_default().trim(), 16)
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        cursor = size_end + 2;

        if size == 0 {
            return Ok(decoded);
        }

        if decoded.len() + size > MAX_DOH_RESPONSE_BYTES {
            return Err(EncryptedDnsError::Request("chunked DoH response exceeds maximum size".to_string()));
        }

        while buffer.len() < cursor + size + 2 {
            if read_more_doh_bytes(stream, &mut buffer).await? == 0 {
                return Err(EncryptedDnsError::Request("chunked DoH response truncated".to_string()));
            }
        }

        decoded.extend_from_slice(&buffer[cursor..cursor + size]);
        cursor += size;
        if &buffer[cursor..cursor + 2] != b"\r\n" {
            return Err(EncryptedDnsError::Request("chunked DoH response missing chunk terminator".to_string()));
        }
        cursor += 2;

        buffer.drain(..cursor);
        cursor = 0;
    }
}

async fn read_more_doh_bytes<S>(stream: &mut S, buffer: &mut Vec<u8>) -> Result<usize, EncryptedDnsError>
where
    S: tokio::io::AsyncRead + Unpin,
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

fn find_crlf(bytes: &[u8]) -> Option<usize> {
    bytes.windows(2).position(|window| window == b"\r\n")
}

fn find_http_header_terminator(bytes: &[u8]) -> Option<usize> {
    bytes.windows(4).position(|window| window == b"\r\n\r\n")
}

fn should_ignore_tls_eof(error: &std::io::Error) -> bool {
    error.kind() == std::io::ErrorKind::UnexpectedEof && error.to_string().contains("close_notify")
}

#[cfg(test)]
mod tests {
    use tokio::io::AsyncWriteExt;

    use super::*;

    #[tokio::test]
    async fn read_doh_body_with_content_length_rejects_oversized_length() {
        let error = read_doh_body_with_content_length(&mut tokio::io::empty(), Vec::new(), MAX_DOH_RESPONSE_BYTES + 1)
            .await
            .expect_err("oversized Content-Length should fail");

        match error {
            EncryptedDnsError::Request(message) => {
                assert!(message.contains("Content-Length exceeds maximum size"));
            }
            other => panic!("expected request error, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn read_chunked_doh_body_rejects_chunk_larger_than_limit() {
        let error = read_chunked_doh_body(&mut tokio::io::empty(), b"10000\r\n".to_vec())
            .await
            .expect_err("oversized chunk should fail");

        match error {
            EncryptedDnsError::Request(message) => {
                assert!(message.contains("chunked DoH response exceeds maximum size"));
            }
            other => panic!("expected request error, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn read_doh_response_head_rejects_oversized_headers() {
        let oversized_headers = format!("HTTP/1.1 200 OK\r\nX-Fill: {}\r\n\r\n", "a".repeat(MAX_DOH_HEADER_BYTES),);
        let (mut client, mut server) = tokio::io::duplex(oversized_headers.len() + 16);
        let writer = tokio::spawn(async move {
            server.write_all(oversized_headers.as_bytes()).await.expect("write oversized headers");
            server.shutdown().await.expect("shutdown writer");
        });

        let error = read_doh_response_head(&mut client).await.expect_err("oversized headers should fail");
        writer.await.expect("writer task");

        match error {
            EncryptedDnsError::Request(message) => {
                assert!(message.contains("headers exceed maximum size"));
            }
            other => panic!("expected request error, got {other:?}"),
        }
    }
}
