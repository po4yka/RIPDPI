use std::collections::BTreeSet;
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[cfg(test)]
use std::io::{Read, Write};

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RData, RecordType};
use reqwest::{Client, Proxy};
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use rustls::{ClientConfig, RootCertStore};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use url::Url;

use crate::dnscrypt::dnscrypt_verifying_key;
use crate::health::HealthRegistry;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport};

pub(crate) const DNS_MESSAGE_MEDIA_TYPE: &str = "application/dns-message";
pub(crate) const DEFAULT_TIMEOUT: Duration = Duration::from_secs(4);

pub(crate) fn normalize_endpoint(
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

pub(crate) fn build_client_config(
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    extra_roots: &[CertificateDer<'static>],
) -> Arc<ClientConfig> {
    if let Some(verifier) = verifier {
        Arc::new(
            ClientConfig::builder()
                .dangerous()
                .with_custom_certificate_verifier(verifier.clone())
                .with_no_client_auth(),
        )
    } else {
        Arc::new(ClientConfig::builder().with_root_certificates(default_root_store(extra_roots)).with_no_client_auth())
    }
}

pub(crate) fn build_doh_client(
    endpoint: &EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: &[CertificateDer<'static>],
    health: Option<&HealthRegistry>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<Client, EncryptedDnsError> {
    let mut builder = Client::builder().timeout(timeout).connect_timeout(timeout);

    if let Some(verifier) = tls_verifier {
        let mut config = ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier.clone())
            .with_no_client_auth();
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        builder = builder.use_preconfigured_tls(Arc::new(config));
    } else {
        builder = builder.use_rustls_tls();
        for certificate in tls_roots {
            let reqwest_certificate = reqwest::Certificate::from_der(certificate.as_ref())
                .map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))?;
            builder = builder.add_root_certificate(reqwest_certificate);
        }
    }

    match transport {
        EncryptedDnsTransport::Direct => {
            let ips = if let Some(h) = health {
                h.rank_bootstrap_ips(&endpoint.bootstrap_ips)
            } else {
                endpoint.bootstrap_ips.clone()
            };
            let addresses = ips.iter().copied().map(|ip| SocketAddr::new(ip, endpoint.port)).collect::<Vec<_>>();
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

pub(crate) fn default_root_store(extra_roots: &[CertificateDer<'static>]) -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    for certificate in extra_roots {
        let _ = roots.add(certificate.clone());
    }
    roots
}

pub(crate) fn resolve_socket_addr(host: &str, port: u16) -> Result<SocketAddr, EncryptedDnsError> {
    std::net::ToSocketAddrs::to_socket_addrs(&(host, port))
        .map_err(|err| EncryptedDnsError::Request(err.to_string()))?
        .next()
        .ok_or_else(|| EncryptedDnsError::Request("no socket addresses resolved".to_string()))
}

#[cfg(test)]
pub(crate) fn write_length_prefixed_frame(stream: &mut impl Write, payload: &[u8]) -> Result<(), EncryptedDnsError> {
    let length = u16::try_from(payload.len())
        .map_err(|_| EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()))?;
    stream.write_all(&length.to_be_bytes()).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.write_all(payload).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.flush().map_err(|err| EncryptedDnsError::Request(err.to_string()))
}

pub(crate) async fn write_length_prefixed_frame_async(
    stream: &mut (impl AsyncWrite + Unpin),
    payload: &[u8],
) -> Result<(), EncryptedDnsError> {
    let length = u16::try_from(payload.len())
        .map_err(|_| EncryptedDnsError::Request("DNS payload is too large for TCP framing".to_string()))?;
    stream.write_all(&length.to_be_bytes()).await.map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.write_all(payload).await.map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    stream.flush().await.map_err(|err| EncryptedDnsError::Request(err.to_string()))
}

#[cfg(test)]
pub(crate) fn read_length_prefixed_frame(stream: &mut impl Read) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut length = [0u8; 2];
    stream.read_exact(&mut length).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    let frame_len = usize::from(u16::from_be_bytes(length));
    let mut payload = vec![0u8; frame_len];
    stream.read_exact(&mut payload).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    Ok(payload)
}

pub(crate) async fn read_length_prefixed_frame_async(
    stream: &mut (impl AsyncRead + Unpin),
) -> Result<Vec<u8>, EncryptedDnsError> {
    let mut length = [0u8; 2];
    stream.read_exact(&mut length).await.map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    let frame_len = usize::from(u16::from_be_bytes(length));
    let mut payload = vec![0u8; frame_len];
    stream.read_exact(&mut payload).await.map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
    Ok(payload)
}

pub(crate) async fn consume_socks5_bind_address_async(
    stream: &mut (impl AsyncRead + Unpin),
    atyp: u8,
) -> Result<(), EncryptedDnsError> {
    match atyp {
        0x01 => {
            let mut address = [0u8; 6];
            stream.read_exact(&mut address).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            Ok(())
        }
        0x04 => {
            let mut address = [0u8; 18];
            stream.read_exact(&mut address).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            Ok(())
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            let mut address = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut address).await.map_err(|err| EncryptedDnsError::Socks5(err.to_string()))?;
            Ok(())
        }
        value => Err(EncryptedDnsError::Socks5(format!("unsupported bind atyp: {value}"))),
    }
}

pub(crate) fn build_dns_query(name: &str, record_type: RecordType) -> Result<Vec<u8>, EncryptedDnsError> {
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

pub(crate) fn unix_time_secs() -> u32 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs().try_into().unwrap_or(u32::MAX)
}

pub(crate) fn format_error_chain(error: &(dyn std::error::Error + 'static)) -> String {
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
