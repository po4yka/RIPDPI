use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crypto_box::aead::Aead;
use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use ed25519_dalek::{Signature as Ed25519Signature, Verifier, VerifyingKey};
use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RData, RecordType};
use once_cell::sync::Lazy;
use rand::RngCore;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use reqwest::{Client, Proxy};
use rustls::pki_types::{CertificateDer, ServerName};
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use thiserror::Error;
use tokio::runtime::{Builder, Runtime};
use url::Url;

static BLOCKING_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_current_thread()
        .enable_all()
        .thread_name("ripdpi-encrypted-dns-blocking")
        .build()
        .expect("blocking encrypted DNS runtime must initialize")
});

const DNS_MESSAGE_MEDIA_TYPE: &str = "application/dns-message";
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(4);

const DNSCRYPT_CERT_MAGIC: [u8; 4] = *b"DNSC";
const DNSCRYPT_ES_VERSION: u16 = 2;
const DNSCRYPT_RESPONSE_MAGIC: [u8; 8] = [0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38];
const DNSCRYPT_NONCE_SIZE: usize = 24;
const DNSCRYPT_QUERY_NONCE_HALF: usize = DNSCRYPT_NONCE_SIZE / 2;
const DNSCRYPT_CERT_SIZE: usize = 124;
const DNSCRYPT_PADDING_BLOCK_SIZE: usize = 64;
const DOT_DEFAULT_PORT: u16 = 853;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncryptedDnsProtocol {
    Doh,
    Dot,
    DnsCrypt,
}

impl EncryptedDnsProtocol {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Doh => "doh",
            Self::Dot => "dot",
            Self::DnsCrypt => "dnscrypt",
        }
    }

    fn default_port(self) -> u16 {
        match self {
            Self::Doh => 443,
            Self::Dot => DOT_DEFAULT_PORT,
            Self::DnsCrypt => 443,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsEndpoint {
    pub protocol: EncryptedDnsProtocol,
    pub resolver_id: Option<String>,
    pub host: String,
    pub port: u16,
    pub tls_server_name: Option<String>,
    pub bootstrap_ips: Vec<IpAddr>,
    pub doh_url: Option<String>,
    pub dnscrypt_provider_name: Option<String>,
    pub dnscrypt_public_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EncryptedDnsTransport {
    Direct,
    Socks5 { host: String, port: u16 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsExchangeSuccess {
    pub response_bytes: Vec<u8>,
    pub endpoint_label: String,
    pub latency_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncryptedDnsErrorKind {
    Bootstrap,
    Connect,
    Timeout,
    Tls,
    Http,
    DnsCrypt,
    Decode,
    NoAnswer,
}

#[derive(Debug, Clone)]
pub struct EncryptedDnsResolver {
    inner: Arc<ResolverInner>,
}

#[derive(Debug)]
struct ResolverInner {
    endpoint: EncryptedDnsEndpoint,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    doh_client: Option<Client>,
    tls_roots: Vec<CertificateDer<'static>>,
    dnscrypt_state: Mutex<Option<DnsCryptCachedCertificate>>,
}

#[derive(Debug, Clone)]
struct DnsCryptCachedCertificate {
    resolver_public_key: [u8; 32],
    client_magic: [u8; 8],
    valid_from: u32,
    valid_until: u32,
}

#[derive(Debug, Error)]
pub enum EncryptedDnsError {
    #[error("invalid encrypted DNS endpoint: {0}")]
    InvalidEndpoint(String),
    #[error("encrypted DNS endpoint must include a host")]
    MissingHost,
    #[error("bootstrap IPs are required for direct transport")]
    MissingBootstrapIps,
    #[error("DoH URL is required")]
    MissingDohUrl,
    #[error("invalid DoH URL: {0}")]
    InvalidUrl(String),
    #[error("invalid DNSCrypt public key: {0}")]
    InvalidDnsCryptPublicKey(String),
    #[error("invalid DNSCrypt provider name")]
    MissingDnsCryptProviderName,
    #[error("request build failed: {0}")]
    ClientBuild(String),
    #[error("request failed: {0}")]
    Request(String),
    #[error("DoH server returned HTTP {0}")]
    HttpStatus(reqwest::StatusCode),
    #[error("DNS response parse failed: {0}")]
    DnsParse(String),
    #[error("TLS handshake failed: {0}")]
    Tls(String),
    #[error("SOCKS5 negotiation failed: {0}")]
    Socks5(String),
    #[error("DNSCrypt certificate fetch failed: {0}")]
    DnsCryptCertificate(String),
    #[error("DNSCrypt certificate verification failed: {0}")]
    DnsCryptVerification(String),
    #[error("DNSCrypt response decryption failed: {0}")]
    DnsCryptDecrypt(String),
    #[error("task join failed: {0}")]
    TaskJoin(String),
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

    fn with_extra_tls_roots(
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

impl EncryptedDnsError {
    pub fn kind(&self) -> EncryptedDnsErrorKind {
        match self {
            EncryptedDnsError::MissingBootstrapIps => EncryptedDnsErrorKind::Bootstrap,
            EncryptedDnsError::InvalidEndpoint(_)
            | EncryptedDnsError::MissingHost
            | EncryptedDnsError::MissingDohUrl
            | EncryptedDnsError::InvalidUrl(_)
            | EncryptedDnsError::InvalidDnsCryptPublicKey(_)
            | EncryptedDnsError::MissingDnsCryptProviderName
            | EncryptedDnsError::DnsParse(_) => EncryptedDnsErrorKind::Decode,
            EncryptedDnsError::ClientBuild(_)
            | EncryptedDnsError::Request(_)
            | EncryptedDnsError::Socks5(_)
            | EncryptedDnsError::TaskJoin(_) => EncryptedDnsErrorKind::Connect,
            EncryptedDnsError::HttpStatus(_) => EncryptedDnsErrorKind::Http,
            EncryptedDnsError::Tls(_) => EncryptedDnsErrorKind::Tls,
            EncryptedDnsError::DnsCryptCertificate(_)
            | EncryptedDnsError::DnsCryptVerification(_)
            | EncryptedDnsError::DnsCryptDecrypt(_) => EncryptedDnsErrorKind::DnsCrypt,
        }
    }
}

fn normalize_endpoint(
    mut endpoint: EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
) -> Result<EncryptedDnsEndpoint, EncryptedDnsError> {
    if matches!(transport, EncryptedDnsTransport::Direct) && endpoint.bootstrap_ips.is_empty() {
        return Err(EncryptedDnsError::MissingBootstrapIps);
    }

    endpoint.host = endpoint.host.trim().to_string();
    if endpoint.port == 0 {
        endpoint.port = endpoint.protocol.default_port();
    }

    match endpoint.protocol {
        EncryptedDnsProtocol::Doh => {
            let doh_url = endpoint.doh_url.clone().ok_or(EncryptedDnsError::MissingDohUrl)?;
            let parsed = Url::parse(&doh_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
            let url_host = parsed.host_str().ok_or(EncryptedDnsError::MissingHost)?.to_string();
            if endpoint.host.is_empty() {
                endpoint.host = url_host.clone();
            }
            if endpoint.port == endpoint.protocol.default_port() {
                endpoint.port = parsed.port_or_known_default().unwrap_or(443);
            }
            if endpoint.tls_server_name.as_deref().unwrap_or_default().is_empty() {
                endpoint.tls_server_name = Some(url_host);
            }
        }
        EncryptedDnsProtocol::Dot => {
            if endpoint.host.is_empty() {
                return Err(EncryptedDnsError::MissingHost);
            }
            if endpoint.tls_server_name.as_deref().unwrap_or_default().is_empty() {
                endpoint.tls_server_name = Some(endpoint.host.clone());
            }
        }
        EncryptedDnsProtocol::DnsCrypt => {
            if endpoint.host.is_empty() {
                return Err(EncryptedDnsError::MissingHost);
            }
            if endpoint.dnscrypt_provider_name.as_deref().map(str::trim).filter(|value| !value.is_empty()).is_none() {
                return Err(EncryptedDnsError::MissingDnsCryptProviderName);
            }
            dnscrypt_verifying_key(&endpoint)?;
        }
    }

    Ok(endpoint)
}

fn build_doh_client(
    endpoint: &EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: &[CertificateDer<'static>],
) -> Result<Client, EncryptedDnsError> {
    let mut builder = Client::builder().use_rustls_tls().timeout(timeout).connect_timeout(timeout);

    for certificate in tls_roots {
        let reqwest_certificate = reqwest::Certificate::from_der(certificate.as_ref())
            .map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))?;
        builder = builder.add_root_certificate(reqwest_certificate);
    }

    match transport {
        EncryptedDnsTransport::Direct => {
            let addresses =
                endpoint.bootstrap_ips.iter().copied().map(|ip| SocketAddr::new(ip, endpoint.port)).collect::<Vec<_>>();
            builder = builder.resolve_to_addrs(endpoint.host.as_str(), &addresses);
        }
        EncryptedDnsTransport::Socks5 { host, port } => {
            let proxy = Proxy::all(format!("socks5h://{host}:{port}"))
                .map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))?;
            builder = builder.proxy(proxy);
        }
    }

    builder.build().map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))
}

fn default_root_store(extra_roots: &[CertificateDer<'static>]) -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    for certificate in extra_roots {
        let _ = roots.add(certificate.clone());
    }
    roots
}

fn complete_tls_handshake(stream: &mut StreamOwned<ClientConnection, TcpStream>) -> Result<(), EncryptedDnsError> {
    while stream.conn.is_handshaking() {
        stream.conn.complete_io(&mut stream.sock).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
    }
    Ok(())
}

fn resolve_socket_addr(host: &str, port: u16) -> Result<SocketAddr, EncryptedDnsError> {
    std::net::ToSocketAddrs::to_socket_addrs(&(host, port))
        .map_err(|err| EncryptedDnsError::Request(err.to_string()))?
        .next()
        .ok_or_else(|| EncryptedDnsError::Request("no socket addresses resolved".to_string()))
}

fn write_length_prefixed_frame(stream: &mut impl Write, payload: &[u8]) -> Result<(), EncryptedDnsError> {
    let length = u16::try_from(payload.len())
        .map_err(|_| EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()))?;
    stream.write_all(&length.to_be_bytes()).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.write_all(payload).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.flush().map_err(|err| EncryptedDnsError::Request(err.to_string()))
}

fn read_length_prefixed_frame(stream: &mut impl Read) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut length = [0u8; 2];
    stream.read_exact(&mut length).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    let frame_len = usize::from(u16::from_be_bytes(length));
    let mut payload = vec![0u8; frame_len];
    stream.read_exact(&mut payload).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    Ok(payload)
}

fn consume_socks5_bind_address(stream: &mut TcpStream, atyp: u8) -> Result<(), EncryptedDnsError> {
    match atyp {
        0x01 => {
            let mut address = [0u8; 6];
            stream.read_exact(&mut address).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))
        }
        0x04 => {
            let mut address = [0u8; 18];
            stream.read_exact(&mut address).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            let mut address = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut address).map_err(|err| EncryptedDnsError::Socks5(err.to_string()))
        }
        value => Err(EncryptedDnsError::Socks5(format!("unsupported bind atyp: {value}"))),
    }
}

fn dnscrypt_provider_name(endpoint: &EncryptedDnsEndpoint) -> Result<String, EncryptedDnsError> {
    endpoint
        .dnscrypt_provider_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
        .ok_or(EncryptedDnsError::MissingDnsCryptProviderName)
}

fn dnscrypt_verifying_key(endpoint: &EncryptedDnsEndpoint) -> Result<VerifyingKey, EncryptedDnsError> {
    let encoded = endpoint
        .dnscrypt_public_key
        .as_deref()
        .ok_or_else(|| EncryptedDnsError::InvalidDnsCryptPublicKey("missing public key".to_string()))?;
    let mut bytes = [0u8; 32];
    hex::decode_to_slice(encoded.trim(), &mut bytes)
        .map_err(|err| EncryptedDnsError::InvalidDnsCryptPublicKey(err.to_string()))?;
    VerifyingKey::from_bytes(&bytes).map_err(|err| EncryptedDnsError::InvalidDnsCryptPublicKey(err.to_string()))
}

fn parse_dnscrypt_certificate(
    bytes: &[u8],
    verifying_key: &VerifyingKey,
    _provider_name: &str,
) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
    if bytes.len() != DNSCRYPT_CERT_SIZE {
        return Err(EncryptedDnsError::DnsCryptCertificate(format!("unexpected certificate size {}", bytes.len())));
    }
    if bytes[..4] != DNSCRYPT_CERT_MAGIC {
        return Err(EncryptedDnsError::DnsCryptCertificate("unexpected cert magic".to_string()));
    }
    let es_version = u16::from_be_bytes([bytes[4], bytes[5]]);
    if es_version != DNSCRYPT_ES_VERSION {
        return Err(EncryptedDnsError::DnsCryptCertificate(format!("unsupported es_version {es_version}")));
    }

    let mut signature = [0u8; 64];
    signature.copy_from_slice(&bytes[8..72]);
    let signature = Ed25519Signature::from_bytes(&signature);
    let signed = &bytes[72..];
    verifying_key.verify(signed, &signature).map_err(|err| EncryptedDnsError::DnsCryptVerification(err.to_string()))?;

    let mut resolver_public_key = [0u8; 32];
    resolver_public_key.copy_from_slice(&bytes[72..104]);
    let mut client_magic = [0u8; 8];
    client_magic.copy_from_slice(&bytes[104..112]);
    let valid_from = u32::from_be_bytes([bytes[116], bytes[117], bytes[118], bytes[119]]);
    let valid_until = u32::from_be_bytes([bytes[120], bytes[121], bytes[122], bytes[123]]);

    Ok(DnsCryptCachedCertificate { resolver_public_key, client_magic, valid_from, valid_until })
}

fn decrypt_dnscrypt_response(
    crypto_box: &ChaChaBox,
    response: &[u8],
    expected_nonce_prefix: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    if response.len() <= 8 + DNSCRYPT_NONCE_SIZE {
        return Err(EncryptedDnsError::DnsCryptDecrypt("response too short".to_string()));
    }
    if response[..8] != DNSCRYPT_RESPONSE_MAGIC {
        return Err(EncryptedDnsError::DnsCryptDecrypt("unexpected response magic".to_string()));
    }
    let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
    nonce.copy_from_slice(&response[8..8 + DNSCRYPT_NONCE_SIZE]);
    if nonce[..DNSCRYPT_QUERY_NONCE_HALF] != *expected_nonce_prefix {
        return Err(EncryptedDnsError::DnsCryptDecrypt("nonce prefix mismatch".to_string()));
    }
    let plaintext = crypto_box
        .decrypt((&nonce).into(), &response[8 + DNSCRYPT_NONCE_SIZE..])
        .map_err(|err| EncryptedDnsError::DnsCryptDecrypt(err.to_string()))?;
    dnscrypt_unpad(&plaintext)
}

fn dnscrypt_pad(payload: &[u8]) -> Vec<u8> {
    let mut padded = Vec::with_capacity(
        ((payload.len() + 1 + DNSCRYPT_PADDING_BLOCK_SIZE - 1) / DNSCRYPT_PADDING_BLOCK_SIZE)
            * DNSCRYPT_PADDING_BLOCK_SIZE,
    );
    padded.extend_from_slice(payload);
    padded.push(0x80);
    while padded.len() % DNSCRYPT_PADDING_BLOCK_SIZE != 0 {
        padded.push(0x00);
    }
    padded
}

fn dnscrypt_unpad(payload: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
    let marker = payload
        .iter()
        .rposition(|byte| *byte != 0x00)
        .ok_or_else(|| EncryptedDnsError::DnsCryptDecrypt("missing padding marker".to_string()))?;
    if payload[marker] != 0x80 {
        return Err(EncryptedDnsError::DnsCryptDecrypt("invalid padding marker".to_string()));
    }
    Ok(payload[..marker].to_vec())
}

fn build_dns_query(name: &str, record_type: RecordType) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut message = Message::new();
    message
        .add_query(Query::query(
            Name::from_ascii(name).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?,
            record_type,
        ))
        .set_id(0x1234)
        .set_message_type(MessageType::Query)
        .set_op_code(OpCode::Query)
        .set_recursion_desired(true);
    message.to_vec().map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))
}

fn unix_time_secs() -> u32 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs().try_into().unwrap_or(u32::MAX)
}

fn format_error_chain(error: &(dyn std::error::Error + 'static)) -> String {
    let mut message = error.to_string();
    let mut current = error.source();
    while let Some(source) = current {
        message.push_str(": ");
        message.push_str(&source.to_string());
        current = source.source();
    }
    message
}

pub fn extract_ip_answers(packet: &[u8]) -> Result<Vec<String>, EncryptedDnsError> {
    let message = Message::from_vec(packet).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let mut answers = BTreeSet::new();
    for record in message.answers() {
        match record.data() {
            Some(RData::A(address)) => {
                answers.insert(IpAddr::V4(address.0).to_string());
            }
            Some(RData::AAAA(address)) => {
                answers.insert(IpAddr::V6(address.0).to_string());
            }
            _ => {}
        }
    }
    Ok(answers.into_iter().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    use ed25519_dalek::Signer;
    use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
    use hickory_proto::rr::rdata::{A, TXT};
    use hickory_proto::rr::{Name, RData, Record};
    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::{ServerConfig, ServerConnection};
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    #[derive(Clone)]
    struct DnsCryptTestServer {
        provider_public_key_hex: String,
        provider_name: String,
        certificate: DnsCryptCachedCertificate,
        resolver_secret: CryptoSecretKey,
    }

    fn build_query(name: &str) -> Vec<u8> {
        build_dns_query(name, RecordType::A).expect("query serializes")
    }

    fn build_response(query: &[u8], answer_ip: Ipv4Addr) -> Vec<u8> {
        let request = Message::from_vec(query).expect("query parses");
        let mut response = Message::new();
        response
            .set_id(request.id())
            .set_message_type(MessageType::Response)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(request.recursion_desired())
            .set_recursion_available(true)
            .set_response_code(ResponseCode::NoError);
        for query in request.queries() {
            response.add_query(query.clone());
            if query.query_type() == RecordType::A {
                response.add_answer(Record::from_rdata(query.name().clone(), 60, RData::A(A(answer_ip))));
            }
        }
        response.to_vec().expect("response serializes")
    }

    #[tokio::test]
    async fn doh_exchange_uses_direct_bootstrap_over_https() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 10);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let response_body = build_response(&query, answer_ip);
        let server_query = query.clone();
        let server_response = response_body.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, server_config, &server_query, &server_response);
        });

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange(&query).await.expect("DoH response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        server.join().expect("server joins");
    }

    #[tokio::test]
    async fn exchange_with_metadata_reports_endpoint_and_latency() {
        let query = build_query("fixture.test");
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let response_body = build_response(&query, Ipv4Addr::new(198, 18, 0, 42));
        let server_query = query.clone();
        let server_response = response_body.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, server_config, &server_query, &server_response);
        });

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let success = resolver.exchange_with_metadata(&query).await.expect("exchange metadata");
        assert_eq!(success.endpoint_label, format!("https://fixture.test:{port}/dns-query"));
        assert!(success.latency_ms <= 4_000);
        assert_eq!(extract_ip_answers(&success.response_bytes).expect("answers"), vec!["198.18.0.42".to_string()]);
        server.join().expect("server joins");
    }

    #[tokio::test]
    async fn doh_exchange_supports_socks_transport() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 10);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let response_body = build_response(&query, answer_ip);
        let server_query = query.clone();
        let server_response = response_body.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, server_config, &server_query, &server_response);
        });
        let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port, 1);

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: Vec::new(),
                doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange(&query).await.expect("DoH response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        proxy_handle.join().expect("proxy thread completes");
        server.join().expect("server thread completes");
    }

    #[test]
    fn dot_exchange_supports_direct_and_tls_validation() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 11);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let server_query = query.clone();
        let server_response = build_response(&query, answer_ip);
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_dot(stream, server_config, &server_query, &server_response);
        });

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Dot,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("DoT response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        server.join().expect("server thread completes");
    }

    #[test]
    fn dot_exchange_supports_socks_transport() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 12);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let server_query = query.clone();
        let server_response = build_response(&query, answer_ip);
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_dot(stream, server_config, &server_query, &server_response);
        });
        let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port, 1);

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Dot,
                resolver_id: Some("fixture".to_string()),
                host: "fixture.test".to_string(),
                port,
                tls_server_name: Some("fixture.test".to_string()),
                bootstrap_ips: Vec::new(),
                doh_url: None,
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("DoT response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        proxy_handle.join().expect("proxy thread completes");
        server.join().expect("server thread completes");
    }

    #[test]
    fn dnscrypt_exchange_supports_direct_transport() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 13);
        let server = DnsCryptTestServer::new("resolver.test");
        let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));

        let resolver = EncryptedDnsResolver::new(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::DnsCrypt,
                resolver_id: Some("fixture".to_string()),
                host: "resolver.test".to_string(),
                port,
                tls_server_name: None,
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: None,
                dnscrypt_provider_name: Some(server.provider_name.clone()),
                dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
            },
            EncryptedDnsTransport::Direct,
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        handle.join().expect("server thread completes");
    }

    #[test]
    fn dnscrypt_exchange_supports_socks_transport() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 14);
        let server = DnsCryptTestServer::new("resolver.test");
        let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));
        let (proxy_port, proxy_handle) = start_socks_proxy("resolver.test", port, 2);

        let resolver = EncryptedDnsResolver::new(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::DnsCrypt,
                resolver_id: Some("fixture".to_string()),
                host: "resolver.test".to_string(),
                port,
                tls_server_name: None,
                bootstrap_ips: Vec::new(),
                doh_url: None,
                dnscrypt_provider_name: Some(server.provider_name.clone()),
                dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
            },
            EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        proxy_handle.join().expect("proxy thread completes");
        handle.join().expect("server thread completes");
    }

    #[test]
    fn h2_only_doh_server_is_supported() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["localhost".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let mut server_config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config");
        server_config.alpn_protocols = vec![b"h2".to_vec()];
        let server_config = Arc::new(server_config);

        let expected_query = build_query("fixture.test");
        let expected_response = expected_query.clone();
        let server_query = expected_query.clone();
        let server_response = expected_response.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_h2_doh(stream, server_config, &server_query, &server_response);
        });

        let resolver = EncryptedDnsResolver::with_extra_tls_roots(
            EncryptedDnsEndpoint {
                protocol: EncryptedDnsProtocol::Doh,
                resolver_id: Some("fixture".to_string()),
                host: "localhost".to_string(),
                port,
                tls_server_name: Some("localhost".to_string()),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
                doh_url: Some(format!("https://localhost:{port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            },
            EncryptedDnsTransport::Direct,
            DEFAULT_TIMEOUT,
            vec![certificate_der],
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&expected_query).expect("blocking exchange");
        assert_eq!(response, expected_response);
        server.join().expect("server thread completes");
    }

    #[test]
    fn error_kind_maps_common_failures() {
        assert_eq!(EncryptedDnsError::MissingBootstrapIps.kind(), EncryptedDnsErrorKind::Bootstrap);
        assert_eq!(EncryptedDnsError::Tls("handshake failed".to_string()).kind(), EncryptedDnsErrorKind::Tls,);
        assert_eq!(EncryptedDnsError::DnsCryptDecrypt("bad nonce".to_string()).kind(), EncryptedDnsErrorKind::DnsCrypt,);
        assert_eq!(EncryptedDnsError::DnsParse("bad packet".to_string()).kind(), EncryptedDnsErrorKind::Decode,);
    }

    impl DnsCryptTestServer {
        fn new(provider_suffix: &str) -> Self {
            use ed25519_dalek::{SigningKey, VerifyingKey};

            let provider_secret = SigningKey::from_bytes(&[7u8; 32]);
            let provider_public = VerifyingKey::from(&provider_secret);
            let resolver_secret = CryptoSecretKey::from([9u8; 32]);
            let resolver_public = resolver_secret.public_key();
            let valid_from = unix_time_secs().saturating_sub(60);
            let valid_until = valid_from.saturating_add(86_400);
            let mut client_magic = [0u8; 8];
            client_magic.copy_from_slice(&resolver_public.as_bytes()[..8]);

            let mut inner = [0u8; 52];
            inner[..32].copy_from_slice(resolver_public.as_bytes());
            inner[32..40].copy_from_slice(&client_magic);
            inner[40..44].copy_from_slice(&1u32.to_be_bytes());
            inner[44..48].copy_from_slice(&valid_from.to_be_bytes());
            inner[48..52].copy_from_slice(&valid_until.to_be_bytes());
            let signature = provider_secret.sign(&inner).to_bytes();

            let mut cert_bytes = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
            cert_bytes.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
            cert_bytes.extend_from_slice(&DNSCRYPT_ES_VERSION.to_be_bytes());
            cert_bytes.extend_from_slice(&0u16.to_be_bytes());
            cert_bytes.extend_from_slice(&signature);
            cert_bytes.extend_from_slice(&inner);
            let certificate = parse_dnscrypt_certificate(
                &cert_bytes,
                &provider_public,
                &format!("2.dnscrypt-cert.{provider_suffix}"),
            )
            .expect("certificate parses");

            Self {
                provider_public_key_hex: hex::encode(provider_public.as_bytes()),
                provider_name: format!("2.dnscrypt-cert.{provider_suffix}"),
                certificate,
                resolver_secret,
            }
        }

        fn certificate_bytes(&self) -> Vec<u8> {
            let mut inner = [0u8; 52];
            inner[..32].copy_from_slice(&self.certificate.resolver_public_key);
            inner[32..40].copy_from_slice(&self.certificate.client_magic);
            inner[40..44].copy_from_slice(&1u32.to_be_bytes());
            inner[44..48].copy_from_slice(&self.certificate.valid_from.to_be_bytes());
            inner[48..52].copy_from_slice(&self.certificate.valid_until.to_be_bytes());
            let signing = ed25519_dalek::SigningKey::from_bytes(&[7u8; 32]);
            let signature = signing.sign(&inner).to_bytes();
            let mut cert_bytes = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
            cert_bytes.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
            cert_bytes.extend_from_slice(&DNSCRYPT_ES_VERSION.to_be_bytes());
            cert_bytes.extend_from_slice(&0u16.to_be_bytes());
            cert_bytes.extend_from_slice(&signature);
            cert_bytes.extend_from_slice(&inner);
            cert_bytes
        }
    }

    fn start_dnscrypt_server(server: DnsCryptTestServer, response_packet: Vec<u8>) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
        let port = listener.local_addr().expect("dnscrypt local addr").port();
        let handle = thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().expect("dnscrypt accept");
                let packet = read_length_prefixed_frame(&mut stream).expect("dnscrypt tcp read");
                if let Ok(message) = Message::from_vec(&packet) {
                    let is_txt_cert_query =
                        message.query().map(|query| query.query_type() == RecordType::TXT).unwrap_or(false);
                    if is_txt_cert_query {
                        let response =
                            build_dnscrypt_cert_response(&packet, &server.provider_name, &server.certificate_bytes());
                        write_length_prefixed_frame(&mut stream, &response).expect("dnscrypt cert write");
                        continue;
                    }
                }

                let mut client_public = [0u8; 32];
                client_public.copy_from_slice(&packet[8..40]);
                let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
                nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
                let crypto_box = ChaChaBox::new(&CryptoPublicKey::from(client_public), &server.resolver_secret);
                let decrypted = crypto_box.decrypt((&nonce).into(), &packet[52..]).expect("dnscrypt request decrypt");
                let query = dnscrypt_unpad(&decrypted).expect("dnscrypt request unpad");
                assert_eq!(query, build_query("fixture.test"));

                let mut response_nonce = nonce;
                response_nonce[DNSCRYPT_QUERY_NONCE_HALF..].fill(0x11);
                let ciphertext = crypto_box
                    .encrypt((&response_nonce).into(), dnscrypt_pad(&response_packet).as_slice())
                    .expect("dnscrypt response encrypt");
                let mut wrapped = Vec::with_capacity(8 + DNSCRYPT_NONCE_SIZE + ciphertext.len());
                wrapped.extend_from_slice(&DNSCRYPT_RESPONSE_MAGIC);
                wrapped.extend_from_slice(&response_nonce);
                wrapped.extend_from_slice(&ciphertext);
                write_length_prefixed_frame(&mut stream, &wrapped).expect("dnscrypt response write");
            }
        });
        (port, handle)
    }

    fn build_dnscrypt_cert_response(query: &[u8], provider_name: &str, cert_bytes: &[u8]) -> Vec<u8> {
        let request = Message::from_vec(query).expect("cert query parses");
        let mut response = Message::new();
        response
            .set_id(request.id())
            .set_message_type(MessageType::Response)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(request.recursion_desired())
            .set_recursion_available(true)
            .set_response_code(ResponseCode::NoError)
            .add_query(Query::query(Name::from_ascii(provider_name).expect("provider name"), RecordType::TXT));
        response.add_answer(Record::from_rdata(
            Name::from_ascii(provider_name).expect("provider name"),
            600,
            RData::TXT(TXT::from_bytes(vec![cert_bytes])),
        ));
        response.to_vec().expect("cert response encodes")
    }

    fn serve_dot(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
        let connection = ServerConnection::new(config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
        }
        let query = read_length_prefixed_frame(&mut tls_stream).expect("read DoT query");
        assert_eq!(query, expected_query);
        write_length_prefixed_frame(&mut tls_stream, response_body).expect("write DoT response");
    }

    fn serve_https_doh(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
        let connection = ServerConnection::new(config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
        }

        let (request_line, body) = read_http_request(&mut tls_stream);
        assert_eq!(request_line, "POST /dns-query HTTP/1.1");
        assert_eq!(body, expected_query);

        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            response_body.len()
        );
        tls_stream.write_all(response.as_bytes()).expect("write headers");
        tls_stream.write_all(response_body).expect("write body");
        tls_stream.flush().expect("flush response");
    }

    fn read_http_request(stream: &mut impl Read) -> (String, Vec<u8>) {
        let mut raw = Vec::new();
        let mut chunk = [0u8; 1];
        while !raw.windows(4).any(|window| window == b"\r\n\r\n") {
            stream.read_exact(&mut chunk).expect("read request");
            raw.push(chunk[0]);
        }
        let request = String::from_utf8_lossy(&raw).into_owned();
        let request_line = request.lines().next().expect("request line").to_string();
        let content_length = request
            .lines()
            .find_map(|line| {
                let (name, value) = line.split_once(':')?;
                name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
            })
            .unwrap_or(0);
        let mut body = vec![0u8; content_length];
        if content_length > 0 {
            stream.read_exact(&mut body).expect("read body");
        }
        (request_line, body)
    }

    fn start_socks_proxy(
        expected_host: &str,
        target_port: u16,
        expected_connections: usize,
    ) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("proxy listener");
        let port = listener.local_addr().expect("proxy addr").port();
        let expected_host = expected_host.to_string();
        let handle = thread::spawn(move || {
            for _ in 0..expected_connections {
                let (mut client, _) = listener.accept().expect("proxy accept");

                let mut greeting = [0u8; 3];
                client.read_exact(&mut greeting).expect("proxy greeting");
                assert_eq!(greeting, [0x05, 0x01, 0x00]);
                client.write_all(&[0x05, 0x00]).expect("proxy greeting reply");

                let mut header = [0u8; 4];
                client.read_exact(&mut header).expect("proxy request header");
                assert_eq!(&header[..3], &[0x05, 0x01, 0x00]);
                let (host, port) = read_socks_target(&mut client, header[3]);
                assert_eq!(host, expected_host);
                assert_eq!(port, target_port);

                let upstream = TcpStream::connect((Ipv4Addr::LOCALHOST, target_port)).expect("proxy upstream");
                let [p1, p2] = target_port.to_be_bytes();
                client.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p1, p2]).expect("proxy success reply");
                relay_proxy_streams(client, upstream);
            }
        });
        (port, handle)
    }

    fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> (String, u16) {
        match atyp {
            0x01 => {
                let mut host = [0u8; 4];
                let mut port = [0u8; 2];
                stream.read_exact(&mut host).expect("IPv4 host");
                stream.read_exact(&mut port).expect("IPv4 port");
                (IpAddr::V4(Ipv4Addr::from(host)).to_string(), u16::from_be_bytes(port))
            }
            0x03 => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len).expect("domain length");
                let mut host = vec![0u8; len[0] as usize];
                let mut port = [0u8; 2];
                stream.read_exact(&mut host).expect("domain host");
                stream.read_exact(&mut port).expect("domain port");
                (String::from_utf8(host).expect("valid domain"), u16::from_be_bytes(port))
            }
            other => panic!("unexpected SOCKS address type: {other}"),
        }
    }

    fn relay_proxy_streams(client: TcpStream, upstream: TcpStream) {
        let mut client_reader = client.try_clone().expect("client clone");
        let mut client_writer = client;
        let mut upstream_reader = upstream.try_clone().expect("upstream clone");
        let mut upstream_writer = upstream;
        let to_upstream = thread::spawn(move || {
            let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
        });
        let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
        to_upstream.join().expect("relay join");
    }

    fn serve_h2_doh(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
        let connection = ServerConnection::new(config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
        }
        assert_eq!(tls_stream.conn.alpn_protocol(), Some(b"h2".as_slice()));

        let mut preface = [0u8; 24];
        tls_stream.read_exact(&mut preface).expect("h2 preface");
        assert_eq!(&preface, b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

        let mut frames = Vec::new();
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while std::time::Instant::now() < deadline {
            let mut header = [0u8; 9];
            tls_stream.read_exact(&mut header).expect("frame header");
            let length = ((header[0] as usize) << 16) | ((header[1] as usize) << 8) | header[2] as usize;
            let frame_type = header[3];
            let flags = header[4];
            let stream_id = u32::from_be_bytes([header[5] & 0x7f, header[6], header[7], header[8]]);
            let mut payload = vec![0u8; length];
            tls_stream.read_exact(&mut payload).expect("frame payload");
            frames.push((frame_type, flags, stream_id, payload.clone()));
            if frame_type == 0x0 && flags & 0x1 == 0x1 && stream_id == 1 {
                break;
            }
        }

        let mut body = Vec::new();
        for (frame_type, _, stream_id, payload) in &frames {
            if *frame_type == 0x0 && *stream_id == 1 {
                body.extend_from_slice(payload);
            }
        }
        assert_eq!(body, expected_query);

        let settings_ack = [0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00];
        let headers_frame = [0x00, 0x00, 0x01, 0x01, 0x04, 0x00, 0x00, 0x00, 0x01, 0x88];
        let mut data_frame = vec![
            ((response_body.len() >> 16) & 0xff) as u8,
            ((response_body.len() >> 8) & 0xff) as u8,
            (response_body.len() & 0xff) as u8,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
        ];
        data_frame.extend_from_slice(response_body);
        tls_stream.write_all(&settings_ack).expect("write settings ack");
        tls_stream.write_all(&headers_frame).expect("write headers frame");
        tls_stream.write_all(&data_frame).expect("write data frame");
        tls_stream.flush().expect("flush h2 response");
    }
}
