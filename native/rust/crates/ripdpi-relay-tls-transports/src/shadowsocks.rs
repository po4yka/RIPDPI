use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use rand::RngExt;
use ripdpi_network_time::NetworkTimeProvider;
use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use ripdpi_shadowsocks::{
    Aead2022UdpPacketType, Aead2022UdpSession, Cipher, PresharedKey, SecretString, TcpStream as ShadowsocksTcpCodec,
    UdpPacket,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream, UdpSocket, lookup_host};

const BUFFER_SIZE: usize = 65_536;

#[derive(Clone)]
pub struct ShadowsocksSessionFactory {
    config: Arc<ShadowsocksClientConfig>,
}

pub struct ShadowsocksSession {
    config: Arc<ShadowsocksClientConfig>,
}

pub struct ShadowsocksUdpSession {
    socket: UdpSocket,
    config: Arc<ShadowsocksClientConfig>,
    codec: ShadowsocksUdpCodec,
    /// Whether this session has calibrated the shared network-time provider from
    /// a server packet's authenticated timestamp (done once per session).
    calibrated: bool,
}

#[derive(Clone)]
struct ShadowsocksClientConfig {
    server_host: String,
    server_port: u16,
    cipher: Cipher,
    password: String,
    outbound_bind_ip: Option<IpAddr>,
}

enum ShadowsocksUdpCodec {
    Legacy(UdpPacket),
    Aead2022(Aead2022UdpSession),
}

impl ShadowsocksSessionFactory {
    pub fn new(
        server_host: String,
        server_port: u16,
        method: String,
        password: String,
        outbound_bind_ip: Option<IpAddr>,
    ) -> io::Result<Self> {
        let cipher = Cipher::from_name(&method).map_err(invalid_input)?;
        if cipher.is_aead_2022() {
            PresharedKey::from_base64(cipher, &password).map_err(invalid_input)?;
        }
        Ok(Self {
            config: Arc::new(ShadowsocksClientConfig { server_host, server_port, cipher, password, outbound_bind_ip }),
        })
    }
}

impl RelaySession for ShadowsocksSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ShadowsocksUdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        connect_tcp(Arc::clone(&self.config), target).await
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let config = Arc::clone(&self.config);
        let socket = bind_udp(config.outbound_bind_ip).await?;
        let want_v4 = socket.local_addr()?.is_ipv4();
        let server_addr = lookup_host((config.server_host.as_str(), config.server_port))
            .await?
            .find(|addr| addr.is_ipv4() == want_v4)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::AddrNotAvailable, "no UDP server address matches socket family")
            })?;
        // VpnService.protect() invariant: protect the bound UDP carrier fd before
        // the first send so it bypasses the app's own TUN route. REL-1.
        crate::protect::protect_carrier_socket(&socket, server_addr)?;
        socket.connect(server_addr).await?;
        let codec = if config.cipher.is_aead_2022() {
            let psk = PresharedKey::from_base64(config.cipher, &config.password).map_err(invalid_input)?;
            let mut session_id = [0_u8; 8];
            rand::rng().fill(&mut session_id);
            ShadowsocksUdpCodec::Aead2022(Aead2022UdpSession::new(config.cipher, psk, session_id).map_err(to_io)?)
        } else {
            ShadowsocksUdpCodec::Legacy(UdpPacket::new(config.cipher, false))
        };
        Ok(ShadowsocksUdpSession { socket, config, codec, calibrated: false })
    }
}

impl RelaySessionFactory for ShadowsocksSessionFactory {
    type Session = ShadowsocksSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = Arc::clone(&self.config);
        Ok(Arc::new(ShadowsocksSession { config }))
    }
}

impl ShadowsocksUdpSession {
    pub async fn send_to(&mut self, target: &str, payload: &[u8]) -> io::Result<()> {
        let plain = encode_address(target, payload)?;
        let secret = SecretString::new(self.config.password.clone());
        let packet = match &mut self.codec {
            ShadowsocksUdpCodec::Legacy(codec) => codec.encrypt(&secret, &plain).map_err(to_io)?,
            ShadowsocksUdpCodec::Aead2022(codec) => {
                let now = NetworkTimeProvider::shared().now_unix_u64();
                codec.encrypt(Aead2022UdpPacketType::Client, now, &plain).map_err(to_io)?
            }
        };
        self.socket.send(&packet).await?;
        Ok(())
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        let mut buffer = vec![0_u8; BUFFER_SIZE];
        let read = self.socket.recv(&mut buffer).await?;
        let secret = SecretString::new(self.config.password.clone());
        let mut server_timestamp = None;
        let plain = match &mut self.codec {
            ShadowsocksUdpCodec::Legacy(codec) => codec.decrypt(&secret, &buffer[..read]).map_err(to_io)?,
            ShadowsocksUdpCodec::Aead2022(codec) => {
                let now = NetworkTimeProvider::shared().now_unix_u64();
                let packet = codec.decrypt(&buffer[..read], Aead2022UdpPacketType::Server, now).map_err(to_io)?;
                server_timestamp = Some(packet.timestamp);
                packet.payload
            }
        };
        // Calibrate the shared replay clock once per session from the server's
        // authenticated SIP022 timestamp (second granularity), so other
        // transports (and future sessions) derive freshness from network time
        // rather than the device clock.
        if let Some(timestamp) = server_timestamp
            && !self.calibrated
        {
            NetworkTimeProvider::shared().calibrate(i64::try_from(timestamp).unwrap_or(i64::MAX));
            self.calibrated = true;
        }
        let (target, payload) = decode_address(&plain)?;
        Ok((target, payload.to_vec()))
    }
}

pub async fn connect_shadowsocks_tcp(
    factory: &ShadowsocksSessionFactory,
    target: &str,
) -> io::Result<tokio::io::DuplexStream> {
    connect_tcp(Arc::clone(&factory.config), target).await
}

pub async fn connect_shadowsocks_tcp_over<S>(
    factory: &ShadowsocksSessionFactory,
    transport: S,
    target: &str,
) -> io::Result<tokio::io::DuplexStream>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    connect_tcp_over_transport(Arc::clone(&factory.config), transport, target).await
}

pub fn shadowsocks_proxy_target(factory: &ShadowsocksSessionFactory) -> String {
    format!("{}:{}", factory.config.server_host, factory.config.server_port)
}

async fn connect_tcp(config: Arc<ShadowsocksClientConfig>, target: &str) -> io::Result<tokio::io::DuplexStream> {
    let socket = connect_server(&config).await?;
    connect_tcp_over_transport(config, socket, target).await
}

async fn connect_tcp_over_transport<S>(
    config: Arc<ShadowsocksClientConfig>,
    mut transport: S,
    target: &str,
) -> io::Result<tokio::io::DuplexStream>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let secret = SecretString::new(config.password.clone());
    let (mut encrypt, salt) =
        ShadowsocksTcpCodec::new_encrypt(config.cipher, &secret, config.cipher.is_aead_2022()).map_err(to_io)?;
    transport.write_all(&salt).await?;
    let request = encode_address(target, &[])?;
    transport.write_all(&encrypt.encrypt_payload(&request).map_err(to_io)?).await?;

    let mut response_salt = vec![0_u8; config.cipher.salt_len()];
    transport.read_exact(&mut response_salt).await?;
    let mut decrypt =
        ShadowsocksTcpCodec::new_decrypt(config.cipher, &secret, &response_salt, config.cipher.is_aead_2022())
            .map_err(to_io)?;
    let (app_stream, relay_stream) = tokio::io::duplex(BUFFER_SIZE);
    let (mut app_read, mut app_write) = tokio::io::split(relay_stream);
    let (mut socket_read, mut socket_write) = tokio::io::split(transport);

    tokio::spawn(async move {
        let mut buffer = [0_u8; 4096];
        loop {
            let Ok(read) = app_read.read(&mut buffer).await else {
                return;
            };
            if read == 0 {
                return;
            }
            let Ok(encrypted) = encrypt.encrypt_payload(&buffer[..read]).map_err(to_io) else {
                return;
            };
            if socket_write.write_all(&encrypted).await.is_err() {
                return;
            }
        }
    });

    tokio::spawn(async move {
        let mut encrypted = Vec::new();
        let mut buffer = [0_u8; 4096];
        loop {
            while let Ok(Some((plain, consumed))) = decrypt.decrypt_chunk(&encrypted, 0).map_err(to_io) {
                encrypted.drain(..consumed);
                if app_write.write_all(&plain).await.is_err() {
                    return;
                }
            }
            let Ok(read) = socket_read.read(&mut buffer).await else {
                return;
            };
            if read == 0 {
                return;
            }
            encrypted.extend_from_slice(&buffer[..read]);
        }
    });

    Ok(app_stream)
}

async fn connect_server(config: &ShadowsocksClientConfig) -> io::Result<TcpStream> {
    let bind_ip = config.outbound_bind_ip;
    let mut addrs = lookup_host((config.server_host.as_str(), config.server_port)).await?;
    let server_addr = match bind_ip {
        Some(ip) => addrs.find(|addr| addr.is_ipv4() == ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs.next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no address resolved for shadowsocks server")
        })?,
    };
    let socket = match server_addr {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    // VpnService.protect() invariant: protect the carrier fd BEFORE bind/connect
    // so this non-loopback socket bypasses the app's own TUN route (otherwise it
    // loops back into the tunnel the VPN owns). Loopback-skip and fail-closed are
    // handled by the shared helper. REL-1.
    crate::protect::protect_carrier_socket(&socket, server_addr)?;
    if let Some(ip) = bind_ip {
        socket.bind(SocketAddr::new(ip, 0))?;
    }
    socket.connect(server_addr).await
}

async fn bind_udp(bind_ip: Option<IpAddr>) -> io::Result<UdpSocket> {
    match bind_ip {
        Some(ip) => UdpSocket::bind(SocketAddr::new(ip, 0)).await,
        None => UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).await,
    }
}

fn encode_address(target: &str, payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut out = Vec::with_capacity(payload.len() + 32);
    if let Ok(addr) = target.parse::<SocketAddr>() {
        match addr {
            SocketAddr::V4(addr) => {
                out.push(0x01);
                out.extend_from_slice(&addr.ip().octets());
                out.extend_from_slice(&addr.port().to_be_bytes());
            }
            SocketAddr::V6(addr) => {
                out.push(0x04);
                out.extend_from_slice(&addr.ip().octets());
                out.extend_from_slice(&addr.port().to_be_bytes());
            }
        }
    } else {
        let (host, port) = target.rsplit_once(':').ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}"))
        })?;
        let port = port.parse::<u16>().map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {target}"))
        })?;
        let len = u8::try_from(host.len())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Shadowsocks domain target exceeds 255 bytes"))?;
        out.push(0x03);
        out.push(len);
        out.extend_from_slice(host.as_bytes());
        out.extend_from_slice(&port.to_be_bytes());
    }
    out.extend_from_slice(payload);
    Ok(out)
}

fn decode_address(data: &[u8]) -> io::Result<(String, &[u8])> {
    if data.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "missing Shadowsocks address type"));
    }
    match data[0] {
        0x01 => {
            if data.len() < 7 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks IPv4 address"));
            }
            let ip = IpAddr::V4(Ipv4Addr::new(data[1], data[2], data[3], data[4]));
            let port = u16::from_be_bytes([data[5], data[6]]);
            Ok((SocketAddr::new(ip, port).to_string(), &data[7..]))
        }
        0x03 => {
            if data.len() < 2 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks domain address"));
            }
            let len = usize::from(data[1]);
            if data.len() < 2 + len + 2 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks domain payload"));
            }
            let host = std::str::from_utf8(&data[2..2 + len])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid Shadowsocks domain"))?;
            let port = u16::from_be_bytes([data[2 + len], data[2 + len + 1]]);
            Ok((format!("{host}:{port}"), &data[2 + len + 2..]))
        }
        0x04 => {
            if data.len() < 19 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks IPv6 address"));
            }
            let mut octets = [0_u8; 16];
            octets.copy_from_slice(&data[1..17]);
            let port = u16::from_be_bytes([data[17], data[18]]);
            Ok((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port).to_string(), &data[19..]))
        }
        atyp => {
            Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unsupported Shadowsocks address type {atyp:#x}")))
        }
    }
}

fn to_io(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

fn invalid_input(error: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, error.to_string())
}
