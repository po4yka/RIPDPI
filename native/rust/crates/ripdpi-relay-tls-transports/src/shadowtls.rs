use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite};

pub type ShadowTlsClientConfig = ripdpi_shadowtls::Config;

#[derive(Debug, Clone)]
pub struct ShadowTlsInnerConfig {
    pub kind: String,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_flow: String,
    pub vless_transport: String,
    pub xhttp_mode: String,
    pub vless_uuid: Option<String>,
    pub tls_fingerprint_profile: String,
}

#[derive(Clone)]
pub struct ShadowTlsSessionFactory {
    pub client_config: ripdpi_shadowtls::Config,
    pub outer_server: String,
    pub outer_server_port: i32,
    pub inner: ShadowTlsInnerConfig,
}

pub struct ShadowTlsSession {
    pub client_config: ripdpi_shadowtls::Config,
    pub outer_server: String,
    pub outer_server_port: i32,
    pub inner: ShadowTlsInnerConfig,
}

pub async fn connect_shadowtls_tcp(
    factory: &ShadowTlsSessionFactory,
    target: &str,
) -> io::Result<Box<dyn ripdpi_vless::AsyncIo>> {
    let client = ripdpi_shadowtls::ShadowTlsClient::new(factory.client_config.clone());
    let transport =
        client.connect(&factory.outer_server, factory.outer_server_port).await.map_err(wrap_shadowtls_connect_error)?;
    connect_inner_vless(transport, &factory.inner, target).await
}

/// Wrap a ShadowTLS connect error with a diagnostic prefix — but preserve a
/// typed [`ripdpi_shadowtls::ShadowTlsHandshakeError`] unchanged. The relay layer
/// downcasts that typed error to `FailureClass::ShadowTlsVersionMismatch`;
/// re-wrapping it into a fresh string `io::Error` here would erase the typed
/// inner and the version-mismatch diagnostic would never reach service telemetry.
fn wrap_shadowtls_connect_error(error: io::Error) -> io::Error {
    if let Some(inner) = error.get_ref()
        && inner.is::<ripdpi_shadowtls::ShadowTlsHandshakeError>()
    {
        return error;
    }
    io::Error::new(error.kind(), format!("shadowtls connect: {error}"))
}

pub async fn connect_shadowtls_tcp_over<S>(
    factory: &ShadowTlsSessionFactory,
    transport: S,
    target: &str,
) -> io::Result<Box<dyn ripdpi_vless::AsyncIo>>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let client = ripdpi_shadowtls::ShadowTlsClient::new(factory.client_config.clone());
    let shadowtls_transport = client.connect_over(transport).await.map_err(wrap_shadowtls_connect_error)?;
    connect_inner_vless(shadowtls_transport, &factory.inner, target).await
}

pub fn shadowtls_proxy_target(factory: &ShadowTlsSessionFactory) -> String {
    format!("{}:{}", factory.outer_server, factory.outer_server_port)
}

impl RelaySession for ShadowTlsSession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let factory = ShadowTlsSessionFactory {
            client_config: self.client_config.clone(),
            outer_server: self.outer_server.clone(),
            outer_server_port: self.outer_server_port,
            inner: self.inner.clone(),
        };
        connect_shadowtls_tcp(&factory, target).await
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "ShadowTLS relay does not support UDP ASSOCIATE"))
    }
}

async fn connect_inner_vless<S>(
    transport: S,
    inner: &ShadowTlsInnerConfig,
    target: &str,
) -> io::Result<Box<dyn ripdpi_vless::AsyncIo>>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    match inner.kind.as_str() {
        "vless_reality" => {
            if inner.vless_transport == "xhttp" {
                return Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "ShadowTLS inner VLESS xHTTP transport is not supported yet",
                ));
            }
            let config = inner_vless_config(inner)?;
            let stream = ripdpi_vless::VlessRealityClient::connect_over(&config, transport, target).await?;
            let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
            Ok(stream)
        }
        other => Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("ShadowTLS inner relay kind {other} is not supported"),
        )),
    }
}

fn inner_vless_config(inner: &ShadowTlsInnerConfig) -> io::Result<ripdpi_vless::config::VlessRealityConfig> {
    let uuid = inner
        .vless_uuid
        .as_ref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing ShadowTLS inner VLESS UUID"))?;
    ripdpi_vless::config::VlessRealityConfig::from_strings(
        &inner.server,
        inner.server_port,
        uuid,
        &inner.server_name,
        &inner.reality_public_key,
        &inner.reality_short_id,
        &inner.tls_fingerprint_profile,
    )
    .and_then(|config| config.with_flow_str(&inner.vless_flow))
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("shadowtls inner vless: {error}")))
}

impl RelaySessionFactory for ShadowTlsSessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = ShadowTlsSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let client_config = self.client_config.clone();
        let outer_server = self.outer_server.clone();
        let outer_server_port = self.outer_server_port;
        let inner = self.inner.clone();
        Ok(Arc::new(ShadowTlsSession { client_config, outer_server, outer_server_port, inner }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inner_vless_config_preserves_selected_tls_fingerprint() {
        let config = inner_vless_config(&ShadowTlsInnerConfig {
            kind: "vless_reality".to_string(),
            server: "inner.example".to_string(),
            server_port: 443,
            server_name: "inner.example".to_string(),
            reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
            reality_short_id: String::new(),
            vless_flow: "xtls-rprx-vision".to_string(),
            vless_transport: "reality_tcp".to_string(),
            xhttp_mode: "auto".to_string(),
            vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
            tls_fingerprint_profile: "firefox_stable".to_string(),
        })
        .expect("valid inner VLESS config");

        assert_eq!("firefox_stable", config.tls_fingerprint_profile);
    }
}
