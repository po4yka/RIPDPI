use std::sync::{Arc, Mutex};
use std::time::Duration;

use crypto_box::aead::Aead;
use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use hickory_proto::op::Message;
use hickory_proto::rr::{RData, RecordType};
use rand::RngCore;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::{CertificateDer, ServerName};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream as TokioTcpStream;
use tokio::runtime::Builder;
use tokio::sync::Mutex as AsyncMutex;
use tokio::time::timeout;
use tokio_rustls::client::TlsStream as TokioTlsStream;
use tokio_rustls::TlsConnector;

use crate::dnscrypt::*;
use crate::health::HealthRegistry;
use crate::transport::*;
use crate::types::*;

type DotTlsStream = TokioTlsStream<TokioTcpStream>;

enum PooledConnection {
    Dot(Box<DotTlsStream>),
    DnsCrypt(TokioTcpStream),
}

impl std::fmt::Debug for PooledConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Dot(_) => f.write_str("PooledConnection::Dot(..)"),
            Self::DnsCrypt(_) => f.write_str("PooledConnection::DnsCrypt(..)"),
        }
    }
}

#[derive(Default)]
struct ConnectionPool {
    idle: AsyncMutex<Option<PooledConnection>>,
}

impl ConnectionPool {
    async fn take(&self) -> Option<PooledConnection> {
        self.idle.lock().await.take()
    }

    async fn put(&self, connection: PooledConnection) {
        *self.idle.lock().await = Some(connection);
    }
}

impl std::fmt::Debug for ConnectionPool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConnectionPool").finish_non_exhaustive()
    }
}

#[derive(Debug)]
struct ResolverInner {
    endpoint: EncryptedDnsEndpoint,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    doh_client: Option<reqwest::Client>,
    tls_roots: Vec<CertificateDer<'static>>,
    tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    dnscrypt_state: Mutex<Option<DnsCryptCachedCertificate>>,
    connection_pool: ConnectionPool,
    health: Option<HealthRegistry>,
}

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

    pub fn with_tls_verifier(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health(endpoint, transport, DEFAULT_TIMEOUT, Vec::new(), None, tls_verifier)
    }

    pub(crate) fn with_extra_tls_roots(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
    ) -> Result<Self, EncryptedDnsError> {
        Self::with_health(endpoint, transport, timeout, tls_roots, None, None)
    }

    pub(crate) fn with_health(
        endpoint: EncryptedDnsEndpoint,
        transport: EncryptedDnsTransport,
        timeout: Duration,
        tls_roots: Vec<CertificateDer<'static>>,
        health: Option<crate::health::HealthRegistry>,
        tls_verifier: Option<Arc<dyn ServerCertVerifier>>,
    ) -> Result<Self, EncryptedDnsError> {
        let normalized = normalize_endpoint(endpoint, &transport)?;
        let doh_client = if normalized.protocol == EncryptedDnsProtocol::Doh {
            Some(build_doh_client(
                &normalized,
                &transport,
                timeout,
                &tls_roots,
                health.as_ref(),
                tls_verifier.as_ref(),
            )?)
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
                tls_verifier,
                dnscrypt_state: Mutex::new(None),
                connection_pool: ConnectionPool::default(),
                health,
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
            EncryptedDnsProtocol::Dot => self.exchange_dot(query_bytes).await,
            EncryptedDnsProtocol::DnsCrypt => self.exchange_dnscrypt(query_bytes).await,
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
        if tokio::runtime::Handle::try_current().is_ok() {
            return Err(EncryptedDnsError::TaskJoin(
                "blocking encrypted DNS exchange cannot run inside a Tokio runtime".to_string(),
            ));
        }

        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|err| EncryptedDnsError::TaskJoin(err.to_string()))?;
        runtime.block_on(self.exchange_with_metadata(query_bytes))
    }

    pub fn exchange_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        self.exchange_blocking_with_metadata(query_bytes).map(|success| success.response_bytes)
    }

    pub fn endpoint_label(&self) -> String {
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

    async fn exchange_dot(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let (mut stream, reused) = self.take_dot_session().await?;
        match self.exchange_dot_with_session(&mut stream, query_bytes).await {
            Ok(response) => {
                self.inner.connection_pool.put(PooledConnection::Dot(Box::new(stream))).await;
                Ok(response)
            }
            Err(_) if reused => {
                let mut fresh = self.connect_dot_session().await?;
                let response = self.exchange_dot_with_session(&mut fresh, query_bytes).await?;
                self.inner.connection_pool.put(PooledConnection::Dot(Box::new(fresh))).await;
                Ok(response)
            }
            Err(err) => Err(err),
        }
    }

    async fn exchange_dot_with_session(
        &self,
        stream: &mut DotTlsStream,
        query_bytes: &[u8],
    ) -> Result<Vec<u8>, EncryptedDnsError> {
        match timeout(self.inner.timeout, async {
            write_length_prefixed_frame_async(stream, query_bytes).await?;
            read_length_prefixed_frame_async(stream).await
        })
        .await
        {
            Ok(result) => result,
            Err(_) => Err(EncryptedDnsError::Request("DNS-over-TLS exchange timed out".to_string())),
        }
    }

    async fn take_dot_session(&self) -> Result<(DotTlsStream, bool), EncryptedDnsError> {
        match self.inner.connection_pool.take().await {
            Some(PooledConnection::Dot(stream)) => Ok((*stream, true)),
            Some(PooledConnection::DnsCrypt(stream)) => {
                self.inner.connection_pool.put(PooledConnection::DnsCrypt(stream)).await;
                self.connect_dot_session().await.map(|stream| (stream, false))
            }
            None => self.connect_dot_session().await.map(|stream| (stream, false)),
        }
    }

    async fn connect_dot_session(&self) -> Result<DotTlsStream, EncryptedDnsError> {
        let tcp_stream = self.connect_plain_tcp().await?;
        let tls_name = self.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| self.inner.endpoint.host.clone());
        let server_name = ServerName::try_from(tls_name).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
        let config = build_client_config(self.inner.tls_verifier.as_ref(), &self.inner.tls_roots);
        let connector = TlsConnector::from(config);
        match timeout(self.inner.timeout, connector.connect(server_name, tcp_stream)).await {
            Ok(Ok(stream)) => Ok(stream),
            Ok(Err(err)) => Err(EncryptedDnsError::Tls(err.to_string())),
            Err(_) => Err(EncryptedDnsError::Tls("TLS handshake timed out".to_string())),
        }
    }

    async fn exchange_dnscrypt(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
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
        decrypt_dnscrypt_response(&crypto_box, &response, &full_nonce[..DNSCRYPT_QUERY_NONCE_HALF])
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
        if let Ok(guard) = self.inner.dnscrypt_state.lock() {
            if let Some(cached) = guard.clone() {
                if cached.valid_from <= now && now <= cached.valid_until.saturating_sub(60) {
                    return Ok(cached);
                }
            }
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

        for answer in message.answers() {
            let Some(RData::TXT(txt)) = answer.data() else {
                continue;
            };
            let mut bytes = Vec::new();
            for chunk in txt.txt_data() {
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

    async fn connect_plain_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        match &self.inner.transport {
            EncryptedDnsTransport::Direct => self.connect_direct_tcp().await,
            EncryptedDnsTransport::Socks5 { host, port } => self.connect_socks5_tcp(host, *port).await,
        }
    }

    async fn connect_direct_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        let endpoint = &self.inner.endpoint;
        let ips = if let Some(health) = &self.inner.health {
            health.rank_bootstrap_ips(&endpoint.bootstrap_ips)
        } else {
            endpoint.bootstrap_ips.clone()
        };
        let mut last_error = None;
        for ip in ips {
            let address = std::net::SocketAddr::new(ip, endpoint.port);
            let started = std::time::Instant::now();
            match timeout(self.inner.timeout, TokioTcpStream::connect(address)).await {
                Ok(Ok(stream)) => {
                    let _ = stream.set_nodelay(true);
                    if let Some(health) = &self.inner.health {
                        let latency_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, true, latency_ms);
                    }
                    return Ok(stream);
                }
                Ok(Err(err)) => {
                    if let Some(health) = &self.inner.health {
                        let latency_ms = self.inner.timeout.as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, false, latency_ms);
                    }
                    last_error = Some(err.to_string());
                }
                Err(_) => {
                    if let Some(health) = &self.inner.health {
                        let latency_ms = self.inner.timeout.as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, false, latency_ms);
                    }
                    last_error = Some(format!("connect to {address} timed out"));
                }
            }
        }
        Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
    }

    async fn connect_socks5_tcp(&self, proxy_host: &str, proxy_port: u16) -> Result<TokioTcpStream, EncryptedDnsError> {
        let proxy_target = resolve_socket_addr(proxy_host, proxy_port)?;
        let mut proxy_stream = match timeout(self.inner.timeout, TokioTcpStream::connect(proxy_target)).await {
            Ok(Ok(stream)) => stream,
            Ok(Err(err)) => return Err(EncryptedDnsError::Socks5(err.to_string())),
            Err(_) => {
                return Err(EncryptedDnsError::Socks5("SOCKS5 connect timed out".to_string()));
            }
        };
        let _ = proxy_stream.set_nodelay(true);

        let host_bytes = self.inner.endpoint.host.as_bytes();
        if host_bytes.len() > u8::MAX as usize {
            return Err(EncryptedDnsError::Socks5("resolver host is too long".to_string()));
        }

        match timeout(self.inner.timeout, async {
            proxy_stream
                .write_all(&[0x05, 0x01, 0x00])
                .await
                .map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            let mut auth_reply = [0u8; 2];
            proxy_stream.read_exact(&mut auth_reply).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            if auth_reply != [0x05, 0x00] {
                return Err(EncryptedDnsError::Socks5(format!("unexpected auth reply: {auth_reply:?}")));
            }

            let mut request = Vec::with_capacity(host_bytes.len() + 7);
            request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
            request.extend_from_slice(host_bytes);
            request.extend_from_slice(&self.inner.endpoint.port.to_be_bytes());
            proxy_stream.write_all(&request).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;

            let mut header = [0u8; 4];
            proxy_stream.read_exact(&mut header).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            if header[1] != 0x00 {
                return Err(EncryptedDnsError::Socks5(format!("connect reply {:x}", header[1])));
            }
            consume_socks5_bind_address_async(&mut proxy_stream, header[3]).await?;
            Ok::<(), EncryptedDnsError>(())
        })
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(EncryptedDnsError::Socks5("SOCKS5 handshake timed out".to_string()));
            }
        }

        Ok(proxy_stream)
    }
}
