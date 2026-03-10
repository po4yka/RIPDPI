use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio::net::{TcpStream, UdpSocket};
use tokio_util::sync::CancellationToken;

use crate::socks5::{associate, decode_udp_frame, encode_udp_frame, handshake, Auth};

/// Default timeout waiting for a UDP response from the relay.
const DEFAULT_RECV_TIMEOUT: Duration = Duration::from_secs(10);

/// Single-shot UDP relay session.
///
/// For each UDP datagram to be forwarded:
/// 1. Open a TCP control connection to the SOCKS5 proxy.
/// 2. Perform SOCKS5 handshake (auth).
/// 3. Send UDP ASSOCIATE; receive the relay `SocketAddr`.
/// 4. Bind a local UDP socket; send the framed datagram to the relay.
/// 5. Wait for one response datagram (with timeout / cancel).
/// 6. Return `(response_payload, from_addr)`.
///
/// This matches the C `hev-socks5-session-udp` single-datagram flow.
/// For long-lived UDP flows, call `relay_once` again for each round-trip.
pub struct UdpSession {
    proxy_addr: SocketAddr,
    auth: Auth,
    recv_timeout: Duration,
}

impl UdpSession {
    pub fn new(proxy_addr: SocketAddr, auth: Auth) -> Self {
        Self {
            proxy_addr,
            auth,
            recv_timeout: DEFAULT_RECV_TIMEOUT,
        }
    }

    /// Override the per-datagram receive timeout (default 10 s).
    pub fn with_recv_timeout(mut self, timeout: Duration) -> Self {
        self.recv_timeout = timeout;
        self
    }

    /// Relay a single UDP datagram through the SOCKS5 proxy.
    ///
    /// - `dst`: where the datagram should be delivered.
    /// - `payload`: raw UDP payload bytes.
    /// - `cancel`: signals early termination (returns `Ok(None)`).
    ///
    /// Returns `Ok(Some((payload, from)))` on success, `Ok(None)` if
    /// cancelled or timed out, `Err` on I/O failure.
    pub async fn relay_once(
        &self,
        dst: SocketAddr,
        payload: &[u8],
        cancel: CancellationToken,
    ) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
        // ── 1. TCP control connection ────────────────────────────────────────
        let mut ctrl = TcpStream::connect(self.proxy_addr).await?;

        // ── 2. SOCKS5 handshake ───────────────────────────────────────────────
        handshake(&mut ctrl, &self.auth).await?;

        // ── 3. UDP ASSOCIATE → relay addr ────────────────────────────────────
        let relay_addr = associate(&mut ctrl).await?;

        // ── 4. Local UDP socket + send ────────────────────────────────────────
        let bind_addr: SocketAddr = if relay_addr.is_ipv4() {
            "0.0.0.0:0".parse().unwrap()
        } else {
            "[::]:0".parse().unwrap()
        };
        let udp = UdpSocket::bind(bind_addr).await?;
        udp.connect(relay_addr).await?;

        let frame = encode_udp_frame(dst, payload);
        udp.send(&frame).await?;

        // ── 5. Receive response (with timeout / cancel) ───────────────────────
        let mut buf = vec![0u8; 65535];

        let recv_fut = async {
            let n = udp.recv(&mut buf).await?;
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;
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
    /// Returns (proxy_listen_addr, udp_echo_addr).
    async fn spawn_stub_proxy() -> (SocketAddr, SocketAddr) {
        // Bind UDP echo socket first so we know the port.
        let udp_echo = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let udp_echo_addr = udp_echo.local_addr().unwrap();
        let relay_port = udp_echo_addr.port();

        // Spawn UDP echo task: recv one datagram, parse SOCKS5 frame, echo it.
        tokio::spawn(async move {
            let mut buf = vec![0u8; 65535];
            if let Ok((n, peer)) = udp_echo.recv_from(&mut buf).await {
                // Parse the SOCKS5 UDP frame to extract the real payload.
                if let Ok((_from, payload)) = decode_udp_frame(&buf[..n]) {
                    // Echo back: wrap in SOCKS5 UDP frame with src = udp_echo_addr.
                    let src: SocketAddr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), relay_port);
                    let reply = encode_udp_frame(src, payload);
                    let _ = udp_echo.send_to(&reply, peer).await;
                }
            }
        });

        // Bind TCP proxy listener.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();

        // Spawn TCP proxy stub.
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 64];

            // Handshake: read greeting, reply NoAuth
            let _ = stream.read(&mut buf).await;
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            // Read ASSOCIATE request
            let _ = stream.read(&mut buf).await;

            // Reply: VER=5, REP=0, RSV=0, ATYP=1, 127.0.0.1, relay_port
            let port_bytes = relay_port.to_be_bytes();
            stream
                .write_all(&[
                    0x05,
                    0x00,
                    0x00,
                    0x01,
                    127,
                    0,
                    0,
                    1,
                    port_bytes[0],
                    port_bytes[1],
                ])
                .await
                .unwrap();

            // Keep TCP control connection alive while UDP flows.
            tokio::time::sleep(Duration::from_secs(2)).await;
        });

        (proxy_addr, udp_echo_addr)
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /// Full relay round-trip: send a datagram, receive the echo.
    #[tokio::test]
    async fn relay_once_round_trip() {
        let (proxy_addr, echo_addr) = spawn_stub_proxy().await;

        let session =
            UdpSession::new(proxy_addr, Auth::NoAuth).with_recv_timeout(Duration::from_secs(3));

        let cancel = CancellationToken::new();
        let result = session
            .relay_once(echo_addr, b"ping", cancel)
            .await
            .unwrap();

        assert!(result.is_some(), "expected a response from echo server");
        let (payload, _from) = result.unwrap();
        assert_eq!(payload, b"ping");
    }

    /// Cancel before response arrives → Ok(None).
    #[tokio::test]
    async fn relay_once_cancel_returns_none() {
        let (proxy_addr, echo_addr) = spawn_stub_proxy().await;

        let session =
            UdpSession::new(proxy_addr, Auth::NoAuth).with_recv_timeout(Duration::from_secs(5));

        let cancel = CancellationToken::new();
        cancel.cancel(); // cancel immediately

        let result = session
            .relay_once(echo_addr, b"ping", cancel)
            .await
            .unwrap();
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
            stream
                .write_all(&[
                    0x05,
                    0x00,
                    0x00,
                    0x01,
                    127,
                    0,
                    0,
                    1,
                    port_bytes[0],
                    port_bytes[1],
                ])
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_secs(2)).await;
        });

        let dst: SocketAddr = "127.0.0.1:9999".parse().unwrap();
        let session =
            UdpSession::new(proxy_addr, Auth::NoAuth).with_recv_timeout(Duration::from_millis(100));

        let result = session
            .relay_once(dst, b"ping", CancellationToken::new())
            .await
            .unwrap();
        assert!(result.is_none(), "timed-out relay must return None");
    }

    /// Unreachable proxy → Err.
    #[tokio::test]
    async fn relay_once_bad_proxy_returns_err() {
        let bad_proxy: SocketAddr = "127.0.0.1:1".parse().unwrap();
        let session = UdpSession::new(bad_proxy, Auth::NoAuth);
        let result = session
            .relay_once(bad_proxy, b"x", CancellationToken::new())
            .await;
        assert!(result.is_err(), "unreachable proxy must yield Err");
    }
}
