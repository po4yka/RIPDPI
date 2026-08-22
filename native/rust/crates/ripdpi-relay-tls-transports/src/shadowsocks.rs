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
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

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
    receive_buffer: Vec<u8>,
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
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
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
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    ) -> io::Result<Self> {
        let cipher = Cipher::from_name(&method).map_err(invalid_input)?;
        if cipher.is_aead_2022() {
            PresharedKey::from_base64(cipher, &password).map_err(invalid_input)?;
        }
        Ok(Self {
            config: Arc::new(ShadowsocksClientConfig {
                server_host,
                server_port,
                cipher,
                password,
                outbound_bind_ip,
                socket_protection,
            }),
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
        let server_addr = config
            .socket_protection
            .resolve_host(&config.server_host, config.server_port)
            .await?
            .into_iter()
            .find(|addr| addr.is_ipv4() == want_v4)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::AddrNotAvailable, "no UDP server address matches socket family")
            })?;
        // VpnService.protect() invariant: protect the bound UDP carrier fd before
        // the first send so it bypasses the app's own TUN route. REL-1.
        crate::protect::protect_carrier_socket(&socket, server_addr, config.socket_protection)?;
        socket.connect(server_addr).await?;
        let codec = if config.cipher.is_aead_2022() {
            let psk = PresharedKey::from_base64(config.cipher, &config.password).map_err(invalid_input)?;
            let mut session_id = [0_u8; 8];
            rand::rng().fill(&mut session_id);
            ShadowsocksUdpCodec::Aead2022(Aead2022UdpSession::new(config.cipher, psk, session_id).map_err(to_io)?)
        } else {
            ShadowsocksUdpCodec::Legacy(UdpPacket::new(config.cipher, false))
        };
        Ok(ShadowsocksUdpSession { socket, config, codec, receive_buffer: vec![0_u8; BUFFER_SIZE], calibrated: false })
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
        let read = self.socket.recv(&mut self.receive_buffer).await?;
        let secret = SecretString::new(self.config.password.clone());
        let mut server_timestamp = None;
        let plain = match &mut self.codec {
            ShadowsocksUdpCodec::Legacy(codec) => {
                codec.decrypt(&secret, &self.receive_buffer[..read]).map_err(to_io)?
            }
            ShadowsocksUdpCodec::Aead2022(codec) => {
                let now = NetworkTimeProvider::shared().now_unix_u64();
                let packet =
                    codec.decrypt(&self.receive_buffer[..read], Aead2022UdpPacketType::Server, now).map_err(to_io)?;
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

    // The two pumps each hold one half of the split relay stream, so a single
    // pump exiting cannot deliver EOF to the application -- the sibling's half
    // keeps `relay_stream` alive and the application hangs. Whenever either
    // pump finishes, abort the sibling so the whole relay tears down and the
    // application half observes EOF too. The application half is a
    // DuplexStream handoff, which cannot express a write-only half-close: an
    // upstream exit therefore means the application is gone entirely, and the
    // pump has already forwarded that close to the server (TLS close_notify /
    // TCP FIN) before exiting. The pumps own no state that needs graceful
    // teardown beyond that forwarded close, so abort is safe here.
    let mut pumps = tokio::task::JoinSet::new();
    pumps.spawn(async move {
        let result: io::Result<()> = async {
            let mut buffer = [0_u8; 4096];
            loop {
                let read = app_read.read(&mut buffer).await?;
                if read == 0 {
                    // Forward the application's disconnect to the server in
                    // order -- instead of dropping the transport mid-flight --
                    // so the request stream ends cleanly rather than truncated.
                    socket_write.shutdown().await?;
                    return Ok(());
                }
                let encrypted = encrypt.encrypt_payload(&buffer[..read]).map_err(to_io)?;
                socket_write.write_all(&encrypted).await?;
            }
        }
        .await;
        result
    });
    pumps.spawn(async move {
        let result: io::Result<()> = async {
            let mut encrypted = Vec::new();
            let mut buffer = [0_u8; 4096];
            loop {
                // Drain every complete AEAD chunk already buffered.
                loop {
                    match decrypt.decrypt_chunk(&encrypted, 0) {
                        Ok(Some((plain, consumed))) => {
                            encrypted.drain(..consumed);
                            app_write.write_all(&plain).await?;
                        }
                        Ok(None) => break,
                        // A tag failure is terminal for the response direction:
                        // the stream is AEAD-authenticated and the codec's nonce
                        // counter has already advanced past this chunk, so a retry
                        // can never succeed. Falling through to the socket read
                        // instead would spin on the same bytes while `encrypted`
                        // grows without bound.
                        Err(_) => {
                            return Err(io::Error::other("shadowsocks response authentication failed"));
                        }
                    }
                }
                let read = socket_read.read(&mut buffer).await?;
                if read == 0 {
                    return Ok(());
                }
                encrypted.extend_from_slice(&buffer[..read]);
            }
        }
        .await;
        result
    });
    tokio::spawn(async move {
        let _ = pumps.join_next().await;
        pumps.abort_all();
    });

    Ok(app_stream)
}

async fn connect_server(config: &ShadowsocksClientConfig) -> io::Result<TcpStream> {
    let bind_ip = config.outbound_bind_ip;
    let addrs = config.socket_protection.resolve_host(&config.server_host, config.server_port).await?;
    let server_addr = match bind_ip {
        Some(ip) => addrs.into_iter().find(|addr| addr.is_ipv4() == ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "no server address matches outbound bind IP family")
        })?,
        None => addrs.into_iter().next().ok_or_else(|| {
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
    crate::protect::protect_carrier_socket(&socket, server_addr, config.socket_protection)?;
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use tokio::io::ReadBuf;
    use tokio::sync::Notify;

    #[tokio::test]
    async fn udp_receive_reuses_session_buffer_across_datagrams() {
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind server");
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind client");
        socket.connect(server.local_addr().expect("server address")).await.expect("connect client");
        let client_addr = socket.local_addr().expect("client address");
        let cipher = Cipher::AeadAes128Gcm;
        let credential = "test-credential";
        let mut session = ShadowsocksUdpSession {
            socket,
            config: Arc::new(ShadowsocksClientConfig {
                server_host: Ipv4Addr::LOCALHOST.to_string(),
                server_port: server.local_addr().expect("server address").port(),
                cipher,
                password: credential.to_owned(),
                outbound_bind_ip: None,
                socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            }),
            codec: ShadowsocksUdpCodec::Legacy(UdpPacket::new(cipher, false)),
            receive_buffer: vec![0_u8; BUFFER_SIZE],
            calibrated: false,
        };
        let receive_buffer = session.receive_buffer.as_ptr();
        let encoder = UdpPacket::new(cipher, false);
        let secret = SecretString::new(credential.to_owned());

        for payload in [b"first".as_slice(), b"second".as_slice()] {
            let plain = encode_address("192.0.2.1:53", payload).expect("encode datagram");
            let packet = encoder.encrypt(&secret, &plain).expect("encrypt datagram");
            server.send_to(&packet, client_addr).await.expect("send datagram");

            let (target, received) = session.recv_from().await.expect("receive datagram");
            assert_eq!(target, "192.0.2.1:53");
            assert_eq!(received, payload);
            assert_eq!(session.receive_buffer.as_ptr(), receive_buffer, "receive buffer must retain its allocation");
        }
    }

    /// Regression test for the downstream pump retry loop: a failed AEAD
    /// authentication is terminal, so the relay task must stop and deliver EOF
    /// to the application half. Before the fix, the `while let Ok(Some(..))`
    /// loop silently fell through to the socket read on `Err` and retried the
    /// same undecryptable bytes forever: the application hung, the buffered
    /// ciphertext grew without bound, and the codec's nonce counter (already
    /// advanced past the failed chunk) guaranteed the retry could never
    /// succeed.
    #[tokio::test]
    async fn downstream_decrypt_failure_terminates_relay_instead_of_retrying() {
        let cipher = Cipher::AeadAes128Gcm;
        let credential = "test-credential";
        let config = Arc::new(ShadowsocksClientConfig {
            server_host: "192.0.2.1".to_string(),
            server_port: 443,
            cipher,
            password: credential.to_owned(),
            outbound_bind_ip: None,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        });
        let (transport, mut server) = tokio::io::duplex(4096);

        // A hostile/broken server: valid salt, then ciphertext that never
        // authenticates. The connection is deliberately held open so the
        // pre-fix retry loop would park on the socket read forever.
        let server_task = tokio::spawn(async move {
            let mut salt = vec![0_u8; cipher.salt_len()];
            rand::rng().fill(&mut salt);
            server.write_all(&salt).await.expect("write salt");
            server.write_all(&[0xAB_u8; 128]).await.expect("write garbage ciphertext");
            std::future::pending::<()>().await;
        });

        let mut app = connect_tcp_over_transport(config, transport, "192.0.2.1:443").await.expect("connect");

        let mut sink = Vec::new();
        let result = tokio::time::timeout(std::time::Duration::from_secs(5), app.read_to_end(&mut sink)).await;
        assert!(result.is_ok(), "relay must terminate after a failed authentication, not hang");
        assert!(sink.is_empty(), "no plaintext may be delivered from unauthenticated bytes");

        server_task.abort();
    }

    /// Regression test for half-close propagation: when the application
    /// disconnects, the upstream pump must forward the close to the server
    /// (`AsyncWrite::shutdown`, i.e. TLS close_notify / TCP FIN) BEFORE the
    /// relay tears down. Before the fix the pump returned on app EOF without
    /// shutting anything down and the supervisor aborted the sibling, so the
    /// carrier socket was dropped mid-flight and the server saw a truncated,
    /// unordered end of stream.
    #[tokio::test]
    async fn app_disconnect_forwards_shutdown_to_server_before_teardown() {
        let cipher = Cipher::AeadAes128Gcm;
        let credential = "test-credential";
        let config = Arc::new(ShadowsocksClientConfig {
            server_host: "192.0.2.1".to_string(),
            server_port: 443,
            cipher,
            password: credential.to_owned(),
            outbound_bind_ip: None,
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        });

        // tokio's DuplexStream shutdown is a no-op, so wrap the transport in a
        // double that records shutdown calls instead of forwarding them.
        struct ShutdownRecordingTransport {
            inner: tokio::io::DuplexStream,
            shutdowns: Arc<Notify>,
        }

        impl AsyncRead for ShutdownRecordingTransport {
            fn poll_read(
                mut self: Pin<&mut Self>,
                cx: &mut Context<'_>,
                buf: &mut ReadBuf<'_>,
            ) -> Poll<io::Result<()>> {
                Pin::new(&mut self.inner).poll_read(cx, buf)
            }
        }

        impl AsyncWrite for ShutdownRecordingTransport {
            fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
                Pin::new(&mut self.inner).poll_write(cx, buf)
            }

            fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
                Pin::new(&mut self.inner).poll_flush(cx)
            }

            fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
                self.shutdowns.notify_one();
                Poll::Ready(Ok(()))
            }
        }

        let (inner, mut server) = tokio::io::duplex(4096);
        let shutdowns = Arc::new(Notify::new());
        let transport = ShutdownRecordingTransport { inner, shutdowns: Arc::clone(&shutdowns) };

        let salt_len = cipher.salt_len();
        let server_task = tokio::spawn(async move {
            let mut salt = vec![0_u8; salt_len];
            rand::rng().fill(&mut salt);
            server.write_all(&salt).await.expect("write salt");

            // Receive (and ignore) the encrypted request bytes.
            let mut request = [0_u8; 512];
            let read = server.read(&mut request).await.expect("read request");
            assert!(read > 0, "server must receive the client request");

            // The forwarded close must arrive while the relay is still alive.
            tokio::time::timeout(std::time::Duration::from_secs(5), shutdowns.notified())
                .await
                .expect("app disconnect must be forwarded to the server as a shutdown");
        });

        let mut app = connect_tcp_over_transport(config, transport, "192.0.2.1:443").await.expect("connect");
        app.write_all(b"GET /").await.expect("write request");

        // The application goes away entirely; through the DuplexStream handoff
        // this is the only observable form of write-side close.
        drop(app);

        server_task.await.expect("server task");
    }
}
