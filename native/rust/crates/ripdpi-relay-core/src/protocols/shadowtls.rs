use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::config::ResolvedShadowTlsInnerRelayConfig;

#[derive(Clone)]
pub(crate) struct ShadowTlsSessionFactory {
    pub(crate) client_config: ripdpi_shadowtls::Config,
    pub(crate) outer_server: String,
    pub(crate) outer_server_port: i32,
    pub(crate) inner: ResolvedShadowTlsInnerRelayConfig,
}

pub(crate) struct ShadowTlsSession {
    pub(crate) client_config: ripdpi_shadowtls::Config,
    pub(crate) outer_server: String,
    pub(crate) outer_server_port: i32,
    pub(crate) inner: ResolvedShadowTlsInnerRelayConfig,
}

impl RelaySession for ShadowTlsSession {
    type Stream = Box<dyn ripdpi_vless::AsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let client = ripdpi_shadowtls::ShadowTlsClient::new(self.client_config.clone());
            let transport = client
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
                    let stream = ripdpi_vless::VlessRealityClient::connect_over(&config, transport, target).await?;
                    let stream: Box<dyn ripdpi_vless::AsyncIo> = Box::new(stream);
                    Ok(stream)
                }
                other => Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    format!("ShadowTLS inner relay kind {other} is not supported"),
                )),
            }
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "ShadowTLS relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for ShadowTlsSessionFactory {
    type Session = ShadowTlsSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let client_config = self.client_config.clone();
        let outer_server = self.outer_server.clone();
        let outer_server_port = self.outer_server_port;
        let inner = self.inner.clone();
        Box::pin(
            async move { Ok(Arc::new(ShadowTlsSession { client_config, outer_server, outer_server_port, inner })) },
        )
    }
}
