#![forbid(unsafe_code)]

use std::collections::VecDeque;
use std::future::Future;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::Duration;

use boring::x509::X509;
use sha2::{Digest, Sha224};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio_boring::SslStream;

mod stream;

pub use stream::TrojanStream;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrojanCommand {
    TcpConnect,
    UdpAssociate,
}

impl TrojanCommand {
    fn byte(self) -> u8 {
        match self {
            Self::TcpConnect => 0x01,
            Self::UdpAssociate => 0x03,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrojanAddr {
    Ipv4(Ipv4Addr),
    Ipv6(Ipv6Addr),
    Domain(String),
}

#[derive(Debug, thiserror::Error)]
pub enum TrojanError {
    #[error("domain name too long ({0} bytes, max 255)")]
    DomainTooLong(usize),
    #[error("UDP payload too long ({0} bytes, max 65535)")]
    PayloadTooLong(usize),
    #[error("invalid address type byte 0x{0:02x}")]
    InvalidAddressType(u8),
    #[error("Trojan UDP domain is not valid UTF-8")]
    InvalidDomainUtf8,
    #[error("invalid CRLF delimiter")]
    InvalidCrlf,
    #[error("truncated Trojan UDP packet while reading {0}")]
    Truncated(&'static str),
    #[error("Trojan UDP packet has {0} trailing bytes after payload")]
    TrailingBytes(usize),
    #[error("I/O error writing Trojan request: {0}")]
    Io(#[from] std::io::Error),
    #[error("TLS profile error: {0}")]
    TlsProfile(String),
    #[error("TLS configuration error: {0}")]
    TlsConfig(String),
    #[error("TLS handshake error: {0}")]
    TlsHandshake(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrojanUdpPacket {
    pub addr: TrojanAddr,
    pub port: u16,
    pub payload: Vec<u8>,
}

pub fn hash_password(password: &str) -> String {
    let mut hasher = Sha224::new();
    hasher.update(password.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn encode_addr(addr: &TrojanAddr, port: u16) -> Result<Vec<u8>, TrojanError> {
    let mut buf = Vec::new();
    match addr {
        TrojanAddr::Ipv4(ip) => {
            buf.push(0x01);
            buf.extend_from_slice(&ip.octets());
        }
        TrojanAddr::Ipv6(ip) => {
            buf.push(0x04);
            buf.extend_from_slice(&ip.octets());
        }
        TrojanAddr::Domain(name) => {
            let bytes = name.as_bytes();
            if bytes.len() > 255 {
                return Err(TrojanError::DomainTooLong(bytes.len()));
            }
            buf.push(0x03);
            buf.push(bytes.len() as u8);
            buf.extend_from_slice(bytes);
        }
    }
    buf.extend_from_slice(&port.to_be_bytes());
    Ok(buf)
}

pub fn build_request(
    password: &str,
    command: TrojanCommand,
    addr: &TrojanAddr,
    port: u16,
) -> Result<Vec<u8>, TrojanError> {
    build_request_frame(password, command, addr, port, &[])
}

pub fn build_request_frame(
    password: &str,
    command: TrojanCommand,
    addr: &TrojanAddr,
    port: u16,
    payload: &[u8],
) -> Result<Vec<u8>, TrojanError> {
    let hex_pw = hash_password(password);
    let addr_bytes = encode_addr(addr, port)?;

    let mut buf = Vec::with_capacity(56 + 2 + 1 + addr_bytes.len() + 2 + payload.len());
    buf.extend_from_slice(hex_pw.as_bytes());
    buf.extend_from_slice(b"\r\n");
    buf.push(command.byte());
    buf.extend_from_slice(&addr_bytes);
    buf.extend_from_slice(b"\r\n");
    buf.extend_from_slice(payload);
    Ok(buf)
}

pub const MAX_UDP_PAYLOAD: usize = 8192;

pub fn encode_udp_packet(addr: &TrojanAddr, port: u16, payload: &[u8]) -> Result<Vec<u8>, TrojanError> {
    let payload_len = u16::try_from(payload.len()).map_err(|_| TrojanError::PayloadTooLong(payload.len()))?;
    let addr_bytes = encode_addr(addr, port)?;
    let mut buf = Vec::with_capacity(addr_bytes.len() + 2 + 2 + payload.len());
    buf.extend_from_slice(&addr_bytes);
    buf.extend_from_slice(&payload_len.to_be_bytes());
    buf.extend_from_slice(b"\r\n");
    buf.extend_from_slice(payload);
    Ok(buf)
}

pub fn decode_udp_packet(packet: &[u8]) -> Result<TrojanUdpPacket, TrojanError> {
    let (packet_out, used) = decode_udp_prefix(packet)?;
    if used != packet.len() {
        return Err(TrojanError::TrailingBytes(packet.len() - used));
    }
    Ok(packet_out)
}

fn decode_udp_prefix(bytes: &[u8]) -> Result<(TrojanUdpPacket, usize), TrojanError> {
    let mut cursor = 0;
    let addr = decode_addr(bytes, &mut cursor)?;
    let port = read_u16(bytes, &mut cursor, "port")?;
    let payload_len = usize::from(read_u16(bytes, &mut cursor, "payload length")?);
    let crlf = read_exact(bytes, &mut cursor, 2, "CRLF")?;
    if crlf != b"\r\n" {
        return Err(TrojanError::InvalidCrlf);
    }
    let payload = read_exact(bytes, &mut cursor, payload_len, "payload")?.to_vec();
    Ok((TrojanUdpPacket { addr, port, payload }, cursor))
}

#[derive(Debug, Default)]
pub struct TrojanUdpReader {
    buffer: Vec<u8>,
}

impl TrojanUdpReader {
    /// # Cancel safety
    ///
    /// cancel-safe: bytes taken from the stream stay in the reader until a
    /// whole frame is decoded, so a cancelled call loses none of them.
    pub async fn read_packet<S>(&mut self, stream: &mut S) -> Result<TrojanUdpPacket, TrojanError>
    where
        S: AsyncRead + Unpin,
    {
        loop {
            match decode_udp_prefix(&self.buffer) {
                Ok((packet, used)) => {
                    self.buffer.drain(..used);
                    return Ok(packet);
                }
                Err(TrojanError::Truncated(_)) => {}
                Err(error) => return Err(error),
            }
            let mut chunk = [0_u8; 4096];
            let read = stream.read(&mut chunk).await?;
            if read == 0 {
                return Err(TrojanError::Io(io::Error::new(io::ErrorKind::UnexpectedEof, "Trojan UDP stream ended")));
            }
            self.buffer.extend_from_slice(&chunk[..read]);
        }
    }
}

fn decode_addr(packet: &[u8], cursor: &mut usize) -> Result<TrojanAddr, TrojanError> {
    let atyp = read_u8(packet, cursor, "address type")?;
    match atyp {
        0x01 => {
            let bytes = read_exact(packet, cursor, 4, "IPv4 address")?;
            Ok(TrojanAddr::Ipv4(Ipv4Addr::new(bytes[0], bytes[1], bytes[2], bytes[3])))
        }
        0x03 => {
            let len = usize::from(read_u8(packet, cursor, "domain length")?);
            let bytes = read_exact(packet, cursor, len, "domain address")?;
            let domain = std::str::from_utf8(bytes).map_err(|_| TrojanError::InvalidDomainUtf8)?;
            Ok(TrojanAddr::Domain(domain.to_owned()))
        }
        0x04 => {
            let bytes = read_exact(packet, cursor, 16, "IPv6 address")?;
            let mut octets = [0_u8; 16];
            octets.copy_from_slice(bytes);
            Ok(TrojanAddr::Ipv6(Ipv6Addr::from(octets)))
        }
        other => Err(TrojanError::InvalidAddressType(other)),
    }
}

fn read_u8(packet: &[u8], cursor: &mut usize, field: &'static str) -> Result<u8, TrojanError> {
    Ok(read_exact(packet, cursor, 1, field)?[0])
}

fn read_u16(packet: &[u8], cursor: &mut usize, field: &'static str) -> Result<u16, TrojanError> {
    let bytes = read_exact(packet, cursor, 2, field)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_exact<'a>(
    packet: &'a [u8],
    cursor: &mut usize,
    len: usize,
    field: &'static str,
) -> Result<&'a [u8], TrojanError> {
    let end = cursor.checked_add(len).ok_or(TrojanError::Truncated(field))?;
    let bytes = packet.get(*cursor..end).ok_or(TrojanError::Truncated(field))?;
    *cursor = end;
    Ok(bytes)
}

pub struct TrojanClient;

#[derive(Clone, PartialEq, Eq)]
pub struct TrojanClientConfig {
    pub server_host: String,
    pub server_port: u16,
    pub server_name: String,
    pub password: String,
    pub tls_fingerprint_profile: String,
    pub root_certificate_pem: Option<String>,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    pub outbound_bind_ip: Option<IpAddr>,
}

impl std::fmt::Debug for TrojanClientConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TrojanClientConfig")
            .field("server_host", &self.server_host)
            .field("server_port", &self.server_port)
            .field("server_name", &self.server_name)
            .field("password", &"<redacted>")
            .field("tls_fingerprint_profile", &self.tls_fingerprint_profile)
            .field("root_certificate_pem", &self.root_certificate_pem.as_ref().map(|_| "<redacted>"))
            .field("socket_protection", &self.socket_protection)
            .field("outbound_bind_ip", &self.outbound_bind_ip)
            .finish()
    }
}

/// # Cancel safety
///
/// cancel-safe: the future owns the socket being connected; dropping it closes it.
async fn connect_server_tcp(config: &TrojanClientConfig) -> io::Result<TcpStream> {
    let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
    connect_first_reachable(addrs, config.outbound_bind_ip, config.socket_protection).await
}

const PER_ADDRESS_CONNECT_TIMEOUT: Duration =
    if cfg!(test) { Duration::from_millis(500) } else { Duration::from_secs(8) };

/// # Cancel safety
///
/// cancel-safe: the future owns the socket being connected; dropping it closes it.
async fn connect_first_reachable(
    addrs: Vec<SocketAddr>,
    bind_ip: Option<IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<TcpStream> {
    try_each_address(addrs, bind_ip, PER_ADDRESS_CONNECT_TIMEOUT, |addr| connect_one(addr, bind_ip, socket_protection))
        .await
}

/// # Cancel safety
///
/// cancel-safe: holds no state besides the pending `connect` future, which is
/// dropped with this one.
async fn try_each_address<T, F, Fut>(
    addrs: Vec<SocketAddr>,
    bind_ip: Option<IpAddr>,
    per_address: Duration,
    mut connect: F,
) -> io::Result<T>
where
    F: FnMut(SocketAddr) -> Fut,
    Fut: Future<Output = io::Result<T>>,
{
    let mut addrs = interleave_address_families(addrs);
    addrs.retain(|addr| bind_ip.is_none_or(|ip| ip.is_ipv4() == addr.is_ipv4()));
    let mut last_error = io::Error::new(
        io::ErrorKind::AddrNotAvailable,
        match bind_ip {
            Some(_) => "no server address matches outbound bind IP family",
            None => "no address resolved for trojan server",
        },
    );
    let last_index = addrs.len().saturating_sub(1);
    for (index, server_addr) in addrs.into_iter().enumerate() {
        let attempt = connect(server_addr);
        let outcome = if index == last_index {
            attempt.await
        } else {
            tokio::time::timeout(per_address, attempt)
                .await
                .unwrap_or_else(|_| Err(io::Error::new(io::ErrorKind::TimedOut, "Trojan server connect timed out")))
        };
        match outcome {
            Ok(connected) => return Ok(connected),
            Err(error) => last_error = error,
        }
    }
    Err(last_error)
}

fn interleave_address_families(addrs: Vec<SocketAddr>) -> Vec<SocketAddr> {
    let first_is_ipv6 = addrs.first().is_some_and(SocketAddr::is_ipv6);
    let (mut preferred, mut other): (VecDeque<_>, VecDeque<_>) =
        addrs.into_iter().partition(|addr| addr.is_ipv6() == first_is_ipv6);
    let mut ordered = Vec::with_capacity(preferred.len() + other.len());
    while !preferred.is_empty() || !other.is_empty() {
        ordered.extend(preferred.pop_front());
        ordered.extend(other.pop_front());
    }
    ordered
}

/// # Cancel safety
///
/// cancel-safe: the future owns the socket being connected; dropping it closes it.
async fn connect_one(
    server_addr: SocketAddr,
    bind_ip: Option<IpAddr>,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<TcpStream> {
    let socket = match server_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    protect_outbound_socket(&socket, server_addr, socket_protection)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    let tcp = socket.connect(server_addr).await?;
    tcp.set_nodelay(true)?;
    Ok(tcp)
}

fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect Trojan outbound socket: {error}")))
}

impl TrojanClient {
    /// # Cancel safety
    ///
    /// cancel-safe: the future owns the connection; dropping it closes it.
    pub async fn connect_tcp(
        config: &TrojanClientConfig,
        target_addr: &TrojanAddr,
        target_port: u16,
        first_payload: &[u8],
    ) -> Result<TrojanStream<SslStream<TcpStream>>, TrojanError> {
        let tcp = connect_server_tcp(config).await?;
        connect_stream_over(config, tcp, target_addr, target_port, first_payload).await
    }

    /// # Cancel safety
    ///
    /// cancel-safe: the future owns the connection; dropping it closes it.
    pub async fn connect_tcp_over<S>(
        config: &TrojanClientConfig,
        transport: S,
        target_addr: &TrojanAddr,
        target_port: u16,
        first_payload: &[u8],
    ) -> Result<TrojanStream<SslStream<S>>, TrojanError>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        connect_stream_over(config, transport, target_addr, target_port, first_payload).await
    }

    /// # Cancel safety
    ///
    /// cancel-safe: the future owns the connection; dropping it closes it.
    pub async fn connect_udp_associate(
        config: &TrojanClientConfig,
    ) -> Result<TrojanStream<SslStream<TcpStream>>, TrojanError> {
        let tcp = connect_server_tcp(config).await?;
        let stream = tls_connect(config, tcp).await?;
        let header =
            build_request(&config.password, TrojanCommand::UdpAssociate, &TrojanAddr::Ipv4(Ipv4Addr::UNSPECIFIED), 0)?;
        Ok(TrojanStream::with_deferred_header(stream, header))
    }

    pub async fn write_request<S>(
        stream: &mut S,
        password: &str,
        command: TrojanCommand,
        addr: &TrojanAddr,
        port: u16,
    ) -> Result<(), TrojanError>
    where
        S: AsyncWrite + Unpin,
    {
        let header = build_request(password, command, addr, port)?;
        stream.write_all(&header).await?;
        Ok(())
    }
}

/// # Cancel safety:
///
/// NOT cancel-safe: a partial TLS handshake can remain on a borrowed transport;
/// discard the underlying transport if the future is cancelled.
async fn connect_stream_over<S>(
    config: &TrojanClientConfig,
    transport: S,
    target_addr: &TrojanAddr,
    target_port: u16,
    first_payload: &[u8],
) -> Result<TrojanStream<SslStream<S>>, TrojanError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    if first_payload.is_empty() {
        let stream = tls_connect(config, transport).await?;
        let header = build_request(&config.password, TrojanCommand::TcpConnect, target_addr, target_port)?;
        return Ok(TrojanStream::with_deferred_header(stream, header));
    }
    let stream = connect_with_request_over(
        config,
        transport,
        TrojanCommand::TcpConnect,
        target_addr,
        target_port,
        first_payload,
    )
    .await?;
    Ok(TrojanStream::header_sent(stream))
}

/// # Cancel safety:
///
/// NOT cancel-safe: a partial TLS handshake or request can remain on a borrowed
/// transport; discard the underlying transport if the future is cancelled.
async fn connect_with_request_over<S>(
    config: &TrojanClientConfig,
    transport: S,
    command: TrojanCommand,
    target_addr: &TrojanAddr,
    target_port: u16,
    first_payload: &[u8],
) -> Result<SslStream<S>, TrojanError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut stream = tls_connect(config, transport).await?;
    let frame = build_request_frame(&config.password, command, target_addr, target_port, first_payload)?;
    stream.write_all(&frame).await?;
    Ok(stream)
}

/// # Cancel safety:
///
/// NOT cancel-safe: a partial TLS handshake can remain on a borrowed transport;
/// discard the underlying transport if the future is cancelled.
async fn tls_connect<S>(config: &TrojanClientConfig, transport: S) -> Result<SslStream<S>, TrojanError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let server_name = ripdpi_tls_profiles::normalize_server_name(&config.server_name)
        .map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
    let mut builder = ripdpi_tls_profiles::configure_builder(&config.tls_fingerprint_profile)
        .map_err(|error| TrojanError::TlsProfile(error.to_string()))?;
    if let Some(root_pem) = &config.root_certificate_pem {
        let certs =
            X509::stack_from_pem(root_pem.as_bytes()).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
        if certs.is_empty() {
            return Err(TrojanError::TlsConfig("root certificate PEM contains no certificate".to_owned()));
        }
        for cert in certs {
            builder.cert_store_mut().add_cert(cert).map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
        }
    }

    let connector = builder.build();
    let config_ssl = connector.configure().map_err(|error| TrojanError::TlsConfig(error.to_string()))?;
    tokio_boring::connect(config_ssl, &server_name, transport)
        .await
        .map_err(|error| TrojanError::TlsHandshake(error.to_string()))
}

/// # Cancel safety
///
/// NOT cancel-safe: partial reads consume a frame prefix; callers must discard the stream on cancellation.
pub async fn read_udp_packet<S>(stream: &mut S) -> Result<TrojanUdpPacket, TrojanError>
where
    S: AsyncRead + Unpin,
{
    let atyp = read_stream_u8(stream).await?;
    let addr = match atyp {
        0x01 => {
            let mut octets = [0_u8; 4];
            stream.read_exact(&mut octets).await?;
            TrojanAddr::Ipv4(Ipv4Addr::from(octets))
        }
        0x03 => {
            let len = usize::from(read_stream_u8(stream).await?);
            let mut bytes = vec![0_u8; len];
            stream.read_exact(&mut bytes).await?;
            TrojanAddr::Domain(String::from_utf8(bytes).map_err(|_| TrojanError::InvalidDomainUtf8)?)
        }
        0x04 => {
            let mut octets = [0_u8; 16];
            stream.read_exact(&mut octets).await?;
            TrojanAddr::Ipv6(Ipv6Addr::from(octets))
        }
        other => return Err(TrojanError::InvalidAddressType(other)),
    };
    let mut port = [0_u8; 2];
    stream.read_exact(&mut port).await?;
    let mut len = [0_u8; 2];
    stream.read_exact(&mut len).await?;
    let payload_len = usize::from(u16::from_be_bytes(len));
    let mut crlf = [0_u8; 2];
    stream.read_exact(&mut crlf).await?;
    if crlf != *b"\r\n" {
        return Err(TrojanError::InvalidCrlf);
    }
    let mut payload = vec![0_u8; payload_len];
    stream.read_exact(&mut payload).await?;
    Ok(TrojanUdpPacket { addr, port: u16::from_be_bytes(port), payload })
}

async fn read_stream_u8<S>(stream: &mut S) -> Result<u8, TrojanError>
where
    S: AsyncRead + Unpin,
{
    let mut byte = [0_u8; 1];
    stream.read_exact(&mut byte).await?;
    Ok(byte[0])
}

impl From<IpAddr> for TrojanAddr {
    fn from(ip: IpAddr) -> Self {
        match ip {
            IpAddr::V4(v4) => Self::Ipv4(v4),
            IpAddr::V6(v6) => Self::Ipv6(v6),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};
    use tokio::io::duplex;

    const SPEC_PASSWORD: &str = "123456789";
    const SPEC_PASSWORD_SHA224_HEX: &str = "9b3e61bf29f17c75572fae2e86e17809a4513d07c8a18152acf34521";

    #[tokio::test]
    async fn a_hung_address_does_not_block_the_next_one() {
        let hung: SocketAddr = (Ipv4Addr::new(192, 0, 2, 1), 443).into();
        let live: SocketAddr = (Ipv4Addr::LOCALHOST, 443).into();
        let started = tokio::time::Instant::now();
        let reached = try_each_address(vec![hung, live], None, Duration::from_millis(100), |addr| async move {
            if addr == hung {
                std::future::pending::<()>().await;
            }
            Ok(addr)
        })
        .await
        .expect("the second address is tried after the first times out");
        assert_eq!(reached, live);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn address_families_are_interleaved() {
        let v6a: SocketAddr = "[2001:db8::1]:443".parse().expect("v6a");
        let v6b: SocketAddr = "[2001:db8::2]:443".parse().expect("v6b");
        let v4a: SocketAddr = "192.0.2.1:443".parse().expect("v4a");
        let v4b: SocketAddr = "192.0.2.2:443".parse().expect("v4b");
        assert_eq!(interleave_address_families(vec![v6a, v6b, v4a, v4b]), vec![v6a, v4a, v6b, v4b]);
        assert_eq!(interleave_address_families(vec![v4a, v6a, v6b]), vec![v4a, v6a, v6b]);
        assert!(interleave_address_families(Vec::new()).is_empty());
    }

    #[tokio::test]
    async fn connect_first_reachable_skips_a_refused_address() {
        let refused = {
            let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
            listener.local_addr().expect("addr")
        };
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind live");
        let live = listener.local_addr().expect("live addr");

        let tcp =
            connect_first_reachable(vec![refused, live], None, ripdpi_native_protect::SocketProtectionPolicy::Inactive)
                .await
                .expect("second address is reachable");
        assert_eq!(tcp.peer_addr().expect("peer"), live);

        let error =
            connect_first_reachable(vec![refused], None, ripdpi_native_protect::SocketProtectionPolicy::Inactive)
                .await
                .expect_err("all addresses refused");
        assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    }

    #[test]
    fn password_hash_matches_spec_vector() {
        assert_eq!(hash_password(SPEC_PASSWORD), SPEC_PASSWORD_SHA224_HEX);
    }

    #[test]
    fn password_hash_spec_vector_is_56_ascii_hex_chars() {
        let hash = hash_password(SPEC_PASSWORD);
        assert_eq!(hash.len(), 56);
        assert!(hash.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()));
    }

    #[test]
    fn client_config_debug_redacts_password_and_root_certificate() {
        let config = TrojanClientConfig {
            server_host: "relay.example".to_string(),
            server_port: 443,
            server_name: "cover.example".to_string(),
            password: "debug-trojan-secret".to_string(),
            tls_fingerprint_profile: "chrome".to_string(),
            root_certificate_pem: Some("debug-root-certificate-secret".to_string()),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };

        let rendered = format!("{config:?}");

        assert!(!rendered.contains("debug-trojan-secret"));
        assert!(!rendered.contains("debug-root-certificate-secret"));
        assert!(rendered.contains("<redacted>"));
    }

    #[test]
    fn connect_ipv4_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4)),
            443,
            b"GET / HTTP/1.1\r\n",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x01, 0x01, 1, 2, 3, 4, 0x01, 0xbb]);
        expected.extend_from_slice(b"\r\nGET / HTTP/1.1\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn connect_domain_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::TcpConnect,
            &TrojanAddr::Domain("example.com".to_owned()),
            8443,
            b"",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x01, 0x03, 11]);
        expected.extend_from_slice(b"example.com");
        expected.extend_from_slice(&[0x20, 0xfb]);
        expected.extend_from_slice(b"\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn udp_associate_ipv6_request_frame_matches_spec_golden() {
        let frame = build_request_frame(
            SPEC_PASSWORD,
            TrojanCommand::UdpAssociate,
            &TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST),
            53,
            b"",
        )
        .unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(SPEC_PASSWORD_SHA224_HEX.as_bytes());
        expected.extend_from_slice(b"\r\n");
        expected.extend_from_slice(&[0x03, 0x04]);
        expected.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        expected.extend_from_slice(&[0x00, 0x35]);
        expected.extend_from_slice(b"\r\n");
        assert_eq!(frame, expected);
    }

    #[test]
    fn udp_ipv4_packet_matches_spec_golden() {
        let packet = encode_udp_packet(&TrojanAddr::Ipv4(Ipv4Addr::new(8, 8, 8, 8)), 53, &[0xde, 0xad]).unwrap();

        assert_eq!(packet, vec![0x01, 8, 8, 8, 8, 0x00, 0x35, 0x00, 0x02, 0x0d, 0x0a, 0xde, 0xad]);
    }

    #[test]
    fn udp_domain_packet_matches_spec_golden() {
        let packet = encode_udp_packet(&TrojanAddr::Domain("dns.example".to_owned()), 5353, b"dns").unwrap();

        let mut expected = Vec::new();
        expected.extend_from_slice(&[0x03, 11]);
        expected.extend_from_slice(b"dns.example");
        expected.extend_from_slice(&[0x14, 0xe9, 0x00, 0x03, 0x0d, 0x0a]);
        expected.extend_from_slice(b"dns");
        assert_eq!(packet, expected);
    }

    #[test]
    fn udp_ipv6_packet_round_trips() {
        let packet = encode_udp_packet(&TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST), 443, b"payload").unwrap();

        let decoded = decode_udp_packet(&packet).unwrap();

        assert_eq!(decoded.addr, TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST));
        assert_eq!(decoded.port, 443);
        assert_eq!(decoded.payload, b"payload");
    }

    #[test]
    fn udp_packet_rejects_invalid_crlf() {
        let packet = vec![0x01, 1, 1, 1, 1, 0x00, 0x35, 0x00, 0x01, 0x0a, 0x0d, 0xff];

        let error = decode_udp_packet(&packet).unwrap_err();

        assert!(matches!(error, TrojanError::InvalidCrlf));
    }

    #[test]
    fn udp_packet_rejects_invalid_domain_utf8() {
        let packet = [0x03, 1, 0xff, 0, 53, 0, 0, 0x0d, 0x0a];
        assert!(decode_udp_packet(&packet).is_err());
    }

    /// # Cancel safety
    ///
    /// cancel-safe: the in-memory pipe is dropped with the test on cancellation.
    #[tokio::test]
    async fn udp_reader_survives_a_cancelled_read_mid_frame() {
        let first = encode_udp_packet(&TrojanAddr::Domain("dns.example".to_owned()), 53, b"answer").expect("frame");
        let second = encode_udp_packet(&TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST), 5353, b"next").expect("frame");
        let (mut server, mut client) = duplex(1024);
        let mut reader = TrojanUdpReader::default();

        server.write_all(&first[..7]).await.expect("frame prefix");
        {
            let mut read = std::pin::pin!(reader.read_packet(&mut client));
            let mut cx = std::task::Context::from_waker(std::task::Waker::noop());
            assert!(read.as_mut().poll(&mut cx).is_pending(), "test precondition: the read waits for the rest");
        }
        server.write_all(&first[7..]).await.expect("frame rest");
        server.write_all(&second).await.expect("second frame");

        let packet = reader.read_packet(&mut client).await.expect("first frame after cancellation");
        assert_eq!(
            packet,
            TrojanUdpPacket {
                addr: TrojanAddr::Domain("dns.example".to_owned()),
                port: 53,
                payload: b"answer".to_vec()
            }
        );
        let packet = reader.read_packet(&mut client).await.expect("second frame");
        assert_eq!(packet.payload, b"next");
    }

    /// # Cancel safety
    ///
    /// cancel-safe: the in-memory reader is dropped with the test on cancellation.
    #[tokio::test]
    async fn udp_stream_rejects_invalid_domain_utf8() {
        let (mut writer, mut reader) = duplex(64);
        writer.write_all(&[0x03, 1, 0xff, 0, 53, 0, 0, 0x0d, 0x0a]).await.unwrap();
        assert!(read_udp_packet(&mut reader).await.is_err());
    }

    #[test]
    fn udp_packet_rejects_payloads_over_u16_length() {
        let payload = vec![0; usize::from(u16::MAX) + 1];

        let error = encode_udp_packet(&TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST), 53, &payload).unwrap_err();

        assert!(matches!(error, TrojanError::PayloadTooLong(65536)));
    }

    #[test]
    fn encode_ipv4_target() {
        let addr = TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4));
        let bytes = encode_addr(&addr, 443).unwrap();
        assert_eq!(bytes[0], 0x01, "ATYP must be 0x01 for IPv4");
        assert_eq!(&bytes[1..5], &[1, 2, 3, 4]);
        assert_eq!(&bytes[5..7], &[0x01, 0xBB]);
        assert_eq!(bytes.len(), 7);
    }

    #[test]
    fn encode_ipv6_target() {
        let addr = TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST);
        let bytes = encode_addr(&addr, 80).unwrap();
        assert_eq!(bytes[0], 0x04, "ATYP must be 0x04 for IPv6");
        assert_eq!(bytes.len(), 19);
        assert_eq!(&bytes[17..19], &[0x00, 0x50]);
    }

    #[test]
    fn encode_domain_target() {
        let addr = TrojanAddr::Domain("example.com".to_owned());
        let bytes = encode_addr(&addr, 8080).unwrap();
        assert_eq!(bytes[0], 0x03, "ATYP must be 0x03 for domain");
        assert_eq!(bytes[1], 11, "length prefix must be 11 for 'example.com'");
        assert_eq!(&bytes[2..13], b"example.com");
        assert_eq!(&bytes[13..15], &[0x1F, 0x90]);
    }

    #[test]
    fn build_tcp_connect_request_ipv4() {
        let req = build_request(
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(127, 0, 0, 1)),
            443,
        )
        .unwrap();

        assert_eq!(req.len(), 68);
        assert_eq!(&req[56..58], b"\r\n");
        assert_eq!(req[58], 0x01);
        assert_eq!(req[59], 0x01);
        assert_eq!(&req[60..64], &[127, 0, 0, 1]);
        assert_eq!(&req[64..66], &[0x01, 0xBB]);
        assert_eq!(&req[66..68], b"\r\n");
    }

    #[test]
    fn build_udp_associate_request() {
        let req = build_request(
            "trojan-fixture-pw",
            TrojanCommand::UdpAssociate,
            &TrojanAddr::Ipv4(Ipv4Addr::new(10, 0, 0, 1)),
            53,
        )
        .unwrap();
        assert_eq!(req[58], 0x03);
    }

    #[test]
    fn build_domain_request_correct_length() {
        let domain = "example.com";
        let req =
            build_request("trojan-fixture-pw", TrojanCommand::TcpConnect, &TrojanAddr::Domain(domain.to_owned()), 443)
                .unwrap();
        assert_eq!(req.len(), 76);
        assert_eq!(req[58], 0x01);
        assert_eq!(req[59], 0x03);
        assert_eq!(req[60], 11);
    }

    #[tokio::test]
    async fn full_handshake_duplex_ipv4() {
        let (mut client_side, mut server_side) = duplex(4096);

        TrojanClient::write_request(
            &mut client_side,
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Ipv4(Ipv4Addr::new(93, 184, 216, 34)),
            443,
        )
        .await
        .unwrap();

        drop(client_side);

        let mut received = Vec::new();
        tokio::io::AsyncReadExt::read_to_end(&mut server_side, &mut received).await.unwrap();

        assert_eq!(received.len(), 68);
        let expected_hex = hash_password("trojan-fixture-pw");
        assert_eq!(&received[..56], expected_hex.as_bytes());
        assert_eq!(&received[56..58], b"\r\n");
        assert_eq!(received[58], 0x01);
    }

    #[tokio::test]
    async fn full_handshake_duplex_domain() {
        let (mut client_side, mut server_side) = duplex(4096);

        TrojanClient::write_request(
            &mut client_side,
            "trojan-fixture-pw",
            TrojanCommand::TcpConnect,
            &TrojanAddr::Domain("example.com".to_owned()),
            443,
        )
        .await
        .unwrap();

        drop(client_side);

        let mut received = Vec::new();
        tokio::io::AsyncReadExt::read_to_end(&mut server_side, &mut received).await.unwrap();

        assert_eq!(received.len(), 76);
        assert_eq!(&received[56..58], b"\r\n");
        assert_eq!(received[58], 0x01);
        assert_eq!(received[59], 0x03);
    }

    #[test]
    fn domain_too_long_returns_error() {
        let long_domain = "a".repeat(256);
        let result = encode_addr(&TrojanAddr::Domain(long_domain), 80);
        assert!(matches!(result, Err(TrojanError::DomainTooLong(256))));
    }

    #[test]
    fn from_ipaddr_v4() {
        let ip: IpAddr = "1.2.3.4".parse().unwrap();
        let addr = TrojanAddr::from(ip);
        assert_eq!(addr, TrojanAddr::Ipv4(Ipv4Addr::new(1, 2, 3, 4)));
    }

    #[test]
    fn from_ipaddr_v6() {
        let ip: IpAddr = "::1".parse().unwrap();
        let addr = TrojanAddr::from(ip);
        assert_eq!(addr, TrojanAddr::Ipv6(Ipv6Addr::LOCALHOST));
    }
}

#[cfg(test)]
mod protect_tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}

#[cfg(test)]
mod client_hello_parity_tests {
    use super::{TrojanAddr, TrojanClient, TrojanClientConfig};
    use std::io::Read;
    use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    const PROFILE: &str = "chrome_stable";
    const X25519MLKEM768: u16 = 0x11ec;
    const X25519: u16 = 0x001d;
    const SECP256R1: u16 = 0x0017;
    const SECP384R1: u16 = 0x0018;

    #[derive(Debug)]
    struct ParsedHello {
        ciphers: Vec<u16>,
        ext_types: Vec<u16>,
        groups: Vec<u16>,
        alpn: Vec<Vec<u8>>,
    }

    fn is_grease(value: u16) -> bool {
        let [hi, lo] = value.to_be_bytes();
        hi == lo && (hi & 0x0f) == 0x0a
    }

    fn strip_grease(values: &[u16]) -> Vec<u16> {
        values.iter().copied().filter(|value| !is_grease(*value)).collect()
    }

    fn sorted_no_grease(values: &[u16]) -> Vec<u16> {
        let mut out = strip_grease(values);
        out.sort_unstable();
        out
    }

    fn be16(bytes: &[u8], index: usize) -> u16 {
        u16::from_be_bytes([bytes[index], bytes[index + 1]])
    }

    fn parse_client_hello(record: &[u8]) -> ParsedHello {
        assert_eq!(record.first().copied(), Some(0x16), "expected a TLS handshake record");
        let payload = &record[5..];
        assert_eq!(payload.first().copied(), Some(0x01), "expected a ClientHello handshake message");
        let hs_len = (usize::from(payload[1]) << 16) | (usize::from(payload[2]) << 8) | usize::from(payload[3]);
        let body = &payload[4..4 + hs_len];

        let mut cursor = 34_usize;
        let session_id_len = usize::from(body[cursor]);
        cursor += 1 + session_id_len;

        let cipher_bytes = usize::from(be16(body, cursor));
        cursor += 2;
        let cipher_end = cursor + cipher_bytes;
        let mut ciphers = Vec::new();
        while cursor + 1 < cipher_end {
            ciphers.push(be16(body, cursor));
            cursor += 2;
        }
        cursor = cipher_end;

        let compression_len = usize::from(body[cursor]);
        cursor += 1 + compression_len;

        let ext_total = usize::from(be16(body, cursor));
        cursor += 2;
        let ext_end = cursor + ext_total;
        let mut ext_types = Vec::new();
        let mut groups = Vec::new();
        let mut alpn = Vec::new();
        while cursor + 4 <= ext_end {
            let ext_type = be16(body, cursor);
            let ext_len = usize::from(be16(body, cursor + 2));
            let data = &body[cursor + 4..cursor + 4 + ext_len];
            ext_types.push(ext_type);
            match ext_type {
                0x000a => groups = parse_u16_list(data),
                0x0010 => alpn = parse_alpn(data),
                _ => {}
            }
            cursor += 4 + ext_len;
        }

        ParsedHello { ciphers, ext_types, groups, alpn }
    }

    fn parse_u16_list(data: &[u8]) -> Vec<u16> {
        if data.len() < 2 {
            return Vec::new();
        }
        let list_len = usize::from(be16(data, 0));
        let end = 2 + list_len.min(data.len() - 2);
        let mut out = Vec::new();
        let mut index = 2;
        while index + 1 < end {
            out.push(be16(data, index));
            index += 2;
        }
        out
    }

    fn parse_alpn(data: &[u8]) -> Vec<Vec<u8>> {
        if data.len() < 2 {
            return Vec::new();
        }
        let list_len = usize::from(be16(data, 0));
        let end = 2 + list_len.min(data.len() - 2);
        let mut out = Vec::new();
        let mut index = 2;
        while index < end {
            let proto_len = usize::from(data[index]);
            index += 1;
            if index + proto_len > end {
                break;
            }
            out.push(data[index..index + proto_len].to_vec());
            index += proto_len;
        }
        out
    }

    fn spawn_capture() -> (SocketAddr, thread::JoinHandle<Vec<u8>>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind capture listener");
        let addr = listener.local_addr().expect("capture listener addr");
        let handle = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept capture connection");
            socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set capture read timeout");
            let mut header = [0_u8; 5];
            socket.read_exact(&mut header).expect("read TLS record header");
            let payload_len = usize::from(u16::from_be_bytes([header[3], header[4]]));
            let mut payload = vec![0_u8; payload_len];
            socket.read_exact(&mut payload).expect("read TLS record payload");
            let _ = socket.shutdown(Shutdown::Both);
            [header.to_vec(), payload].concat()
        });
        (addr, handle)
    }

    fn capture_sync(connect: impl FnOnce(SocketAddr)) -> ParsedHello {
        let (addr, handle) = spawn_capture();
        connect(addr);
        parse_client_hello(&handle.join().expect("capture thread join"))
    }

    async fn capture_trojan_client_hello() -> ParsedHello {
        let (addr, handle) = spawn_capture();
        let config = TrojanClientConfig {
            server_host: Ipv4Addr::LOCALHOST.to_string(),
            server_port: addr.port(),
            server_name: "trojan-parity.test".to_owned(),
            password: "parity-password".to_owned(),
            tls_fingerprint_profile: PROFILE.to_owned(),
            root_certificate_pem: None,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };
        let _ = TrojanClient::connect_tcp(&config, &TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST), 443, &[]).await;
        parse_client_hello(&handle.join().expect("capture thread join"))
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn client_hello_carries_selected_browser_profile_fingerprint() {
        let trojan = capture_trojan_client_hello().await;
        let profile = capture_sync(|addr| {
            let connector = ripdpi_tls_profiles::build_connector(PROFILE, false).expect("build profile connector");
            let stream = TcpStream::connect(addr).expect("connect profile capture");
            let _ = connector.connect("trojan-parity.test", stream);
        });
        let stock = capture_sync(|addr| {
            let connector = boring::ssl::SslConnector::builder(boring::ssl::SslMethod::tls())
                .expect("stock connector builder")
                .build();
            let stream = TcpStream::connect(addr).expect("connect stock capture");
            let _ = connector.connect("trojan-parity.test", stream);
        });

        assert_eq!(
            strip_grease(&trojan.groups),
            vec![X25519MLKEM768, X25519, SECP256R1, SECP384R1],
            "Trojan ClientHello must offer the chrome_stable supported groups"
        );
        assert_eq!(
            trojan.alpn,
            vec![b"h2".to_vec(), b"http/1.1".to_vec()],
            "Trojan ClientHello must offer the chrome_stable ALPN list"
        );
        assert!(trojan.ciphers.iter().any(|cipher| is_grease(*cipher)), "expected a GREASE cipher suite");
        assert!(trojan.ext_types.iter().any(|ext| is_grease(*ext)), "expected a GREASE extension");

        assert_eq!(
            strip_grease(&trojan.ciphers),
            strip_grease(&profile.ciphers),
            "Trojan cipher suites must match the chrome_stable profile connector"
        );
        assert_eq!(
            sorted_no_grease(&trojan.ext_types),
            sorted_no_grease(&profile.ext_types),
            "Trojan extension set must match the chrome_stable profile connector"
        );
        assert_eq!(
            strip_grease(&trojan.groups),
            strip_grease(&profile.groups),
            "Trojan supported groups must match the chrome_stable profile connector"
        );

        assert_ne!(
            sorted_no_grease(&stock.groups),
            sorted_no_grease(&trojan.groups),
            "stock BoringSSL groups must differ from the chrome_stable profile"
        );
    }
}
