use boring::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode};
use boring::x509::X509;
use tokio::time::timeout;

use super::connection::{DotTlsStream, PooledConnection};
use super::EncryptedDnsResolver;
use crate::transport::{read_length_prefixed_frame_async, write_length_prefixed_frame_async};
use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsError};

impl EncryptedDnsResolver {
    pub(super) async fn exchange_dot(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let (mut stream, reused) = self.take_dot_session().await?;
        match self.exchange_dot_with_session(&mut stream, query_bytes).await {
            Ok(response) => {
                self.inner.connection_pool.put(PooledConnection::Dot(Box::new(stream))).await;
                Ok(response)
            }
            Err(_) if reused => {
                let mut fresh = self.connect_dot_session().await?;
                let response = self.exchange_dot_with_session(&mut fresh, query_bytes).await?;
                self.inner.connection_pool.put(PooledConnection::Dot(Box::new(fresh))).await;
                Ok(response)
            }
            Err(err) => Err(err),
        }
    }

    async fn exchange_dot_with_session(
        &self,
        stream: &mut DotTlsStream,
        query_bytes: &[u8],
    ) -> Result<Vec<u8>, EncryptedDnsError> {
        match timeout(self.inner.timeout, async {
            write_length_prefixed_frame_async(stream, query_bytes).await?;
            read_length_prefixed_frame_async(stream).await
        })
        .await
        {
            Ok(result) => result,
            Err(_) => Err(EncryptedDnsError::Request("DNS-over-TLS exchange timed out".to_string())),
        }
    }

    async fn take_dot_session(&self) -> Result<(DotTlsStream, bool), EncryptedDnsError> {
        match self.inner.connection_pool.take().await {
            Some(PooledConnection::Dot(stream)) => Ok((*stream, true)),
            Some(PooledConnection::DnsCrypt(stream)) => {
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(stream)).await;
                self.connect_dot_session().await.map(|stream| (stream, false))
            }
            None => self.connect_dot_session().await.map(|stream| (stream, false)),
        }
    }

    async fn connect_dot_session(&self) -> Result<DotTlsStream, EncryptedDnsError> {
        let tcp_stream = self.connect_plain_tcp().await?;
        let tls_name = self.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| self.inner.endpoint.host.clone());
        let verify = !self.inner.dot_skip_verify;
        let mut builder = build_dot_connector_builder(&self.inner.connect_hooks)?;
        if !verify {
            builder.set_verify(SslVerifyMode::NONE);
        }
        for root_der in &self.inner.dot_extra_roots {
            let x509 = X509::from_der(root_der.as_ref())
                .map_err(|e| EncryptedDnsError::Tls(format!("DoT extra root cert: {e}")))?;
            builder
                .cert_store_mut()
                .add_cert(x509)
                .map_err(|e| EncryptedDnsError::Tls(format!("DoT add root cert: {e}")))?;
        }
        let connector = builder.build();
        let config_ssl =
            connector.configure().map_err(|e| EncryptedDnsError::Tls(format!("DoT TLS configure: {e}")))?;
        match timeout(self.inner.timeout, tokio_boring::connect(config_ssl, &tls_name, tcp_stream)).await {
            Ok(Ok(stream)) => Ok(stream),
            Ok(Err(err)) => Err(EncryptedDnsError::Tls(format!("DoT TLS handshake to {tls_name}: {err}"))),
            Err(_) => Err(EncryptedDnsError::Tls(format!("DoT TLS handshake to {tls_name} timed out"))),
        }
    }
}

fn build_dot_connector_builder(hooks: &EncryptedDnsConnectHooks) -> Result<SslConnectorBuilder, EncryptedDnsError> {
    if let Some(factory) = &hooks.dot_tls_connector_builder {
        return factory().map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS builder: {error}")));
    }

    let builder = SslConnector::builder(SslMethod::tls())
        .map_err(|error| EncryptedDnsError::Tls(format!("DoT TLS builder: {error}")))?;
    Ok(builder)
}
