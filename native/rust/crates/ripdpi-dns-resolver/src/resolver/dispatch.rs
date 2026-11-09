use super::EncryptedDnsResolver;
use crate::transport::{prepare_query, restore_response};
use crate::types::{EncryptedDnsError, EncryptedDnsProtocol};

impl EncryptedDnsResolver {
    pub(crate) async fn exchange_protocol(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let protocol = self.inner.endpoint.protocol;
        // DoQ, DNSCrypt and ODoH pad on their own layer, so EDNS padding is only for DoH and DoT.
        let pad = matches!(protocol, EncryptedDnsProtocol::Doh | EncryptedDnsProtocol::Dot);
        let (prepared, change) = prepare_query(query_bytes, pad);
        let query = prepared.as_ref();
        let response = match protocol {
            EncryptedDnsProtocol::Doh => self.exchange_doh(query).await,
            EncryptedDnsProtocol::Dot => self.exchange_dot(query).await,
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt(query).await,
            EncryptedDnsProtocol::Doq => self.exchange_doq(query).await,
            EncryptedDnsProtocol::Odoh => self.exchange_odoh(query).await,
        }?;
        Ok(restore_response(response, change))
    }
}
