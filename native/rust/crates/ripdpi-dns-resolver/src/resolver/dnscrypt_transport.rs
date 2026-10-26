use crypto_box::{PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use hickory_proto::op::Message;
use hickory_proto::rr::{RData, RecordType};
use ring::rand::{SecureRandom, SystemRandom};
use tokio::net::TcpStream as TokioTcpStream;
use tokio::time::timeout;

use super::EncryptedDnsResolver;
use super::connection::PooledConnection;
use crate::dnscrypt::*;
use crate::transport::{
    STREAM_CLOSED_BEFORE_FRAME, build_dns_query, ensure_response_matches_query, read_length_prefixed_frame_async,
    unix_time_secs, write_length_prefixed_frame_async,
};
use crate::types::{DnsCryptCachedCertificate, EncryptedDnsError};

impl EncryptedDnsResolver {
    /// # Cancel safety
    ///
    /// Cancel-safe: the connection slot and the stream are owned by this call; a
    /// cancelled exchange drops its stream instead of returning it to the pool.
    pub(super) async fn exchange_dnscrypt(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let certificate = self.current_dnscrypt_certificate().await?;
        let result = self.exchange_dnscrypt_with_certificate(&certificate, query_bytes).await;
        if let Err(error) = &result
            && implicates_certificate(error)
        {
            // A rotated, withdrawn or tampered certificate (es_version is not
            // signed) fails every exchange: fetch a fresh one for the next query.
            self.forget_dnscrypt_certificate(&certificate);
        }
        result
    }

    /// # Cancel safety
    ///
    /// Cancel-safe: the connection slot and the stream are owned by this call; a
    /// cancelled exchange drops its stream instead of returning it to the pool.
    async fn exchange_dnscrypt_with_certificate(
        &self,
        certificate: &DnsCryptCachedCertificate,
        query_bytes: &[u8],
    ) -> Result<Vec<u8>, EncryptedDnsError> {
        let _slot = self.inner.connection_pool.slot().await?;
        let (mut stream, reused) = self.take_dnscrypt_session().await?;
        match self.exchange_dnscrypt_with_session(&mut stream, certificate, query_bytes).await {
            Ok(response) => {
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(stream)).await;
                Ok(response)
            }
            Err(_) if reused => {
                let mut fresh = self.connect_dnscrypt_session().await?;
                let response = self.exchange_dnscrypt_with_session(&mut fresh, certificate, query_bytes).await?;
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(fresh)).await;
                Ok(response)
            }
            Err(err) => Err(err),
        }
    }

    async fn exchange_dnscrypt_with_session(
        &self,
        stream: &mut TokioTcpStream,
        certificate: &DnsCryptCachedCertificate,
        query_bytes: &[u8],
    ) -> Result<Vec<u8>, EncryptedDnsError> {
        let rng = SystemRandom::new();
        let mut client_secret = [0u8; 32];
        rng.fill(&mut client_secret)
            .map_err(|_| EncryptedDnsError::Request("failed to generate random client secret".to_string()))?;
        let client_secret = CryptoSecretKey::from(client_secret);
        let client_public = client_secret.public_key();
        let resolver_public = CryptoPublicKey::from(certificate.resolver_public_key);
        let cipher = DnsCryptCipher::new(certificate.es_version, &resolver_public, &client_secret)?;

        let mut full_nonce = [0u8; DNSCRYPT_NONCE_SIZE];
        rng.fill(&mut full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])
            .map_err(|_| EncryptedDnsError::Request("failed to generate random nonce".to_string()))?;
        let padded_query = dnscrypt_pad(query_bytes);
        let ciphertext = cipher
            .encrypt(&full_nonce, padded_query.as_slice())
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;

        let mut wrapped_query =
            Vec::with_capacity(8 + client_public.as_bytes().len() + DNSCRYPT_QUERY_NONCE_HALF + ciphertext.len());
        wrapped_query.extend_from_slice(&certificate.client_magic);
        wrapped_query.extend_from_slice(client_public.as_bytes());
        wrapped_query.extend_from_slice(&full_nonce[..DNSCRYPT_QUERY_NONCE_HALF]);
        wrapped_query.extend_from_slice(&ciphertext);

        let response = match timeout(self.inner.timeout, async {
            write_length_prefixed_frame_async(stream, &wrapped_query).await?;
            read_length_prefixed_frame_async(stream).await
        })
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(EncryptedDnsError::Request("DNSCrypt exchange timed out".to_string()));
            }
        };
        let response = decrypt_dnscrypt_response(&cipher, &response, &full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])?;
        ensure_response_matches_query(query_bytes, &response)?;
        Ok(response)
    }

    async fn take_dnscrypt_session(&self) -> Result<(TokioTcpStream, bool), EncryptedDnsError> {
        match self.inner.connection_pool.take().await {
            Some(PooledConnection::DnsCrypt(stream)) => Ok((stream, true)),
            Some(PooledConnection::Dot(stream)) => {
                self.inner.connection_pool.put(PooledConnection::Dot(stream)).await;
                self.connect_dnscrypt_session().await.map(|stream| (stream, false))
            }
            None => self.connect_dnscrypt_session().await.map(|stream| (stream, false)),
        }
    }

    async fn connect_dnscrypt_session(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        self.connect_plain_tcp().await
    }

    async fn current_dnscrypt_certificate(&self) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
        let now = unix_time_secs();
        if let Ok(guard) = self.inner.dnscrypt_state.lock()
            && let Some((cached, fetched_at)) = guard.clone()
            && cached_certificate_is_usable(&cached, fetched_at, now)
        {
            return Ok(cached);
        }

        let fetched = self.fetch_dnscrypt_certificate().await?;
        if let Ok(mut guard) = self.inner.dnscrypt_state.lock() {
            *guard = Some((fetched.clone(), now));
        }
        Ok(fetched)
    }

    /// Drops the cached certificate if it is still `used`, so a failed query
    /// does not wipe a newer certificate that a concurrent query stored. The
    /// lock guards plain assignments only; a poisoned lock leaves the cache as is.
    fn forget_dnscrypt_certificate(&self, used: &DnsCryptCachedCertificate) {
        if let Ok(mut guard) = self.inner.dnscrypt_state.lock()
            && guard.as_ref().is_some_and(|(cached, _)| cached == used)
        {
            *guard = None;
        }
    }

    async fn fetch_dnscrypt_certificate(&self) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
        let provider_name = dnscrypt_provider_name(&self.inner.endpoint)?;
        let query_name = if provider_name.starts_with("2.dnscrypt-cert.") {
            provider_name.clone()
        } else {
            format!("2.dnscrypt-cert.{provider_name}")
        };
        let request = build_dns_query(&query_name, RecordType::TXT)?;
        let mut stream = self.connect_plain_tcp().await?;
        let response = match timeout(self.inner.timeout, async {
            write_length_prefixed_frame_async(&mut stream, &request).await?;
            read_length_prefixed_frame_async(&mut stream).await
        })
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(EncryptedDnsError::DnsCryptCertificate("DNSCrypt certificate fetch timed out".to_string()));
            }
        };
        let message = Message::from_vec(&response).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
        let verifying_key = dnscrypt_verifying_key(&self.inner.endpoint)?;
        let records: Vec<Vec<u8>> = message
            .answers
            .iter()
            .filter_map(|answer| match &answer.data {
                RData::TXT(txt) => Some(txt.txt_data.concat()),
                _ => None,
            })
            .collect();
        select_dnscrypt_certificate(records.iter().map(Vec::as_slice), &verifying_key, &provider_name, unix_time_secs())
    }
}

/// How long a fetched certificate is reused before it is fetched again. The
/// DNSCrypt protocol says clients MUST refresh certificates periodically and
/// calls an hourly refresh suitable; a refresh also picks up a higher serial.
const DNSCRYPT_CERT_REFRESH_SECS: u32 = 3_600;

/// Whether an exchange error points at the certificate rather than the path:
/// the reply did not decrypt, or the server closed the stream instead of
/// answering a query it could not decrypt. Connect failures, resets and
/// timeouts keep the certificate.
fn implicates_certificate(error: &EncryptedDnsError) -> bool {
    match error {
        EncryptedDnsError::DnsCryptDecrypt(_) => true,
        EncryptedDnsError::Request(message) => message == STREAM_CLOSED_BEFORE_FRAME,
        _ => false,
    }
}

/// Whether a certificate fetched at `fetched_at` can still be used at `now`.
fn cached_certificate_is_usable(certificate: &DnsCryptCachedCertificate, fetched_at: u32, now: u32) -> bool {
    certificate.valid_from <= now
        && now <= certificate.valid_until.saturating_sub(60)
        && now.saturating_sub(fetched_at) < DNSCRYPT_CERT_REFRESH_SECS
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::*;
    use crate::types::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsTransport};

    fn certificate_with_serial(serial: u32) -> DnsCryptCachedCertificate {
        DnsCryptCachedCertificate {
            resolver_public_key: [0; 32],
            client_magic: [0; 8],
            serial,
            valid_from: 1_000,
            valid_until: 100_000,
            es_version: crate::DNSCRYPT_ES_VERSION_XCHACHA,
        }
    }

    #[test]
    fn a_failed_query_does_not_forget_a_newer_certificate() {
        let resolver = EncryptedDnsResolver::new(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::DnsCrypt,
                resolver_id: Some("fixture".to_string()),
                host: "resolver.test".to_string(),
                port: 443,
                tls_server_name: None,
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: None,
                dnscrypt_provider_name: Some("2.dnscrypt-cert.resolver.test".to_string()),
                dnscrypt_public_key: Some("11".repeat(32)),
                odoh: None,
            },
            EncryptedDnsTransport::Direct,
        )
        .expect("resolver builds");
        let (old, newer) = (certificate_with_serial(1), certificate_with_serial(2));
        let cached = || resolver.inner.dnscrypt_state.lock().expect("state lock").as_ref().map(|(c, _)| c.serial);
        *resolver.inner.dnscrypt_state.lock().expect("state lock") = Some((newer.clone(), 0));

        resolver.forget_dnscrypt_certificate(&old);
        assert_eq!(cached(), Some(2));
        resolver.forget_dnscrypt_certificate(&newer);
        assert_eq!(cached(), None);
    }

    #[test]
    fn cached_certificate_is_refetched_hourly_and_before_expiry() {
        let certificate = certificate_with_serial(1);

        assert!(cached_certificate_is_usable(&certificate, 2_000, 2_000 + DNSCRYPT_CERT_REFRESH_SECS - 1));
        assert!(!cached_certificate_is_usable(&certificate, 2_000, 2_000 + DNSCRYPT_CERT_REFRESH_SECS));
        assert!(!cached_certificate_is_usable(&certificate, 99_000, 99_950));
        assert!(!cached_certificate_is_usable(&certificate, 500, 900));
    }
}
