use super::EncryptedDnsResolver;
use crate::types::{EncryptedDnsError, EncryptedDnsProtocol};

impl EncryptedDnsResolver {
    pub(crate) async fn exchange_protocol(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        match self.inner.endpoint.protocol {
            EncryptedDnsProtocol::Doh => self.exchange_doh(query_bytes).await,
            EncryptedDnsProtocol::Dot => self.exchange_dot(query_bytes).await,
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt(query_bytes).await,
            EncryptedDnsProtocol::Doq => self.exchange_doq(query_bytes).await,
            EncryptedDnsProtocol::Odoh => self.exchange_odoh(query_bytes).await,
        }
    }
}
