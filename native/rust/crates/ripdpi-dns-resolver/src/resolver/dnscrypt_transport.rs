use std::time::{Duration, Instant};

use crypto_box::{PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use hickory_proto::op::{Message, ResponseCode};
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
    /// Cancel-safe: a cancel during the certificate step behaves as
    /// `current_dnscrypt_certificate` describes; a cancel during the exchange drops
    /// the stream instead of pooling it. The certificate is marked stale only after
    /// an exchange completes with a certificate-implicating error, never on cancel.
    pub(super) async fn exchange_dnscrypt(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        ensure_dnscrypt_query(query_bytes)?;
        let certificate = self.current_dnscrypt_certificate().await?;
        let result = self.exchange_dnscrypt_with_certificate(&certificate, query_bytes).await;
        if let Err(error) = &result
            && implicates_certificate(error)
        {
            // A rotated, withdrawn or tampered certificate (es_version is not
            // signed) fails every exchange: refresh it before the next query.
            self.mark_dnscrypt_certificate_stale(&certificate);
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
        // An idle connection the resolver dropped silently must not cost a full
        // timeout before the fresh retry; most resolvers close after one reply.
        let deadline = if reused { self.inner.timeout / 4 } else { self.inner.timeout };
        match self.exchange_dnscrypt_with_session(&mut stream, certificate, query_bytes, deadline).await {
            Ok(response) => {
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(stream)).await;
                Ok(response)
            }
            Err(_) if reused => {
                let mut fresh = self.connect_dnscrypt_session().await?;
                let response = self
                    .exchange_dnscrypt_with_session(&mut fresh, certificate, query_bytes, self.inner.timeout)
                    .await?;
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(fresh)).await;
                Ok(response)
            }
            Err(err) => Err(err),
        }
    }

    /// # Cancel safety
    ///
    /// NOT cancel-safe for `stream`: a cancel or timeout during the write-then-read
    /// can leave a partial frame on it. Callers drop `stream` on every non-`Ok`
    /// path and on cancellation. No resolver state is touched.
    async fn exchange_dnscrypt_with_session(
        &self,
        stream: &mut TokioTcpStream,
        certificate: &DnsCryptCachedCertificate,
        query_bytes: &[u8],
        deadline: Duration,
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
        let padded_query = dnscrypt_pad(query_bytes)?;
        let ciphertext = cipher
            .encrypt(&full_nonce, padded_query.as_slice())
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;

        let mut wrapped_query =
            Vec::with_capacity(8 + client_public.as_bytes().len() + DNSCRYPT_QUERY_NONCE_HALF + ciphertext.len());
        wrapped_query.extend_from_slice(&certificate.client_magic);
        wrapped_query.extend_from_slice(client_public.as_bytes());
        wrapped_query.extend_from_slice(&full_nonce[..DNSCRYPT_QUERY_NONCE_HALF]);
        wrapped_query.extend_from_slice(&ciphertext);

        let response = match timeout(deadline, async {
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

    /// The cached certificate while it is fresh; otherwise one query refreshes
    /// it for all concurrent ones. A failed refresh keeps a cached certificate
    /// that is still inside its validity window, and while one is kept the
    /// refresh is retried only after `DNSCRYPT_REFRESH_RETRY_BACKOFF`.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: the cache is written only after a complete fetch, and a
    /// cancelled refresh releases the refresh lock without recording a failure.
    /// A cancelled refresh also skips the fallback to a cached certificate that
    /// is still valid; the next waiter fetches again.
    async fn current_dnscrypt_certificate(&self) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
        if let Some(fresh) = self.fresh_dnscrypt_certificate() {
            return Ok(fresh);
        }
        let waiting_since = Instant::now();
        let mut last_failure = self.inner.dnscrypt_refresh.lock().await;
        if let Some(fresh) = self.fresh_dnscrypt_certificate() {
            return Ok(fresh);
        }
        if let Some((failed_at, message)) = last_failure.as_ref() {
            match self.valid_cached_dnscrypt_certificate() {
                Some(cached) if failed_at.elapsed() < DNSCRYPT_REFRESH_RETRY_BACKOFF => return Ok(cached),
                // Without a usable certificate, a refresh that failed while this
                // query waited is reported instead of being repeated at once.
                None if *failed_at >= waiting_since => {
                    return Err(EncryptedDnsError::DnsCryptCertificate(format!(
                        "DNSCrypt certificate refresh failed: {message}"
                    )));
                }
                _ => {}
            }
        }
        match self.fetch_dnscrypt_certificate().await {
            Ok(fetched) => {
                *last_failure = None;
                if let Ok(mut guard) = self.inner.dnscrypt_state.lock() {
                    *guard = Some((fetched.clone(), Some((Instant::now(), unix_time_secs()))));
                }
                Ok(fetched)
            }
            Err(error) => {
                *last_failure = Some((Instant::now(), error.to_string()));
                self.valid_cached_dnscrypt_certificate().ok_or(error)
            }
        }
    }

    /// The cached certificate if it was fetched less than an hour ago. Both
    /// clocks count: the monotonic one survives a clock step back, the Unix one
    /// keeps counting while an Android device sleeps.
    fn fresh_dnscrypt_certificate(&self) -> Option<DnsCryptCachedCertificate> {
        let (cached, fetched_at) = self.inner.dnscrypt_state.lock().ok()?.clone()?;
        let (fetched_instant, fetched_unix) = fetched_at?;
        let now = unix_time_secs();
        let age = fetched_instant.elapsed().max(Duration::from_secs(now.saturating_sub(fetched_unix).into()));
        cached_certificate_is_usable(&cached, age, now).then_some(cached)
    }

    /// The cached certificate if the current time is inside its validity window.
    fn valid_cached_dnscrypt_certificate(&self) -> Option<DnsCryptCachedCertificate> {
        let (cached, _) = self.inner.dnscrypt_state.lock().ok()?.clone()?;
        let now = unix_time_secs();
        (cached.valid_from <= now && now <= cached.valid_until).then_some(cached)
    }

    /// Marks the cached certificate for refresh if it is still `used`, so a
    /// failed query does not affect a newer certificate that a concurrent query
    /// stored. The certificate stays usable until a refresh replaces it. The
    /// lock guards plain assignments only; a poisoned lock leaves the cache as is.
    fn mark_dnscrypt_certificate_stale(&self, used: &DnsCryptCachedCertificate) {
        if let Ok(mut guard) = self.inner.dnscrypt_state.lock()
            && let Some((cached, fetched_at)) = guard.as_mut()
            && cached == used
        {
            *fetched_at = None;
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
        ensure_response_matches_query(&request, &response)?;
        let message = Message::from_vec(&response).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
        if message.metadata.response_code != ResponseCode::NoError {
            return Err(EncryptedDnsError::DnsCryptCertificate(format!(
                "certificate query failed with {}",
                message.metadata.response_code
            )));
        }
        let question = message
            .queries
            .first()
            .ok_or_else(|| EncryptedDnsError::DnsParse("certificate response has no question".to_string()))?;
        let verifying_key = dnscrypt_verifying_key(&self.inner.endpoint)?;
        let records: Vec<Vec<u8>> = message
            .answers
            .iter()
            .filter(|answer| answer.name == question.name)
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
const DNSCRYPT_CERT_REFRESH: Duration = Duration::from_secs(3_600);

/// How long queries keep a still-valid certificate after a failed refresh
/// before they try again, so a blocked plaintext certificate query does not
/// cost every DNS query a fetch timeout.
const DNSCRYPT_REFRESH_RETRY_BACKOFF: Duration = Duration::from_secs(30);

/// A DNSCrypt query is one DNS question with QR clear, small enough that the
/// padded and encrypted packet stays within 4096 bytes. Checked before any I/O.
fn ensure_dnscrypt_query(query: &[u8]) -> Result<(), EncryptedDnsError> {
    if query.len() >= DNSCRYPT_MAX_PADDED_QUERY {
        return Err(EncryptedDnsError::DnsMessageTooLarge {
            transport: "dnscrypt",
            size: query.len(),
            max: DNSCRYPT_MAX_PADDED_QUERY - 1,
        });
    }
    if query.len() < 12 || query[2] & 0x80 != 0 || u16::from_be_bytes([query[4], query[5]]) != 1 {
        return Err(EncryptedDnsError::DnsParse("DNSCrypt query must be one question with QR clear".to_string()));
    }
    Ok(())
}

/// Whether an exchange error points at the certificate rather than the path:
/// a reply with our nonce failed authentication, or the server closed the
/// stream instead of answering a query it could not decrypt. A wrong magic or
/// nonce prefix, connect failures, resets and timeouts say nothing about it.
fn implicates_certificate(error: &EncryptedDnsError) -> bool {
    match error {
        EncryptedDnsError::DnsCryptDecrypt(message) => message == DNSCRYPT_RESPONSE_AUTH_FAILED,
        EncryptedDnsError::Request(message) => message == STREAM_CLOSED_BEFORE_FRAME,
        _ => false,
    }
}

/// Whether a certificate fetched `age` ago can still be used at Unix time `now`.
/// The age comes from the monotonic clock, so a clock step does not stop refreshes.
fn cached_certificate_is_usable(certificate: &DnsCryptCachedCertificate, age: Duration, now: u32) -> bool {
    certificate.valid_from <= now && now <= certificate.valid_until.saturating_sub(60) && age < DNSCRYPT_CERT_REFRESH
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
    fn a_failed_query_does_not_mark_a_newer_certificate_stale() {
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
        let cached = || {
            resolver.inner.dnscrypt_state.lock().expect("state lock").as_ref().map(|(c, at)| (c.serial, at.is_some()))
        };
        *resolver.inner.dnscrypt_state.lock().expect("state lock") =
            Some((newer.clone(), Some((Instant::now(), 2_000))));

        resolver.mark_dnscrypt_certificate_stale(&old);
        assert_eq!(cached(), Some((2, true)));
        resolver.mark_dnscrypt_certificate_stale(&newer);
        assert_eq!(cached(), Some((2, false)), "the certificate stays cached until a refresh replaces it");
    }

    #[test]
    fn cached_certificate_is_refetched_hourly_and_before_expiry() {
        let certificate = certificate_with_serial(1);
        let hour = Duration::from_secs(3_600);

        assert!(cached_certificate_is_usable(&certificate, hour - Duration::from_secs(1), 2_000));
        assert!(!cached_certificate_is_usable(&certificate, hour, 2_000));
        assert!(!cached_certificate_is_usable(&certificate, Duration::ZERO, 99_950));
        assert!(!cached_certificate_is_usable(&certificate, Duration::ZERO, 900));
    }
}
