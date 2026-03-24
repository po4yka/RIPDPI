use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

use super::socks5::{associate, decode_udp_frame, encode_udp_frame, handshake, Auth};

/// Default timeout waiting for a UDP response from the relay.
const DEFAULT_RECV_TIMEOUT: Duration = Duration::from_secs(10);

/// Persistent SOCKS5 UDP association.
///
/// The TCP control connection and UDP relay socket stay open for the lifetime
/// of the session so the caller can forward multiple datagrams and receive
/// multiple replies over the same association.
#[derive(Clone)]
pub struct UdpSession {
    _ctrl: Arc<Mutex<TcpStream>>,
    udp: Arc<UdpSocket>,
    recv_timeout: Duration,
}

impl UdpSession {
    pub async fn connect(proxy_addr: SocketAddr, auth: Auth) -> io::Result<Self> {
        let mut ctrl = TcpStream::connect(proxy_addr).await?;
        handshake(&mut ctrl, &auth).await?;
        let relay_addr = associate(&mut ctrl).await?;

        let bind_addr: SocketAddr =
            if relay_addr.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
        let udp = UdpSocket::bind(bind_addr).await?;
        udp.connect(relay_addr).await?;

        Ok(Self { _ctrl: Arc::new(Mutex::new(ctrl)), udp: Arc::new(udp), recv_timeout: DEFAULT_RECV_TIMEOUT })
    }

    /// Override the receive timeout (default 10 s).
    pub fn with_recv_timeout(mut self, timeout: Duration) -> Self {
        self.recv_timeout = timeout;
        self
    }

    /// Send a UDP datagram through the established SOCKS5 relay.
    pub async fn send_to(&self, dst: SocketAddr, payload: &[u8]) -> io::Result<()> {
        let frame = encode_udp_frame(dst, payload);
        let _ = self.udp.send(&frame).await?;
        Ok(())
    }

    /// Receive a UDP datagram from the established SOCKS5 relay.
    ///
    /// - `cancel`: signals early termination (returns `Ok(None)`).
    ///
    /// Returns `Ok(Some((payload, from)))` on success, `Ok(None)` if
    /// cancelled or timed out, `Err` on I/O failure.
    pub async fn recv_from(&self, cancel: CancellationToken) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
        let mut buf = vec![0u8; 65535];

        let recv_fut = async {
            let n = self.udp.recv(&mut buf).await?;
            let (from, data) = decode_udp_frame(&buf[..n])?;
            Ok::<_, io::Error>((from, data.to_vec()))
        };

        let timeout_fut = tokio::time::sleep(self.recv_timeout);

        tokio::select! {
            result = recv_fut => {
                let (from, data) = result?;
                Ok(Some((data, from)))
            }
            _ = timeout_fut => Ok(None),
            _ = cancel.cancelled() => Ok(None),
        }
    }

    /// Convenience helper: send one datagram and wait for one reply.
    pub async fn relay_once(
        &self,
        dst: SocketAddr,
        payload: &[u8],
        cancel: CancellationToken,
    ) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
        self.send_to(dst, payload).await?;
        self.recv_from(cancel).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /// Start a minimal SOCKS5 proxy stub that:
    /// - accepts the handshake (NoAuth)
    /// - accepts a UDP ASSOCIATE
    /// - replies with relay = 127.0.0.1:<udp_port>
    ///
    /// Also starts a real UDP echo server at the returned port.
    /// Returns (proxy_listen_addr, udp_echo_addr, accepted_tcp_connections).
    async fn spawn_stub_proxy(
        expected_datagrams: usize,
        duplicate_first_reply: bool,
    ) -> (SocketAddr, SocketAddr, Arc<AtomicUsize>) {
        // Bind UDP echo socket first so we know the port.
        let udp_echo = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let udp_echo_addr = udp_echo.local_addr().unwrap();
        let relay_port = udp_echo_addr.port();

        // Spawn UDP echo task: recv datagrams, parse SOCKS5 frames, echo them.
        tokio::spawn(async move {
            for datagram_index in 0..expected_datagrams {
                let mut buf = vec![0u8; 65535];
                if let Ok((n, peer)) = udp_echo.recv_from(&mut buf).await {
                    if let Ok((_from, payload)) = decode_udp_frame(&buf[..n]) {
                        let src: SocketAddr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), relay_port);
                        let reply = encode_udp_frame(src, payload);
                        let _ = udp_echo.send_to(&reply, peer).await;
                        if duplicate_first_reply && datagram_index == 0 {
                            let push = encode_udp_frame(src, b"push");
                            let _ = udp_echo.send_to(&push, peer).await;
                        }
                    }
                }
            }
        });

        // Bind TCP proxy listener.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();

        let accepted_tcp_connections = Arc::new(AtomicUsize::new(0));
        let accepted_tcp_connections_task = Arc::clone(&accepted_tcp_connections);

        // Spawn TCP proxy stub.
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            accepted_tcp_connections_task.fetch_add(1, Ordering::Relaxed);
            let mut buf = [0u8; 64];

            // Handshake: read greeting, reply NoAuth
            let _ = stream.read(&mut buf).await;
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // Read ASSOCIATE request
            let _ = stream.read(&mut buf).await;

            // Reply: VER=5, REP=0, RSV=0, ATYP=1, 127.0.0.1, relay_port
            let port_bytes = relay_port.to_be_bytes();
            stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, port_bytes[0], port_bytes[1]]).await.unwrap();

            // Keep TCP control connection alive while UDP flows.
            tokio::time::sleep(Duration::from_secs(2)).await;
        });

        (proxy_addr, udp_echo_addr, accepted_tcp_connections)
    }

    async fn read_socks5_assoc_addr<S>(stream: &mut S, atyp: u8) -> io::Result<()>
    where
        S: tokio::io::AsyncRead + Unpin,
    {
        match atyp {
            0x01 => {
                let mut addr = [0u8; 6];
                stream.read_exact(&mut addr).await?;
            }
            0x03 => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len).await?;
                let mut addr = vec![0u8; usize::from(len[0]) + 2];
                stream.read_exact(&mut addr).await?;
            }
            0x04 => {
                let mut addr = [0u8; 18];
                stream.read_exact(&mut addr).await?;
            }
            value => panic!("unexpected SOCKS5 atyp {value:#x}"),
        }
        Ok(())
    }

    #[derive(Clone)]
    struct TurmoilUdpSession {
        _ctrl: Arc<Mutex<turmoil::net::TcpStream>>,
        udp: Arc<turmoil::net::UdpSocket>,
        relay_addr: SocketAddr,
        recv_timeout: Duration,
    }

    impl TurmoilUdpSession {
        async fn connect(proxy_addr: SocketAddr, auth: Auth) -> io::Result<Self> {
            let mut ctrl = turmoil::net::TcpStream::connect(proxy_addr).await?;
            handshake(&mut ctrl, &auth).await?;
            let relay_addr = associate(&mut ctrl).await?;

            let bind_addr: SocketAddr =
                if relay_addr.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
            let udp = turmoil::net::UdpSocket::bind(bind_addr).await?;

            Ok(Self {
                _ctrl: Arc::new(Mutex::new(ctrl)),
                udp: Arc::new(udp),
                relay_addr,
                recv_timeout: DEFAULT_RECV_TIMEOUT,
            })
        }

        fn with_recv_timeout(mut self, timeout: Duration) -> Self {
            self.recv_timeout = timeout;
            self
        }

        async fn send_to(&self, dst: SocketAddr, payload: &[u8]) -> io::Result<()> {
            let frame = encode_udp_frame(dst, payload);
            let _ = self.udp.send_to(&frame, self.relay_addr).await?;
            Ok(())
        }

        async fn recv_from(&self, cancel: CancellationToken) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
            let mut buf = vec![0u8; 65535];
            tokio::select! {
                result = self.udp.recv_from(&mut buf) => {
                    let (n, _origin) = result?;
                    let (from, data) = decode_udp_frame(&buf[..n])?;
                    Ok(Some((data.to_vec(), from)))
                }
                _ = tokio::time::sleep(self.recv_timeout) => Ok(None),
                _ = cancel.cancelled() => Ok(None),
            }
        }

        async fn relay_once(
            &self,
            dst: SocketAddr,
            payload: &[u8],
            cancel: CancellationToken,
        ) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
            self.send_to(dst, payload).await?;
            self.recv_from(cancel).await
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /// Full relay round-trip: send a datagram, receive the echo.
    #[tokio::test]
    async fn relay_once_round_trip() {
        let (proxy_addr, echo_addr, _accepted_tcp_connections) = spawn_stub_proxy(1, false).await;

        let session =
            UdpSession::connect(proxy_addr, Auth::NoAuth).await.unwrap().with_recv_timeout(Duration::from_secs(3));

        let cancel = CancellationToken::new();
        let result = session.relay_once(echo_addr, b"ping", cancel).await.unwrap();

        assert!(result.is_some(), "expected a response from echo server");
        let (payload, _from) = result.unwrap();
        assert_eq!(payload, b"ping");
    }

    /// Cancel before response arrives → Ok(None).
    #[tokio::test]
    async fn relay_once_cancel_returns_none() {
        let (proxy_addr, echo_addr, _accepted_tcp_connections) = spawn_stub_proxy(1, false).await;

        let session =
            UdpSession::connect(proxy_addr, Auth::NoAuth).await.unwrap().with_recv_timeout(Duration::from_secs(5));

        let cancel = CancellationToken::new();
        cancel.cancel(); // cancel immediately

        let result = session.relay_once(echo_addr, b"ping", cancel).await.unwrap();
        assert!(result.is_none(), "cancelled relay must return None");
    }

    /// Timeout with no response → Ok(None).
    ///
    /// Uses a stub proxy whose relay UDP socket receives but never replies.
    #[tokio::test]
    async fn relay_once_timeout_returns_none() {
        // Silent relay: binds a UDP socket but never sends anything back.
        let silent_udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let relay_port = silent_udp.local_addr().unwrap().port();
        // Keep it alive but idle.
        tokio::spawn(async move {
            let mut buf = vec![0u8; 65535];
            let _ = silent_udp.recv_from(&mut buf).await;
            // intentionally never reply
        });

        // Proxy stub that advertises the silent relay.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 64];
            let _ = stream.read(&mut buf).await;
            stream.write_all(&[0x05, 0x00]).await.unwrap();
            let _ = stream.read(&mut buf).await;
            let port_bytes = relay_port.to_be_bytes();
            stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, port_bytes[0], port_bytes[1]]).await.unwrap();
            tokio::time::sleep(Duration::from_secs(2)).await;
        });

        let dst: SocketAddr = "127.0.0.1:9999".parse().unwrap();
        let session =
            UdpSession::connect(proxy_addr, Auth::NoAuth).await.unwrap().with_recv_timeout(Duration::from_millis(100));

        let result = session.relay_once(dst, b"ping", CancellationToken::new()).await.unwrap();
        assert!(result.is_none(), "timed-out relay must return None");
    }

    /// Unreachable proxy → Err.
    #[tokio::test]
    async fn relay_once_bad_proxy_returns_err() {
        let bad_proxy: SocketAddr = "127.0.0.1:1".parse().unwrap();
        let result = UdpSession::connect(bad_proxy, Auth::NoAuth).await;
        assert!(result.is_err(), "unreachable proxy must yield Err");
    }

    #[tokio::test]
    async fn send_to_and_recv_from_reuse_single_association() {
        let (proxy_addr, echo_addr, accepted_tcp_connections) = spawn_stub_proxy(2, false).await;

        let session =
            UdpSession::connect(proxy_addr, Auth::NoAuth).await.unwrap().with_recv_timeout(Duration::from_secs(3));

        session.send_to(echo_addr, b"first").await.unwrap();
        let first = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(first.0, b"first");

        session.send_to(echo_addr, b"second").await.unwrap();
        let second = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(second.0, b"second");

        assert_eq!(accepted_tcp_connections.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn recv_from_supports_multiple_replies_on_same_association() {
        let (proxy_addr, echo_addr, accepted_tcp_connections) = spawn_stub_proxy(1, true).await;

        let session =
            UdpSession::connect(proxy_addr, Auth::NoAuth).await.unwrap().with_recv_timeout(Duration::from_secs(3));

        session.send_to(echo_addr, b"ping").await.unwrap();

        let first = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        let second = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();

        assert_eq!(first.0, b"ping");
        assert_eq!(second.0, b"push");
        assert_eq!(accepted_tcp_connections.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn turmoil_udp_association_recovers_after_partition_repair() -> turmoil::Result {
        let mut sim = turmoil::Builder::new()
            .min_message_latency(Duration::from_millis(5))
            .max_message_latency(Duration::from_millis(5))
            .build();

        sim.host("proxy", || async move {
            let relay_port = 5300;
            let relay = turmoil::net::UdpSocket::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), relay_port)).await?;
            let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 1080)).await?;
            let relay_addr = SocketAddr::new(turmoil::lookup("proxy"), relay_port);

            tokio::spawn(async move {
                let mut buf = vec![0u8; 65535];
                loop {
                    let (n, peer) = relay.recv_from(&mut buf).await.expect("udp recv");
                    let (from, payload) = decode_udp_frame(&buf[..n]).expect("decode udp frame");
                    let reply = encode_udp_frame(from, payload);
                    let _ = relay.send_to(&reply, peer).await.expect("udp send");
                }
            });

            let (mut stream, _) = listener.accept().await?;
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).await?;
            assert_eq!(greeting, [0x05, 0x01, 0x00]);
            stream.write_all(&[0x05, 0x00]).await?;

            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await?;
            assert_eq!(&header[..3], &[0x05, 0x03, 0x00]);
            read_socks5_assoc_addr(&mut stream, header[3]).await?;
            let port_bytes = relay_addr.port().to_be_bytes();
            let reply = match relay_addr.ip() {
                IpAddr::V4(ip) => {
                    let octets = ip.octets();
                    vec![
                        0x05,
                        0x00,
                        0x00,
                        0x01,
                        octets[0],
                        octets[1],
                        octets[2],
                        octets[3],
                        port_bytes[0],
                        port_bytes[1],
                    ]
                }
                IpAddr::V6(ip) => {
                    let mut bytes = vec![0x05, 0x00, 0x00, 0x04];
                    bytes.extend_from_slice(&ip.octets());
                    bytes.extend_from_slice(&port_bytes);
                    bytes
                }
            };
            stream.write_all(&reply).await?;

            tokio::time::sleep(Duration::from_secs(1)).await;
            Ok(())
        });

        sim.client("client", async move {
            let proxy_addr = SocketAddr::new(turmoil::lookup("proxy"), 1080);
            let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 25)), 5353);
            let session = TurmoilUdpSession::connect(proxy_addr, Auth::NoAuth)
                .await
                .expect("udp session connects")
                .with_recv_timeout(Duration::from_millis(50));

            turmoil::partition("client", "proxy");
            let timed_out = session
                .relay_once(dst, b"lost", CancellationToken::new())
                .await
                .expect("partitioned udp relay should not error");
            assert!(timed_out.is_none(), "partitioned association should deterministically time out");

            turmoil::repair("client", "proxy");
            let repaired = session
                .relay_once(dst, b"repaired", CancellationToken::new())
                .await
                .expect("repaired udp relay should succeed")
                .expect("repaired association should receive a response");
            assert_eq!(repaired.0, b"repaired");
            assert_eq!(repaired.1, dst);
            Ok(())
        });

        sim.run()
    }
}
