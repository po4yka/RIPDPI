use reqwest::header::{ACCEPT, CONTENT_TYPE};

use super::manual_exchange;
use crate::resolver::EncryptedDnsResolver;
use crate::transport::{format_error_chain, DNS_MESSAGE_MEDIA_TYPE};
use crate::types::EncryptedDnsError;

pub(super) async fn exchange_doh(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    if resolver.uses_direct_tcp_connector() {
        return manual_exchange::exchange_doh_manually(resolver, query_bytes).await;
    }

    let client = resolver
        .inner
        .doh_client
        .as_ref()
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH client not initialized".to_string()))?;
    let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;

    let response = client
        .post(base_url)
        .header(CONTENT_TYPE, DNS_MESSAGE_MEDIA_TYPE)
        .header(ACCEPT, DNS_MESSAGE_MEDIA_TYPE)
        .body(query_bytes.to_vec())
        .send()
        .await
        .map_err(|err| EncryptedDnsError::Request(format_error_chain(&err)))?;

    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }

    response.bytes().await.map(|value| value.to_vec()).map_err(|err| EncryptedDnsError::Request(err.to_string()))
}
