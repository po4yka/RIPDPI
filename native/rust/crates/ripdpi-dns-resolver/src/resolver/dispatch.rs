use super::EncryptedDnsResolver;
#[cfg(feature = "hickory-backend")]
use crate::types::EncryptedDnsTransport;
use crate::types::{EncryptedDnsError, EncryptedDnsProtocol};

impl EncryptedDnsResolver {
    pub(crate) async fn exchange_protocol(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        match self.inner.endpoint.protocol {
            EncryptedDnsProtocol::Doh => self.exchange_doh_protocol(query_bytes).await,
            EncryptedDnsProtocol::Dot => self.exchange_dot_protocol(query_bytes).await,
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt(query_bytes).await,
            EncryptedDnsProtocol::Doq => self.exchange_doq(query_bytes).await,
        }
    }

    async fn exchange_doh_protocol(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        #[cfg(feature = "hickory-backend")]
        {
            if self.can_use_hickory() {
                return crate::hickory_backend::exchange_doh(&self.inner.endpoint, query_bytes, self.inner.timeout)
                    .await;
            }
        }

        self.exchange_doh(query_bytes).await
    }

    async fn exchange_dot_protocol(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        #[cfg(feature = "hickory-backend")]
        {
            if self.can_use_hickory() && matches!(self.inner.transport, EncryptedDnsTransport::Direct) {
                return crate::hickory_backend::exchange_dot(&self.inner.endpoint, query_bytes, self.inner.timeout)
                    .await;
            }
        }

        self.exchange_dot(query_bytes).await
    }

    /// Returns `true` when the hickory-resolver backend can handle this resolver's
    /// configuration. Falls back to the manual path when custom TLS roots, a custom
    /// certificate verifier, or a custom DoT TLS builder are configured, because
    /// hickory-resolver manages its own TLS stack and cannot honor those overrides.
    #[cfg(feature = "hickory-backend")]
    fn can_use_hickory(&self) -> bool {
        self.inner.tls_roots.is_empty()
            && self.inner.tls_verifier.is_none()
            && !self.uses_direct_tcp_connector()
            && !(matches!(self.inner.endpoint.protocol, EncryptedDnsProtocol::Dot)
                && self.inner.connect_hooks.has_dot_tls_connector_builder())
    }
}
