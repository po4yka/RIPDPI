use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRelayRuntimeConfig {
    pub enabled: bool,
    pub kind: String,
    pub profile_id: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub chain_entry_server: String,
    pub chain_entry_port: i32,
    pub chain_entry_server_name: String,
    pub chain_entry_public_key: String,
    pub chain_entry_short_id: String,
    pub chain_exit_server: String,
    pub chain_exit_port: i32,
    pub chain_exit_server_name: String,
    pub chain_exit_public_key: String,
    pub chain_exit_short_id: String,
    pub masque_url: String,
    pub masque_use_http2_fallback: bool,
    pub masque_cloudflare_mode: bool,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub vless_uuid: Option<String>,
    pub chain_entry_uuid: Option<String>,
    pub chain_exit_uuid: Option<String>,
    pub hysteria_password: Option<String>,
    pub hysteria_salamander_key: Option<String>,
    pub tls_fingerprint_profile: String,
    pub masque_auth_mode: Option<String>,
    pub masque_auth_token: Option<String>,
    pub masque_cloudflare_client_id: Option<String>,
    pub masque_cloudflare_key_id: Option<String>,
    pub masque_cloudflare_private_key_pem: Option<String>,
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

enum RelayBackend {
    Hysteria2(Hysteria2Backend),
    VlessReality(VlessRealityBackend),
    ChainRelay(ChainRelayBackend),
    Masque(MasqueBackend),
    Unsupported { kind: String, fallback_mode: Option<String> },
}

impl RelayBackend {
    fn tcp_capable(&self) -> bool {
        matches!(self, Self::Hysteria2(_) | Self::VlessReality(_) | Self::ChainRelay(_) | Self::Masque(_))
    }

    fn udp_capable(&self) -> bool {
        false
    }

    fn fallback_mode(&self) -> Option<&str> {
        match self {
            Self::Unsupported { fallback_mode, .. } => fallback_mode.as_deref(),
            _ => None,
        }
    }

    async fn connect_tcp(&self, target: &str) -> io::Result<BoxedIo> {
        match self {
            Self::Hysteria2(backend) => backend.connect_tcp(target).await,
            Self::VlessReality(backend) => backend.connect_tcp(target).await,
            Self::ChainRelay(backend) => backend.connect_tcp(target).await,
            Self::Masque(backend) => backend.connect_tcp(target).await,
            Self::Unsupported { kind, .. } => {
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("relay backend {kind} is not implemented")))
            }
        }
    }
}

struct Hysteria2Backend {
    config: ResolvedRelayRuntimeConfig,
}

impl Hysteria2Backend {
    async fn connect_tcp(&self, target: &str) -> io::Result<BoxedIo> {
        if self.config.hysteria_salamander_key.as_deref().is_some_and(|value| !value.trim().is_empty()) {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "Hysteria2 Salamander obfuscation is not implemented",
            ));
        }
        let auth = self
            .config
            .hysteria_password
            .as_ref()
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing hysteria password"))?;
        let config = hysteria2::config::Config::from_url(&format!(
            "hysteria2://{auth}@{}:{}/?sni={}",
            self.config.server, self.config.server_port, self.config.server_name,
        ))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
        let client = hysteria2::connect(&config)
            .await
            .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, error.to_string()))?;
        let stream = client
            .tcp_connect(target)
            .await
            .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, error.to_string()))?;
        Ok(Box::new(stream))
    }
}

struct VlessRealityBackend {
    config: ResolvedRelayRuntimeConfig,
}

impl VlessRealityBackend {
    async fn connect_tcp(&self, target: &str) -> io::Result<BoxedIo> {
        let uuid_str = self
            .config
            .vless_uuid
            .as_ref()
            .filter(|s| !s.is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing vless uuid"))?;
        let cfg = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.server,
            self.config.server_port,
            uuid_str,
            &self.config.server_name,
            &self.config.reality_public_key,
            &self.config.reality_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e.to_string()))?;
        let stream = ripdpi_vless::VlessRealityClient::connect(&cfg, target).await?;
        Ok(Box::new(stream))
    }
}

struct ChainRelayBackend {
    config: ResolvedRelayRuntimeConfig,
}

impl ChainRelayBackend {
    async fn connect_tcp(&self, target: &str) -> io::Result<BoxedIo> {
        let entry_cfg = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.chain_entry_server,
            self.config.chain_entry_port,
            self.config.chain_entry_uuid.as_deref().unwrap_or(""),
            &self.config.chain_entry_server_name,
            &self.config.chain_entry_public_key,
            &self.config.chain_entry_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("chain entry config: {e}")))?;

        let exit_cfg = ripdpi_vless::config::VlessRealityConfig::from_strings(
            &self.config.chain_exit_server,
            self.config.chain_exit_port,
            self.config.chain_exit_uuid.as_deref().unwrap_or(""),
            &self.config.chain_exit_server_name,
            &self.config.chain_exit_public_key,
            &self.config.chain_exit_short_id,
            &self.config.tls_fingerprint_profile,
        )
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("chain exit config: {e}")))?;

        // First hop: connect to entry VPS, targeting exit VPS address
        let exit_addr = format!("{}:{}", exit_cfg.server, exit_cfg.port);
        let first_hop = ripdpi_vless::VlessRealityClient::connect(&entry_cfg, &exit_addr).await?;

        // Second hop: VLESS+Reality over first hop, targeting the real destination
        let second_hop = ripdpi_vless::VlessRealityClient::connect_over(&exit_cfg, first_hop, target).await?;

        Ok(Box::new(second_hop))
    }
}

struct MasqueBackend {
    config: ResolvedRelayRuntimeConfig,
}

impl MasqueBackend {
    async fn connect_tcp(&self, target: &str) -> io::Result<BoxedIo> {
        let cfg = ripdpi_masque::config::MasqueConfig {
            url: self.config.masque_url.clone(),
            use_http2_fallback: self.config.masque_use_http2_fallback,
            cloudflare_mode: self.config.masque_cloudflare_mode,
            auth_mode: self.config.masque_auth_mode.clone(),
            auth_token: self.config.masque_auth_token.clone(),
            cf_client_id: self.config.masque_cloudflare_client_id.clone(),
            cf_key_id: self.config.masque_cloudflare_key_id.clone(),
            cf_private_key_pem: self.config.masque_cloudflare_private_key_pem.clone(),
            tls_fingerprint_profile: self.config.tls_fingerprint_profile.clone(),
        };
        let stream = ripdpi_masque::MasqueClient::connect(&cfg, target).await?;
        // Re-box: MasqueClient returns Box<dyn masque::AsyncIo>, which implements
        // our local AsyncIo trait, so we can wrap it in our BoxedIo.
        Ok(Box::new(stream))
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
        let backend = backend_from_config(&self.config);
        let is_running = self.running.load(Ordering::SeqCst);
        let state_str = if is_running { "running" } else { "idle" };
        RelayTelemetry {
            source: "relay",
            state: state_str.to_string(),
            health: state_str.to_string(),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: self.last_target.lock().expect("last target").clone(),
            last_error: self.last_error.lock().expect("last error").clone(),
            profile_id: Some(self.config.profile_id.clone()),
            protocol_kind: Some(self.config.kind.clone()),
            tcp_capable: Some(backend.tcp_capable()),
            udp_capable: Some(backend.udp_capable()),
            fallback_mode: backend.fallback_mode().map(ToOwned::to_owned),
            last_handshake_error: self.last_handshake_error.lock().expect("last handshake error").clone(),
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
        let backend = Arc::new(backend_from_config(&self.config));
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
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socks version"));
        }
        let methods_len = usize::from(greeting[1]);
        let mut methods = vec![0u8; methods_len];
        client.read_exact(&mut methods).await?;
        client.write_all(&[0x05, 0x00]).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        if request_header[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socks request"));
        }
        let command = request_header[1];
        let address = read_target(&mut client, request_header[3]).await?;
        *self.last_target.lock().expect("last target") = Some(address.clone());

        match command {
            0x01 => {
                let mut upstream = match backend.connect_tcp(&address).await {
                    Ok(stream) => stream,
                    Err(error) => {
                        *self.last_handshake_error.lock().expect("last handshake error") = Some(error.to_string());
                        write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                        return Err(error);
                    }
                };
                write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                let _ = copy_bidirectional(&mut client, &mut upstream).await?;
            }
            0x03 => {
                write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(io::Error::new(io::ErrorKind::Unsupported, "UDP associate is not available"));
            }
            _ => {
                write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(io::Error::new(io::ErrorKind::Unsupported, "SOCKS command unsupported"));
            }
        }

        Ok(())
    }
}

fn backend_from_config(config: &ResolvedRelayRuntimeConfig) -> RelayBackend {
    match config.kind.as_str() {
        "hysteria2" => RelayBackend::Hysteria2(Hysteria2Backend { config: config.clone() }),
        "vless_reality" => RelayBackend::VlessReality(VlessRealityBackend { config: config.clone() }),
        "chain_relay" => RelayBackend::ChainRelay(ChainRelayBackend { config: config.clone() }),
        "masque" => RelayBackend::Masque(MasqueBackend { config: config.clone() }),
        other => RelayBackend::Unsupported { kind: other.to_string(), fallback_mode: None },
    }
}

async fn read_target(client: &mut TcpStream, address_type: u8) -> io::Result<String> {
    let address = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets)).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            String::from_utf8(host).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid socks host"))?
        }
        0x04 => {
            let mut octets = [0u8; 16];
            client.read_exact(&mut octets).await?;
            IpAddr::V6(Ipv6Addr::from(octets)).to_string()
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsupported address type")),
    };
    let mut port_bytes = [0u8; 2];
    client.read_exact(&mut port_bytes).await?;
    let port = u16::from_be_bytes(port_bytes);
    Ok(format!("{address}:{port}"))
}

async fn write_reply(client: &mut TcpStream, reply_code: u8, bound: SocketAddr) -> io::Result<()> {
    match bound {
        SocketAddr::V4(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x01];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            client.write_all(&payload).await
        }
        SocketAddr::V6(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x04];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            client.write_all(&payload).await
        }
    }
}

fn describe_upstream(config: &ResolvedRelayRuntimeConfig) -> String {
    match config.kind.as_str() {
        "chain_relay" => format!(
            "{}:{} -> {}:{}",
            config.chain_entry_server, config.chain_entry_port, config.chain_exit_server, config.chain_exit_port,
        ),
        "masque" => config.masque_url.clone(),
        _ => format!("{}:{}", config.server, config.server_port),
    }
}

fn validate_runtime_config(config: &ResolvedRelayRuntimeConfig, backend: &RelayBackend) -> io::Result<()> {
    if config.udp_enabled && !backend.udp_capable() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.kind),
        ));
    }

    if config.kind == "hysteria2"
        && config.hysteria_salamander_key.as_deref().is_some_and(|value| !value.trim().is_empty())
    {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "Hysteria2 Salamander obfuscation is not implemented"));
    }

    if config.kind == "masque" && config.masque_cloudflare_mode {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "Cloudflare MASQUE auth is not implemented"));
    }

    Ok(())
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
            server: "relay.example".to_string(),
            server_port: 443,
            server_name: "relay.example".to_string(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 443,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: 443,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            masque_url: "https://masque.example/".to_string(),
            masque_use_http2_fallback: true,
            masque_cloudflare_mode: false,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 1080,
            udp_enabled: false,
            tcp_fallback_enabled: true,
            vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_entry_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            chain_exit_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
            hysteria_password: Some("secret".to_string()),
            hysteria_salamander_key: None,
            tls_fingerprint_profile: "native_default".to_string(),
            masque_auth_mode: Some("token".to_string()),
            masque_auth_token: Some("token".to_string()),
            masque_cloudflare_client_id: None,
            masque_cloudflare_key_id: None,
            masque_cloudflare_private_key_pem: None,
        }
    }

    #[test]
    fn relay_runtime_rejects_udp_enablement_without_backend_support() {
        let mut config = sample_config("masque");
        config.udp_enabled = true;

        let error = validate_runtime_config(&config, &backend_from_config(&config)).expect_err("UDP must fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[test]
    fn relay_runtime_rejects_hysteria_salamander() {
        let mut config = sample_config("hysteria2");
        config.hysteria_salamander_key = Some("salamander".to_string());

        let error =
            validate_runtime_config(&config, &backend_from_config(&config)).expect_err("Salamander must fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }

    #[test]
    fn relay_runtime_rejects_masque_cloudflare_mode() {
        let mut config = sample_config("masque");
        config.masque_cloudflare_mode = true;

        let error = validate_runtime_config(&config, &backend_from_config(&config))
            .expect_err("Cloudflare mode must fail fast");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }
}
