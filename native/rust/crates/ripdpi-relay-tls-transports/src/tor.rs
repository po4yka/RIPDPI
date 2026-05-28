use std::io;
use std::path::PathBuf;
use std::pin::Pin;
use std::task::{Context, Poll};

use ripdpi_relay_mux::{RelayCapabilities, RelayPoolHealth};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub struct TorBridgePtRelayConfig {
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub bridge_lines: Vec<String>,
    pub transports: Vec<TorPluggableTransportConfig>,
}

pub struct TorPluggableTransportConfig {
    pub protocols: Vec<String>,
    pub binary_path: PathBuf,
    pub arguments: Vec<String>,
    pub run_on_startup: bool,
}

pub struct TorRelayBackend {
    client: ripdpi_tor::TorRelayClient,
}

pub struct TorRelayTarget {
    inner: ripdpi_tor::TorTarget,
}

pub struct TorRelayStream(ripdpi_tor::BoxedIo);

impl TorRelayBackend {
    pub fn from_bridge_pt_config(config: TorBridgePtRelayConfig) -> io::Result<Self> {
        let dirs = ripdpi_tor::prepare_arti_state_dirs(&config.state_dir, &config.cache_dir).map_err(to_io_error)?;
        let arti_config = ripdpi_tor::build_bridge_pt_config(ripdpi_tor::TorBridgePtConfig {
            state_dir: dirs.state_dir().to_path_buf(),
            cache_dir: dirs.cache_dir().to_path_buf(),
            bridge_lines: config.bridge_lines,
            transports: config
                .transports
                .into_iter()
                .map(|transport| ripdpi_tor::TorPluggableTransport {
                    protocols: transport.protocols,
                    binary_path: transport.binary_path,
                    arguments: transport.arguments,
                    run_on_startup: transport.run_on_startup,
                })
                .collect(),
        })
        .map_err(to_io_error)?;
        let client = ripdpi_tor::TorRelayClient::create_unbootstrapped(arti_config).map_err(to_io_error)?;
        Ok(Self { client })
    }

    pub fn capabilities(&self) -> RelayCapabilities {
        let _client = &self.client;
        RelayCapabilities { tcp: true, udp: false, reusable: true }
    }

    pub fn pool_health(&self) -> RelayPoolHealth {
        let _client = &self.client;
        RelayPoolHealth::default()
    }

    pub async fn connect_tcp(&self, target: &TorRelayTarget) -> io::Result<TorRelayStream> {
        let stream = self.client.connect_tcp(&target.inner).await?;
        Ok(TorRelayStream(stream))
    }
}

impl TorRelayTarget {
    pub fn new(host: String, port: u16) -> io::Result<Self> {
        let inner = ripdpi_tor::TorTarget::new(host, port)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
        Ok(Self { inner })
    }
}

impl AsyncRead for TorRelayStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0).poll_read(cx, buf)
    }
}

impl AsyncWrite for TorRelayStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut *self.0).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut *self.0).poll_shutdown(cx)
    }
}

fn to_io_error(error: impl std::error::Error + Send + Sync + 'static) -> io::Error {
    io::Error::other(error)
}
