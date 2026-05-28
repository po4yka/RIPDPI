mod chunked_body;
mod http1_request;
mod http1_response;
mod manual_exchange;
mod request_exchange;

#[cfg(test)]
mod tests;

use super::EncryptedDnsResolver;
use crate::odoh::ODOH_MESSAGE_MEDIA_TYPE;
use crate::transport::unix_time_secs;
use crate::types::EncryptedDnsError;

const MAX_DOH_RESPONSE_BYTES: usize = 65_535;
const MAX_DOH_HEADER_BYTES: usize = 8 * 1024;

impl EncryptedDnsResolver {
    pub(super) async fn exchange_doh(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        request_exchange::exchange_doh(self, query_bytes).await
    }

    pub(super) async fn exchange_odoh(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let odoh = self
            .inner
            .endpoint
            .odoh
            .as_ref()
            .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("ODoH config is required".to_string()))?;
        let resolved = odoh
            .config_source
            .resolve_at(u64::from(unix_time_secs()))
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        let encrypted = resolved
            .target_config()
            .encrypt_query(query_bytes, 0)
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        let proxy_url = odoh_proxy_url(&odoh.proxy_url, &odoh.target_host, &odoh.target_path)?;

        let response = request_exchange::exchange_binary_post(
            self,
            proxy_url.as_str(),
            encrypted.wire_message(),
            ODOH_MESSAGE_MEDIA_TYPE,
            ODOH_MESSAGE_MEDIA_TYPE,
        )
        .await?;
        encrypted
            .decrypt_response(&response)
            .map(|plaintext| plaintext.dns_message().to_vec())
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))
    }
}

fn odoh_proxy_url(proxy_url: &str, target_host: &str, target_path: &str) -> Result<url::Url, EncryptedDnsError> {
    let mut url = url::Url::parse(proxy_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    url.query_pairs_mut().append_pair("targethost", target_host).append_pair("targetpath", target_path);
    Ok(url)
}
