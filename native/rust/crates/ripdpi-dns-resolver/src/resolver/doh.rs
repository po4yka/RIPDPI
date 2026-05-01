mod chunked_body;
mod http1_request;
mod http1_response;
mod manual_exchange;
mod request_exchange;

#[cfg(test)]
mod tests;

use super::EncryptedDnsResolver;
use crate::types::EncryptedDnsError;

const MAX_DOH_RESPONSE_BYTES: usize = 65_535;
const MAX_DOH_HEADER_BYTES: usize = 8 * 1024;

impl EncryptedDnsResolver {
    pub(super) async fn exchange_doh(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        request_exchange::exchange_doh(self, query_bytes).await
    }
}
