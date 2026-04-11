#![forbid(unsafe_code)]

pub mod auth;
pub mod config;

use std::collections::HashMap;
use std::future::poll_fn;
use std::io;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;

use bytes::{Buf, Bytes, BytesMut};
use h3_datagram::datagram_handler::{DatagramSender, HandleDatagramsExt};
use http::{HeaderMap, Request, StatusCode};
use hyper_util::rt::TokioIo;
use rustls::RootCertStore;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex};
use tokio::task::JoinHandle;
use url::{form_urlencoded::byte_serialize, Url};

use crate::auth::{
    build_static_auth_header, parse_privacy_pass_challenge, AuthHeader, PrivacyPassCache, PrivacyPassProviderRequest,
    PrivacyPassProviderResponse,
};
use crate::config::{MasqueAuthMode, MasqueConfig};

const H3_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const UDP_CONTEXT_ID: u8 = 0;

type H3DatagramSender = DatagramSender<h3_quinn::datagram::SendDatagramHandler, Bytes>;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub struct MasqueClient {
    inner: Arc<MasqueClientInner>,
}

struct MasqueClientInner {
    config: MasqueConfig,
    provider_client: reqwest::Client,
    privacy_pass_cache: Mutex<HashMap<String, PrivacyPassCache>>,
}

pub struct MasqueUdpRelay {
    client: Arc<MasqueClientInner>,
    flows: HashMap<String, MasqueUdpFlow>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
    incoming_rx: mpsc::Receiver<(String, Vec<u8>)>,
}

struct MasqueUdpFlow {
    sender: H3DatagramSender,
    driver_task: JoinHandle<()>,
    reader_task: JoinHandle<()>,
}

enum AttemptError {
    Io(io::Error),
    PrivacyPassChallenge(String),
}

impl From<io::Error> for AttemptError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl MasqueClient {
    pub fn new(config: MasqueConfig) -> io::Result<Self> {
        let provider_client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|error| io::Error::other(format!("failed to build Privacy Pass provider client: {error}")))?;

        Ok(Self {
            inner: Arc::new(MasqueClientInner {
                config,
                provider_client,
                privacy_pass_cache: Mutex::new(HashMap::new()),
            }),
        })
    }

    pub async fn connect(config: &MasqueConfig, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        Self::new(config.clone())?.connect_tcp(target).await
    }

    pub async fn connect_tcp(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        if !self.inner.config.use_http2_fallback {
            return self.connect_tcp_h3(target).await;
        }

        match tokio::time::timeout(H3_CONNECT_TIMEOUT, self.connect_tcp_h3(target)).await {
            Ok(Ok(stream)) => Ok(stream),
            Ok(Err(error)) => {
                tracing::info!(target, error = %error, "MASQUE H3 TCP connect failed, falling back to HTTP/2");
                self.connect_tcp_h2(target).await
            }
            Err(_) => {
                tracing::info!(target, "MASQUE H3 TCP connect timed out, falling back to HTTP/2");
                self.connect_tcp_h2(target).await
            }
        }
    }

    pub fn udp_session(&self) -> MasqueUdpRelay {
        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        MasqueUdpRelay { client: Arc::clone(&self.inner), flows: HashMap::new(), incoming_tx, incoming_rx }
    }

    async fn connect_tcp_h3(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        let auth_header = self.inner.request_auth_header(target).await?;
        match attempt_h3_connect_tcp(&self.inner.config, target, auth_header.as_ref()).await {
            Ok(stream) => Ok(Box::new(stream)),
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.inner.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h3_connect_tcp(&self.inner.config, target, Some(&retry_header)).await {
                    Ok(stream) => Ok(Box::new(stream)),
                    Err(AttemptError::Io(error)) => Err(error),
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => Err(error),
        }
    }

    async fn connect_tcp_h2(&self, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        let auth_header = self.inner.request_auth_header(target).await?;
        match attempt_h2_connect_tcp(&self.inner.config, target, auth_header.as_ref()).await {
            Ok(stream) => Ok(Box::new(stream)),
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.inner.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h2_connect_tcp(&self.inner.config, target, Some(&retry_header)).await {
                    Ok(stream) => Ok(Box::new(stream)),
                    Err(AttemptError::Io(error)) => Err(error),
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => Err(error),
        }
    }
}

impl MasqueUdpRelay {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        if !self.flows.contains_key(target) {
            let flow = self.open_flow(target).await?;
            self.flows.insert(target.to_string(), flow);
        }

        let flow = self.flows.get_mut(target).expect("flow inserted above");
        flow.send(payload)
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "MASQUE UDP relay closed"))
    }

    async fn open_flow(&self, target: &str) -> io::Result<MasqueUdpFlow> {
        let auth_header = self.client.request_auth_header(target).await?;
        match attempt_h3_connect_udp(&self.client.config, target, auth_header.as_ref(), self.incoming_tx.clone()).await
        {
            Ok(flow) => Ok(flow),
            Err(AttemptError::PrivacyPassChallenge(challenge)) => {
                let retry_header = self.client.fetch_privacy_pass_header(target, &challenge).await?;
                match attempt_h3_connect_udp(&self.client.config, target, Some(&retry_header), self.incoming_tx.clone())
                    .await
                {
                    Ok(flow) => Ok(flow),
                    Err(AttemptError::Io(error)) => Err(error),
                    Err(AttemptError::PrivacyPassChallenge(_)) => Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "MASQUE proxy requested Privacy Pass credentials again after retry",
                    )),
                }
            }
            Err(AttemptError::Io(error)) => Err(error),
        }
    }
}

impl MasqueUdpFlow {
    fn send(&mut self, payload: &[u8]) -> io::Result<()> {
        let mut datagram = BytesMut::with_capacity(1 + payload.len());
        datagram.extend_from_slice(&[UDP_CONTEXT_ID]);
        datagram.extend_from_slice(payload);
        self.sender
            .send_datagram(datagram.freeze())
            .map_err(|error| io::Error::other(format!("failed to send MASQUE UDP datagram: {error}")))
    }
}

impl Drop for MasqueUdpFlow {
    fn drop(&mut self) {
        self.driver_task.abort();
        self.reader_task.abort();
    }
}

impl MasqueClientInner {
    async fn request_auth_header(&self, target: &str) -> io::Result<Option<AuthHeader>> {
        if self.config.effective_auth_mode() == MasqueAuthMode::PrivacyPass {
            return Ok(self.cached_privacy_pass_header(target).await);
        }
        build_static_auth_header(&self.config)
    }

    async fn cached_privacy_pass_header(&self, target: &str) -> Option<AuthHeader> {
        if self.config.effective_auth_mode() != MasqueAuthMode::PrivacyPass {
            return None;
        }

        self.privacy_pass_cache.lock().await.entry(target.to_string()).or_default().pop()
    }

    async fn fetch_privacy_pass_header(&self, target: &str, challenge_header: &str) -> io::Result<AuthHeader> {
        if self.config.effective_auth_mode() != MasqueAuthMode::PrivacyPass {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "Privacy Pass retry requested while MASQUE auth mode is not privacy_pass",
            ));
        }

        let provider_url =
            self.config.privacy_pass_provider_url.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(
                || {
                    io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "MASQUE privacy_pass mode requires a deployer-supplied token provider URL",
                    )
                },
            )?;

        let mut request = self.provider_client.post(provider_url).json(&PrivacyPassProviderRequest {
            proxy_url: self.config.url.clone(),
            target: target.to_string(),
            challenge_header: challenge_header.to_string(),
        });
        if let Some(token) =
            self.config.privacy_pass_provider_auth_token.as_ref().filter(|value| !value.trim().is_empty())
        {
            request = request.bearer_auth(token);
        }

        let response = request
            .send()
            .await
            .map_err(|error| io::Error::other(format!("Privacy Pass provider request failed: {error}")))?;
        if !response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                format!("Privacy Pass provider returned {}", response.status()),
            ));
        }

        let response: PrivacyPassProviderResponse = response.json().await.map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("invalid Privacy Pass provider body: {error}"))
        })?;
        let expires_at_epoch_ms = response.expires_at_epoch_ms;
        let mut headers = response.into_headers();
        if headers.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Privacy Pass provider returned no authorization headers",
            ));
        }

        let mut cache = self.privacy_pass_cache.lock().await;
        cache.entry(target.to_string()).or_default().extend(std::mem::take(&mut headers), expires_at_epoch_ms);
        cache
            .entry(target.to_string())
            .or_default()
            .pop()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Privacy Pass provider cache was empty"))
    }
}

async fn attempt_h3_connect_tcp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo, AttemptError> {
    let (mut driver, mut send_request) = connect_h3_transport(config, false).await?;
    let request = Request::builder()
        .method("CONNECT")
        .uri(format!("/{target}"))
        .header(":protocol", "connect-tcp")
        .header(":authority", target);
    let request = apply_auth_header(request, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-TCP request: {error}"))
    })?;

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-TCP request: {error}"))
    })?;
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-TCP response: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers())?;

    tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 TCP driver closed");
    });

    Ok(spawn_h3_bridge(stream))
}

async fn attempt_h3_connect_udp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
    incoming_tx: mpsc::Sender<(String, Vec<u8>)>,
) -> Result<MasqueUdpFlow, AttemptError> {
    let target = parse_target(target)?;
    let proxy_origin = parse_proxy_origin(config)?;
    let (mut driver, mut send_request) = connect_h3_transport(config, true).await?;

    let request = Request::builder()
        .method("CONNECT")
        .uri(build_connect_udp_path(&target))
        .header(":protocol", "connect-udp")
        .header(":authority", proxy_origin.authority)
        .header(":scheme", "https")
        .header("capsule-protocol", "?1");
    let request = apply_auth_header(request, auth_header)?.body(()).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H3 CONNECT-UDP request: {error}"))
    })?;

    let mut stream = send_request.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H3 CONNECT-UDP request: {error}"))
    })?;
    stream.finish().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to finish H3 CONNECT-UDP request: {error}"))
    })?;
    let response = stream.recv_response().await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to receive H3 CONNECT-UDP response: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers())?;

    let stream_id = stream.id();
    let datagram_sender = driver.get_datagram_sender(stream_id);
    let mut datagram_reader = driver.get_datagram_reader();
    let target_label = target.authority();

    let reader_task = tokio::spawn(async move {
        let _stream = stream;
        loop {
            let datagram = match datagram_reader.read_datagram().await {
                Ok(datagram) => datagram,
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "MASQUE UDP datagram reader closed");
                    break;
                }
            };
            if datagram.stream_id() != stream_id {
                continue;
            }
            let payload = datagram.into_payload();
            match decode_udp_payload(payload) {
                Ok(payload) => {
                    if incoming_tx.send((target_label.clone(), payload)).await.is_err() {
                        break;
                    }
                }
                Err(error) => {
                    tracing::debug!(error = %error, target = %target_label, "ignored malformed MASQUE UDP datagram");
                }
            }
        }
    });

    let driver_task = tokio::spawn(async move {
        let error = poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::debug!(error = %error, "MASQUE H3 UDP driver closed");
    });

    Ok(MasqueUdpFlow { sender: datagram_sender, driver_task, reader_task })
}

async fn attempt_h2_connect_tcp(
    config: &MasqueConfig,
    target: &str,
    auth_header: Option<&AuthHeader>,
) -> Result<impl AsyncIo, AttemptError> {
    let proxy_origin = parse_proxy_origin(config)?;
    let tcp = TcpStream::connect(proxy_origin.socket_addr)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("failed to connect to MASQUE proxy: {error}")))?;
    tcp.set_nodelay(true)?;

    let connector = ripdpi_tls_profiles::build_connector(&config.tls_fingerprint_profile, true)
        .map_err(|error| io::Error::other(format!("failed to build H2 TLS profile: {error}")))?;
    let ssl = connector
        .configure()
        .map_err(|error| io::Error::other(format!("failed to configure H2 TLS profile: {error}")))?;
    let tls = tokio_boring::connect(ssl, &proxy_origin.host, tcp).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake failed: {error}"))
    })?;

    let io = TokioIo::new(tls);
    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), io).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate H2: {error}"))
        })?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "MASQUE H2 TCP driver closed");
        }
    });

    let request = apply_auth_header(hyper::Request::builder().method("CONNECT").uri(target), auth_header)?
        .body(http_body_util::Empty::<Bytes>::new())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid H2 CONNECT request: {error}")))?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send H2 CONNECT request: {error}"))
    })?;
    validate_proxy_response(response.status(), response.headers())?;

    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade H2 CONNECT stream: {error}"))
    })?;
    Ok(TokioIo::new(upgraded))
}

async fn connect_h3_transport(
    config: &MasqueConfig,
    enable_datagram: bool,
) -> Result<
    (h3::client::Connection<h3_quinn::Connection, Bytes>, h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>),
    AttemptError,
> {
    let proxy_origin = parse_proxy_origin(config)?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut tls_config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];

    let quic_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::other(format!("failed to build QUIC TLS config: {error}")))?,
    ));

    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse().expect("literal socket address"))
        .map_err(|error| io::Error::other(format!("failed to create QUIC client endpoint: {error}")))?;
    endpoint.set_default_client_config(quic_config);

    let connection = endpoint
        .connect(proxy_origin.socket_addr, &proxy_origin.host)
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC connect failed: {error}")))?
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC handshake failed: {error}")))?;

    let mut builder = h3::client::builder();
    builder.enable_extended_connect(true);
    builder.enable_datagram(enable_datagram);
    builder.build(h3_quinn::Connection::new(connection)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate HTTP/3: {error}")).into()
    })
}

fn apply_auth_header(
    mut builder: http::request::Builder,
    auth_header: Option<&AuthHeader>,
) -> io::Result<http::request::Builder> {
    if let Some(header) = auth_header {
        builder = builder.header(header.name, header.value.as_str());
    }
    Ok(builder)
}

fn validate_proxy_response(status: StatusCode, headers: &HeaderMap) -> Result<(), AttemptError> {
    if status.is_success() {
        return Ok(());
    }

    if status == StatusCode::PROXY_AUTHENTICATION_REQUIRED || status == StatusCode::UNAUTHORIZED {
        let challenge = parse_privacy_pass_challenge(
            headers.get("www-authenticate").or_else(|| headers.get("proxy-authenticate")),
        )?;
        return Err(AttemptError::PrivacyPassChallenge(challenge));
    }

    Err(io::Error::new(io::ErrorKind::ConnectionRefused, format!("MASQUE proxy rejected request with status {status}"))
        .into())
}

fn parse_proxy_origin(config: &MasqueConfig) -> io::Result<ProxyOrigin> {
    let parsed = Url::parse(&config.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE URL: {error}")))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?
        .to_string();
    let port = parsed.port().unwrap_or(443);
    let authority = if port == 443 { host.clone() } else { format!("{host}:{port}") };
    let socket_addr = authority
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "failed to resolve MASQUE proxy host"))?;
    Ok(ProxyOrigin { host, authority, socket_addr })
}

fn build_connect_udp_path(target: &TargetAuthority) -> String {
    let host = byte_serialize(target.host.as_bytes()).collect::<String>();
    format!("/.well-known/masque/udp/{host}/{}/", target.port)
}

fn parse_target(target: &str) -> io::Result<TargetAuthority> {
    if let Ok(address) = target.parse::<SocketAddr>() {
        return Ok(TargetAuthority { host: address.ip().to_string(), port: address.port() });
    }

    if let Some(rest) = target.strip_prefix('[') {
        let (host, port) = rest.rsplit_once("]:").ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}"))
        })?;
        let port = port
            .parse::<u16>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port: {target}")))?;
        return Ok(TargetAuthority { host: host.to_string(), port });
    }

    let (host, port) = target
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}")))?;
    Ok(TargetAuthority {
        host: host.to_string(),
        port: port
            .parse::<u16>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port: {target}")))?,
    })
}

fn decode_udp_payload(payload: Bytes) -> io::Result<Vec<u8>> {
    let Some((&context_id, payload)) = payload.split_first() else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "MASQUE UDP datagram is missing the required context identifier",
        ));
    };
    if context_id != UDP_CONTEXT_ID {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported MASQUE UDP context identifier {context_id}"),
        ));
    }
    Ok(payload.to_vec())
}

fn spawn_h3_bridge(mut stream: h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>) -> impl AsyncIo {
    let (app_io, bridge_io) = tokio::io::duplex(64 * 1024);
    let (mut bridge_reader, mut bridge_writer) = tokio::io::split(bridge_io);

    tokio::spawn(async move {
        let mut send_buffer = vec![0u8; 16 * 1024];
        loop {
            tokio::select! {
                received = stream.recv_data() => {
                    match received {
                        Ok(Some(mut data)) => {
                            let bytes = data.copy_to_bytes(data.remaining());
                            if let Err(error) = bridge_writer.write_all(&bytes).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge writer closed");
                                break;
                            }
                        }
                        Ok(None) => break,
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge recv error");
                            break;
                        }
                    }
                }
                read = bridge_reader.read(&mut send_buffer) => {
                    match read {
                        Ok(0) => break,
                        Ok(count) => {
                            if let Err(error) = stream.send_data(Bytes::copy_from_slice(&send_buffer[..count])).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge send error");
                                break;
                            }
                        }
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge reader closed");
                            break;
                        }
                    }
                }
            }
        }
        let _ = stream.finish().await;
    });

    app_io
}

struct ProxyOrigin {
    host: String,
    authority: String,
    socket_addr: SocketAddr,
}

struct TargetAuthority {
    host: String,
    port: u16,
}

impl TargetAuthority {
    fn authority(&self) -> String {
        if self.host.contains(':') {
            format!("[{}]:{}", self.host, self.port)
        } else {
            format!("{}:{}", self.host, self.port)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    use serde_json::to_string;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio::sync::Mutex;

    fn privacy_pass_test_config(provider_url: String, provider_auth_token: Option<&str>) -> MasqueConfig {
        MasqueConfig {
            url: "https://masque.example/".to_string(),
            use_http2_fallback: false,
            auth_mode: Some("privacy_pass".to_string()),
            auth_token: None,
            privacy_pass_provider_url: Some(provider_url),
            privacy_pass_provider_auth_token: provider_auth_token.map(ToOwned::to_owned),
            tls_fingerprint_profile: "native_default".to_string(),
        }
    }

    async fn start_provider_stub(
        responses: Vec<(u16, PrivacyPassProviderResponse)>,
    ) -> io::Result<(String, Arc<Mutex<Vec<String>>>, tokio::task::JoinHandle<io::Result<()>>)> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let requests = Arc::new(Mutex::new(Vec::new()));
        let request_log = Arc::clone(&requests);

        let handle = tokio::spawn(async move {
            for (status, payload) in responses {
                let (mut socket, _) = listener.accept().await?;
                let request = read_http_request(&mut socket).await?;
                request_log.lock().await.push(request);

                let body = to_string(&payload)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
                let status_text = http_status_text(status);
                let response = format!(
                    "HTTP/1.1 {status} {status_text}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
                socket.write_all(response.as_bytes()).await?;
            }
            Ok(())
        });

        Ok((format!("http://{address}/token"), requests, handle))
    }

    async fn read_http_request(stream: &mut tokio::net::TcpStream) -> io::Result<String> {
        let mut buffer = Vec::new();
        let mut chunk = [0u8; 512];
        loop {
            let read = stream.read(&mut chunk).await?;
            if read == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "provider request ended before the full body arrived",
                ));
            }
            buffer.extend_from_slice(&chunk[..read]);
            if let Some(headers_end) = find_headers_end(&buffer) {
                let content_length = parse_content_length(&buffer[..headers_end])?;
                if buffer.len() >= headers_end + content_length {
                    return String::from_utf8(buffer)
                        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
                }
            }
        }
    }

    fn find_headers_end(buffer: &[u8]) -> Option<usize> {
        buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|index| index + 4)
    }

    fn parse_content_length(headers: &[u8]) -> io::Result<usize> {
        let headers = std::str::from_utf8(headers)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()))?;
        Ok(headers
            .lines()
            .find_map(|line| {
                let (name, value) = line.split_once(':')?;
                if name.trim().eq_ignore_ascii_case("content-length") {
                    value.trim().parse::<usize>().ok()
                } else {
                    None
                }
            })
            .unwrap_or(0))
    }

    fn http_status_text(status: u16) -> &'static str {
        match status {
            200 => "OK",
            403 => "Forbidden",
            _ => "Test",
        }
    }

    #[test]
    fn connect_udp_path_percent_encodes_ipv6_hosts() {
        let path = build_connect_udp_path(&TargetAuthority { host: "2001:db8::42".to_string(), port: 443 });

        assert_eq!(path, "/.well-known/masque/udp/2001%3Adb8%3A%3A42/443/");
    }

    #[test]
    fn decode_udp_payload_requires_context_zero() {
        let error = decode_udp_payload(Bytes::from_static(&[1, 2, 3])).expect_err("must reject non-zero context");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn parse_target_supports_domain_and_ipv6_authorities() {
        let domain = parse_target("example.com:53").expect("domain");
        assert_eq!(domain.host, "example.com");
        assert_eq!(domain.port, 53);

        let ipv6 = parse_target("[2001:db8::1]:443").expect("ipv6");
        assert_eq!(ipv6.host, "2001:db8::1");
        assert_eq!(ipv6.port, 443);
    }

    #[tokio::test]
    async fn privacy_pass_provider_fetch_caches_spare_headers() {
        let (provider_url, requests, provider_task) = start_provider_stub(vec![(
            200,
            PrivacyPassProviderResponse {
                authorization_headers: Some(vec![
                    "PrivateToken token-one".to_string(),
                    "PrivateToken token-two".to_string(),
                ]),
                authorization_header: None,
                proxy_authorization_headers: None,
                proxy_authorization_header: None,
                expires_at_epoch_ms: None,
            },
        )])
        .await
        .expect("provider stub");
        let client =
            MasqueClient::new(privacy_pass_test_config(provider_url, Some("provider-secret"))).expect("client");

        let first = client
            .inner
            .fetch_privacy_pass_header("example.com:443", "PrivateToken challenge=AAAA, token-key=BBBB")
            .await
            .expect("first provider header");
        assert_eq!(first.name, "authorization");
        assert_eq!(first.value, "PrivateToken token-one");

        let cached = client.inner.cached_privacy_pass_header("example.com:443").await.expect("cached provider header");
        assert_eq!(cached.name, "authorization");
        assert_eq!(cached.value, "PrivateToken token-two");
        assert!(client.inner.cached_privacy_pass_header("example.com:443").await.is_none());

        provider_task.await.expect("provider task").expect("provider result");
        let requests = requests.lock().await.clone();
        assert_eq!(requests.len(), 1);
        let request = &requests[0];
        let request_lower = request.to_ascii_lowercase();
        assert!(request.starts_with("POST /token HTTP/1.1"));
        assert!(request_lower.contains("authorization: bearer provider-secret"));
        assert!(request.contains("\"proxyUrl\":\"https://masque.example/\""));
        assert!(request.contains("\"target\":\"example.com:443\""));
        assert!(request.contains("\"challengeHeader\":\"PrivateToken challenge=AAAA, token-key=BBBB\""));
    }

    #[tokio::test]
    async fn privacy_pass_provider_non_success_is_permission_denied() {
        let (provider_url, requests, provider_task) = start_provider_stub(vec![(
            403,
            PrivacyPassProviderResponse {
                authorization_headers: None,
                authorization_header: None,
                proxy_authorization_headers: None,
                proxy_authorization_header: None,
                expires_at_epoch_ms: None,
            },
        )])
        .await
        .expect("provider stub");
        let client = MasqueClient::new(privacy_pass_test_config(provider_url, None)).expect("client");

        let error = client
            .inner
            .fetch_privacy_pass_header("forbidden.example:443", "PrivateToken challenge=AAAA, token-key=BBBB")
            .await
            .expect_err("403 must fail");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);

        provider_task.await.expect("provider task").expect("provider result");
        assert_eq!(requests.lock().await.len(), 1);
    }
}
