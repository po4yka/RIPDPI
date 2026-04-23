use std::collections::BTreeMap;
use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream as StdTcpStream};
use std::os::fd::AsRawFd;
use std::time::Duration;

use base64::Engine;
use bytes::Bytes;
use http::header::{HeaderName, HeaderValue, HOST, LOCATION};
use http::{Method, Request, StatusCode};
use http_body_util::{BodyExt, Full};
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use ripdpi_dns_resolver::{
    extract_ip_answers, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver,
    EncryptedDnsTransport,
};
use ripdpi_native_protect::{has_protect_callback, protect_socket_via_callback};
use ripdpi_tls_profiles::{configure_builder, profile_catalog_version, selected_profile_metadata};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;
use url::Url;

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";
const DEFAULT_TLS_PROFILE: &str = "chrome_stable";
const DNS_RECORD_TYPE_A: u16 = 1;
const DNS_RECORD_TYPE_AAAA: u16 = 28;
const OWNED_FETCH_DOH_HOST: &str = "dns.adguard-dns.com";
const OWNED_FETCH_DOH_URL: &str = "https://dns.adguard-dns.com/dns-query";
const OWNED_FETCH_DOH_BOOTSTRAP_IPS: &[&str] = &["94.140.14.14", "94.140.15.15"];

#[derive(Debug, Deserialize)]
pub struct NativeOwnedTlsHttpRequest {
    #[serde(default = "default_method")]
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    #[serde(rename = "tlsProfileId", default = "default_tls_profile")]
    pub tls_profile_id: String,
    #[serde(rename = "connectTimeoutMs", default = "default_connect_timeout_ms")]
    pub connect_timeout_ms: u64,
    #[serde(rename = "readTimeoutMs", default = "default_read_timeout_ms")]
    pub read_timeout_ms: u64,
    #[serde(rename = "callTimeoutMs", default = "default_call_timeout_ms")]
    pub call_timeout_ms: u64,
    #[serde(rename = "maxRedirects", default = "default_max_redirects")]
    pub max_redirects: usize,
}

#[derive(Debug, Serialize)]
pub struct NativeOwnedTlsHttpResponse {
    #[serde(rename = "statusCode")]
    pub status_code: Option<u16>,
    #[serde(rename = "bodyBase64")]
    pub body_base64: Option<String>,
    #[serde(rename = "finalUrl")]
    pub final_url: Option<String>,
    #[serde(rename = "tlsProfileId")]
    pub tls_profile_id: Option<String>,
    #[serde(rename = "tlsProfileCatalogVersion")]
    pub tls_profile_catalog_version: Option<String>,
    #[serde(rename = "tlsJa3ParityTarget")]
    pub tls_ja3_parity_target: Option<String>,
    #[serde(rename = "tlsJa4ParityTarget")]
    pub tls_ja4_parity_target: Option<String>,
    #[serde(rename = "tlsBrowserFamily")]
    pub tls_browser_family: Option<String>,
    #[serde(rename = "tlsBrowserTrack")]
    pub tls_browser_track: Option<String>,
    #[serde(rename = "tlsTemplateAlpn")]
    pub tls_template_alpn: Option<String>,
    #[serde(rename = "tlsTemplateExtensionOrderFamily")]
    pub tls_template_extension_order_family: Option<String>,
    #[serde(rename = "tlsTemplateGreaseStyle")]
    pub tls_template_grease_style: Option<String>,
    #[serde(rename = "tlsTemplateSupportedGroupsProfile")]
    pub tls_template_supported_groups_profile: Option<String>,
    #[serde(rename = "tlsTemplateKeyShareProfile")]
    pub tls_template_key_share_profile: Option<String>,
    #[serde(rename = "tlsTemplateRecordChoreography")]
    pub tls_template_record_choreography: Option<String>,
    #[serde(rename = "tlsTemplateEchCapable")]
    pub tls_template_ech_capable: Option<bool>,
    #[serde(rename = "tlsTemplateEchBootstrapPolicy")]
    pub tls_template_ech_bootstrap_policy: Option<String>,
    #[serde(rename = "tlsTemplateEchBootstrapResolverId")]
    pub tls_template_ech_bootstrap_resolver_id: Option<String>,
    #[serde(rename = "tlsTemplateEchOuterExtensionPolicy")]
    pub tls_template_ech_outer_extension_policy: Option<String>,
    #[serde(rename = "clientHelloSizeHint")]
    pub client_hello_size_hint: Option<usize>,
    #[serde(rename = "clientHelloInvariantStatus")]
    pub client_hello_invariant_status: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug)]
struct RawHttpResponse {
    status_code: StatusCode,
    headers: http::HeaderMap,
    body: Bytes,
}

pub fn execute(request_json: &str) -> io::Result<String> {
    let request: NativeOwnedTlsHttpRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let response = runtime.block_on(async {
        timeout(Duration::from_millis(request.call_timeout_ms), execute_async(request))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "native TLS fetch timed out"))?
    });
    let payload = match response {
        Ok(response) => response,
        Err(error) => NativeOwnedTlsHttpResponse {
            status_code: None,
            body_base64: None,
            final_url: None,
            tls_profile_id: None,
            tls_profile_catalog_version: None,
            tls_ja3_parity_target: None,
            tls_ja4_parity_target: None,
            tls_browser_family: None,
            tls_browser_track: None,
            tls_template_alpn: None,
            tls_template_extension_order_family: None,
            tls_template_grease_style: None,
            tls_template_supported_groups_profile: None,
            tls_template_key_share_profile: None,
            tls_template_record_choreography: None,
            tls_template_ech_capable: None,
            tls_template_ech_bootstrap_policy: None,
            tls_template_ech_bootstrap_resolver_id: None,
            tls_template_ech_outer_extension_policy: None,
            client_hello_size_hint: None,
            client_hello_invariant_status: None,
            error: Some(error.to_string()),
        },
    };
    serde_json::to_string(&payload).map_err(io::Error::other)
}

async fn execute_async(request: NativeOwnedTlsHttpRequest) -> io::Result<NativeOwnedTlsHttpResponse> {
    let profile_metadata = selected_profile_metadata(&request.tls_profile_id);
    let method = Method::from_bytes(request.method.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid method: {error}")))?;
    let mut current_url = Url::parse(&request.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid URL: {error}")))?;
    let mut redirects_remaining = request.max_redirects;

    loop {
        let response = execute_once(&method, &current_url, &request).await?;
        if let Some(location) = redirect_target(&current_url, &response)? {
            if redirects_remaining == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("too many redirects while fetching {}", request.url),
                ));
            }
            redirects_remaining -= 1;
            current_url = location;
            continue;
        }
        return Ok(NativeOwnedTlsHttpResponse {
            status_code: Some(response.status_code.as_u16()),
            body_base64: Some(base64::engine::general_purpose::STANDARD.encode(response.body)),
            final_url: Some(current_url.into()),
            tls_profile_id: Some(profile_metadata.profile_name.to_string()),
            tls_profile_catalog_version: Some(profile_catalog_version().to_string()),
            tls_ja3_parity_target: Some(profile_metadata.parity_targets.ja3.to_string()),
            tls_ja4_parity_target: Some(profile_metadata.parity_targets.ja4.to_string()),
            tls_browser_family: Some(profile_metadata.parity_targets.browser_family.to_string()),
            tls_browser_track: Some(profile_metadata.parity_targets.browser_track.to_string()),
            tls_template_alpn: Some(profile_metadata.template.alpn_template.to_string()),
            tls_template_extension_order_family: Some(profile_metadata.template.extension_order_family.to_string()),
            tls_template_grease_style: Some(profile_metadata.template.grease_style.to_string()),
            tls_template_supported_groups_profile: Some(profile_metadata.template.supported_groups_profile.to_string()),
            tls_template_key_share_profile: Some(profile_metadata.template.key_share_profile.to_string()),
            tls_template_record_choreography: Some(profile_metadata.template.record_choreography.to_string()),
            tls_template_ech_capable: Some(profile_metadata.template.ech_capable),
            tls_template_ech_bootstrap_policy: Some(profile_metadata.template.ech_bootstrap_policy.to_string()),
            tls_template_ech_bootstrap_resolver_id: profile_metadata
                .template
                .ech_bootstrap_resolver_id
                .map(ToString::to_string),
            tls_template_ech_outer_extension_policy: Some(
                profile_metadata.template.ech_outer_extension_policy.to_string(),
            ),
            client_hello_size_hint: Some(profile_metadata.client_hello_size_hint),
            client_hello_invariant_status: Some(profile_metadata.invariant_status.as_str().to_string()),
            error: None,
        });
    }
}

fn redirect_target(current_url: &Url, response: &RawHttpResponse) -> io::Result<Option<Url>> {
    match response.status_code {
        StatusCode::MOVED_PERMANENTLY
        | StatusCode::FOUND
        | StatusCode::SEE_OTHER
        | StatusCode::TEMPORARY_REDIRECT
        | StatusCode::PERMANENT_REDIRECT => {
            let Some(location) = response.headers.get(LOCATION) else {
                return Ok(None);
            };
            let location = location.to_str().map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidData, format!("invalid redirect location: {error}"))
            })?;
            let location = current_url.join(location).map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("invalid redirect target from {}: {error}", current_url),
                )
            })?;
            if current_url.scheme() == "https" && location.scheme() == "http" {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("refusing HTTPS to HTTP redirect downgrade from {current_url} to {location}"),
                ));
            }
            Ok(Some(location))
        }
        _ => Ok(None),
    }
}

async fn execute_once(method: &Method, url: &Url, request: &NativeOwnedTlsHttpRequest) -> io::Result<RawHttpResponse> {
    let host = url
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "native TLS fetch URL has no host"))?
        .to_string();
    let port = url.port_or_known_default().unwrap_or(default_port(url.scheme()));
    let path = url.path().to_string();
    let query_suffix = url.query().map(|query| format!("?{query}")).unwrap_or_default();
    let target_path = format!("{path}{query_suffix}");
    let tcp = connect_transport(&host, port, request.connect_timeout_ms).await?;
    tcp.set_nodelay(true)?;

    match url.scheme() {
        "https" => {
            let mut connector_builder = configure_builder(&request.tls_profile_id)
                .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
            connector_builder
                .set_alpn_protos(HTTP11_ALPN)
                .map_err(|error| io::Error::other(format!("TLS ALPN: {error}")))?;
            let ssl = connector_builder
                .build()
                .configure()
                .map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
            let tls =
                timeout(Duration::from_millis(request.connect_timeout_ms), tokio_boring::connect(ssl, &host, tcp))
                    .await
                    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("TLS handshake to {host} timed out")))?
                    .map_err(|error| {
                        io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS handshake failed: {error}"))
                    })?;
            send_request(method, &target_path, &host, port, request, TokioIo::new(tls)).await
        }
        "http" => send_request(method, &target_path, &host, port, request, TokioIo::new(tcp)).await,
        scheme => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("unsupported scheme for native TLS fetch: {scheme}"),
        )),
    }
}

async fn connect_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    timeout(Duration::from_millis(connect_timeout_ms), connect_transport_inner(host, port))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("connect to {host}:{port} timed out")))?
}

async fn connect_transport_inner(host: &str, port: u16) -> io::Result<TcpStream> {
    let targets = resolve_connect_targets(host, port).await?;
    let mut last_error = None;
    for target in targets {
        let socket = tcp_socket_for(target)?;
        protect_socket_if_available(&socket)?;
        match socket.connect(target).await {
            Ok(stream) => return Ok(stream),
            Err(error) => {
                last_error = Some(io::Error::new(error.kind(), format!("connect to {target}: {error}")));
            }
        }
    }
    Err(last_error.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, format!("connect to {host}:{port}: no usable addresses"))
    }))
}

async fn resolve_connect_targets(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    let resolver = owned_fetch_encrypted_resolver()?;
    let mut targets = encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_A).await?;
    targets.extend(encrypted_dns_targets(&resolver, host, port, DNS_RECORD_TYPE_AAAA).await?);
    if targets.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::AddrNotAvailable,
            format!("encrypted DNS resolved no addresses for {host}:{port}"),
        ));
    }
    Ok(targets)
}

fn owned_fetch_encrypted_resolver() -> io::Result<EncryptedDnsResolver> {
    let bootstrap_ips = OWNED_FETCH_DOH_BOOTSTRAP_IPS
        .iter()
        .map(|value| {
            value.parse::<IpAddr>().map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("invalid owned fetch DoH bootstrap IP {value}: {error}"),
                )
            })
        })
        .collect::<io::Result<Vec<_>>>()?;
    EncryptedDnsResolver::with_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("adguard".to_string()),
            host: OWNED_FETCH_DOH_HOST.to_string(),
            port: 443,
            tls_server_name: Some(OWNED_FETCH_DOH_HOST.to_string()),
            bootstrap_ips,
            doh_url: Some(OWNED_FETCH_DOH_URL.to_string()),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        owned_fetch_dns_connect_hooks(),
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("build owned fetch resolver: {error}")))
}

async fn encrypted_dns_targets(
    resolver: &EncryptedDnsResolver,
    host: &str,
    port: u16,
    record_type: u16,
) -> io::Result<Vec<SocketAddr>> {
    let query = build_dns_query(host, record_type, dns_query_id())?;
    let response = resolver
        .exchange(&query)
        .await
        .map_err(|error| io::Error::other(format!("encrypted DNS resolve {host}: {error}")))?;
    let answers =
        extract_ip_answers(&response).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
    Ok(answers
        .into_iter()
        .filter_map(|answer| answer.parse::<IpAddr>().ok())
        .map(|ip| SocketAddr::new(ip, port))
        .collect())
}

fn owned_fetch_dns_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_tcp_connector(|target, timeout| {
        let domain = match target {
            SocketAddr::V4(_) => Domain::IPV4,
            SocketAddr::V6(_) => Domain::IPV6,
        };
        let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
        if has_protect_callback() {
            protect_socket_via_callback(socket.as_raw_fd())
                .map_err(|error| io::Error::new(error.kind(), format!("protect owned fetch DNS socket: {error}")))?;
        }
        socket.connect_timeout(&SockAddr::from(target), timeout)?;
        let stream: StdTcpStream = socket.into();
        stream.set_nodelay(true)?;
        Ok(stream)
    })
}

fn build_dns_query(domain: &str, record_type: u16, query_id: u16) -> io::Result<Vec<u8>> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.trim_end_matches('.').split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("invalid DNS name: {domain}")));
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(record_type.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

fn dns_query_id() -> u16 {
    use std::time::{SystemTime, UNIX_EPOCH};

    (((SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos() as u64) & 0xffff) as u16).max(1)
}

fn tcp_socket_for(target: SocketAddr) -> io::Result<TcpSocket> {
    match target {
        SocketAddr::V4(_) => TcpSocket::new_v4(),
        SocketAddr::V6(_) => TcpSocket::new_v6(),
    }
}

fn protect_socket_if_available(socket: &TcpSocket) -> io::Result<()> {
    if has_protect_callback() {
        protect_socket_via_callback(socket.as_raw_fd())
            .map_err(|error| io::Error::new(error.kind(), format!("protect native TLS fetch socket: {error}")))?;
    }
    Ok(())
}

async fn send_request<T>(
    method: &Method,
    target_path: &str,
    host: &str,
    port: u16,
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

    let http_request = build_request(method, target_path, host, port, &request.headers)?;
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

fn build_request(
    method: &Method,
    target_path: &str,
    host: &str,
    port: u16,
    headers: &BTreeMap<String, String>,
) -> io::Result<Request<Full<Bytes>>> {
    let mut builder = Request::builder().method(method.clone()).uri(target_path);
    let mut has_host_header = false;
    for (name, value) in headers {
        let header_name = HeaderName::try_from(name.as_str())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid header name: {error}")))?;
        if header_name == HOST {
            has_host_header = true;
        }
        let header_value = HeaderValue::try_from(value.as_str())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid header value: {error}")))?;
        builder = builder.header(header_name, header_value);
    }
    if !has_host_header {
        builder = builder.header(HOST, authority_header_value(host, port));
    }
    builder
        .body(Full::new(Bytes::new()))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid request body: {error}")))
}

fn authority_header_value(host: &str, port: u16) -> String {
    if port == 443 || port == 80 {
        host.to_string()
    } else {
        format!("{host}:{port}")
    }
}

fn default_port(scheme: &str) -> u16 {
    match scheme {
        "http" => 80,
        _ => 443,
    }
}

fn default_method() -> String {
    "GET".to_string()
}

fn default_tls_profile() -> String {
    DEFAULT_TLS_PROFILE.to_string()
}

const fn default_connect_timeout_ms() -> u64 {
    20_000
}

const fn default_read_timeout_ms() -> u64 {
    90_000
}

const fn default_call_timeout_ms() -> u64 {
    120_000
}

const fn default_max_redirects() -> usize {
    5
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread;

    use base64::engine::general_purpose::STANDARD;
    use ripdpi_native_protect::{register_protect_callback, unregister_protect_callback, ProtectCallback};
    use serde_json::Value;

    static PROTECT_TEST_MUTEX: Mutex<()> = Mutex::new(());

    #[test]
    fn execute_fetches_plain_http_response() {
        let server = spawn_http_server(vec![http_response("200 OK", &[], b"manifest")]);
        let port = server.local_addr().expect("local addr").port();
        let request = serde_json::json!({
            "url": format!("http://127.0.0.1:{port}/manifest.json"),
            "headers": {"User-Agent": "RIPDPI test"},
            "tlsProfileId": "chrome_stable",
        });

        let payload = execute(&request.to_string()).expect("execute");
        let response: Value = serde_json::from_str(&payload).expect("json response");

        assert_eq!(response["statusCode"], 200);
        assert_eq!(STANDARD.decode(response["bodyBase64"].as_str().expect("body")).expect("decode body"), b"manifest");
        assert_eq!(response["finalUrl"].as_str().expect("final url"), format!("http://127.0.0.1:{port}/manifest.json"));
        assert_eq!(response["tlsProfileId"], "chrome_stable");
        assert_eq!(response["tlsProfileCatalogVersion"], "v1");
        assert_eq!(response["tlsBrowserFamily"], "chrome");
        assert_eq!(response["tlsBrowserTrack"], "android-stable");
        assert_eq!(response["tlsTemplateAlpn"], "h2_http11");
        assert_eq!(response["clientHelloInvariantStatus"], "avoids_blocked_517_byte_client_hello");
    }

    #[test]
    fn execute_follows_redirects() {
        let server = spawn_http_server(vec![
            http_response("302 Found", &[("Location", "/final.json")], b""),
            http_response("200 OK", &[], b"catalog"),
        ]);
        let port = server.local_addr().expect("local addr").port();
        let request = serde_json::json!({
            "url": format!("http://127.0.0.1:{port}/manifest.json"),
            "headers": {"User-Agent": "RIPDPI test"},
            "tlsProfileId": "chrome_stable",
            "maxRedirects": 2,
        });

        let payload = execute(&request.to_string()).expect("execute");
        let response: Value = serde_json::from_str(&payload).expect("json response");

        assert_eq!(response["statusCode"], 200);
        assert_eq!(STANDARD.decode(response["bodyBase64"].as_str().expect("body")).expect("decode body"), b"catalog");
        assert_eq!(response["finalUrl"].as_str().expect("final url"), format!("http://127.0.0.1:{port}/final.json"));
        assert_eq!(response["tlsJa3ParityTarget"], "chrome-stable");
        assert_eq!(response["tlsJa4ParityTarget"], "chrome-stable");
        assert_eq!(response["tlsTemplateGreaseStyle"], "chromium_single_grease");
    }

    #[test]
    fn redirect_target_rejects_https_to_http_downgrade() {
        let current_url = Url::parse("https://example.com/start.json").expect("current url");
        let response = RawHttpResponse {
            status_code: StatusCode::FOUND,
            headers: http::HeaderMap::from_iter([(
                LOCATION,
                HeaderValue::from_static("http://example.com/insecure.json"),
            )]),
            body: Bytes::new(),
        };

        let error = redirect_target(&current_url, &response).expect_err("downgrade should be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("HTTPS to HTTP redirect downgrade"));
    }

    #[test]
    fn execute_protects_socket_before_connect_when_callback_registered() {
        let _lock = PROTECT_TEST_MUTEX.lock().expect("test mutex");
        let guard = ProtectRegistrationGuard::register();
        let callback = Arc::new(TestProtectCallback::default());
        let callback_for_registration: Arc<dyn ProtectCallback> = callback.clone();
        guard.install(callback_for_registration);

        let server = spawn_http_server(vec![http_response("200 OK", &[], b"manifest")]);
        let port = server.local_addr().expect("local addr").port();
        let request = serde_json::json!({
            "url": format!("http://127.0.0.1:{port}/manifest.json"),
            "headers": {"User-Agent": "RIPDPI test"},
            "tlsProfileId": "chrome_stable",
        });

        let payload = execute(&request.to_string()).expect("execute");
        let response: Value = serde_json::from_str(&payload).expect("json response");

        assert_eq!(response["statusCode"], 200);
        assert!(callback.last_fd.load(Ordering::Relaxed) >= 0, "protect callback should observe a socket fd");
    }

    #[derive(Default)]
    struct TestProtectCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for TestProtectCallback {
        fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Relaxed);
            Ok(())
        }
    }

    struct ProtectRegistrationGuard;

    impl ProtectRegistrationGuard {
        fn register() -> Self {
            unregister_protect_callback();
            Self
        }

        fn install(&self, callback: Arc<dyn ProtectCallback>) {
            register_protect_callback(callback);
        }
    }

    impl Drop for ProtectRegistrationGuard {
        fn drop(&mut self) {
            unregister_protect_callback();
        }
    }

    fn http_response(status_line: &str, headers: &[(&str, &str)], body: &[u8]) -> Vec<u8> {
        let mut response = format!("HTTP/1.1 {status_line}\r\nContent-Length: {}\r\n", body.len());
        for (name, value) in headers {
            response.push_str(&format!("{name}: {value}\r\n"));
        }
        response.push_str("\r\n");
        let mut bytes = response.into_bytes();
        bytes.extend_from_slice(body);
        bytes
    }

    fn spawn_http_server(responses: Vec<Vec<u8>>) -> TcpListener {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let server = listener.try_clone().expect("clone listener");
        thread::spawn(move || {
            for response in responses {
                let (mut stream, _) = server.accept().expect("accept");
                let mut buffer = [0u8; 1024];
                let _ = stream.read(&mut buffer);
                stream.write_all(&response).expect("write response");
                stream.flush().expect("flush response");
            }
        });
        listener
    }
}
