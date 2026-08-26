use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::Mutex as StdMutex;
use std::time::Duration;

use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};
use tokio::sync::{Mutex, OwnedSemaphorePermit, Semaphore};
use tokio_util::sync::CancellationToken;

use super::protect::protect_socket_if_available;
use super::socks5::{
    Auth, TargetAddr, associate, decode_udp_frame, decode_udp_target_frame, encode_udp_frame, encode_udp_target_frame,
    handshake,
};

/// Default timeout waiting for a UDP response from the relay.
const DEFAULT_RECV_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_UDP_DATAGRAM_LEN: usize = 65_535;
const MAX_SOCKS5_UDP_HEADER_LEN: usize = 261;
const DEFAULT_RECEIVE_POOL_CONCURRENCY: usize = 64;
const DEFAULT_QUEUED_UDP_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone)]
pub(crate) struct UdpMemoryBudget {
    receive_pool: Arc<UdpReceiveBufferPool>,
    queued_bytes: Arc<Semaphore>,
}

struct UdpReceiveBufferPool {
    buffer_len: usize,
    max_cached: usize,
    permits: Arc<Semaphore>,
    buffers: StdMutex<Vec<Vec<u8>>>,
}

struct PooledUdpBuffer {
    pool: Arc<UdpReceiveBufferPool>,
    buffer: Option<Vec<u8>>,
    _permit: OwnedSemaphorePermit,
}

impl UdpMemoryBudget {
    pub(crate) fn for_tunnel_mtu(mtu: usize) -> Self {
        let receive_len = mtu.saturating_add(MAX_SOCKS5_UDP_HEADER_LEN).min(MAX_UDP_DATAGRAM_LEN);
        Self::new(receive_len, DEFAULT_RECEIVE_POOL_CONCURRENCY, DEFAULT_QUEUED_UDP_BYTES)
    }

    fn new(receive_len: usize, receive_concurrency: usize, queued_bytes: usize) -> Self {
        let receive_concurrency = receive_concurrency.max(1);
        Self {
            receive_pool: Arc::new(UdpReceiveBufferPool {
                buffer_len: receive_len.max(1),
                max_cached: receive_concurrency,
                permits: Arc::new(Semaphore::new(receive_concurrency)),
                buffers: StdMutex::new(Vec::new()),
            }),
            queued_bytes: Arc::new(Semaphore::new(queued_bytes)),
        }
    }

    pub(crate) fn try_reserve_queued_bytes(&self, bytes: usize) -> Option<OwnedSemaphorePermit> {
        let permits = u32::try_from(bytes).ok()?;
        Arc::clone(&self.queued_bytes).try_acquire_many_owned(permits).ok()
    }
}

impl UdpReceiveBufferPool {
    async fn acquire(self: &Arc<Self>) -> io::Result<PooledUdpBuffer> {
        let permit = Arc::clone(&self.permits)
            .acquire_owned()
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "UDP receive buffer pool closed"))?;
        let buffer = self
            .buffers
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .pop()
            .unwrap_or_else(|| vec![0; self.buffer_len]);
        Ok(PooledUdpBuffer { pool: Arc::clone(self), buffer: Some(buffer), _permit: permit })
    }
}

impl PooledUdpBuffer {
    fn as_mut_slice(&mut self) -> &mut [u8] {
        self.buffer.as_mut().expect("pooled UDP buffer is present").as_mut_slice()
    }
}

impl Drop for PooledUdpBuffer {
    fn drop(&mut self) {
        let Some(buffer) = self.buffer.take() else {
            return;
        };
        let mut buffers = self.pool.buffers.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if buffers.len() < self.pool.max_cached {
            buffers.push(buffer);
        }
    }
}

/// Persistent SOCKS5 UDP association.
///
/// The TCP control connection and UDP relay socket stay open for the lifetime
/// of the session so the caller can forward multiple datagrams and receive
/// multiple replies over the same association.
#[derive(Clone)]
pub struct UdpSession {
    _ctrl: Arc<Mutex<TcpStream>>,
    udp: Arc<UdpSocket>,
    memory_budget: UdpMemoryBudget,
    recv_timeout: Duration,
}

impl UdpSession {
    /// Establish a SOCKS5 UDP ASSOCIATE session to `proxy_addr`.
    ///
    /// Both the TCP control connection and the UDP relay socket are protected
    /// via [`protect_socket_if_available`] *before* they issue `connect`/`bind`,
    /// honoring the `VpnService.protect()` invariant: the tunnel→proxy hop is
    /// loopback by default (a no-op for protect), but a non-loopback proxy
    /// address is a supported configuration whose sockets would otherwise loop
    /// back into the TUN the VPN owns. `protect_path` selects the CLI UDS
    /// fallback when no JNI protect callback is registered (`None` on the
    /// Android callback path and in tests). See
    /// `.claude/rules/vpnservice-protect-invariant.md`.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe. The association is a multi-step sequence — connect the
    /// control TCP socket, SOCKS5 handshake, `ASSOCIATE`, then bind/connect the
    /// relay UDP socket — with no rollback. Dropping the future partway leaves a
    /// half-built association (e.g. a control connection past handshake but with
    /// no usable relay socket); the dropped values close their fds, but the proxy
    /// may briefly see a torn `ASSOCIATE`. The tunnel association worker awaits it
    /// to completion outside the single TUN/smoltcp owner task, so it is never
    /// cancelled mid-setup. Do not call it inside a `select!`/`timeout`.
    pub async fn connect(proxy_addr: SocketAddr, auth: Auth, protect_path: Option<&str>) -> io::Result<Self> {
        Self::connect_with_memory_budget(
            proxy_addr,
            auth,
            protect_path,
            UdpMemoryBudget::new(MAX_UDP_DATAGRAM_LEN, 1, DEFAULT_QUEUED_UDP_BYTES),
        )
        .await
    }

    pub(crate) async fn connect_with_memory_budget(
        proxy_addr: SocketAddr,
        auth: Auth,
        protect_path: Option<&str>,
        memory_budget: UdpMemoryBudget,
    ) -> io::Result<Self> {
        // Control connection: create the socket, protect it, *then* connect.
        let ctrl_socket = match proxy_addr {
            SocketAddr::V4(_) => TcpSocket::new_v4()?,
            SocketAddr::V6(_) => TcpSocket::new_v6()?,
        };
        protect_socket_if_available(&ctrl_socket, protect_path)?;
        let mut ctrl = ctrl_socket.connect(proxy_addr).await?;
        handshake(&mut ctrl, &auth).await?;
        let relay_addr = associate(&mut ctrl).await?;

        // Relay socket: create + protect + bind + connect, in that order, so the
        // fd is protected before any traffic can leave it.
        let (domain, bind_addr): (Domain, SocketAddr) = if relay_addr.is_ipv4() {
            (Domain::IPV4, "0.0.0.0:0".parse().unwrap())
        } else {
            (Domain::IPV6, "[::]:0".parse().unwrap())
        };
        let relay_socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
        protect_socket_if_available(&relay_socket, protect_path)?;
        relay_socket.bind(&SockAddr::from(bind_addr))?;
        relay_socket.set_nonblocking(true)?;
        let udp = UdpSocket::from_std(relay_socket.into())?;
        udp.connect(relay_addr).await?;

        Ok(Self {
            _ctrl: Arc::new(Mutex::new(ctrl)),
            udp: Arc::new(udp),
            memory_budget,
            recv_timeout: DEFAULT_RECV_TIMEOUT,
        })
    }

    /// Override the receive timeout (default 10 s).
    pub fn with_recv_timeout(mut self, timeout: Duration) -> Self {
        self.recv_timeout = timeout;
        self
    }

    /// Send a UDP datagram through the established SOCKS5 relay.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe. The single `.await` is one `UdpSocket::send`, which is
    /// message-atomic: it either queues the whole datagram or none of it. A
    /// cancel before completion sends nothing and leaves no partial datagram and
    /// no torn relay state. The frame is built synchronously before the await.
    pub async fn send_to(&self, dst: SocketAddr, payload: &[u8]) -> io::Result<()> {
        let frame = encode_udp_frame(dst, payload);
        let _ = self.udp.send(&frame).await?;
        Ok(())
    }

    /// Send a UDP datagram with its logical SOCKS5 destination authority intact.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: frame construction is synchronous and the only `.await` is
    /// one message-atomic `UdpSocket::send`; cancellation cannot leave a partial
    /// datagram or mutate the established relay association.
    pub(crate) async fn send_to_target(&self, dst: &TargetAddr, payload: &[u8]) -> io::Result<()> {
        let frame = encode_udp_target_frame(dst, payload)?;
        let _ = self.udp.send(&frame).await?;
        Ok(())
    }

    /// Receive a UDP datagram from the established SOCKS5 relay.
    ///
    /// - `cancel`: signals early termination (returns `Ok(None)`).
    ///
    /// Returns `Ok(Some((payload, from)))` on success, `Ok(None)` if
    /// cancelled or timed out, `Err` on I/O failure.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe. The `recv` arm is one message-atomic `UdpSocket::recv`
    /// followed by a synchronous decode (no `.await` between consuming the
    /// datagram and returning it), so dropping this future never consumes-then-
    /// loses a datagram. The `cancel`/`timeout` arms are pure timers. The
    /// internal `select!` is intentionally unbiased: recv, timeout, and cancel
    /// are mutually exclusive single-shot outcomes with no teardown arm to
    /// protect from starvation.
    pub async fn recv_from(&self, cancel: CancellationToken) -> io::Result<Option<(Vec<u8>, SocketAddr)>> {
        let recv_fut = async {
            let mut buf = self.memory_budget.receive_pool.acquire().await?;
            let n = self.udp.recv(buf.as_mut_slice()).await?;
            let (from, data) = decode_udp_frame(&buf.as_mut_slice()[..n])?;
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

    /// Receive a UDP datagram while retaining the SOCKS target identity.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe. The socket receive is message-atomic and target decoding is
    /// synchronous, so cancellation cannot consume and lose a datagram.
    /// Timeout and cancellation arms do not mutate session state.
    pub(crate) async fn recv_from_target(
        &self,
        cancel: CancellationToken,
    ) -> io::Result<Option<(Vec<u8>, TargetAddr)>> {
        let recv_fut = async {
            let mut buf = self.memory_budget.receive_pool.acquire().await?;
            let n = self.udp.recv(buf.as_mut_slice()).await?;
            let (from, data) = decode_udp_target_frame(&buf.as_mut_slice()[..n])?;
            Ok::<_, io::Error>((from, data.to_vec()))
        };
        tokio::select! {
            result = recv_fut => {
                let (from, data) = result?;
                Ok(Some((data, from)))
            }
            _ = tokio::time::sleep(self.recv_timeout) => Ok(None),
            _ = cancel.cancelled() => Ok(None),
        }
    }

    /// Convenience helper: send one datagram and wait for one reply.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe. It is the message-atomic [`Self::send_to`] followed by the
    /// `cancel`/timeout-aware [`Self::recv_from`]; a cancel before the send
    /// transmits nothing, and a cancel during the wait is handled by `recv_from`
    /// returning `Ok(None)`. No partial datagram or torn relay state results.
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
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    #[tokio::test]
    async fn tunnel_receive_pool_allocates_mtu_sized_buffers_on_demand() {
        let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
        assert!(budget.receive_pool.buffers.lock().expect("buffer pool").is_empty());

        let buffer = budget.receive_pool.acquire().await.expect("receive buffer");

        assert_eq!(buffer.buffer.as_ref().expect("pooled buffer").len(), 1500 + MAX_SOCKS5_UDP_HEADER_LEN);
        drop(buffer);
        assert_eq!(budget.receive_pool.buffers.lock().expect("buffer pool").len(), 1);
    }

    #[test]
    fn aggregate_queue_budget_is_shared_and_released_with_datagram() {
        let budget = UdpMemoryBudget::new(1500, 1, 5);
        let first = budget.try_reserve_queued_bytes(4).expect("first reservation");
        assert!(budget.try_reserve_queued_bytes(2).is_none(), "aggregate byte budget must reject excess");

        drop(first);

        assert!(budget.try_reserve_queued_bytes(5).is_some(), "dropping queued datagram must release its bytes");
    }

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
                if let Ok((n, peer)) = udp_echo.recv_from(&mut buf).await
                    && let Ok((_from, payload)) = decode_udp_frame(&buf[..n])
                {
                    let src: SocketAddr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), relay_port);
                    let reply = encode_udp_frame(src, payload);
                    let _ = udp_echo.send_to(&reply, peer).await;
                    if duplicate_first_reply && datagram_index == 0 {
                        let push = encode_udp_frame(src, b"push");
                        let _ = udp_echo.send_to(&push, peer).await;
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
        recv_buf: Arc<Mutex<Vec<u8>>>,
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
                recv_buf: Arc::new(Mutex::new(vec![0; MAX_UDP_DATAGRAM_LEN])),
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
            tokio::select! {
                result = async {
                    let mut buf = self.recv_buf.lock().await;
                    let result = self.udp.recv_from(&mut buf).await;
                    (result, buf)
                } => {
                    let (result, buf) = result;
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

        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_secs(3));

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

        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_secs(5));

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
        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_millis(100));

        let result = session.relay_once(dst, b"ping", CancellationToken::new()).await.unwrap();
        assert!(result.is_none(), "timed-out relay must return None");
    }

    /// Unreachable proxy → Err.
    #[tokio::test]
    async fn relay_once_bad_proxy_returns_err() {
        let bad_proxy: SocketAddr = "127.0.0.1:1".parse().unwrap();
        let result = UdpSession::connect(bad_proxy, Auth::NoAuth, None).await;
        assert!(result.is_err(), "unreachable proxy must yield Err");
    }

    #[tokio::test]
    async fn send_to_and_recv_from_reuse_single_association() {
        let (proxy_addr, echo_addr, accepted_tcp_connections) = spawn_stub_proxy(2, false).await;

        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_secs(3));

        session.send_to(echo_addr, b"first").await.unwrap();
        let first = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(first.0, b"first");

        session.send_to(echo_addr, b"second").await.unwrap();
        let second = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(second.0, b"second");

        assert_eq!(accepted_tcp_connections.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn recv_from_reuses_the_session_receive_buffer() {
        let (proxy_addr, echo_addr, _accepted_tcp_connections) = spawn_stub_proxy(2, false).await;
        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_secs(3));

        session.send_to(echo_addr, b"first").await.unwrap();
        let (first, _from) = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(first, b"first");
        let initial_ptr = session
            .memory_budget
            .receive_pool
            .buffers
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .last()
            .expect("receive buffer returned to pool")
            .as_ptr();

        session.send_to(echo_addr, b"second").await.unwrap();
        let (second, _from) = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        assert_eq!(second, b"second");

        let buffers =
            session.memory_budget.receive_pool.buffers.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let final_buf = buffers.last().expect("receive buffer returned to pool");
        assert_eq!(final_buf.len(), MAX_UDP_DATAGRAM_LEN);
        assert_eq!(final_buf.as_ptr(), initial_ptr, "the pooled receive allocation must be reused across datagrams");
    }

    #[tokio::test]
    async fn recv_from_supports_multiple_replies_on_same_association() {
        let (proxy_addr, echo_addr, accepted_tcp_connections) = spawn_stub_proxy(1, true).await;

        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .unwrap()
            .with_recv_timeout(Duration::from_secs(3));

        session.send_to(echo_addr, b"ping").await.unwrap();

        let first = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();
        let second = session.recv_from(CancellationToken::new()).await.unwrap().unwrap();

        assert_eq!(first.0, b"ping");
        assert_eq!(second.0, b"push");
        assert_eq!(accepted_tcp_connections.load(Ordering::Relaxed), 1);
    }

    /// G1 regression: both the control TCP socket and the relay UDP socket must
    /// pass through the `VpnService.protect()` callback before they issue
    /// connect/bind, so a non-loopback proxy address cannot loop traffic back
    /// into the TUN the VPN owns. See
    /// `.claude/rules/vpnservice-protect-invariant.md`.
    #[tokio::test]
    async fn udp_session_protects_ctrl_and_relay_sockets() {
        use ripdpi_runtime_platform::protect::{
            ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
        };
        use std::os::fd::RawFd;
        use std::sync::Mutex as StdMutex;

        struct Recorder {
            fds: StdMutex<Vec<RawFd>>,
        }
        impl ProtectCallback for Recorder {
            fn protect(&self, fd: RawFd) -> io::Result<()> {
                self.fds.lock().expect("recorder lock").push(fd);
                Ok(())
            }
        }

        // RAII release so a panicking assertion still clears the process-global
        // protect slot; the generation guard makes it a no-op if anything else
        // superseded the registration.
        struct Guard(ProtectGeneration);
        impl Drop for Guard {
            fn drop(&mut self) {
                // Best-effort RAII release: a false return (superseded by another
                // test's registration) is intentionally ignored because this guard
                // only owns cleanup for its own generation.
                let _ = unregister_protect_callback_if(self.0);
            }
        }

        // Serialize against other tests that touch the process-global protect slot
        // (see crate::PROTECT_CALLBACK_TEST_LOCK). Declared before the Guard so the
        // callback is unregistered before the lock is released.
        let _protect_lock = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;

        let recorder = Arc::new(Recorder { fds: StdMutex::new(Vec::new()) });
        let generation = register_protect_callback_versioned(Arc::clone(&recorder) as Arc<dyn ProtectCallback>);
        let _guard = Guard(generation);

        let (proxy_addr, echo_addr, _accepted) = spawn_stub_proxy(1, false).await;
        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .expect("session connects")
            .with_recv_timeout(Duration::from_secs(3));

        // A successful round-trip proves both sockets were created and usable.
        let (payload, _from) = session
            .relay_once(echo_addr, b"ping", CancellationToken::new())
            .await
            .expect("relay ok")
            .expect("echo response");
        assert_eq!(payload, b"ping");

        // The recorder captured at least the control TCP fd and the relay UDP
        // fd, both protected before they were used. (Other concurrent tests in
        // this binary may add more fds to the shared slot; the floor is what
        // this session protected.)
        let protected = recorder.fds.lock().expect("recorder lock").len();
        assert!(
            protected >= 2,
            "expected the ctrl + relay sockets to be protected before use, got {protected} protect call(s)"
        );
    }

    // ── QUIC E2E: real QUIC handshake + echo through the tunnel's SOCKS5 UDP relay ──

    /// `quinn::AsyncUdpSocket` adapter that drives a quinn client through the
    /// tunnel's SOCKS5 UDP relay codec.
    ///
    /// Every outbound QUIC datagram is SOCKS5-UDP-framed (`encode_udp_frame`)
    /// before sending; every inbound SOCKS5 frame is decoded (`decode_udp_frame`)
    /// so quinn receives raw QUIC bytes. Uses the production codec so RSV
    /// validation, IPv6 checks, and FRAG rejection are all exercised.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    mod quic_adapter {
        use std::pin::Pin;
        use std::sync::Arc;
        use std::task::{Context, Poll};
        use tokio::net::UdpSocket as TokioUdpSocket;

        use super::{decode_udp_frame, encode_udp_frame};

        #[derive(Debug)]
        pub(super) struct Socks5QuicAdapter {
            pub(super) socket: TokioUdpSocket,
            pub(super) quic_server_addr: std::net::SocketAddr,
        }

        impl quinn::AsyncUdpSocket for Socks5QuicAdapter {
            fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn quinn::UdpPoller>> {
                #[derive(Debug)]
                struct Poller(Arc<Socks5QuicAdapter>);
                impl quinn::UdpPoller for Poller {
                    fn poll_writable(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
                        self.0.socket.poll_send_ready(cx)
                    }
                }
                Box::pin(Poller(self))
            }

            fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> std::io::Result<()> {
                // No GSO through SOCKS5 framing: send each segment individually.
                self.socket.try_io(tokio::io::Interest::WRITABLE, || {
                    let seg = transmit.segment_size.unwrap_or(transmit.contents.len());
                    for chunk in transmit.contents.chunks(seg) {
                        let frame = encode_udp_frame(self.quic_server_addr, chunk);
                        let sent = self.socket.try_send(&frame)?;
                        if sent != frame.len() {
                            return Err(std::io::Error::new(std::io::ErrorKind::WriteZero, "short SOCKS5 UDP send"));
                        }
                    }
                    Ok(())
                })
            }

            fn poll_recv(
                &self,
                cx: &mut Context<'_>,
                bufs: &mut [std::io::IoSliceMut<'_>],
                meta: &mut [quinn::udp::RecvMeta],
            ) -> Poll<std::io::Result<usize>> {
                let mut scratch = vec![0u8; 65535];
                std::task::ready!(self.socket.poll_recv_ready(cx))?;
                match self.socket.try_io(tokio::io::Interest::READABLE, || self.socket.try_recv(&mut scratch)) {
                    Ok(n) => match decode_udp_frame(&scratch[..n]) {
                        Ok((from_addr, payload)) => {
                            let first = bufs.first_mut().ok_or_else(|| {
                                std::io::Error::new(std::io::ErrorKind::InvalidInput, "no recv buffer")
                            })?;
                            let copy_len = payload.len().min(first.len());
                            first[..copy_len].copy_from_slice(&payload[..copy_len]);
                            meta[0] = quinn::udp::RecvMeta {
                                addr: from_addr,
                                len: copy_len,
                                stride: copy_len,
                                ecn: None,
                                dst_ip: None,
                            };
                            Poll::Ready(Ok(1))
                        }
                        Err(_) => Poll::Pending,
                    },
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Poll::Pending,
                    Err(e) => Poll::Ready(Err(e)),
                }
            }

            fn local_addr(&self) -> std::io::Result<std::net::SocketAddr> {
                self.socket.local_addr()
            }

            fn may_fragment(&self) -> bool {
                false
            }
        }
    }

    /// Test-only TLS verifier: accepts any server certificate.
    ///
    /// The quinn echo server presents an rcgen self-signed cert with no chain;
    /// this verifier bypasses chain validation so the test stays hermetic.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    #[derive(Debug)]
    struct AcceptAnyCert;

    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    impl rustls::client::danger::ServerCertVerifier for AcceptAnyCert {
        fn verify_server_cert(
            &self,
            _end_entity: &rustls::pki_types::CertificateDer<'_>,
            _intermediates: &[rustls::pki_types::CertificateDer<'_>],
            _server_name: &rustls::pki_types::ServerName<'_>,
            _ocsp_response: &[u8],
            _now: rustls::pki_types::UnixTime,
        ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        }
        fn verify_tls12_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
        }
        fn verify_tls13_signature(
            &self,
            _message: &[u8],
            _cert: &rustls::pki_types::CertificateDer<'_>,
            _dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
        }
        fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
            rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
        }
    }

    /// Bind a quinn QUIC echo server on a loopback port (rcgen self-signed cert,
    /// ring crypto). Returns `(addr, join_handle)`.
    ///
    /// The server task accepts one connection, echoes one bi-directional stream,
    /// then closes; the caller awaits the handle after the client finishes.
    // cancel-safe: not applicable — test helper, not an async path.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    async fn spawn_quinn_echo_server(alpn: &[u8]) -> (SocketAddr, tokio::task::JoinHandle<()>) {
        use rcgen::generate_simple_self_signed;
        use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};

        let cert = generate_simple_self_signed(vec!["localhost".to_string()]).expect("rcgen self-signed cert");
        let cert_der = CertificateDer::from(cert.cert.der().to_vec());
        let key_der = PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der());

        let mut server_tls =
            rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der.into())
                .expect("server TLS config");
        server_tls.alpn_protocols = vec![alpn.to_vec()];

        let mut server_cfg = quinn::ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).expect("QuicServerConfig"),
        ));
        let mut transport = quinn::TransportConfig::default();
        // Short idle timeout so wait_idle() drains quickly at teardown.
        transport.max_idle_timeout(Some(Duration::from_secs(3).try_into().expect("idle timeout")));
        server_cfg.transport = Arc::new(transport);

        let endpoint =
            quinn::Endpoint::server(server_cfg, "127.0.0.1:0".parse().unwrap()).expect("bind QUIC echo server");
        let addr = endpoint.local_addr().expect("server local_addr");

        let task = tokio::spawn(async move {
            let conn = endpoint.accept().await.expect("server accept").await.expect("server establish");
            let (mut send, mut recv) = conn.accept_bi().await.expect("accept bi-stream");
            let mut buf = [0u8; 256];
            let n = recv.read(&mut buf).await.expect("server read").expect("some bytes");
            send.write_all(&buf[..n]).await.expect("server echo write");
            send.finish().expect("server finish stream");
            conn.closed().await;
            endpoint.close(0u32.into(), b"done");
            endpoint.wait_idle().await;
        });
        (addr, task)
    }

    /// Spawn a transparent SOCKS5 forwarding relay + minimal stub proxy.
    ///
    /// The relay forwards between the Socks5QuicAdapter and the quinn server:
    /// - client→server: decode SOCKS5 frame → send raw QUIC bytes to server.
    /// - server→client: wrap raw QUIC as SOCKS5 frame (source = `server_addr`).
    ///
    /// Returns `(proxy_addr, relay_addr, c2s_handle, s2c_handle)`.
    /// Abort both handles at teardown.
    // cancel-safe: not applicable — test helper.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    async fn spawn_socks5_forwarding_relay(
        server_addr: SocketAddr,
    ) -> (SocketAddr, SocketAddr, tokio::task::JoinHandle<()>, tokio::task::JoinHandle<()>) {
        let relay_udp = Arc::new(UdpSocket::bind("127.0.0.1:0").await.expect("bind relay UDP"));
        let relay_addr = relay_udp.local_addr().expect("relay local_addr");

        let fwd_udp = Arc::new(UdpSocket::bind("127.0.0.1:0").await.expect("bind fwd UDP"));
        fwd_udp.connect(server_addr).await.expect("connect fwd to quinn server");

        // Two independent tasks prevent either direction from starving the other
        // during the multi-packet QUIC handshake.
        let client_addr_cell = Arc::new(tokio::sync::Mutex::new(Option::<SocketAddr>::None));

        let (fwd_c2s, relay_c2s, cell_c2s) =
            (Arc::clone(&fwd_udp), Arc::clone(&relay_udp), Arc::clone(&client_addr_cell));
        let c2s = tokio::spawn(async move {
            let mut buf = vec![0u8; 65535];
            loop {
                let Ok((n, peer)) = relay_c2s.recv_from(&mut buf).await else { break };
                *cell_c2s.lock().await = Some(peer);
                if let Ok((_dst, payload)) = decode_udp_frame(&buf[..n]) {
                    let _ = fwd_c2s.send(payload).await;
                }
            }
        });

        let (fwd_s2c, relay_s2c, cell_s2c) =
            (Arc::clone(&fwd_udp), Arc::clone(&relay_udp), Arc::clone(&client_addr_cell));
        let s2c = tokio::spawn(async move {
            let mut buf = vec![0u8; 65535];
            loop {
                let Ok(n) = fwd_s2c.recv(&mut buf).await else { break };
                if let Some(peer) = *cell_s2c.lock().await {
                    // Source = server_addr so quinn's RecvMeta.addr matches the peer.
                    let frame = encode_udp_frame(server_addr, &buf[..n]);
                    let _ = relay_s2c.send_to(&frame, peer).await;
                }
            }
        });

        // Stub proxy: NoAuth handshake + ASSOCIATE reply advertising relay_addr.
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind stub proxy");
        let proxy_addr = listener.local_addr().expect("proxy local_addr");
        let relay_port = relay_addr.port();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("proxy accept");
            let mut buf = [0u8; 64];
            let _ = stream.read(&mut buf).await;
            stream.write_all(&[0x05, 0x00]).await.expect("proxy greeting");
            let _ = stream.read(&mut buf).await;
            let p = relay_port.to_be_bytes();
            stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p[0], p[1]]).await.expect("proxy ASSOCIATE reply");
            tokio::time::sleep(Duration::from_secs(10)).await;
        });

        (proxy_addr, relay_addr, c2s, s2c)
    }

    /// Build a `quinn::ClientConfig` with `AcceptAnyCert`, a generous idle
    /// timeout, and keep-alive (robust under heavy parallel test load).
    // cancel-safe: not applicable — synchronous.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    fn build_insecure_quinn_client_config(alpn: &[u8]) -> quinn::ClientConfig {
        let mut tls = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring supports default TLS versions")
            // SAFETY: test-only — server uses an rcgen self-signed cert with no chain.
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(AcceptAnyCert))
            .with_no_client_auth();
        tls.alpn_protocols = vec![alpn.to_vec()];

        let mut cfg = quinn::ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(tls).expect("QuicClientConfig"),
        ));
        let mut transport = quinn::TransportConfig::default();
        // Generous idle timeout + keep-alive: nextest runs this test alongside
        // ~1000 others, and packets relayed through the two forwarding tasks can
        // be delayed under that load. A tight 3 s idle timeout flaked with
        // ConnectionLost(TimedOut); 30 s plus 1 s keep-alive PINGs keep the
        // connection alive across scheduler jitter without slowing the happy path.
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().expect("idle timeout")));
        transport.keep_alive_interval(Some(Duration::from_secs(1)));
        cfg.transport_config(Arc::new(transport));
        cfg
    }

    /// Bind a quinn client `Endpoint` using a `Socks5QuicAdapter` socket
    /// already connected to `relay_addr`.
    // cancel-safe: not applicable — async only for the UdpSocket bind.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    async fn build_quinn_client_endpoint(
        relay_addr: SocketAddr,
        server_addr: SocketAddr,
        cfg: quinn::ClientConfig,
    ) -> quinn::Endpoint {
        use quic_adapter::Socks5QuicAdapter;
        let raw_udp = tokio::net::UdpSocket::bind("127.0.0.1:0").await.expect("bind quinn client UDP");
        raw_udp.connect(relay_addr).await.expect("connect quinn client UDP to relay");
        let adapter = Arc::new(Socks5QuicAdapter { socket: raw_udp, quic_server_addr: server_addr });
        let mut ep = quinn::Endpoint::new_with_abstract_socket(
            quinn::EndpointConfig::default(),
            None,
            adapter,
            Arc::new(quinn::TokioRuntime),
        )
        .expect("build QUIC client endpoint");
        ep.set_default_client_config(cfg);
        ep
    }

    /// End-to-end proof: QUIC 1-RTT handshake + app-data echo through the
    /// tunnel's SOCKS5 UDP ASSOCIATE relay (`UdpSession`).
    ///
    /// ```text
    /// quinn client (Socks5QuicAdapter)
    ///   ↓  QUIC in SOCKS5 UDP frames
    /// forwarding relay  (decode → raw QUIC → server; reply → encode → client)
    /// quinn echo server (rcgen self-signed cert, ring crypto)
    /// ```
    ///
    /// Hermetic (all loopback), no external deps, < 10 s CI.
    // cancel-safe: not applicable — #[tokio::test] drives to completion.
    #[cfg(all(not(target_os = "android"), target_os = "macos"))]
    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "covered by ripdpi-proxy-runtime network_e2e; this hand-rolled relay stub is timing-sensitive"]
    async fn quic_handshake_and_echo_round_trip_through_udp_session_relay() {
        const ALPN: &[u8] = b"tunnel-quic-e2e";

        let (server_addr, server_task) = spawn_quinn_echo_server(ALPN).await;
        let (proxy_addr, relay_addr, c2s, s2c) = spawn_socks5_forwarding_relay(server_addr).await;

        // UdpSession proves the tunnel's ASSOCIATE path reaches the relay.
        let session = UdpSession::connect(proxy_addr, Auth::NoAuth, None)
            .await
            .expect("UdpSession connects")
            .with_recv_timeout(Duration::from_secs(10));

        // Quinn client wired through Socks5QuicAdapter on the same relay.
        let cfg = build_insecure_quinn_client_config(ALPN);
        let endpoint = build_quinn_client_endpoint(relay_addr, server_addr, cfg).await;

        // 1-RTT handshake.
        // quinn 0.11: connect() → Result<Connecting>; awaiting → Result<Connection>.
        let conn = tokio::time::timeout(
            Duration::from_secs(10),
            endpoint.connect(server_addr, "localhost").expect("start QUIC connect"),
        )
        .await
        .expect("QUIC handshake completed within 10 s")
        .expect("QUIC 1-RTT handshake succeeded");

        // Application-level echo round-trip.
        let (mut send, mut recv) = conn.open_bi().await.expect("open bi-stream");
        const PAYLOAD: &[u8] = b"tunnel-quic-e2e";
        send.write_all(PAYLOAD).await.expect("client write");
        send.finish().expect("client finish stream");
        let echoed = recv.read_to_end(256).await.expect("client read echo");
        assert_eq!(&echoed, PAYLOAD, "echoed payload must match sent payload");

        // UdpSession send_to exercises the production encode path on the same relay.
        session.send_to(server_addr, b"probe").await.expect("session send_to ok");

        conn.close(0u32.into(), b"done");
        endpoint.close(0u32.into(), b"done");
        endpoint.wait_idle().await;
        server_task.await.expect("server task completed without panic");
        c2s.abort();
        s2c.abort();
        drop(session);
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
