pub mod cloudflare;
pub mod config;
pub mod h2_fallback;
pub mod h3_connect;

use std::io;

use tokio::io::{AsyncRead, AsyncWrite};

use crate::config::MasqueConfig;

/// Trait alias for an async bidirectional stream that is `Send`.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// MASQUE (RFC 9484 Connect-TCP) client with HTTP/2 fallback.
pub struct MasqueClient;

impl MasqueClient {
    /// Connect to a target through the MASQUE proxy.
    ///
    /// Strategy:
    /// - If `use_http2_fallback` is false: try H3 only.
    /// - If `use_http2_fallback` is true: try H3 first with 5s timeout,
    ///   on timeout/error fall back to H2.
    pub async fn connect(config: &MasqueConfig, target: &str) -> io::Result<Box<dyn AsyncIo>> {
        if !config.use_http2_fallback {
            let stream = h3_connect::h3_connect_tcp(config, target).await?;
            return Ok(Box::new(stream));
        }

        // Try H3 with a 5-second timeout, fall back to H2
        match tokio::time::timeout(std::time::Duration::from_secs(5), h3_connect::h3_connect_tcp(config, target)).await
        {
            Ok(Ok(stream)) => {
                tracing::debug!(target, "MASQUE: H3 connection succeeded");
                Ok(Box::new(stream))
            }
            Ok(Err(e)) => {
                tracing::info!(target, error = %e, "MASQUE: H3 failed, falling back to H2");
                let stream = h2_fallback::h2_connect_tcp(config, target).await?;
                Ok(Box::new(stream))
            }
            Err(_) => {
                tracing::info!(target, "MASQUE: H3 timed out, falling back to H2");
                let stream = h2_fallback::h2_connect_tcp(config, target).await?;
                Ok(Box::new(stream))
            }
        }
    }
}
