use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use http::Request;
use rand::RngExt;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, RootCertStore, SignatureScheme};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::config::Config;
use crate::error::{HysteriaError, Result};
use crate::salamander::SalamanderUdpSocket;

const HYSTERIA_AUTH_STATUS: u16 = 233;

#[derive(Debug, Clone)]
pub(crate) struct ClientSocketSpec {
    pub(crate) ipv6: bool,
    pub(crate) bind_low_port: bool,
    pub(crate) salamander_key: Option<String>,
}

pub(crate) async fn authenticate_connection(config: &Config, connection: &quinn::Connection) -> Result<bool> {
    let (mut h3_connection, mut send_request) = h3::client::new(h3_quinn::Connection::new(connection.clone())).await?;
    let padding = generate_padding();
    let request = Request::builder()
        .method("POST")
        .uri("https://hysteria/auth")
        .header("Host", "hysteria")
        .header("Hysteria-Auth", &config.auth)
        .header("Hysteria-CC-RX", "0")
        .header("Hysteria-Padding", padding)
        .body(())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;

    let mut stream = send_request.send_request(request).await?;
    stream.finish().await?;
    let response = stream.recv_response().await?;
    tokio::spawn(async move {
        let _ = std::future::poll_fn(|cx| h3_connection.poll_close(cx)).await;
    });

    if response.status().as_u16() != HYSTERIA_AUTH_STATUS {
        return Err(HysteriaError::AuthFailed);
    }

    Ok(response.headers().get("Hysteria-UDP").and_then(|value| value.to_str().ok()) == Some("true"))
}

pub(crate) fn build_endpoint(
    config: &Config,
    tls_config: rustls::ClientConfig,
    socket_spec: ClientSocketSpec,
) -> Result<(quinn::Endpoint, std::net::UdpSocket)> {
    let socket = build_client_udp_socket(&socket_spec)?;
    let socket_clone = socket.try_clone()?;
    let mut endpoint = if let Some(key) = config.salamander_key.as_ref() {
        let wrapped = SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?;
        quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            Arc::new(wrapped),
            Arc::new(quinn::TokioRuntime),
        )?
    } else {
        quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?
    };

    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?,
    )));
    Ok((endpoint, socket_clone))
}

pub(crate) fn build_client_udp_socket(socket_spec: &ClientSocketSpec) -> io::Result<std::net::UdpSocket> {
    let bind_addr = if socket_spec.ipv6 {
        SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0))
    } else {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    if socket_spec.ipv6 {
        let _ = socket.set_only_v6(false);
    }
    if socket_spec.bind_low_port {
        try_bind_low_port(&socket, bind_addr.ip())?;
    } else {
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    Ok(socket.into())
}

fn try_bind_low_port(socket: &Socket, bind_ip: IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

pub(crate) fn rebind_endpoint(
    endpoint: &quinn::Endpoint,
    socket_spec: &ClientSocketSpec,
    socket: std::net::UdpSocket,
) -> io::Result<()> {
    if let Some(key) = socket_spec.salamander_key.as_ref() {
        endpoint.rebind_abstract(Arc::new(SalamanderUdpSocket::new(socket, key.as_bytes().to_vec())?))
    } else {
        endpoint.rebind(socket)
    }
}

pub(crate) fn build_tls_config(config: &Config) -> Result<rustls::ClientConfig> {
    let builder = rustls::ClientConfig::builder();
    let builder = if config.insecure {
        builder.dangerous().with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
    } else {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        builder.with_root_certificates(roots)
    };

    let mut tls_config = builder.with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    Ok(tls_config)
}

fn generate_padding() -> String {
    const PADDING_CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::rng();
    let padding_len = rng.random_range(8..40);
    let mut padding = String::with_capacity(padding_len);
    for _ in 0..padding_len {
        let index = rng.random_range(0..PADDING_CHARS.len());
        padding.push(PADDING_CHARS[index] as char);
    }
    padding
}

#[derive(Debug)]
struct NoCertificateVerification;

impl ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}
