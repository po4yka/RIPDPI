mod socks5;

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::time::timeout;

use crate::socks5::{decode_udp_frame, encode_udp_frame, read_target, write_reply, RelayTargetAddr};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const UDP_BUFFER_SIZE: usize = 65_536;

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayRuntimeConfig {
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub chain_entry_server: String,
    pub chain_entry_port: i32,
    pub chain_entry_server_name: String,
    pub chain_entry_public_key: String,
    pub chain_entry_short_id: String,
    pub chain_entry_profile_id: String,
    pub chain_exit_server: String,
    pub chain_exit_port: i32,
    pub chain_exit_server_name: String,
    pub chain_exit_public_key: String,
    pub chain_exit_short_id: String,
    pub chain_exit_profile_id: String,
    pub masque_url: String,
    pub masque_use_http2_fallback: bool,
    pub masque_cloudflare_mode: bool,
    pub tuic_zero_rtt: bool,
    pub tuic_congestion_control: String,
    pub shadow_tls_inner_profile_id: String,
    pub shadow_tls_inner: Option<ResolvedShadowTlsInnerRelayConfig>,
    pub naive_path: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub vless_uuid: Option<String>,
    pub chain_entry_uuid: Option<String>,
    pub chain_exit_uuid: Option<String>,
    pub hysteria_password: Option<String>,
    pub hysteria_salamander_key: Option<String>,
    pub tuic_uuid: Option<String>,
    pub tuic_password: Option<String>,
    pub shadow_tls_password: Option<String>,
    pub naive_username: Option<String>,
    pub naive_password: Option<String>,
    pub tls_fingerprint_profile: String,
    pub masque_auth_mode: Option<String>,
    pub masque_auth_token: Option<String>,
    pub masque_cloudflare_client_id: Option<String>,
    pub masque_cloudflare_key_id: Option<String>,
    pub masque_cloudflare_private_key_pem: Option<String>,
    pub masque_privacy_pass_provider_url: Option<String>,
    pub masque_privacy_pass_provider_auth_token: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedShadowTlsInnerRelayConfig {
    pub kind: String,
    pub profile_id: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_transport: String,
    pub vless_uuid: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub last_target: Option<String>,
    pub last_error: Option<String>,
    pub profile_id: Option<String>,
    pub protocol_kind: Option<String>,
    pub tcp_capable: Option<bool>,
    pub udp_capable: Option<bool>,
    pub fallback_mode: Option<String>,
    pub last_handshake_error: Option<String>,
    pub chain_entry_state: Option<String>,
    pub chain_exit_state: Option<String>,
    pub captured_at: u64,
}

trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}
type BoxedIo = Box<dyn AsyncIo>;

enum RelayUdpSession {
    Hysteria2(ripdpi_hysteria2::UdpSession),
    Tuic(ripdpi_tuic::UdpSession),
    Masque(ripdpi_masque::MasqueUdpRelay),
}

impl RelayUdpSession {
    async fn send_to(&mut self, target: &RelayTargetAddr, payload: &[u8]) -> io::Result<()> {
        match self {
            Self::Hysteria2(session) => {
                session.send_to(&target.to_connect_target(), payload).await.map_err(to_io_error)
            }
            Self::Tuic(session) => session.send_to(&target.to_connect_target(), payload).await,
            Self::Masque(session) => session.send_to(&target.to_connect_target(), payload).await,
        }
    }

    async fn recv_from(&mut self) -> io::Result<(RelayTargetAddr, Vec<u8>)> {
        match self {
            Self::Hysteria2(session) => {
                let (address, payload) = session.recv_from().await.map_err(to_io_error)?;
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
            Self::Tuic(session) => {
                let (address, payload) = session.recv_from().await?;
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
            Self::Masque(session) => {
                let (address, payload) = session.recv_from().await?;
                Ok((RelayTargetAddr::from_authority(&address)?, payload))
            }
        }
    }
}

enum RelayBackend {
    Hysteria2(Hysteria2Backend),
    Tuic(TuicBackend),
    VlessReality(VlessRealityBackend),
    Xhttp(XhttpBackend),
    ChainRelay(ChainRelayBackend),
    Masque(MasqueBackend),
    ShadowTls(ShadowTlsBackend),
    Unsupported { kind: String },
}

impl RelayBackend {
    fn udp_capable(&self) -> bool {
        matches!(self, Self::Hysteria2(_) | Self::Masque(_) | Self::Tuic(_))
    }

    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        match self {
            Self::Hysteria2(backend) => backend.connect_tcp(target).await,
            Self::Tuic(backend) => backend.connect_tcp(target).await,
            Self::VlessReality(backend) => backend.connect_tcp(target).await,
            Self::Xhttp(backend) => backend.connect_tcp(target).await,
            Self::ChainRelay(backend) => backend.connect_tcp(target).await,
            Self::Masque(backend) => backend.connect_tcp(target).await,
            Self::ShadowTls(backend) => backend.connect_tcp(target).await,
            Self::Unsupported { kind, .. } => {
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("relay backend {kind} is not implemented")))
            }
        }
    }

    async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        match self {
            Self::Hysteria2(backend) => backend.open_udp_session().await,
            Self::Tuic(backend) => backend.open_udp_session().await,
            Self::Masque(backend) => backend.open_udp_session().await,
            Self::VlessReality(_) | Self::Xhttp(_) | Self::ChainRelay(_) | Self::ShadowTls(_) => {
                Err(io::Error::new(io::ErrorKind::Unsupported, "relay backend does not support UDP ASSOCIATE"))
            }
            Self::Unsupported { kind, .. } => {
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("relay backend {kind} is not implemented")))
            }
        }
    }
}

struct Hysteria2Backend {
    client: ripdpi_hysteria2::HysteriaClient,
}

struct TuicBackend {
    client: ripdpi_tuic::TuicClient,
}

impl Hysteria2Backend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.client.tcp_connect(&target.to_connect_target()).await.map_err(to_io_error)?;
        Ok(Box::new(stream))
    }

    async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        self.client.udp_session().await.map(RelayUdpSession::Hysteria2).map_err(to_io_error)
    }
}

impl TuicBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.client.tcp_connect(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }

    async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        self.client.udp_session().await.map(RelayUdpSession::Tuic)
    }
}

struct VlessRealityBackend {
    config: ResolvedRelayRuntimeConfig,
    outbound_bind_ip: Option<IpAddr>,
}

impl VlessRealityBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let uuid = self
            .config
            .vless_uuid
            .as_ref()
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing VLESS UUID"))?;
        let config = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.server,
            self.config.server_port,
            uuid,
            &self.config.server_name,
            &self.config.reality_public_key,
            &self.config.reality_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
        let stream = match self.outbound_bind_ip {
            Some(bind_ip) => {
                ripdpi_vless::VlessRealityClient::connect_with_bind(&config, bind_ip, &target.to_connect_target())
                    .await?
            }
            None => ripdpi_vless::VlessRealityClient::connect(&config, &target.to_connect_target()).await?,
        };
        Ok(Box::new(stream))
    }
}

struct XhttpBackend {
    client: ripdpi_xhttp::XhttpClient,
}

impl XhttpBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.client.connect(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }
}

struct ChainRelayBackend {
    config: ResolvedRelayRuntimeConfig,
    outbound_bind_ip: Option<IpAddr>,
}

impl ChainRelayBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let entry_config = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.chain_entry_server,
            self.config.chain_entry_port,
            self.config.chain_entry_uuid.as_deref().unwrap_or_default(),
            &self.config.chain_entry_server_name,
            &self.config.chain_entry_public_key,
            &self.config.chain_entry_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain entry: {error}")))?;
        let exit_config = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.chain_exit_server,
            self.config.chain_exit_port,
            self.config.chain_exit_uuid.as_deref().unwrap_or_default(),
            &self.config.chain_exit_server_name,
            &self.config.chain_exit_public_key,
            &self.config.chain_exit_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("chain exit: {error}")))?;

        let exit_target = format!("{}:{}", exit_config.server, exit_config.port);
        let first_hop = match self.outbound_bind_ip {
            Some(bind_ip) => {
                ripdpi_vless::VlessRealityClient::connect_with_bind(&entry_config, bind_ip, &exit_target).await?
            }
            None => ripdpi_vless::VlessRealityClient::connect(&entry_config, &exit_target).await?,
        };
        let second_hop =
            ripdpi_vless::VlessRealityClient::connect_over(&exit_config, first_hop, &target.to_connect_target())
                .await?;
        Ok(Box::new(second_hop))
    }
}

struct MasqueBackend {
    client: ripdpi_masque::MasqueClient,
}

struct ShadowTlsBackend {
    client: ripdpi_shadowtls::ShadowTlsClient,
    outer_server: String,
    outer_server_port: i32,
    inner: ResolvedShadowTlsInnerRelayConfig,
}

impl MasqueBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.client.connect_tcp(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }

    async fn open_udp_session(&self) -> io::Result<RelayUdpSession> {
        Ok(RelayUdpSession::Masque(self.client.udp_session()))
    }
}

impl ShadowTlsBackend {
    async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let transport = self
            .client
            .connect(&self.outer_server, self.outer_server_port)
            .await
            .map_err(|error| io::Error::new(error.kind(), format!("shadowtls connect: {error}")))?;

        match self.inner.kind.as_str() {
            "vless_reality" => {
                if self.inner.vless_transport == "xhttp" {
                    return Err(io::Error::new(
                        io::ErrorKind::Unsupported,
                        "ShadowTLS inner VLESS xHTTP transport is not supported yet",
                    ));
                }
                let uuid =
                    self.inner.vless_uuid.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(|| {
                        io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner VLESS UUID")
                    })?;
                let config = ripdpi_vless::config::VlessRealityConfig::from_strings(
                    &self.inner.server,
                    self.inner.server_port,
                    uuid,
                    &self.inner.server_name,
                    &self.inner.reality_public_key,
                    &self.inner.reality_short_id,
                    "chrome_stable",
                )
                .map_err(|error| {
                    io::Error::new(io::ErrorKind::InvalidInput, format!("shadowtls inner vless: {error}"))
                })?;
                let stream =
                    ripdpi_vless::VlessRealityClient::connect_over(&config, transport, &target.to_connect_target())
                        .await?;
                Ok(Box::new(stream))
            }
            other => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("ShadowTLS inner relay kind {other} is not supported"),
            )),
        }
    }
}

pub struct RelayRuntime {
    config: ResolvedRelayRuntimeConfig,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_target: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    last_handshake_error: Mutex<Option<String>>,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            config,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            listener_address: Mutex::new(None),
            last_target: Mutex::new(None),
            last_error: Mutex::new(None),
            last_handshake_error: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        let capabilities = backend_capabilities(&self.config);
        let is_running = self.running.load(Ordering::SeqCst);
        let state = if is_running { "running" } else { "idle" };

        RelayTelemetry {
            source: "relay",
            state: state.to_string(),
            health: state.to_string(),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: self.last_target.lock().expect("last target").clone(),
            last_error: self.last_error.lock().expect("last error").clone(),
            profile_id: Some(self.config.profile_id.clone()),
            protocol_kind: Some(self.config.kind.clone()),
            tcp_capable: Some(capabilities.0),
            udp_capable: Some(capabilities.1),
            fallback_mode: capabilities.2,
            last_handshake_error: self.last_handshake_error.lock().expect("handshake error").clone(),
            chain_entry_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            chain_exit_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            captured_at: now_ms(),
        }
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        let backend = Arc::new(build_backend(&self.config).await?);
        validate_runtime_config(&self.config, &backend)?;

        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    let runtime = Arc::clone(&self);
                    let backend = Arc::clone(&backend);
                    tokio::spawn(async move {
                        runtime.active_sessions.fetch_add(1, Ordering::SeqCst);
                        runtime.total_sessions.fetch_add(1, Ordering::SeqCst);
                        if let Err(error) = runtime.handle_client(stream, backend).await {
                            *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        Ok(())
    }

    async fn handle_client(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        let mut greeting = [0u8; 2];
        client.read_exact(&mut greeting).await?;
        if greeting[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
        }

        let method_count = usize::from(greeting[1]);
        let mut methods = vec![0u8; method_count];
        client.read_exact(&mut methods).await?;
        client.write_all(&[0x05, 0x00]).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        if request_header[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
        }

        let command = request_header[1];
        let target = read_target(&mut client, request_header[3]).await?;
        *self.last_target.lock().expect("last target") = Some(target.to_string());

        match command {
            0x01 => self.handle_connect(client, backend, target).await,
            0x03 => self.handle_udp_associate(client, backend).await,
            _ => {
                write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
            }
        }
    }

    async fn handle_connect(
        &self,
        mut client: TcpStream,
        backend: Arc<RelayBackend>,
        target: RelayTargetAddr,
    ) -> io::Result<()> {
        let mut upstream = match backend.connect_tcp(&target).await {
            Ok(stream) => stream,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        let _ = copy_bidirectional(&mut client, &mut upstream).await?;
        Ok(())
    }

    async fn handle_udp_associate(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        if !backend.udp_capable() {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("relay backend {} does not support UDP ASSOCIATE", self.config.kind),
            ));
        }

        let mut udp_session = match backend.open_udp_session().await {
            Ok(session) => session,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        let udp_socket = UdpSocket::bind(format!("{}:0", self.config.local_socks_host)).await?;
        let bound = udp_socket.local_addr()?;
        write_reply(&mut client, 0x00, bound).await?;

        let control_ip = client.peer_addr()?.ip();
        let mut associated_client = None;
        let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
        let mut control_probe = [0u8; 1];
        let control_closed = async {
            let _ = client.read(&mut control_probe).await;
        };
        tokio::pin!(control_closed);

        loop {
            tokio::select! {
                _ = &mut control_closed => break,
                recv = udp_socket.recv_from(&mut udp_buffer) => {
                    let (received, source) = recv?;
                    if source.ip() != control_ip {
                        continue;
                    }
                    associated_client = Some(source);
                let (target, payload) = decode_udp_frame(&udp_buffer[..received])?;
                *self.last_target.lock().expect("last target") = Some(target.to_string());
                    if let Err(error) = udp_session.send_to(&target, payload).await {
                        *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                        return Err(error);
                    }
                }
                result = udp_session.recv_from() => {
                    let (target, payload) = result?;
                    let Some(destination) = associated_client else {
                        continue;
                    };
                    let frame = encode_udp_frame(&target, &payload)?;
                    udp_socket.send_to(&frame, destination).await?;
                }
            }
        }

        Ok(())
    }
}

async fn build_backend(config: &ResolvedRelayRuntimeConfig) -> io::Result<RelayBackend> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.outbound_bind_ip)?;
    match config.kind.as_str() {
        "hysteria2" => {
            let password = config
                .hysteria_password
                .as_ref()
                .filter(|value| !value.trim().is_empty())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing Hysteria2 password"))?;
            let mut client_config = ripdpi_hysteria2::Config::from_url(&format!(
                "hysteria2://{password}@{}:{}/?sni={}",
                config.server, config.server_port, config.server_name,
            ))
            .map_err(to_io_error)?;
            client_config.salamander_key =
                config.hysteria_salamander_key.as_ref().filter(|value| !value.trim().is_empty()).cloned();
            let client = ripdpi_hysteria2::connect(&client_config).await.map_err(to_io_error)?;
            Ok(RelayBackend::Hysteria2(Hysteria2Backend { client }))
        }
        "tuic_v5" => {
            let client = ripdpi_tuic::TuicClient::connect(ripdpi_tuic::Config {
                server: config.server.clone(),
                server_port: config.server_port,
                server_name: config.server_name.clone(),
                uuid: config.tuic_uuid.clone().unwrap_or_default(),
                password: config.tuic_password.clone().unwrap_or_default(),
                zero_rtt: config.tuic_zero_rtt,
                congestion_control: config.tuic_congestion_control.clone(),
                udp_enabled: config.udp_enabled,
            })
            .await?;
            Ok(RelayBackend::Tuic(TuicBackend { client }))
        }
        "vless_reality" if config.vless_transport == "xhttp" => {
            let vless = ripdpi_vless::config::VlessRealityConfig::from_strings(
                &config.server,
                config.server_port,
                config.vless_uuid.as_deref().unwrap_or_default(),
                &config.server_name,
                &config.reality_public_key,
                &config.reality_short_id,
                &config.tls_fingerprint_profile,
            )
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
            Ok(RelayBackend::Xhttp(XhttpBackend {
                client: ripdpi_xhttp::XhttpClient::new_reality(ripdpi_xhttp::XhttpRealityConfig {
                    vless,
                    path: config.xhttp_path.clone(),
                    host: if config.xhttp_host.trim().is_empty() {
                        None
                    } else {
                        Some(config.xhttp_host.trim().to_owned())
                    },
                    bind_ip: outbound_bind_ip,
                    xmux: ripdpi_xhttp::XmuxConfig::default(),
                }),
            }))
        }
        "vless_reality" => {
            Ok(RelayBackend::VlessReality(VlessRealityBackend { config: config.clone(), outbound_bind_ip }))
        }
        "cloudflare_tunnel" => Ok(RelayBackend::Xhttp(XhttpBackend {
            client: ripdpi_xhttp::XhttpClient::new_tls({
                let mut tls = ripdpi_xhttp::XhttpTlsConfig::from_strings(
                    &config.server,
                    config.server_port,
                    &config.server_name,
                    config.vless_uuid.as_deref().unwrap_or_default(),
                    &config.xhttp_path,
                    &config.xhttp_host,
                    &config.tls_fingerprint_profile,
                )
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
                tls.bind_ip = outbound_bind_ip;
                tls
            }),
        })),
        "chain_relay" => Ok(RelayBackend::ChainRelay(ChainRelayBackend { config: config.clone(), outbound_bind_ip })),
        "masque" => Ok(RelayBackend::Masque(MasqueBackend {
            client: ripdpi_masque::MasqueClient::new(ripdpi_masque::config::MasqueConfig {
                url: config.masque_url.clone(),
                use_http2_fallback: config.masque_use_http2_fallback,
                cloudflare_mode: config.masque_cloudflare_mode,
                auth_mode: config.masque_auth_mode.clone(),
                auth_token: config.masque_auth_token.clone(),
                cf_client_id: config.masque_cloudflare_client_id.clone(),
                cf_key_id: config.masque_cloudflare_key_id.clone(),
                cf_private_key_pem: config.masque_cloudflare_private_key_pem.clone(),
                privacy_pass_provider_url: config.masque_privacy_pass_provider_url.clone(),
                privacy_pass_provider_auth_token: config.masque_privacy_pass_provider_auth_token.clone(),
                tls_fingerprint_profile: config.tls_fingerprint_profile.clone(),
            })?,
        })),
        "shadowtls_v3" => Ok(RelayBackend::ShadowTls(ShadowTlsBackend {
            client: ripdpi_shadowtls::ShadowTlsClient::new(ripdpi_shadowtls::Config {
                password: config.shadow_tls_password.clone().unwrap_or_default(),
                server_name: config.server_name.clone(),
                inner_profile_id: config.shadow_tls_inner_profile_id.clone(),
            }),
            outer_server: config.server.clone(),
            outer_server_port: config.server_port,
            inner: config
                .shadow_tls_inner
                .clone()
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner relay config"))?,
        })),
        other => Ok(RelayBackend::Unsupported { kind: other.to_string() }),
    }
}

fn backend_capabilities(config: &ResolvedRelayRuntimeConfig) -> (bool, bool, Option<String>) {
    match config.kind.as_str() {
        "hysteria2" => (true, true, None),
        "masque" => (true, true, None),
        "tuic_v5" => (true, true, None),
        "shadowtls_v3" | "naiveproxy" | "vless_reality" | "cloudflare_tunnel" | "chain_relay" => (true, false, None),
        other => (false, false, Some(format!("unsupported:{other}"))),
    }
}

fn describe_upstream(config: &ResolvedRelayRuntimeConfig) -> String {
    match config.kind.as_str() {
        "chain_relay" => format!(
            "{}:{} -> {}:{}",
            config.chain_entry_server, config.chain_entry_port, config.chain_exit_server, config.chain_exit_port,
        ),
        "vless_reality" if config.vless_transport == "xhttp" => {
            format!("{}:{}{}", config.server, config.server_port, normalized_xhttp_path(config))
        }
        "cloudflare_tunnel" => format!("{}:{}{}", config.server, config.server_port, normalized_xhttp_path(config)),
        "masque" => config.masque_url.clone(),
        _ => format!("{}:{}", config.server, config.server_port),
    }
}

fn normalized_xhttp_path(config: &ResolvedRelayRuntimeConfig) -> String {
    let trimmed = config.xhttp_path.trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{}", trimmed)
    }
}

fn validate_runtime_config(config: &ResolvedRelayRuntimeConfig, backend: &RelayBackend) -> io::Result<()> {
    let outbound_bind_ip = parse_outbound_bind_ip(&config.outbound_bind_ip)?;
    if config.udp_enabled && !backend.udp_capable() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.kind),
        ));
    }

    if outbound_bind_ip.is_some() && matches!(config.kind.as_str(), "hysteria2" | "masque") {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support outbound bind IP", config.kind),
        ));
    }

    Ok(())
}

fn parse_outbound_bind_ip(value: &str) -> io::Result<Option<IpAddr>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    trimmed.parse::<IpAddr>().map(Some).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid relay outbound_bind_ip {trimmed}: {error}"))
    })
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_millis() as u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_config(kind: &str) -> ResolvedRelayRuntimeConfig {
        ResolvedRelayRuntimeConfig {
            enabled: true,
            kind: kind.to_string(),
            profile_id: "default".to_string(),
            outbound_bind_ip: String::new(),
            server: "relay.example".to_string(),
            server_port: 443,
            server_name: "relay.example".to_string(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 443,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_entry_profile_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: 443,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            chain_exit_profile_id: String::new(),
            masque_url: "https://masque.example/".to_string(),
            masque_use_http2_fallback: true,
            masque_cloudflare_mode: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: "bbr".to_string(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            naive_path: String::new(),
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 10_80,
            udp_enabled: false,
            tcp_fallback_enabled: true,
            vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_entry_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_exit_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            hysteria_password: Some("secret".to_string()),
            hysteria_salamander_key: None,
            tuic_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            tuic_password: Some("secret".to_string()),
            shadow_tls_password: Some("secret".to_string()),
            naive_username: Some("user".to_string()),
            naive_password: Some("secret".to_string()),
            tls_fingerprint_profile: "chrome_stable".to_string(),
            masque_auth_mode: Some("token".to_string()),
            masque_auth_token: Some("token".to_string()),
            masque_cloudflare_client_id: None,
            masque_cloudflare_key_id: None,
            masque_cloudflare_private_key_pem: None,
            masque_privacy_pass_provider_url: None,
            masque_privacy_pass_provider_auth_token: None,
        }
    }

    #[test]
    fn relay_runtime_allows_hysteria_udp_and_salamander() {
        let mut config = sample_config("hysteria2");
        config.udp_enabled = true;
        config.hysteria_salamander_key = Some("salamander".to_string());
        let capabilities = backend_capabilities(&config);
        assert!(capabilities.1, "Hysteria2 should report UDP capability");
    }

    #[test]
    fn relay_runtime_allows_tuic_udp_and_zero_rtt() {
        let mut config = sample_config("tuic_v5");
        config.udp_enabled = true;
        config.tuic_zero_rtt = true;

        let capabilities = backend_capabilities(&config);
        assert!(capabilities.0, "TUIC should report TCP capability");
        assert!(capabilities.1, "TUIC should report UDP capability");
        assert_eq!("relay.example:443", describe_upstream(&config));
    }

    #[test]
    fn relay_runtime_rejects_udp_without_backend_support() {
        let mut config = sample_config("vless_reality");
        config.udp_enabled = true;
        let backend = RelayBackend::VlessReality(VlessRealityBackend {
            config: sample_config("vless_reality"),
            outbound_bind_ip: None,
        });

        let error = validate_runtime_config(&config, &backend).expect_err("UDP must fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[test]
    fn relay_runtime_allows_masque_udp_and_privacy_pass_provider() {
        let mut config = sample_config("masque");
        config.udp_enabled = true;
        config.masque_cloudflare_mode = true;
        config.masque_auth_mode = Some("privacy_pass".to_string());
        config.masque_auth_token = None;
        config.masque_privacy_pass_provider_url = Some("https://provider.example/token".to_string());

        let capabilities = backend_capabilities(&config);
        assert!(capabilities.1, "MASQUE should report UDP capability");
        validate_runtime_config(
            &config,
            &RelayBackend::Masque(MasqueBackend {
                client: ripdpi_masque::MasqueClient::new(ripdpi_masque::config::MasqueConfig {
                    url: config.masque_url.clone(),
                    use_http2_fallback: config.masque_use_http2_fallback,
                    cloudflare_mode: config.masque_cloudflare_mode,
                    auth_mode: config.masque_auth_mode.clone(),
                    auth_token: config.masque_auth_token.clone(),
                    cf_client_id: config.masque_cloudflare_client_id.clone(),
                    cf_key_id: config.masque_cloudflare_key_id.clone(),
                    cf_private_key_pem: config.masque_cloudflare_private_key_pem.clone(),
                    privacy_pass_provider_url: config.masque_privacy_pass_provider_url.clone(),
                    privacy_pass_provider_auth_token: config.masque_privacy_pass_provider_auth_token.clone(),
                    tls_fingerprint_profile: config.tls_fingerprint_profile.clone(),
                })
                .expect("client"),
            }),
        )
        .expect("MASQUE privacy pass should validate");
    }

    #[test]
    fn relay_runtime_routes_vless_xhttp_through_tcp_only_backend() {
        let mut config = sample_config("vless_reality");
        config.vless_transport = "xhttp".to_string();
        config.xhttp_path = "/api/v1/stream".to_string();

        let capabilities = backend_capabilities(&config);
        assert_eq!((true, false), (capabilities.0, capabilities.1));
        assert_eq!("relay.example:443/api/v1/stream", describe_upstream(&config));
    }

    #[tokio::test]
    async fn relay_runtime_routes_cloudflare_tunnel_through_xhttp_backend() {
        let mut config = sample_config("cloudflare_tunnel");
        config.server = "edge.example.com".to_string();
        config.server_name = "edge.example.com".to_string();
        config.xhttp_path = "/cdn/api".to_string();

        let backend = build_backend(&config).await;
        assert!(backend.is_ok(), "cloudflare tunnel backend should resolve");
        assert_eq!("edge.example.com:443/cdn/api", describe_upstream(&config));
    }

    #[test]
    fn relay_runtime_rejects_invalid_outbound_bind_ip() {
        let mut config = sample_config("vless_reality");
        config.outbound_bind_ip = "not-an-ip".to_string();
        let backend = RelayBackend::VlessReality(VlessRealityBackend {
            config: sample_config("vless_reality"),
            outbound_bind_ip: None,
        });

        let error = validate_runtime_config(&config, &backend).expect_err("invalid bind ip must fail");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn relay_runtime_rejects_bind_ip_for_unsupported_backend() {
        let mut config = sample_config("hysteria2");
        config.outbound_bind_ip = "203.0.113.10".to_string();
        let backend = RelayBackend::Unsupported { kind: "hysteria2".to_string() };

        let error = validate_runtime_config(&config, &backend).expect_err("unsupported bind ip must fail");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[tokio::test]
    async fn relay_runtime_builds_shadowtls_backend_with_inner_vless_profile() {
        let mut config = sample_config("shadowtls_v3");
        config.shadow_tls_inner_profile_id = "inner-vless".to_string();
        config.shadow_tls_inner = Some(ResolvedShadowTlsInnerRelayConfig {
            kind: "vless_reality".to_string(),
            profile_id: "inner-vless".to_string(),
            server: "inner.example".to_string(),
            server_port: 443,
            server_name: "inner.example".to_string(),
            reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
            reality_short_id: String::new(),
            vless_transport: "reality_tcp".to_string(),
            vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        });

        let backend = build_backend(&config).await.expect("shadowtls backend");
        match backend {
            RelayBackend::ShadowTls(_) => {}
            other => panic!("expected ShadowTLS backend, got {:?}", std::mem::discriminant(&other)),
        }
    }
}
