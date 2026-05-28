use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Instant;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite};

use crate::telemetry::{ChainHopRole, ChainHopTelemetryState};

pub(crate) trait ChainAsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> ChainAsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

#[derive(Clone)]
pub(crate) struct ChainRelaySessionFactory {
    pub(crate) entry: ChainEntryConnector,
    pub(crate) exit: ChainExitConnector,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) telemetry: ChainHopTelemetryState,
}

#[derive(Clone)]
pub(crate) enum ChainEntryConnector {
    VlessReality(ripdpi_vless::config::VlessRealityConfig),
    Masque(ripdpi_masque::config::MasqueConfig),
    Trojan(ripdpi_relay_tls_transports::TrojanClientConfig),
    Shadowsocks(ripdpi_relay_tls_transports::ShadowsocksSessionFactory),
}

impl ChainEntryConnector {
    async fn connect(&self, outbound_bind_ip: Option<IpAddr>, target: &str) -> io::Result<Box<dyn ChainAsyncIo>> {
        match self {
            Self::VlessReality(config) => {
                let stream = match outbound_bind_ip {
                    Some(bind_ip) => {
                        ripdpi_vless::VlessRealityClient::connect_with_bind(config, bind_ip, target).await?
                    }
                    None => ripdpi_vless::VlessRealityClient::connect(config, target).await?,
                };
                Ok(Box::new(stream))
            }
            Self::Masque(config) => {
                if outbound_bind_ip.is_some() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "chain MASQUE entry does not support outbound bind IP",
                    ));
                }
                let stream = ripdpi_masque::MasqueClient::connect(config, target).await?;
                Ok(Box::new(stream))
            }
            Self::Trojan(config) => {
                if outbound_bind_ip.is_some() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "chain Trojan entry does not support outbound bind IP",
                    ));
                }
                let stream = ripdpi_relay_tls_transports::connect_trojan_tcp(config, target).await?;
                Ok(Box::new(stream))
            }
            Self::Shadowsocks(factory) => {
                let stream = ripdpi_relay_tls_transports::connect_shadowsocks_tcp(factory, target).await?;
                Ok(Box::new(stream))
            }
        }
    }
}

#[derive(Clone)]
pub(crate) enum ChainExitConnector {
    VlessReality(ripdpi_vless::config::VlessRealityConfig),
    Masque(ripdpi_masque::config::MasqueConfig),
    Trojan(ripdpi_relay_tls_transports::TrojanClientConfig),
    Shadowsocks(ripdpi_relay_tls_transports::ShadowsocksSessionFactory),
}

impl ChainExitConnector {
    pub(crate) fn proxy_target(&self) -> io::Result<String> {
        match self {
            Self::VlessReality(config) => Ok(format!("{}:{}", config.server, config.port)),
            Self::Masque(config) => masque_proxy_target(config),
            Self::Trojan(config) => Ok(ripdpi_relay_tls_transports::trojan_proxy_target(config)),
            Self::Shadowsocks(factory) => Ok(ripdpi_relay_tls_transports::shadowsocks_proxy_target(factory)),
        }
    }

    async fn connect_over(&self, transport: Box<dyn ChainAsyncIo>, target: &str) -> io::Result<Box<dyn ChainAsyncIo>> {
        match self {
            Self::VlessReality(config) => {
                let stream = ripdpi_vless::VlessRealityClient::connect_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Masque(config) => {
                let stream = ripdpi_masque::MasqueClient::connect_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Trojan(config) => {
                let stream = ripdpi_relay_tls_transports::connect_trojan_tcp_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Shadowsocks(factory) => {
                let stream =
                    ripdpi_relay_tls_transports::connect_shadowsocks_tcp_over(factory, transport, target).await?;
                Ok(Box::new(stream))
            }
        }
    }
}

fn masque_proxy_target(config: &ripdpi_masque::config::MasqueConfig) -> io::Result<String> {
    if let Some(address) = config.proxy_socket_addr {
        return Ok(address.to_string());
    }
    let parsed = url::Url::parse(&config.url).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?;
    let port = parsed
        .port_or_known_default()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a port"))?;
    Ok(format!("{host}:{port}"))
}

pub(crate) struct ChainRelaySession {
    pub(crate) entry: ChainEntryConnector,
    pub(crate) exit: ChainExitConnector,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) telemetry: ChainHopTelemetryState,
}

impl RelaySession for ChainRelaySession {
    type Stream = Box<dyn ChainAsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let exit_target = self.exit.proxy_target()?;
            self.telemetry.record(ChainHopRole::Entry, "connecting", None);
            let entry_started = Instant::now();
            let first_hop_result = self.entry.connect(self.outbound_bind_ip, &exit_target).await;
            let first_hop = match first_hop_result {
                Ok(stream) => {
                    self.telemetry.record(
                        ChainHopRole::Entry,
                        "connected",
                        Some(entry_started.elapsed().as_millis() as u64),
                    );
                    stream
                }
                Err(error) => {
                    self.telemetry.record(
                        ChainHopRole::Entry,
                        "failed",
                        Some(entry_started.elapsed().as_millis() as u64),
                    );
                    return Err(error);
                }
            };
            self.telemetry.record(ChainHopRole::Exit, "connecting", None);
            let exit_started = Instant::now();
            let second_hop_result = self.exit.connect_over(first_hop, target).await;
            let second_hop = match second_hop_result {
                Ok(stream) => {
                    self.telemetry.record(
                        ChainHopRole::Exit,
                        "connected",
                        Some(exit_started.elapsed().as_millis() as u64),
                    );
                    stream
                }
                Err(error) => {
                    self.telemetry.record(
                        ChainHopRole::Exit,
                        "failed",
                        Some(exit_started.elapsed().as_millis() as u64),
                    );
                    return Err(error);
                }
            };
            Ok(second_hop)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "chain relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for ChainRelaySessionFactory {
    type Session = ChainRelaySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let entry = self.entry.clone();
        let exit = self.exit.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        let telemetry = self.telemetry.clone();
        Box::pin(async move { Ok(Arc::new(ChainRelaySession { entry, exit, outbound_bind_ip, telemetry })) })
    }
}
