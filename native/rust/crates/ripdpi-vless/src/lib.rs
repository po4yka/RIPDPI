pub mod addons;
pub mod config;
pub mod reality;
pub mod vision;
pub mod wire;

use std::io;

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::addons::VISION_ADDONS;
use crate::config::VlessRealityConfig;
use crate::vision::VisionStream;

/// Trait alias for an async bidirectional stream that is `Send`.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// VLESS+Reality client.
///
/// Provides two connection methods:
/// - `connect()`: opens a fresh TCP connection to the server
/// - `connect_over()`: performs VLESS+Reality over an existing transport (for chain relay)
pub struct VlessRealityClient;

impl VlessRealityClient {
    /// Open `TCP -> Reality TLS -> VLESS handshake -> VisionStream`.
    pub async fn connect(config: &VlessRealityConfig, target: &str) -> io::Result<impl AsyncIo> {
        let addr = format!("{}:{}", config.server, config.port);
        tracing::debug!(server = %addr, target, "VLESS+Reality: connecting");

        let tcp = TcpStream::connect(&addr)
            .await
            .map_err(|e| io::Error::new(e.kind(), format!("VLESS TCP connect to {addr}: {e}")))?;
        tcp.set_nodelay(true)?;

        let tls = reality::connect_reality_tls(tcp, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Perform `Reality TLS -> VLESS handshake` over an existing transport.
    ///
    /// Used for chain relay: the `transport` is the output of a previous
    /// `VlessRealityClient::connect()` call (first hop), and we layer a second
    /// VLESS+Reality connection on top of it to reach the final destination.
    pub async fn connect_over<S>(config: &VlessRealityConfig, transport: S, target: &str) -> io::Result<impl AsyncIo>
    where
        S: AsyncIo + 'static,
    {
        tracing::debug!(
            server_name = %config.server_name,
            target,
            "VLESS+Reality (chained): connecting over existing transport"
        );

        let tls = reality::connect_reality_tls_over(transport, config).await?;
        Self::vless_handshake_and_wrap(tls, config, target).await
    }

    /// Send the VLESS request, read the response, and wrap in a VisionStream.
    async fn vless_handshake_and_wrap<S>(
        mut tls: S,
        config: &VlessRealityConfig,
        target: &str,
    ) -> io::Result<impl AsyncIo>
    where
        S: AsyncIo + 'static,
    {
        // Write VLESS request
        let request = wire::encode_request(&config.uuid, VISION_ADDONS, target);
        tls.write_all(&request).await?;

        // Read VLESS response header
        wire::read_response(&mut tls).await?;

        tracing::debug!(target, "VLESS handshake completed");

        // Wrap in VisionStream for TLS-in-TLS detection avoidance
        Ok(VisionStream::new(tls))
    }
}
