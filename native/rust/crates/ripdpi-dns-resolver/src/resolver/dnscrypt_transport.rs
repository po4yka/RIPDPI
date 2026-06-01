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
    build_dns_query, read_length_prefixed_frame_async, unix_time_secs, write_length_prefixed_frame_async,
};
use crate::types::{DnsCryptCachedCertificate, EncryptedDnsError};

impl EncryptedDnsResolver {
    pub(super) async fn exchange_dnscrypt(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let certificate = self.current_dnscrypt_certificate().await?;
        let (mut stream, reused) = self.take_dnscrypt_session().await?;
        match self.exchange_dnscrypt_with_session(&mut stream, &certificate, query_bytes).await {
            Ok(response) => {
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(stream)).await;
                Ok(response)
            }
            Err(_) if reused => {
                let mut fresh = self.connect_dnscrypt_session().await?;
                let response = self.exchange_dnscrypt_with_session(&mut fresh, &certificate, query_bytes).await?;
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
        decrypt_dnscrypt_response(&cipher, &response, &full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])
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
            && let Some(cached) = guard.clone()
            && cached.valid_from <= now
            && now <= cached.valid_until.saturating_sub(60)
        {
            return Ok(cached);
        }

        let fetched = self.fetch_dnscrypt_certificate().await?;
        if let Ok(mut guard) = self.inner.dnscrypt_state.lock() {
            *guard = Some(fetched.clone());
        }
        Ok(fetched)
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
        let now = unix_time_secs();
        let mut best: Option<DnsCryptCachedCertificate> = None;

        for answer in &message.answers {
            let RData::TXT(txt) = &answer.data else {
                continue;
            };
            let mut bytes = Vec::new();
            for chunk in &txt.txt_data {
                bytes.extend_from_slice(chunk);
            }
            let certificate = parse_dnscrypt_certificate(&bytes, &verifying_key, &provider_name)?;
            if certificate.valid_from <= now
                && now <= certificate.valid_until
                && best.as_ref().is_none_or(|value| value.valid_until < certificate.valid_until)
            {
                best = Some(certificate);
            }
        }

        best.ok_or_else(|| {
            EncryptedDnsError::DnsCryptCertificate("resolver did not return a valid certificate".to_string())
        })
    }
}
