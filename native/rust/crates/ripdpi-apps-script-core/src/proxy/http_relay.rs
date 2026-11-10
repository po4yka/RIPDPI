use crate::domain_fronter::{AppsScriptDomainFronter, RelayResponse};
use crate::telemetry::SharedTelemetryState;
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::body::{Body, Incoming};
use hyper::service::service_fn;
use hyper_util::rt::{TokioIo, TokioTimer};
use std::io;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;

const MAX_REQUEST_BODY_BYTES: usize = 16 * 1024 * 1024;
const CLIENT_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

/// # Cancel safety:
/// NOT cancel-safe: cancellation closes the owned connection and drops its pending requests.
pub(crate) async fn relay_raw(
    stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    telemetry.record_target(&format!("{}:{port}", authority_host(host)));
    serve(stream, host, port, if port == 443 { "https" } else { "http" }, relay.as_ref(), telemetry.as_ref()).await
}

/// # Cancel safety:
/// NOT cancel-safe: all request and connection futures are owned here and dropped together.
pub(crate) async fn serve<S>(
    stream: S,
    host: &str,
    port: u16,
    scheme: &str,
    relay: &AppsScriptDomainFronter,
    telemetry: &crate::telemetry::RelayTelemetryState,
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let service = service_fn(|request| handle_request(request, host, port, scheme, relay));
    let stream = super::http_transport::CountedIo { stream, telemetry };
    hyper::server::conn::http1::Builder::new()
        .timer(TokioTimer::new())
        .header_read_timeout(CLIENT_IDLE_TIMEOUT)
        .max_buf_size(1024 * 1024)
        .serve_connection(TokioIo::new(stream), service)
        .await
        .map_err(io::Error::other)
}

/// # Cancel safety:
/// NOT cancel-safe: the server owns body consumption and drops the connection on cancellation.
async fn handle_request(
    request: ::http::Request<Incoming>,
    host: &str,
    port: u16,
    scheme: &str,
    relay: &AppsScriptDomainFronter,
) -> io::Result<RelayResponse> {
    let (parts, incoming) = request.into_parts();
    crate::domain_fronter::validate_transfer_encoding(&parts.headers).map_err(io::Error::other)?;
    let url = request_url(scheme, host, port, &parts.uri.to_string());
    if parts.method == ::http::Method::OPTIONS {
        read_body(incoming, MAX_REQUEST_BODY_BYTES).await?;
        let mut response = ::http::Response::new(Full::new(Bytes::new()));
        *response.status_mut() = ::http::StatusCode::NOT_IMPLEMENTED;
        return Ok(response);
    }
    let headers = parts
        .headers
        .iter()
        .map(|(name, value)| {
            value.to_str().map(|value| (name.to_string(), value.to_string())).map_err(io::Error::other)
        })
        .collect::<io::Result<Vec<_>>>()?;
    let body = read_body(incoming, MAX_REQUEST_BODY_BYTES).await?;
    Ok(relay.relay(parts.method.as_str(), &url, &headers, &body).await)
}

/// # Cancel safety:
/// NOT cancel-safe: consumed frames are local; its server drops the connection on cancellation.
async fn read_body(mut body: Incoming, limit: usize) -> io::Result<Vec<u8>> {
    if body.size_hint().upper().is_some_and(|size| size > limit as u64) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "request body too large"));
    }
    let mut result = Vec::new();
    while let Some(frame) = tokio::time::timeout(CLIENT_IDLE_TIMEOUT, body.frame())
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "client body timeout"))?
    {
        if let Ok(data) = frame.map_err(io::Error::other)?.into_data() {
            if data.len() > limit.saturating_sub(result.len()) {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "request body too large"));
            }
            result.extend_from_slice(&data);
        }
    }
    Ok(result)
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

const HTTP_METHOD_TOKENS: [&str; 9] =
    ["GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE "];

pub(crate) fn looks_like_http(bytes: &[u8]) -> bool {
    HTTP_METHOD_TOKENS.iter().any(|method| bytes.starts_with(method.as_bytes()))
}

/// True when `bytes` is a strict prefix of a method token, such as "GE".
pub(crate) fn is_http_method_prefix(bytes: &[u8]) -> bool {
    HTTP_METHOD_TOKENS.iter().any(|method| method.len() > bytes.len() && method.as_bytes().starts_with(bytes))
}

#[cfg(test)]
mod tests;
