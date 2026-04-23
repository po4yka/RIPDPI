use std::io::Read;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme};
use tokio_rustls::TlsConnector;

use crate::AppsScriptRuntimeConfig;

const HTTP_HOST: &str = "script.google.com";
const RELAY_TIMEOUT: Duration = Duration::from_secs(25);

#[derive(Debug, thiserror::Error)]
pub enum FronterError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("tls: {0}")]
    Tls(#[from] rustls::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid dns name: {0}")]
    InvalidDnsName(#[from] rustls::pki_types::InvalidDnsNameError),
    #[error("bad response: {0}")]
    BadResponse(String),
    #[error("relay error: {0}")]
    Relay(String),
    #[error("timeout")]
    Timeout,
}

pub struct AppsScriptDomainFronter {
    connect_host: String,
    front_domain: String,
    auth_key: String,
    script_ids: Vec<String>,
    next_script_index: AtomicUsize,
    tls_connector: TlsConnector,
}

impl AppsScriptDomainFronter {
    pub fn new(config: &AppsScriptRuntimeConfig) -> Self {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls_config = if config.verify_ssl {
            ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()
        } else {
            ClientConfig::builder()
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(NoVerify))
                .with_no_client_auth()
        };

        Self {
            connect_host: config.google_ip.clone(),
            front_domain: config.front_domain.clone(),
            auth_key: config.auth_key.clone(),
            script_ids: config.script_ids.clone(),
            next_script_index: AtomicUsize::new(0),
            tls_connector: TlsConnector::from(Arc::new(tls_config)),
        }
    }

    pub async fn relay(&self, method: &str, url: &str, headers: &[(String, String)], body: &[u8]) -> Vec<u8> {
        match timeout(RELAY_TIMEOUT, self.relay_once(method, url, headers, body)).await {
            Ok(Ok(response)) => response,
            Ok(Err(error)) => error_response(502, &format!("Apps Script relay error: {error}")),
            Err(_) => error_response(504, "Apps Script relay timeout"),
        }
    }

    async fn relay_once(
        &self,
        method: &str,
        url: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>, FronterError> {
        let mut stream = self.open_fronted_stream().await?;
        let payload = self.build_payload_json(method, url, headers, body)?;
        let path = format!("/macros/s/{}/exec", self.next_script_id());

        send_http_post(&mut stream, &path, HTTP_HOST, &payload).await?;
        let (mut status, mut response_headers, mut response_body) = read_http_response(&mut stream).await?;
        for _ in 0..5 {
            if !matches!(status, 301 | 302 | 303 | 307 | 308) {
                break;
            }
            let Some(location) = header_value(&response_headers, "location") else {
                break;
            };
            let (redirect_path, redirect_host) = parse_redirect(location);
            send_http_get(&mut stream, &redirect_path, redirect_host.as_deref().unwrap_or(HTTP_HOST)).await?;
            (status, response_headers, response_body) = read_http_response(&mut stream).await?;
        }

        if status != 200 {
            let snippet = String::from_utf8_lossy(&response_body);
            return Err(FronterError::Relay(format!(
                "Apps Script HTTP {status}: {}",
                snippet.chars().take(200).collect::<String>()
            )));
        }

        parse_relay_json(&response_body)
    }

    async fn open_fronted_stream(&self) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        let stream = TcpStream::connect((self.connect_host.as_str(), 443)).await?;
        stream.set_nodelay(true)?;
        let server_name = ServerName::try_from(self.front_domain.clone())?;
        Ok(self.tls_connector.connect(server_name, stream).await?)
    }

    fn build_payload_json(
        &self,
        method: &str,
        url: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>, FronterError> {
        let filtered_headers = filter_forwarded_headers(headers);
        let header_map = if filtered_headers.is_empty() {
            None
        } else {
            let mut map = serde_json::Map::new();
            for (key, value) in filtered_headers {
                map.insert(key, Value::String(value));
            }
            Some(map)
        };
        let content_type =
            if body.is_empty() { None } else { header_value(headers, "content-type").map(ToOwned::to_owned) };
        let request = RelayRequest {
            auth_key: &self.auth_key,
            method,
            url,
            headers: header_map,
            body: (!body.is_empty()).then(|| BASE64_STANDARD.encode(body)),
            content_type,
            follow_redirects: true,
        };
        Ok(serde_json::to_vec(&request)?)
    }

    fn next_script_id(&self) -> String {
        let index = self.next_script_index.fetch_add(1, Ordering::Relaxed);
        self.script_ids[index % self.script_ids.len()].clone()
    }
}

#[derive(Serialize)]
struct RelayRequest<'a> {
    #[serde(rename = "k")]
    auth_key: &'a str,
    #[serde(rename = "m")]
    method: &'a str,
    #[serde(rename = "u")]
    url: &'a str,
    #[serde(rename = "h", skip_serializing_if = "Option::is_none")]
    headers: Option<serde_json::Map<String, Value>>,
    #[serde(rename = "b", skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    #[serde(rename = "ct", skip_serializing_if = "Option::is_none")]
    content_type: Option<String>,
    #[serde(rename = "r")]
    follow_redirects: bool,
}

#[derive(Default, Deserialize)]
struct RelayResponse {
    #[serde(default, rename = "s")]
    status: Option<u16>,
    #[serde(default, rename = "h")]
    headers: Option<serde_json::Map<String, Value>>,
    #[serde(default, rename = "b")]
    body: Option<String>,
    #[serde(default, rename = "e")]
    error: Option<String>,
}

async fn send_http_post<S>(stream: &mut S, path: &str, host: &str, body: &[u8]) -> Result<(), FronterError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = format!(
        "POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nAccept-Encoding: gzip\r\nConnection: keep-alive\r\n\r\n",
        body.len()
    );
    stream.write_all(request.as_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await?;
    Ok(())
}

async fn send_http_get<S>(stream: &mut S, path: &str, host: &str) -> Result<(), FronterError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request =
        format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept-Encoding: gzip\r\nConnection: keep-alive\r\n\r\n");
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;
    Ok(())
}

async fn read_http_response<S>(stream: &mut S) -> Result<(u16, Vec<(String, String)>, Vec<u8>), FronterError>
where
    S: AsyncRead + Unpin,
{
    let mut buffer = Vec::with_capacity(8_192);
    let mut scratch = [0u8; 8_192];
    let headers_end = loop {
        let read =
            timeout(Duration::from_secs(10), stream.read(&mut scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed before headers".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
        if let Some(position) = find_headers_end(&buffer) {
            break position;
        }
        if buffer.len() > 1024 * 1024 {
            return Err(FronterError::BadResponse("response headers too large".to_string()));
        }
    };

    let header_section = std::str::from_utf8(&buffer[..headers_end])
        .map_err(|_| FronterError::BadResponse("response headers are not utf-8".to_string()))?;
    let mut lines = header_section.split("\r\n");
    let status = parse_status_line(lines.next().unwrap_or_default())?;
    let mut headers = Vec::new();
    for line in lines {
        if let Some((key, value)) = line.split_once(':') {
            headers.push((key.trim().to_string(), value.trim().to_string()));
        }
    }

    let mut body = buffer[headers_end + 4..].to_vec();
    let content_length = header_value(&headers, "content-length").and_then(|value| value.parse::<usize>().ok());
    let is_chunked = header_value(&headers, "transfer-encoding")
        .map(|value| value.to_ascii_lowercase().contains("chunked"))
        .unwrap_or(false);

    if is_chunked {
        body = read_chunked(stream, body).await?;
    } else if let Some(content_length) = content_length {
        while body.len() < content_length {
            let wanted = (content_length - body.len()).min(scratch.len());
            let read = timeout(Duration::from_secs(10), stream.read(&mut scratch[..wanted]))
                .await
                .map_err(|_| FronterError::Timeout)??;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&scratch[..read]);
        }
    } else {
        loop {
            match timeout(Duration::from_millis(750), stream.read(&mut scratch)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(read)) => body.extend_from_slice(&scratch[..read]),
                Ok(Err(error)) => return Err(error.into()),
                Err(_) => break,
            }
        }
    }

    if header_value(&headers, "content-encoding").map(|value| value.eq_ignore_ascii_case("gzip")).unwrap_or(false) {
        body = decode_gzip(&body).map_err(FronterError::Io)?;
    }

    Ok((status, headers, body))
}

async fn read_chunked<S>(stream: &mut S, mut buffer: Vec<u8>) -> Result<Vec<u8>, FronterError>
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
            .map_err(|_| FronterError::BadResponse("invalid chunk size line".to_string()))?
            .trim()
            .to_string();
        let size = usize::from_str_radix(line.split(';').next().unwrap_or_default(), 16)
            .map_err(|_| FronterError::BadResponse(format!("invalid chunk size {line}")))?;
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

async fn read_crlf_line<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8]) -> Result<Vec<u8>, FronterError>
where
    S: AsyncRead + Unpin,
{
    loop {
        if let Some(position) = buffer.windows(2).position(|window| window == b"\r\n") {
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
        }
        let read =
            timeout(Duration::from_secs(10), stream.read(scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed while decoding chunked body".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
}

async fn fill_buffer<S>(
    stream: &mut S,
    buffer: &mut Vec<u8>,
    scratch: &mut [u8],
    wanted: usize,
) -> Result<(), FronterError>
where
    S: AsyncRead + Unpin,
{
    while buffer.len() < wanted {
        let read =
            timeout(Duration::from_secs(10), stream.read(scratch)).await.map_err(|_| FronterError::Timeout)??;
        if read == 0 {
            return Err(FronterError::BadResponse("connection closed while filling response buffer".to_string()));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
    Ok(())
}

fn parse_relay_json(body: &[u8]) -> Result<Vec<u8>, FronterError> {
    let text = std::str::from_utf8(body)
        .map_err(|_| FronterError::BadResponse("Apps Script payload is not utf-8".to_string()))?;
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err(FronterError::BadResponse("Apps Script payload is empty".to_string()));
    }
    let response = match serde_json::from_str::<RelayResponse>(trimmed) {
        Ok(response) => response,
        Err(_) => {
            let start = trimmed
                .find('{')
                .ok_or_else(|| FronterError::BadResponse("Apps Script payload does not contain JSON".to_string()))?;
            let end = trimmed
                .rfind('}')
                .ok_or_else(|| FronterError::BadResponse("Apps Script payload does not terminate JSON".to_string()))?;
            serde_json::from_str(&trimmed[start..=end])?
        }
    };
    if let Some(error) = response.error {
        return Err(FronterError::Relay(error));
    }

    let status = response.status.unwrap_or(200);
    let body = response
        .body
        .map(|value| BASE64_STANDARD.decode(value))
        .transpose()
        .map_err(|error| FronterError::BadResponse(format!("invalid body base64: {error}")))?
        .unwrap_or_default();

    let mut output = Vec::with_capacity(body.len() + 256);
    output.extend_from_slice(format!("HTTP/1.1 {status} {}\r\n", status_text(status)).as_bytes());
    if let Some(headers) = response.headers {
        for (key, value) in headers {
            let lower = key.to_ascii_lowercase();
            if matches!(lower.as_str(), "connection" | "keep-alive" | "content-length" | "content-encoding") {
                continue;
            }
            if let Some(value) = header_value_from_json(&value) {
                output.extend_from_slice(format!("{key}: {value}\r\n").as_bytes());
            }
        }
    }
    output.extend_from_slice(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes());
    output.extend_from_slice(&body);
    Ok(output)
}

fn filter_forwarded_headers(headers: &[(String, String)]) -> Vec<(String, String)> {
    headers
        .iter()
        .filter_map(|(key, value)| {
            let lower = key.to_ascii_lowercase();
            if matches!(
                lower.as_str(),
                "host" | "connection" | "content-length" | "transfer-encoding" | "proxy-connection"
            ) {
                return None;
            }
            if lower == "accept-encoding" {
                let value = strip_unsupported_encodings(value);
                return (!value.is_empty()).then(|| (key.clone(), value));
            }
            Some((key.clone(), value.clone()))
        })
        .collect()
}

fn strip_unsupported_encodings(value: &str) -> String {
    value
        .split(',')
        .filter_map(|part| {
            let part = part.trim();
            let name = part.split(';').next().unwrap_or_default().trim().to_ascii_lowercase();
            (!matches!(name.as_str(), "br" | "zstd")).then(|| part.to_string())
        })
        .collect::<Vec<_>>()
        .join(", ")
}

fn parse_redirect(value: &str) -> (String, Option<String>) {
    if let Some(rest) = value.strip_prefix("https://").or_else(|| value.strip_prefix("http://")) {
        let slash = rest.find('/').unwrap_or(rest.len());
        let host = rest[..slash].to_string();
        let path = if slash < rest.len() { rest[slash..].to_string() } else { "/".to_string() };
        return (path, Some(host));
    }
    (value.to_string(), None)
}

fn parse_status_line(value: &str) -> Result<u16, FronterError> {
    let mut parts = value.split_whitespace();
    let _version = parts.next();
    let code = parts.next().ok_or_else(|| FronterError::BadResponse(format!("invalid status line {value}")))?;
    code.parse::<u16>().map_err(|_| FronterError::BadResponse(format!("invalid status code {code}")))
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n")
}

fn header_value<'a>(headers: &'a [(String, String)], name: &str) -> Option<&'a str> {
    headers.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
}

fn header_value_from_json(value: &Value) -> Option<String> {
    match value {
        Value::Array(values) => Some(values.iter().filter_map(header_value_from_json).collect::<Vec<_>>().join(", ")),
        Value::String(value) => Some(value.clone()),
        Value::Bool(value) => Some(value.to_string()),
        Value::Number(value) => Some(value.to_string()),
        Value::Null | Value::Object(_) => None,
    }
}

fn decode_gzip(data: &[u8]) -> std::io::Result<Vec<u8>> {
    let mut decoder = flate2::read::GzDecoder::new(data);
    let mut output = Vec::new();
    decoder.read_to_end(&mut output)?;
    Ok(output)
}

fn status_text(status: u16) -> &'static str {
    match status {
        200 => "OK",
        201 => "Created",
        204 => "No Content",
        301 => "Moved Permanently",
        302 => "Found",
        304 => "Not Modified",
        307 => "Temporary Redirect",
        308 => "Permanent Redirect",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Response",
    }
}

pub(crate) fn error_response(status: u16, message: &str) -> Vec<u8> {
    let body = message.as_bytes();
    let mut output = Vec::with_capacity(body.len() + 128);
    output.extend_from_slice(format!("HTTP/1.1 {status} {}\r\n", status_text(status)).as_bytes());
    output.extend_from_slice(
        format!(
            "Content-Length: {}\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n",
            body.len()
        )
        .as_bytes(),
    );
    output.extend_from_slice(body);
    output
}

#[derive(Debug)]
struct NoVerify;

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_brotli_from_forwarded_headers() {
        let filtered = filter_forwarded_headers(&[
            ("Accept-Encoding".to_string(), "gzip, br, deflate, zstd".to_string()),
            ("Host".to_string(), "example.com".to_string()),
        ]);
        assert_eq!(filtered, vec![("Accept-Encoding".to_string(), "gzip, deflate".to_string())]);
    }

    #[test]
    fn parses_redirect_urls() {
        let (path, host) = parse_redirect("https://example.com/next");
        assert_eq!(path, "/next");
        assert_eq!(host.as_deref(), Some("example.com"));
    }
}
