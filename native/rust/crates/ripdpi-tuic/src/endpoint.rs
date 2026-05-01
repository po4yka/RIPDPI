use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::sync::Once;

use quinn::congestion::{BbrConfig, CubicConfig, NewRenoConfig};
use quinn::{ClientConfig, Endpoint, TransportConfig, VarInt};
use rustls::pki_types::CertificateDer;
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::config::Config;

const MAX_CONCURRENT_STREAMS: u32 = 512;
static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Debug, Clone, Copy)]
pub(crate) struct ClientSocketSpec {
    pub(crate) ipv6: bool,
    pub(crate) bind_low_port: bool,
}

pub(crate) fn build_endpoint(
    config: &Config,
    tls_config: RustlsClientConfig,
    socket_spec: ClientSocketSpec,
) -> io::Result<(Endpoint, std::net::UdpSocket)> {
    let mut transport = TransportConfig::default();
    transport.max_concurrent_bidi_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    transport.max_concurrent_uni_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    transport.max_idle_timeout(None);
    match config.congestion_control.trim().to_ascii_lowercase().as_str() {
        "cubic" => transport.congestion_controller_factory(Arc::new(CubicConfig::default())),
        "new_reno" | "newreno" => transport.congestion_controller_factory(Arc::new(NewRenoConfig::default())),
        _ => transport.congestion_controller_factory(Arc::new(BbrConfig::default())),
    };

    let mut client_config = ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config).map_err(io::Error::other)?,
    ));
    client_config.transport_config(Arc::new(transport));

    let socket = build_client_udp_socket(socket_spec)?;
    let socket_clone = socket.try_clone()?;
    let mut endpoint = Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime))?;
    endpoint.set_default_client_config(client_config);
    Ok((endpoint, socket_clone))
}

pub(crate) fn build_client_udp_socket(socket_spec: ClientSocketSpec) -> io::Result<std::net::UdpSocket> {
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

fn try_bind_low_port(socket: &Socket, bind_ip: std::net::IpAddr) -> io::Result<()> {
    for port in [2048u16, 2053, 2080, 2443, 3000, 3074, 4096] {
        let addr = SocketAddr::new(bind_ip, port);
        if socket.bind(&SockAddr::from(addr)).is_ok() {
            return Ok(());
        }
    }
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))
}

pub(crate) fn build_tls_config(
    enable_early_data: bool,
    additional_roots: Option<Vec<CertificateDer<'static>>>,
) -> io::Result<RustlsClientConfig> {
    ensure_crypto_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(certificates) = additional_roots {
        for certificate in certificates {
            roots.add(certificate).map_err(io::Error::other)?;
        }
    }

    let mut tls_config = RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    tls_config.enable_early_data = enable_early_data;
    Ok(tls_config)
}

pub(crate) fn ensure_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

pub(crate) async fn establish_connection(
    endpoint: &Endpoint,
    config: &Config,
    server_addr: SocketAddr,
) -> io::Result<quinn::Connection> {
    let connecting = endpoint.connect(server_addr, &config.server_name).map_err(io::Error::other)?;
    if config.zero_rtt {
        match connecting.into_0rtt() {
            Ok((connection, accepted)) => {
                let _ = accepted.await;
                Ok(connection)
            }
            Err(connecting) => Ok(connecting.await.map_err(io::Error::other)?),
        }
    } else {
        Ok(connecting.await.map_err(io::Error::other)?)
    }
}

pub(crate) fn validate_config(config: &Config) -> io::Result<()> {
    if config.server.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server"));
    }
    if config.server_port <= 0 || config.server_port > i32::from(u16::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid TUIC server port"));
    }
    if config.server_name.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server name"));
    }
    if config.uuid.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC UUID"));
    }
    if config.password.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC password"));
    }
    Ok(())
}

pub(crate) fn resolve_server_addr(server: &str, port: i32) -> io::Result<SocketAddr> {
    (server, port as u16)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve TUIC server"))
}
