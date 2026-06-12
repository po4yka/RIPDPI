use std::io;
use std::time::Duration;

use http::Method;
use http_body_util::BodyExt;
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use tokio::net::TcpStream;
use tokio::time::timeout;
use url::Url;

use crate::dto::{NativeOwnedTlsHttpRequest, RawHttpResponse};
use crate::request_builder::build_request;
use crate::socket_protection::connect_transport;
use crate::tls_profile::connect_tls;

pub(crate) struct UrlEndpoint {
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) target_path: String,
}

pub(crate) async fn execute_once(
    method: &Method,
    url: &Url,
    request: &NativeOwnedTlsHttpRequest,
) -> io::Result<RawHttpResponse> {
    let endpoint = parse_url_endpoint(url)?;
    let tcp = connect_transport(&endpoint.host, endpoint.port, request.connect_timeout_ms).await?;
    tcp.set_nodelay(true)?;

    match url.scheme() {
        "https" => execute_once_https(method, &endpoint, request, tcp).await,
        "http" => {
            send_request(
                method,
                &endpoint.target_path,
                &endpoint.host,
                endpoint.port,
                false,
                request,
                TokioIo::new(tcp),
            )
            .await
        }
        scheme => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("unsupported scheme for native TLS fetch: {scheme}"),
        )),
    }
}

#[inline(never)]
fn parse_url_endpoint(url: &Url) -> io::Result<UrlEndpoint> {
    let host = url
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "native TLS fetch URL has no host"))?
        .to_string();
    let port = url.port_or_known_default().unwrap_or(default_port(url.scheme()));
    let path = url.path().to_string();
    let query_suffix = url.query().map(|query| format!("?{query}")).unwrap_or_default();
    Ok(UrlEndpoint { host, port, target_path: format!("{path}{query_suffix}") })
}

#[inline(never)]
async fn execute_once_https(
    method: &Method,
    endpoint: &UrlEndpoint,
    request: &NativeOwnedTlsHttpRequest,
    tcp: TcpStream,
) -> io::Result<RawHttpResponse> {
    let tls = connect_tls(&endpoint.host, tcp, request.connect_timeout_ms, &request.tls_profile_id).await?;
    send_request(method, &endpoint.target_path, &endpoint.host, endpoint.port, true, request, TokioIo::new(tls)).await
}

async fn send_request<T>(
    method: &Method,
    target_path: &str,
    host: &str,
    port: u16,
    https: bool,
    request: &NativeOwnedTlsHttpRequest,
    io: TokioIo<T>,
) -> io::Result<RawHttpResponse>
where
    T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut sender, connection) = timeout(Duration::from_millis(request.read_timeout_ms), http1::handshake(io))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP handshake timed out"))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("HTTP handshake failed: {error}")))?;
    tokio::spawn(async move {
        let _ = connection.await;
    });

    let http_request = build_request(method, target_path, host, port, https, &request.headers)?;
    let response = timeout(Duration::from_millis(request.read_timeout_ms), sender.send_request(http_request))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP request timed out"))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("request failed: {error}")))?;
    let status_code = response.status();
    let headers = response.headers().clone();
    let body = timeout(Duration::from_millis(request.read_timeout_ms), response.into_body().collect())
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "response body timed out"))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("response body failed: {error}")))?;
    Ok(RawHttpResponse { status_code, headers, body: body.to_bytes() })
}

fn default_port(scheme: &str) -> u16 {
    match scheme {
        "http" => 80,
        _ => 443,
    }
}
