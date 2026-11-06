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
    let headers = parts
        .headers
        .iter()
        .map(|(name, value)| {
            value.to_str().map(|value| (name.to_string(), value.to_string())).map_err(io::Error::other)
        })
        .collect::<io::Result<Vec<_>>>()?;
    let body = read_body(incoming, MAX_REQUEST_BODY_BYTES).await?;
    if parts.method == ::http::Method::OPTIONS {
        return local_options_response(&parts.headers, host);
    }
    let url = request_url(scheme, host, port, &parts.uri.to_string());
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

fn local_options_response(headers: &::http::HeaderMap, host: &str) -> io::Result<RelayResponse> {
    let mut response = ::http::Response::new(Full::new(Bytes::new()));
    let (Some(origin), Some(method)) = (headers.get("origin"), headers.get("access-control-request-method")) else {
        *response.status_mut() = ::http::StatusCode::NOT_IMPLEMENTED;
        return Ok(response);
    };
    *response.status_mut() = ::http::StatusCode::NO_CONTENT;
    let output = response.headers_mut();
    output.insert("access-control-allow-origin", origin.clone());
    output.insert("access-control-allow-methods", method.clone());
    output.insert("vary", ::http::HeaderValue::from_static("Origin"));
    output.insert("access-control-max-age", ::http::HeaderValue::from_static("86400"));
    if let Some(value) = headers.get("access-control-request-headers") {
        output.insert("access-control-allow-headers", value.clone());
    }
    if origin.to_str().ok().and_then(origin_host).is_some_and(|origin| site_key(&origin) == site_key(host)) {
        output.insert("access-control-allow-credentials", ::http::HeaderValue::from_static("true"));
    }
    Ok(response)
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

/// Host of a serialized origin such as `https://www.example.com:8443`.
fn origin_host(origin: &str) -> Option<String> {
    let authority = origin.split_once("://")?.1;
    let host = match authority.strip_prefix('[') {
        Some(bracketed) => bracketed.split_once(']')?.0,
        None => authority.split(':').next()?,
    };
    (!host.is_empty()).then(|| host.to_ascii_lowercase())
}

/// Second-level labels that country-code registries use as public suffixes
/// (`co.uk`, `com.au`, `ne.jp`, `gob.mx`). Other short labels under a
/// two-letter TLD (`ya.ru`, `x.ai`, `fly.io`) are registrable domains.
const COUNTRY_SECOND_LEVEL_SUFFIXES: &[&str] = &[
    "ac", "ad", "co", "com", "ed", "edu", "go", "gob", "gouv", "gov", "gr", "int", "lg", "ltd", "mil", "ne", "net",
    "nic", "nom", "or", "org", "plc", "sch",
];

/// Approximate registrable domain: the last two labels, or three under a
/// two-letter country code with a known public second level (`example.co.uk`).
// shortcut: no public suffix list, so private suffixes such as github.io count as one site; use a PSL if one is bundled.
fn site_key(host: &str) -> String {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.parse::<std::net::IpAddr>().is_ok() {
        return host;
    }
    let labels: Vec<&str> = host.split('.').collect();
    let country_second_level = labels.len() >= 3
        && labels[labels.len() - 1].len() == 2
        && COUNTRY_SECOND_LEVEL_SUFFIXES.contains(&labels[labels.len() - 2]);
    let keep = if country_second_level { 3 } else { 2 };
    labels[labels.len().saturating_sub(keep)..].join(".")
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
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};

    /// # Cancel safety:
    /// cancel-safe: the test owns both endpoints and the server future.
    async fn local_response(request: &str, host: &str) -> String {
        let config = crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#)
            .expect("config");
        let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
        let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
        let (mut client, server) = duplex(4096);
        let request = request.replacen("HTTP/1.1\r\n", "HTTP/1.1\r\nConnection: close\r\n", 1);
        let client_job = async {
            client.write_all(request.as_bytes()).await.expect("request");
            let mut response = String::new();
            client.read_to_string(&mut response).await.expect("response");
            response
        };
        let (response, result) = tokio::join!(client_job, serve(server, host, 443, "https", &relay, &telemetry));
        result.expect("serve");
        response
    }

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety:
    /// cancel-safe: the helper drops both transport ends with its server future.
    async fn cross_site_preflight_is_not_approved_with_credentials() {
        let response = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.example\r\naccess-control-request-Method: PUT\r\n\r\n",
            "api.victim.example",
        )
        .await;
        assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
        assert!(response.contains("access-control-allow-origin: https://evil.example\r\n"), "{response}");
        assert!(!response.contains("access-control-allow-credentials"), "{response}");
        assert!(!response.contains("content-length"), "RFC 9110 8.6: no content-length on 204: {response}");
    }

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety:
    /// cancel-safe: the helper drops both transport ends with its server future.
    async fn same_site_preflight_keeps_credentials() {
        let response = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://www.example.co.uk\r\naccess-control-request-Method: PUT\r\n\r\n",
            "api.example.co.uk",
        )
        .await;
        assert!(response.contains("access-control-allow-credentials: true\r\n"), "{response}");

        let other_site = local_response(
            "OPTIONS /x HTTP/1.1\r\nOrigin: https://evil.co.uk\r\naccess-control-request-Method: PUT\r\n\r\n",
            "victim.co.uk",
        )
        .await;
        assert!(!other_site.contains("access-control-allow-credentials"), "{other_site}");
    }

    #[test]
    fn short_registrable_domains_under_two_letter_tlds_stay_one_site() {
        for (origin, target) in [
            ("music.ya.ru", "api.ya.ru"),
            ("m.ok.ru", "api.ok.ru"),
            ("www.vc.ru", "api.vc.ru"),
            ("www.gmx.de", "api.gmx.de"),
            ("console.x.ai", "api.x.ai"),
            ("app.fly.io", "api.fly.io"),
            ("www.example.co.uk", "api.example.co.uk"),
            ("www.city.go.jp", "api.city.go.jp"),
        ] {
            assert_eq!(site_key(origin), site_key(target), "{origin} and {target} share a site");
        }
        for (origin, target) in
            [("a.co.uk", "b.co.uk"), ("a.com.au", "b.com.au"), ("a.ne.jp", "b.ne.jp"), ("a.gob.mx", "b.gob.mx")]
        {
            assert_ne!(site_key(origin), site_key(target), "{origin} and {target} are different sites");
        }
    }

    #[tokio::test(flavor = "current_thread")]
    /// # Cancel safety:
    /// cancel-safe: the helper drops both transport ends with its server future.
    async fn options_without_preflight_headers_is_not_faked() {
        let response = local_response("OPTIONS * HTTP/1.1\r\nHost: dav.example\r\n\r\n", "dav.example").await;
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        assert!(!response.contains("access-control-"), "{response}");
    }

    #[test]
    fn ipv6_targets_are_bracketed_in_the_relayed_url() {
        assert_eq!(request_url("http", "2001:db8::1", 80, "/a"), "http://[2001:db8::1]/a");
        assert_eq!(request_url("https", "2001:db8::1", 8443, "/a"), "https://[2001:db8::1]:8443/a");
        assert_eq!(request_url("https", "example.com", 443, "/a"), "https://example.com/a");
        assert_eq!(request_url("http", "192.0.2.1", 8080, "/a"), "http://192.0.2.1:8080/a");
    }

    /// # Cancel safety:
    /// cancel-safe: both endpoints and the server are scoped to the test.
    #[tokio::test]
    async fn ambiguous_request_is_normalized_and_closes_before_pipeline() {
        let config =
            crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
        let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
        let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
        let (mut client, server) = duplex(4096);
        let client_job = async {
            client.write_all(b"OPTIONS /one HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\nContent-Length: 999\r\nOrigin: https://local\r\nAccess-Control-Request-Method: GET\r\n\r\n3\r\nabc\r\n0\r\n\r\nOPTIONS /two HTTP/1.1\r\nHost: local\r\n\r\n").await.unwrap();
            let mut response = String::new();
            client.read_to_string(&mut response).await.unwrap();
            response
        };
        let (response, result) = tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry));
        result.unwrap();
        assert_eq!(response.matches("HTTP/1.1").count(), 1, "{response}");
        assert!(response.starts_with("HTTP/1.1 204 "), "{response}");
        assert!(response.contains("connection: close"), "{response}");
    }

    /// # Cancel safety:
    /// cancel-safe: all transport state and futures are scoped to this helper.
    async fn wire_exchange(wire: &[u8]) -> (String, io::Result<()>) {
        let config =
            crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
        let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
        let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
        let (mut client, server) = duplex(4096);
        let client_job = async {
            client.write_all(wire).await.unwrap();
            let mut response = String::new();
            client.read_to_string(&mut response).await.unwrap();
            response
        };
        tokio::time::timeout(Duration::from_secs(3), async {
            tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry))
        })
        .await
        .expect("connection ends")
    }

    /// # Cancel safety:
    /// cancel-safe: every exchange owns its transports.
    #[tokio::test]
    async fn chunked_extensions_trailers_and_pipeline_keep_boundaries() {
        let (response, result) = wire_exchange(b"OPTIONS /one HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=yes\r\nabc\r\n0\r\nX-Trailer: yes\r\n\r\nOPTIONS /two HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 3\r\n\r\nxyz").await;
        result.unwrap();
        assert_eq!(response.matches("HTTP/1.1 501 ").count(), 2, "{response}");
    }

    /// # Cancel safety:
    /// cancel-safe: every exchange owns its transports.
    #[tokio::test]
    async fn malformed_chunks_and_conflicting_lengths_never_dispatch() {
        for wire in [
            &b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nTransfer-Encoding: gzip, chunked\r\n\r\n1\r\nA\r\n0\r\n\r\n"[..],
            &b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nAX0\r\n\r\n"[..],
            b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nAX",
            b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nContent-Length: 16777217\r\n\r\n",
        ] {
            let (response, result) = wire_exchange(wire).await;
            assert!(result.is_err() || response.starts_with("HTTP/1.1 400 "));
            assert!(!response.contains("501"), "{response}");
        }
    }

    /// # Cancel safety:
    /// cancel-safe: both endpoints and futures are scoped to this test.
    #[tokio::test]
    async fn expect_continue_precedes_body_and_final_response() {
        let config =
            crate::AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).unwrap();
        let telemetry = Arc::new(crate::telemetry::RelayTelemetryState::new(&config));
        let relay = AppsScriptDomainFronter::new(&config, telemetry.clone());
        let (mut client, server) = duplex(4096);
        let client_job = async {
            client.write_all(b"OPTIONS / HTTP/1.1\r\nHost: local\r\nConnection: close\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n").await.unwrap();
            let mut interim = [0; 25];
            client.read_exact(&mut interim).await.unwrap();
            assert_eq!(&interim, b"HTTP/1.1 100 Continue\r\n\r\n");
            client.write_all(b"abc").await.unwrap();
            let mut response = String::new();
            client.read_to_string(&mut response).await.unwrap();
            assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        };
        let ((), result) = tokio::join!(client_job, serve(server, "local", 80, "http", &relay, &telemetry));
        result.unwrap();
    }
}
