#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::pin::Pin;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::task::{ready, Context, Poll};
use std::time::{Duration, Instant};

use blake2::digest::consts::U32;
use blake2::digest::Digest;
use blake2::Blake2b;
use bytes::{BufMut, Bytes, BytesMut};
use http::Request;
use quinn::{AsyncUdpSocket, UdpPoller};
use rand::{Rng, RngExt};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, RootCertStore, SignatureScheme};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use thiserror::Error;
use tokio::io::{sink, AsyncRead, AsyncReadExt, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, Mutex};

type Blake2b256 = Blake2b<U32>;

const HYSTERIA_AUTH_STATUS: u16 = 233;
const CLEANUP_INTERVAL: Duration = Duration::from_secs(60);
const REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_REASSEMBLY_SLOTS: usize = 128;
const MIGRATION_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Debug, Clone)]
pub struct Config {
    pub auth: String,
    pub server_addr: String,
    pub server_name: String,
    pub insecure: bool,
    pub salamander_key: Option<String>,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
}

impl Config {
    pub fn from_url(url: &str) -> Result<Self> {
        let parsed = url::Url::parse(&url.replace("hysteria2://", "http://"))?;
        let host =
            parsed.host_str().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria host".to_string()))?;
        let port = parsed.port().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria port".to_string()))?;
        let query: HashMap<String, String> = parsed.query_pairs().into_owned().collect();

        Ok(Self {
            auth: parsed.username().to_string(),
            server_addr: format!("{host}:{port}"),
            server_name: query.get("sni").cloned().unwrap_or_else(|| host.to_string()),
            insecure: query.get("insecure").is_some_and(|value| value == "1"),
            salamander_key: query.get("obfs-password").cloned(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
        })
    }
}

#[derive(Debug, Error)]
pub enum HysteriaError {
    #[error("invalid address: {0}")]
    InvalidAddress(String),
    #[error("QUIC connect error: {0}")]
    QuicConnect(#[from] quinn::ConnectError),
    #[error("QUIC connection error: {0}")]
    QuicConnection(#[from] quinn::ConnectionError),
    #[error("QUIC write error: {0}")]
    QuicWrite(#[from] quinn::WriteError),
    #[error("QUIC datagram send error: {0}")]
    QuicDatagram(#[from] quinn::SendDatagramError),
    #[error("QUIC stream closed")]
    QuicClosed(#[from] quinn::ClosedStream),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("HTTP/3 connection error: {0}")]
    H3Connection(#[from] h3::error::ConnectionError),
    #[error("HTTP/3 stream error: {0}")]
    H3Stream(#[from] h3::error::StreamError),
    #[error("URL parse error: {0}")]
    UrlParse(#[from] url::ParseError),
    #[error("authentication failed")]
    AuthFailed,
    #[error("UDP relay is not available on this server")]
    UdpNotSupported,
    #[error("TCP connect failed: {0}")]
    TcpConnect(String),
    #[error("invalid UDP datagram: {0}")]
    InvalidDatagram(String),
}

pub type Result<T> = std::result::Result<T, HysteriaError>;

pub async fn connect(config: &Config) -> Result<HysteriaClient> {
    let server_addr = config
        .server_addr
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| HysteriaError::InvalidAddress(config.server_addr.clone()))?;

    let tls_config = build_tls_config(config)?;
    let socket_spec = ClientSocketSpec {
        ipv6: server_addr.is_ipv6(),
        bind_low_port: config.quic_bind_low_port,
        salamander_key: config.salamander_key.clone(),
    };
    let (endpoint, current_socket) = build_endpoint(config, tls_config, socket_spec.clone())?;
    let connection = endpoint.connect(server_addr, &config.server_name)?.await?;

    let udp_supported = authenticate_connection(config, &connection).await?;
    let max_datagram_size = connection.max_datagram_size();

    let inner = Arc::new(ClientInner {
        _endpoint: endpoint,
        connection,
        next_session_id: AtomicU32::new(1),
        registrations: Mutex::new(HashMap::new()),
        udp_supported,
        max_datagram_size,
        socket_spec,
        migrate_after_handshake: config.quic_migrate_after_handshake,
        current_socket: Mutex::new(current_socket),
        migration: Mutex::new(QuicMigrationState {
            status: Some("not_attempted".to_string()),
            reason: None,
            validated: false,
            cooldown_until: None,
            previous_socket: None,
        }),
    });

    if udp_supported {
        tokio::spawn(dispatch_udp_datagrams(Arc::clone(&inner)));
    }

    Ok(HysteriaClient { inner })
}

#[derive(Clone)]
pub struct HysteriaClient {
    inner: Arc<ClientInner>,
}

impl HysteriaClient {
    pub async fn tcp_connect(&self, address: &str) -> Result<DuplexStream> {
        let migrated = self.inner.begin_quic_migration().await?;
        match self.open_tcp_stream(address).await {
            Ok(stream) => {
                if migrated {
                    self.inner.complete_quic_migration("path_validated_after_stream_open").await;
                }
                Ok(stream)
            }
            Err(_error) if migrated => {
                let _ = self.inner.rollback_quic_migration("stream_open_failed_after_rebind").await;
                self.open_tcp_stream(address).await
            }
            Err(error) => Err(error),
        }
    }

    pub fn udp_supported(&self) -> bool {
        self.inner.udp_supported
    }

    pub async fn udp_session(&self) -> Result<UdpSession> {
        if !self.inner.udp_supported || self.inner.max_datagram_size.is_none() {
            return Err(HysteriaError::UdpNotSupported);
        }

        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Ok(UdpSession {
            client: Arc::clone(&self.inner),
            incoming_rx,
            incoming_tx,
            session_ids: Mutex::new(HashMap::new()),
            packet_ids: Mutex::new(HashMap::new()),
        })
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.inner.quic_migration_snapshot()
    }

    async fn open_tcp_stream(&self, address: &str) -> Result<DuplexStream> {
        let (mut send, mut recv) = self.inner.connection.open_bi().await?;
        send.write_all(&build_tcp_request(address, 0)).await?;

        let (status_ok, message) = read_tcp_response(&mut recv).await?;
        if !status_ok {
            return Err(HysteriaError::TcpConnect(message));
        }

        Ok(DuplexStream { send, recv })
    }
}

struct ClientInner {
    _endpoint: quinn::Endpoint,
    connection: quinn::Connection,
    next_session_id: AtomicU32,
    registrations: Mutex<HashMap<u32, mpsc::Sender<UdpPacket>>>,
    udp_supported: bool,
    max_datagram_size: Option<usize>,
    socket_spec: ClientSocketSpec,
    migrate_after_handshake: bool,
    current_socket: Mutex<std::net::UdpSocket>,
    migration: Mutex<QuicMigrationState>,
}

#[derive(Debug, Clone)]
struct ClientSocketSpec {
    ipv6: bool,
    bind_low_port: bool,
    salamander_key: Option<String>,
}

#[derive(Default)]
struct QuicMigrationState {
    status: Option<String>,
    reason: Option<String>,
    validated: bool,
    cooldown_until: Option<Instant>,
    previous_socket: Option<std::net::UdpSocket>,
}

impl ClientInner {
    async fn begin_quic_migration(&self) -> Result<bool> {
        let should_attempt = {
            let state = self.migration.lock().await;
            self.should_attempt_quic_migration(&state)
        };
        if !should_attempt {
            return Ok(false);
        }

        let old_socket = self.current_socket.lock().await.try_clone()?;
        let new_socket = build_client_udp_socket(&self.socket_spec)?;
        let new_socket_clone = new_socket.try_clone()?;
        match rebind_endpoint(&self._endpoint, &self.socket_spec, new_socket) {
            Ok(()) => {
                *self.current_socket.lock().await = new_socket_clone;
                let mut state = self.migration.lock().await;
                state.status = Some("not_attempted".to_string());
                state.reason = Some("path_challenge_pending".to_string());
                state.previous_socket = Some(old_socket);
                Ok(true)
            }
            Err(error) => {
                let mut state = self.migration.lock().await;
                state.status = Some("failed".to_string());
                state.reason = Some("endpoint_rebind_failed".to_string());
                state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
                Err(error.into())
            }
        }
    }

    async fn complete_quic_migration(&self, reason: &str) {
        let mut state = self.migration.lock().await;
        if state.previous_socket.is_some() || state.validated {
            state.status = Some("validated".to_string());
            state.reason = Some(reason.to_string());
            state.validated = true;
            state.previous_socket = None;
        }
    }

    async fn rollback_quic_migration(&self, reason: &str) -> Result<()> {
        let previous_socket = {
            let mut state = self.migration.lock().await;
            let Some(previous_socket) = state.previous_socket.take() else {
                return Ok(());
            };
            previous_socket
        };
        let replacement = previous_socket.try_clone()?;
        rebind_endpoint(&self._endpoint, &self.socket_spec, previous_socket)?;
        *self.current_socket.lock().await = replacement;
        let mut state = self.migration.lock().await;
        state.status = Some("reverted".to_string());
        state.reason = Some(reason.to_string());
        state.validated = false;
        state.cooldown_until = Some(Instant::now() + MIGRATION_COOLDOWN);
        Ok(())
    }

    fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.try_lock().map_or_else(
            |_| (Some("not_attempted".to_string()), None),
            |state| (state.status.clone(), state.reason.clone()),
        )
    }

    fn should_attempt_quic_migration(&self, state: &QuicMigrationState) -> bool {
        if !self.migrate_after_handshake {
            return false;
        }
        if state.validated || state.previous_socket.is_some() {
            return false;
        }
        state.cooldown_until.is_none_or(|cooldown_until| cooldown_until <= Instant::now())
    }
}

pub struct DuplexStream {
    send: quinn::SendStream,
    recv: quinn::RecvStream,
}

impl AsyncRead for DuplexStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.recv).poll_read(cx, buf)
    }
}

impl AsyncWrite for DuplexStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.send).poll_write(cx, buf).map_err(io::Error::other)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_shutdown(cx)
    }
}

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    session_ids: Mutex<HashMap<String, u32>>,
    packet_ids: Mutex<HashMap<u32, u16>>,
}

impl UdpSession {
    pub async fn send_to(&self, address: &str, payload: &[u8]) -> Result<()> {
        let session_id = self.session_id_for(address).await;
        let packet_id = self.next_packet_id(session_id).await;
        let migrated = self.client.begin_quic_migration().await?;
        match send_udp_payload(&self.client, session_id, packet_id, address, payload).await {
            Ok(()) => {
                if migrated {
                    self.client.complete_quic_migration("path_validated_after_datagram_send").await;
                }
                Ok(())
            }
            Err(_error) if migrated => {
                let _ = self.client.rollback_quic_migration("datagram_send_failed_after_rebind").await;
                send_udp_payload(&self.client, session_id, packet_id, address, payload).await
            }
            Err(error) => Err(error),
        }
    }

    pub async fn recv_from(&mut self) -> Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| HysteriaError::Io(io::Error::new(io::ErrorKind::BrokenPipe, "Hysteria UDP session ended")))
            .map(|packet| (packet.address, packet.payload))
    }

    async fn session_id_for(&self, address: &str) -> u32 {
        let mut session_ids = self.session_ids.lock().await;
        if let Some(session_id) = session_ids.get(address).copied() {
            return session_id;
        }

        let session_id = self.client.next_session_id.fetch_add(1, Ordering::SeqCst);
        session_ids.insert(address.to_string(), session_id);
        self.client.registrations.lock().await.insert(session_id, self.incoming_tx.clone());
        session_id
    }

    async fn next_packet_id(&self, session_id: u32) -> u16 {
        let mut packet_ids = self.packet_ids.lock().await;
        let next = packet_ids.entry(session_id).or_insert(0);
        let packet_id = *next;
        *next = next.wrapping_add(1);
        packet_id
    }

    pub fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.client.quic_migration_snapshot()
    }
}

#[derive(Debug)]
struct UdpPacket {
    address: String,
    payload: Vec<u8>,
}

#[derive(Debug)]
struct PartialPacket {
    started_at: Instant,
    address: String,
    fragments: Vec<Option<Vec<u8>>>,
    received: usize,
}

async fn dispatch_udp_datagrams(client: Arc<ClientInner>) {
    let mut partials: HashMap<(u32, u16), PartialPacket> = HashMap::new();
    let mut last_cleanup = Instant::now();

    loop {
        let datagram = match client.connection.read_datagram().await {
            Ok(datagram) => datagram,
            Err(error) => {
                tracing::debug!(error = %error, "Hysteria UDP dispatcher stopped");
                break;
            }
        };

        if last_cleanup.elapsed() >= CLEANUP_INTERVAL {
            partials.retain(|_, partial| partial.started_at.elapsed() < REASSEMBLY_TIMEOUT);
            while partials.len() > MAX_REASSEMBLY_SLOTS {
                if let Some(oldest_key) =
                    partials.iter().min_by_key(|(_, partial)| partial.started_at).map(|(key, _)| *key)
                {
                    partials.remove(&oldest_key);
                }
            }
            last_cleanup = Instant::now();
        }

        match parse_udp_datagram(&datagram) {
            Ok(ParsedDatagram { session_id, packet_id, fragment_id, fragment_count, address, payload }) => {
                let sender = {
                    let registrations = client.registrations.lock().await;
                    registrations.get(&session_id).cloned()
                };
                let Some(sender) = sender else {
                    continue;
                };

                if fragment_count <= 1 {
                    let _ = sender.send(UdpPacket { address, payload }).await;
                    continue;
                }

                let key = (session_id, packet_id);
                let partial = partials.entry(key).or_insert_with(|| PartialPacket {
                    started_at: Instant::now(),
                    address: address.clone(),
                    fragments: vec![None; usize::from(fragment_count)],
                    received: 0,
                });
                let index = usize::from(fragment_id);
                if index >= partial.fragments.len() {
                    continue;
                }
                if partial.fragments[index].is_none() {
                    partial.fragments[index] = Some(payload);
                    partial.received += 1;
                    partial.address = address;
                }

                if partial.received == partial.fragments.len() {
                    let mut assembled = Vec::new();
                    for fragment in partial.fragments.iter().flatten() {
                        assembled.extend_from_slice(fragment);
                    }
                    let packet = UdpPacket { address: partial.address.clone(), payload: assembled };
                    partials.remove(&key);
                    let _ = sender.send(packet).await;
                }
            }
            Err(error) => {
                tracing::debug!(error = %error, "Ignoring malformed Hysteria UDP datagram");
            }
        }
    }

    client.registrations.lock().await.clear();
}

async fn send_udp_payload(
    client: &ClientInner,
    session_id: u32,
    packet_id: u16,
    address: &str,
    payload: &[u8],
) -> Result<()> {
    let max_datagram_size = client.max_datagram_size.ok_or(HysteriaError::UdpNotSupported)?;
    let address_len = encode_varint(address.len() as u64);
    let header_len = 4 + 2 + 1 + 1 + address_len.len() + address.len();
    if header_len >= max_datagram_size {
        return Err(HysteriaError::InvalidDatagram("Hysteria header exceeds max QUIC datagram size".to_string()));
    }

    if header_len + payload.len() <= max_datagram_size {
        let datagram = build_udp_datagram(session_id, packet_id, 0, 1, address, payload);
        client.connection.send_datagram(datagram.into())?;
        return Ok(());
    }

    let max_payload = max_datagram_size - header_len;
    let fragment_count = payload.len().div_ceil(max_payload);
    if fragment_count > usize::from(u8::MAX) {
        return Err(HysteriaError::InvalidDatagram("payload requires too many Hysteria UDP fragments".to_string()));
    }

    for (fragment_id, chunk) in payload.chunks(max_payload).enumerate() {
        let datagram =
            build_udp_datagram(session_id, packet_id, fragment_id as u8, fragment_count as u8, address, chunk);
        client.connection.send_datagram(datagram.into())?;
    }

    Ok(())
}

fn build_udp_datagram(
    session_id: u32,
    packet_id: u16,
    fragment_id: u8,
    fragment_count: u8,
    address: &str,
    payload: &[u8],
) -> Vec<u8> {
    let mut datagram = BytesMut::with_capacity(8 + address.len() + payload.len() + 8);
    datagram.extend_from_slice(&session_id.to_be_bytes());
    datagram.extend_from_slice(&packet_id.to_be_bytes());
    datagram.put_u8(fragment_id);
    datagram.put_u8(fragment_count);
    datagram.extend_from_slice(&encode_varint(address.len() as u64));
    datagram.extend_from_slice(address.as_bytes());
    datagram.extend_from_slice(payload);
    datagram.to_vec()
}

struct ParsedDatagram {
    session_id: u32,
    packet_id: u16,
    fragment_id: u8,
    fragment_count: u8,
    address: String,
    payload: Vec<u8>,
}

fn parse_udp_datagram(datagram: &[u8]) -> Result<ParsedDatagram> {
    if datagram.len() < 8 {
        return Err(HysteriaError::InvalidDatagram("Hysteria UDP datagram is too short".to_string()));
    }

    let session_id = u32::from_be_bytes(datagram[0..4].try_into().expect("slice length"));
    let packet_id = u16::from_be_bytes(datagram[4..6].try_into().expect("slice length"));
    let fragment_id = datagram[6];
    let fragment_count = datagram[7];

    let (address_len, next_index) = decode_varint(&datagram[8..])?;
    let address_end = 8 + next_index + address_len as usize;
    if datagram.len() < address_end {
        return Err(HysteriaError::InvalidDatagram("Hysteria UDP address length exceeds datagram".to_string()));
    }

    let address = String::from_utf8(datagram[8 + next_index..address_end].to_vec())
        .map_err(|_| HysteriaError::InvalidDatagram("Hysteria UDP address is not valid UTF-8".to_string()))?;
    let payload = datagram[address_end..].to_vec();

    Ok(ParsedDatagram { session_id, packet_id, fragment_id, fragment_count: fragment_count.max(1), address, payload })
}

async fn authenticate_connection(config: &Config, connection: &quinn::Connection) -> Result<bool> {
    let (mut h3_connection, mut send_request) = h3::client::new(h3_quinn::Connection::new(connection.clone())).await?;
    let padding = generate_padding();
    let request = Request::builder()
        .method("POST")
        .uri("https://hysteria/auth")
        .header("Host", "hysteria")
        .header("Hysteria-Auth", &config.auth)
        .header("Hysteria-CC-RX", "0")
        .header("Hysteria-Padding", padding)
        .body(())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;

    let mut stream = send_request.send_request(request).await?;
    stream.finish().await?;
    let response = stream.recv_response().await?;
    tokio::spawn(async move {
        let _ = std::future::poll_fn(|cx| h3_connection.poll_close(cx)).await;
    });

    if response.status().as_u16() != HYSTERIA_AUTH_STATUS {
        return Err(HysteriaError::AuthFailed);
    }

    Ok(response.headers().get("Hysteria-UDP").and_then(|value| value.to_str().ok()) == Some("true"))
}

fn build_endpoint(
    config: &Config,
    tls_config: rustls::ClientConfig,
    socket_spec: ClientSocketSpec,
) -> Result<(quinn::Endpoint, std::net::UdpSocket)> {
    let socket = build_client_udp_socket(&socket_spec)?;
    let socket_clone = socket.try_clone()?;
    let mut endpoint = if let Some(key) = config.salamander_key.as_ref() {
        let wrapped = SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?;
        quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            Arc::new(wrapped),
            Arc::new(quinn::TokioRuntime),
        )?
    } else {
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?
    };

    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?,
    )));
    Ok((endpoint, socket_clone))
}

fn build_client_udp_socket(socket_spec: &ClientSocketSpec) -> io::Result<std::net::UdpSocket> {
    let bind_addr = if socket_spec.ipv6 {
        SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0))
    } else {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if socket_spec.ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if socket_spec.bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    Ok(socket.into())
}

fn try_bind_low_port(socket: &Socket, bind_ip: IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

fn rebind_endpoint(
    endpoint: &quinn::Endpoint,
    socket_spec: &ClientSocketSpec,
    socket: std::net::UdpSocket,
) -> io::Result<()> {
    if let Some(key) = socket_spec.salamander_key.as_ref() {
        endpoint.rebind_abstract(Arc::new(SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?))
    } else {
        endpoint.rebind(socket)
    }
}

fn build_tls_config(config: &Config) -> Result<rustls::ClientConfig> {
    let builder = rustls::ClientConfig::builder();
    let builder = if config.insecure {
        builder.dangerous().with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
    } else {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        builder.with_root_certificates(roots)
    };

    let mut tls_config = builder.with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    Ok(tls_config)
}

fn generate_padding() -> String {
    const PADDING_CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::rng();
    let padding_len = rng.random_range(8..40);
    let mut padding = String::with_capacity(padding_len);
    for _ in 0..padding_len {
        let index = rng.random_range(0..PADDING_CHARS.len());
        padding.push(PADDING_CHARS[index] as char);
    }
    padding
}

fn build_tcp_request(address: &str, padding_len: usize) -> Bytes {
    let mut buffer = BytesMut::with_capacity(address.len() + padding_len + 32);
    put_varint(0x401, &mut buffer);
    put_varint(address.len() as u64, &mut buffer);
    buffer.extend_from_slice(address.as_bytes());
    put_varint(padding_len as u64, &mut buffer);
    if padding_len > 0 {
        let mut padding = vec![0u8; padding_len];
        rand::rng().fill_bytes(&mut padding);
        buffer.extend_from_slice(&padding);
    }
    buffer.freeze()
}

async fn read_tcp_response<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<(bool, String)> {
    let status = reader.read_u8().await? == 0;
    let message_len = read_varint(reader).await? as usize;
    let mut message = vec![0u8; message_len];
    reader.read_exact(&mut message).await?;
    let padding_len = read_varint(reader).await?;
    if padding_len > 0 {
        tokio::io::copy(&mut reader.take(padding_len), &mut sink()).await?;
    }

    Ok((status, String::from_utf8_lossy(&message).to_string()))
}

async fn read_varint<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<u64> {
    let first = reader.read_u8().await?;
    let tag = first >> 6;
    let value = match tag {
        0 => u64::from(first & 0x3F),
        1 => {
            let second = reader.read_u8().await?;
            u64::from(u16::from_be_bytes([first, second]) & 0x3FFF)
        }
        2 => {
            let mut bytes = [0u8; 4];
            bytes[0] = first;
            reader.read_exact(&mut bytes[1..]).await?;
            u64::from(u32::from_be_bytes(bytes) & 0x3FFF_FFFF)
        }
        _ => {
            let mut bytes = [0u8; 8];
            bytes[0] = first;
            reader.read_exact(&mut bytes[1..]).await?;
            u64::from_be_bytes(bytes) & 0x3FFF_FFFF_FFFF_FFFF
        }
    };
    Ok(value)
}

fn encode_varint(value: u64) -> Vec<u8> {
    let mut buffer = BytesMut::with_capacity(8);
    put_varint(value, &mut buffer);
    buffer.to_vec()
}

fn decode_varint(input: &[u8]) -> Result<(u64, usize)> {
    let Some(first) = input.first().copied() else {
        return Err(HysteriaError::InvalidDatagram("missing Hysteria varint".to_string()));
    };
    let tag = first >> 6;
    let (len, mask): (usize, u64) = match tag {
        0 => (1, 0x3F),
        1 => (2, 0x3FFF),
        2 => (4, 0x3FFF_FFFF),
        _ => (8, 0x3FFF_FFFF_FFFF_FFFF),
    };
    if input.len() < len {
        return Err(HysteriaError::InvalidDatagram("truncated Hysteria varint".to_string()));
    }

    let value = match len {
        1 => u64::from(first & mask as u8),
        2 => u64::from(u16::from_be_bytes([input[0], input[1]]) & mask as u16),
        4 => u64::from(u32::from_be_bytes(input[0..4].try_into().expect("slice length")) & mask as u32),
        _ => u64::from_be_bytes(input[0..8].try_into().expect("slice length")) & mask,
    };
    Ok((value, len))
}

fn put_varint(value: u64, buffer: &mut BytesMut) {
    match value {
        0..=63 => buffer.put_u8(value as u8),
        64..=16_383 => buffer.put_u16(((value & 0x3FFF) | 0x4000) as u16),
        16_384..=1_073_741_823 => buffer.put_u32(((value & 0x3FFF_FFFF) | 0x8000_0000) as u32),
        _ => buffer.put_u64((value & 0x3FFF_FFFF_FFFF_FFFF) | 0xC000_0000_0000_0000),
    }
}

#[derive(Debug)]
struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

#[derive(Debug)]
struct SalamanderUdpSocket {
    io: tokio::net::UdpSocket,
    codec: SalamanderCodec,
}

impl SalamanderUdpSocket {
    fn new(socket: std::net::UdpSocket, key: Vec<u8>) -> io::Result<Self> {
        Ok(Self { io: tokio::net::UdpSocket::from_std(socket)?, codec: SalamanderCodec::new(key) })
    }
}

impl AsyncUdpSocket for SalamanderUdpSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(TokioUdpPoller { socket: self })
    }

    fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> io::Result<()> {
        self.io.try_io(tokio::io::Interest::WRITABLE, || {
            let segments = transmit.segment_size.unwrap_or(transmit.contents.len());
            for chunk in transmit.contents.chunks(segments) {
                let encoded = self.codec.encode(chunk);
                let sent = self.io.try_send_to(&encoded, transmit.destination)?;
                if sent != encoded.len() {
                    return Err(io::Error::new(io::ErrorKind::WriteZero, "short Salamander UDP send"));
                }
            }
            Ok(())
        })
    }

    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [std::io::IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        loop {
            ready!(self.io.poll_recv_ready(cx))?;
            let buffer_len = bufs.first().map_or(0, |buffer| buffer.len());
            let mut scratch = vec![0u8; buffer_len.saturating_mul(2).max(2048)];
            match self.io.try_io(tokio::io::Interest::READABLE, || self.io.try_recv_from(&mut scratch)) {
                Ok((received, addr)) => {
                    let decoded = self.codec.decode(&scratch[..received])?;
                    let first = bufs
                        .first_mut()
                        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing QUIC receive buffer"))?;
                    if decoded.len() > first.len() {
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "Salamander datagram exceeds QUIC receive buffer",
                        )));
                    }
                    first[..decoded.len()].copy_from_slice(&decoded);
                    meta[0] = quinn::udp::RecvMeta {
                        addr,
                        len: decoded.len(),
                        stride: decoded.len(),
                        ecn: None,
                        dst_ip: None,
                    };
                    return Poll::Ready(Ok(1));
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }

    fn may_fragment(&self) -> bool {
        true
    }
}

#[derive(Debug)]
struct TokioUdpPoller {
    socket: Arc<SalamanderUdpSocket>,
}

impl UdpPoller for TokioUdpPoller {
    fn poll_writable(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.socket.io.poll_send_ready(cx)
    }
}

#[derive(Debug)]
struct SalamanderCodec {
    key: Vec<u8>,
}

impl SalamanderCodec {
    fn new(key: Vec<u8>) -> Self {
        Self { key }
    }

    fn encode(&self, payload: &[u8]) -> Vec<u8> {
        let mut salt = [0u8; 8];
        rand::rng().fill_bytes(&mut salt);
        let keystream = self.keystream(&salt);

        let mut out = Vec::with_capacity(8 + payload.len());
        out.extend_from_slice(&salt);
        for (index, byte) in payload.iter().enumerate() {
            out.push(byte ^ keystream[index % keystream.len()]);
        }
        out
    }

    fn decode(&self, payload: &[u8]) -> io::Result<Vec<u8>> {
        if payload.len() < 8 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Salamander datagram is shorter than the required salt prefix",
            ));
        }

        let salt = &payload[..8];
        let body = &payload[8..];
        let keystream = self.keystream(salt);
        Ok(body.iter().enumerate().map(|(index, byte)| byte ^ keystream[index % keystream.len()]).collect())
    }

    fn keystream(&self, salt: &[u8]) -> Vec<u8> {
        let mut hasher = Blake2b256::new();
        hasher.update(&self.key);
        hasher.update(salt);
        hasher.finalize().to_vec()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn salamander_roundtrip() {
        let codec = SalamanderCodec::new(b"top-secret".to_vec());
        let payload = b"hello, salamander";
        let encoded = codec.encode(payload);
        let decoded = codec.decode(&encoded).expect("decode");
        assert_eq!(decoded, payload);
    }

    #[test]
    fn hysteria_udp_datagram_roundtrip() {
        let payload = b"udp payload";
        let datagram = build_udp_datagram(7, 3, 0, 1, "example.com:53", payload);
        let parsed = parse_udp_datagram(&datagram).expect("parse");
        assert_eq!(parsed.session_id, 7);
        assert_eq!(parsed.packet_id, 3);
        assert_eq!(parsed.fragment_id, 0);
        assert_eq!(parsed.fragment_count, 1);
        assert_eq!(parsed.address, "example.com:53");
        assert_eq!(parsed.payload, payload);
    }

    #[test]
    fn hysteria_varint_roundtrip() {
        for value in [0, 1, 63, 64, 512, 16_383, 16_384, 1_000_000, u32::MAX as u64] {
            let encoded = encode_varint(value);
            let (decoded, used) = decode_varint(&encoded).expect("decode");
            assert_eq!(decoded, value);
            assert_eq!(used, encoded.len());
        }
    }
}
