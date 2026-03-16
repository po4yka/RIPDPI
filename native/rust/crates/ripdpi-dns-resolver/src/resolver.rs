use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use crypto_box::aead::Aead;
use hickory_proto::op::Message;
use hickory_proto::rr::{RData, RecordType};
use rand::RngCore;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use rustls::pki_types::{CertificateDer, ServerName};
use rustls::{ClientConfig, ClientConnection, StreamOwned};

use crate::dnscrypt::*;
use crate::transport::*;
use crate::types::*;

#[derive(Debug, Clone)]
pub struct EncryptedDnsResolver {
    inner: Arc<ResolverInner>,
}

impl EncryptedDnsResolver {
    pub fn new(endpoint: EncryptedDnsEndpoint, transport: EncryptedDnsTransport) -> Result<Self, EncryptedDnsError> {
        Self::with_timeout(endpoint, transport, DEFAULT_TIMEOUT)
    }

    pub fn with_timeout(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_extra_tls_roots(endpoint, transport, timeout, Vec::new())
    }

    pub(crate) fn with_extra_tls_roots(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
    ) -> Result<Self, EncryptedDnsError> {
        let normalized = normalize_endpoint(endpoint, &transport)?;
        let doh_client = if normalized.protocol == EncryptedDnsProtocol::Doh {
            Some(build_doh_client(&normalized, &transport, timeout, &tls_roots)?)
        } else {
            None
        };

        Ok(Self {
            inner: Arc::new(ResolverInner {
                endpoint: normalized,
                transport,
                timeout,
                doh_client,
                tls_roots,
                dnscrypt_state: Mutex::new(None),
            }),
        })
    }

    pub fn endpoint(&self) -> &EncryptedDnsEndpoint {
        &self.inner.endpoint
    }

    pub async fn exchange_with_metadata(
        &self,
        query_bytes: &[u8],
    ) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let started = std::time::Instant::now();
        let response_bytes = match self.inner.endpoint.protocol {
            EncryptedDnsProtocol::Doh => self.exchange_doh(query_bytes).await,
            EncryptedDnsProtocol::Dot => {
                let resolver = self.clone();
                let query = query_bytes.to_vec();
                tokio::task::spawn_blocking(move || resolver.exchange_dot_blocking(&query))
                    .await
                    .map_err(|err| EncryptedDnsError::TaskJoin(err.to_string()))?
            }
            EncryptedDnsProtocol::DnsCrypt => {
                let resolver = self.clone();
                let query = query_bytes.to_vec();
                tokio::task::spawn_blocking(move || resolver.exchange_dnscrypt_blocking(&query))
                    .await
                    .map_err(|err| EncryptedDnsError::TaskJoin(err.to_string()))?
            }
        }?;

        Ok(EncryptedDnsExchangeSuccess {
            response_bytes,
            endpoint_label: self.endpoint_label(),
            latency_ms: started.elapsed().as_millis().try_into().unwrap_or(u64::MAX),
        })
    }

    pub async fn exchange(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        self.exchange_with_metadata(query_bytes).await.map(|success| success.response_bytes)
    }

    pub fn exchange_blocking_with_metadata(
        &self,
        query_bytes: &[u8],
    ) -> Result<EncryptedDnsExchangeSuccess, EncryptedDnsError> {
        let started = std::time::Instant::now();
        let response_bytes = match self.inner.endpoint.protocol {
            EncryptedDnsProtocol::Doh => BLOCKING_RUNTIME.block_on(self.exchange_doh(query_bytes)),
            EncryptedDnsProtocol::Dot => self.exchange_dot_blocking(query_bytes),
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt_blocking(query_bytes),
        }?;

        Ok(EncryptedDnsExchangeSuccess {
            response_bytes,
            endpoint_label: self.endpoint_label(),
            latency_ms: started.elapsed().as_millis().try_into().unwrap_or(u64::MAX),
        })
    }

    pub fn exchange_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        self.exchange_blocking_with_metadata(query_bytes).map(|success| success.response_bytes)
    }

    fn endpoint_label(&self) -> String {
        self.inner
            .endpoint
            .doh_url
            .clone()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| format!("{}:{}", self.inner.endpoint.host, self.inner.endpoint.port))
    }

    async fn exchange_doh(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let client = self
            .inner
            .doh_client
            .as_ref()
            .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH client not initialized".to_string()))?;
        let url = self.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;

        let response = client
            .post(url)
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

    fn exchange_dot_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let stream = self.connect_plain_tcp_blocking()?;
        let tls_name = self.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| self.inner.endpoint.host.clone());
        let config = Arc::new(
            ClientConfig::builder()
                .with_root_certificates(default_root_store(&self.inner.tls_roots))
                .with_no_client_auth(),
        );
        let server_name = ServerName::try_from(tls_name).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
        let connection =
            ClientConnection::new(config, server_name).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
        let mut tls_stream = StreamOwned::new(connection, stream);
        complete_tls_handshake(&mut tls_stream)?;
        write_length_prefixed_frame(&mut tls_stream, query_bytes)?;
        read_length_prefixed_frame(&mut tls_stream)
    }

    fn exchange_dnscrypt_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let certificate = self.current_dnscrypt_certificate()?;
        let mut client_secret = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut client_secret);
        let client_secret = CryptoSecretKey::from(client_secret);
        let client_public = client_secret.public_key();
        let resolver_public = CryptoPublicKey::from(certificate.resolver_public_key);
        let crypto_box = ChaChaBox::new(&resolver_public, &client_secret);

        let mut full_nonce = [0u8; DNSCRYPT_NONCE_SIZE];
        rand::rngs::OsRng.fill_bytes(&mut full_nonce[..DNSCRYPT_QUERY_NONCE_HALF]);
        let padded_query = dnscrypt_pad(query_bytes);
        let ciphertext = crypto_box
            .encrypt((&full_nonce).into(), padded_query.as_slice())
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;

        let mut wrapped_query =
            Vec::with_capacity(8 + client_public.as_bytes().len() + DNSCRYPT_QUERY_NONCE_HALF + ciphertext.len());
        wrapped_query.extend_from_slice(&certificate.client_magic);
        wrapped_query.extend_from_slice(client_public.as_bytes());
        wrapped_query.extend_from_slice(&full_nonce[..DNSCRYPT_QUERY_NONCE_HALF]);
        wrapped_query.extend_from_slice(&ciphertext);

        let mut stream = self.connect_plain_tcp_blocking()?;
        write_length_prefixed_frame(&mut stream, &wrapped_query)?;
        let response = read_length_prefixed_frame(&mut stream)?;
        decrypt_dnscrypt_response(&crypto_box, &response, &full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])
    }

    fn current_dnscrypt_certificate(&self) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
        let now = unix_time_secs();
        if let Ok(guard) = self.inner.dnscrypt_state.lock() {
            if let Some(cached) = guard.clone() {
                if cached.valid_from <= now && now <= cached.valid_until.saturating_sub(60) {
                    return Ok(cached);
                }
            }
        }

        let fetched = self.fetch_dnscrypt_certificate()?;
        if let Ok(mut guard) = self.inner.dnscrypt_state.lock() {
            *guard = Some(fetched.clone());
        }
        Ok(fetched)
    }

    fn fetch_dnscrypt_certificate(&self) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
        let provider_name = dnscrypt_provider_name(&self.inner.endpoint)?;
        let query_name = if provider_name.starts_with("2.dnscrypt-cert.") {
            provider_name.clone()
        } else {
            format!("2.dnscrypt-cert.{provider_name}")
        };
        let request = build_dns_query(&query_name, RecordType::TXT)?;
        let mut stream = self.connect_plain_tcp_blocking()?;
        write_length_prefixed_frame(&mut stream, &request)?;
        let response = read_length_prefixed_frame(&mut stream)?;
        let message = Message::from_vec(&response).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
        let verifying_key = dnscrypt_verifying_key(&self.inner.endpoint)?;
        let now = unix_time_secs();
        let mut best: Option<DnsCryptCachedCertificate> = None;

        for answer in message.answers() {
            let Some(RData::TXT(txt)) = answer.data() else {
                continue;
            };
            let mut bytes = Vec::new();
            for chunk in txt.txt_data() {
                bytes.extend_from_slice(chunk);
            }
            let certificate = parse_dnscrypt_certificate(&bytes, &verifying_key, &provider_name)?;
            if certificate.valid_from <= now && now <= certificate.valid_until {
                if best.as_ref().map(|value| value.valid_until < certificate.valid_until).unwrap_or(true) {
                    best = Some(certificate);
                }
            }
        }

        best.ok_or_else(|| {
            EncryptedDnsError::DnsCryptCertificate("resolver did not return a valid certificate".to_string())
        })
    }

    fn connect_plain_tcp_blocking(&self) -> Result<TcpStream, EncryptedDnsError> {
        let stream = match &self.inner.transport {
            EncryptedDnsTransport::Direct => self.connect_direct_tcp_blocking()?,
            EncryptedDnsTransport::Socks5 { host, port } => self.connect_socks5_tcp_blocking(host, *port)?,
        };
        stream.set_read_timeout(Some(self.inner.timeout)).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        stream
            .set_write_timeout(Some(self.inner.timeout))
            .map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
        Ok(stream)
    }

    fn connect_direct_tcp_blocking(&self) -> Result<TcpStream, EncryptedDnsError> {
        let endpoint = &self.inner.endpoint;
        let addresses =
            endpoint.bootstrap_ips.iter().copied().map(|ip| SocketAddr::new(ip, endpoint.port)).collect::<Vec<_>>();
        let mut last_error = None;
        for address in addresses {
            match TcpStream::connect_timeout(&address, self.inner.timeout) {
                Ok(stream) => return Ok(stream),
                Err(err) => last_error = Some(err.to_string()),
            }
        }
        Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
    }

    fn connect_socks5_tcp_blocking(&self, proxy_host: &str, proxy_port: u16) -> Result<TcpStream, EncryptedDnsError> {
        let proxy_target = resolve_socket_addr(proxy_host, proxy_port)?;
        let mut proxy_stream = TcpStream::connect_timeout(&proxy_target, self.inner.timeout)
            .map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
        proxy_stream
            .set_read_timeout(Some(self.inner.timeout))
            .map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
        proxy_stream
            .set_write_timeout(Some(self.inner.timeout))
            .map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;

        proxy_stream.write_all(&[0x05, 0x01, 0x00]).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
        let mut auth_reply = [0u8; 2];
        proxy_stream.read_exact(&mut auth_reply).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
        if auth_reply != [0x05, 0x00] {
            return Err(EncryptedDnsError::Socks5(format!("unexpected auth reply: {auth_reply:?}")));
        }

        let host_bytes = self.inner.endpoint.host.as_bytes();
        if host_bytes.len() > u8::MAX as usize {
            return Err(EncryptedDnsError::Socks5("resolver host is too long".to_string()));
        }

        let mut request = Vec::with_capacity(host_bytes.len() + 7);
        request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
        request.extend_from_slice(host_bytes);
        request.extend_from_slice(&self.inner.endpoint.port.to_be_bytes());
        proxy_stream.write_all(&request).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;

        let mut header = [0u8; 4];
        proxy_stream.read_exact(&mut header).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
        if header[1] != 0x00 {
            return Err(EncryptedDnsError::Socks5(format!("connect reply {:x}", header[1])));
        }
        consume_socks5_bind_address(&mut proxy_stream, header[3])?;
        Ok(proxy_stream)
    }
}
