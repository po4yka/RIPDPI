use std::future::Future;
use std::io;
use std::net::SocketAddr;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use super::socks5::{Auth, TargetAddr};

/// High-level TCP session: connect to SOCKS5 proxy, perform handshake,
/// issue CONNECT to the target, then bidirectionally relay bytes until
/// one side closes or `cancel` is signalled.
pub struct TcpSession {
    proxy_addr: SocketAddr,
    auth: Auth,
    target: TargetAddr,
}

impl TcpSession {
    pub fn new(proxy_addr: SocketAddr, auth: Auth, target: TargetAddr) -> Self {
        Self { proxy_addr, auth, target }
    }

    /// Run the session to completion.
    ///
    /// - Connects to the SOCKS5 proxy at `proxy_addr`.
    /// - Performs SOCKS5 handshake (method negotiation + optional auth).
    /// - Issues a SOCKS5 CONNECT request to `target`.
    /// - Bidirectionally splices `local` ↔ proxy until EOF on both sides or
    ///   until `cancel` is signalled (in which case the function returns `Ok(())`).
    pub async fn run<L>(&self, local: &mut L, cancel: CancellationToken) -> io::Result<()>
    where
        L: AsyncRead + AsyncWrite + Unpin,
    {
        self.run_with_connector(local, cancel, TcpStream::connect).await
    }

    async fn run_with_connector<L, C, F, P>(
        &self,
        local: &mut L,
        cancel: CancellationToken,
        connect: C,
    ) -> io::Result<()>
    where
        L: AsyncRead + AsyncWrite + Unpin,
        C: FnOnce(SocketAddr) -> F,
        F: Future<Output = io::Result<P>>,
        P: AsyncRead + AsyncWrite + Unpin,
    {
        debug!(proxy = %self.proxy_addr, target = ?self.target, "tcp session connecting to proxy");
        let mut proxy = connect(self.proxy_addr).await?;
        debug!(proxy = %self.proxy_addr, target = ?self.target, "tcp session connected to proxy");
        self.run_with_proxy(local, cancel, &mut proxy).await
    }

    async fn run_with_proxy<L, P>(&self, local: &mut L, cancel: CancellationToken, proxy: &mut P) -> io::Result<()>
    where
        L: AsyncRead + AsyncWrite + Unpin,
        P: AsyncRead + AsyncWrite + Unpin,
    {
        super::socks5::handshake(proxy, &self.auth).await?;
        debug!(proxy = %self.proxy_addr, target = ?self.target, "tcp session SOCKS5 handshake complete");
        super::socks5::connect(proxy, &self.target).await?;
        debug!(proxy = %self.proxy_addr, target = ?self.target, "tcp session SOCKS5 CONNECT complete");
        tokio::select! {
            result = splice(local, proxy) => {
                match &result {
                    Ok((forward, backward)) => {
                        debug!(
                            proxy = %self.proxy_addr,
                            target = ?self.target,
                            forward_bytes = *forward,
                            backward_bytes = *backward,
                            "tcp session relay completed"
                        );
                    }
                    Err(err) => {
                        debug!(proxy = %self.proxy_addr, target = ?self.target, error = %err, "tcp session relay failed");
                    }
                }
                result.map(|_| ())
            },
            _ = cancel.cancelled() => Ok(()),
        }
    }
}

/// Bidirectionally splice bytes between `local` and `proxy` until both sides close.
///
/// Returns `(forward_bytes, backward_bytes)`:
/// - `forward_bytes`:  bytes copied local → proxy
/// - `backward_bytes`: bytes copied proxy → local
///
/// When one read side returns EOF, the opposite write side is shut down (half-close),
/// matching RFC 1928 §6 session semantics.  The function returns only when both
/// directions have finished.
pub async fn splice<L, P>(local: &mut L, proxy: &mut P) -> io::Result<(u64, u64)>
where
    L: AsyncRead + AsyncWrite + Unpin,
    P: AsyncRead + AsyncWrite + Unpin,
{
    let (mut local_read, mut local_write) = tokio::io::split(local);
    let (mut proxy_read, mut proxy_write) = tokio::io::split(proxy);

    let forward = async {
        let copied = tokio::io::copy(&mut local_read, &mut proxy_write).await?;
        proxy_write.shutdown().await?;
        Ok::<u64, io::Error>(copied)
    };

    let backward = async {
        let copied = tokio::io::copy(&mut proxy_read, &mut local_write).await?;
        local_write.shutdown().await?;
        Ok::<u64, io::Error>(copied)
    };

    tokio::try_join!(forward, backward)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::socks5;
    use std::net::{IpAddr, Ipv4Addr};
    use std::sync::OnceLock;
    use std::time::Duration;
    use tokio::io::{duplex, AsyncReadExt, AsyncWriteExt};
    use tokio::time::{sleep, timeout};
    use tracing_subscriber::EnvFilter;

    use local_network_fixture::{FixtureConfig, FixtureStack};

    static TRACING_INIT: OnceLock<()> = OnceLock::new();

    fn init_test_tracing() {
        TRACING_INIT.get_or_init(|| {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(EnvFilter::new("ripdpi_tunnel_core=debug"))
                .with_test_writer()
                .with_ansi(false)
                .try_init();
        });
    }

    async fn read_socks5_addr<S>(stream: &mut S, atyp: u8) -> io::Result<()>
    where
        S: AsyncRead + Unpin,
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

    async fn serve_socks5_echo<S>(stream: &mut S) -> io::Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        let mut greeting = [0u8; 3];
        stream.read_exact(&mut greeting).await?;
        assert_eq!(greeting, [0x05, 0x01, 0x00], "unexpected SOCKS5 greeting");
        stream.write_all(&[0x05, 0x00]).await?;

        let mut header = [0u8; 4];
        stream.read_exact(&mut header).await?;
        assert_eq!(&header[..3], &[0x05, 0x01, 0x00], "unexpected CONNECT header");
        read_socks5_addr(stream, header[3]).await?;
        stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0]).await?;

        let mut buf = [0u8; 1024];
        loop {
            let n = stream.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            stream.write_all(&buf[..n]).await?;
        }
        stream.shutdown().await?;
        Ok(())
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /// Write `data` into a DuplexStream and shut down its write half.
    /// The peer's read half will see `data` followed by EOF.
    async fn feed_and_close(stream: &mut tokio::io::DuplexStream, data: &[u8]) {
        stream.write_all(data).await.unwrap();
        stream.shutdown().await.unwrap();
    }

    // ─────────────────────────────────────────────────────────────
    // Forward splice: local → proxy
    // ─────────────────────────────────────────────────────────────

    /// 1 MiB of data written into the local side must arrive at the proxy side
    /// intact, and `splice` must report the correct forward byte count.
    #[tokio::test]
    async fn splice_forward_1mb() {
        const SIZE: usize = 1_048_576;
        let payload: Vec<u8> = (0..SIZE as u32).map(|i| i as u8).collect();

        // Buffers large enough that a 1 MiB write never blocks in a single-
        // threaded test runtime.
        let (mut local, mut test_local) = duplex(SIZE + 1);
        let (mut proxy, mut test_proxy) = duplex(SIZE + 1);

        // Forward source: 1 MiB into local's read buffer, then EOF.
        feed_and_close(&mut test_local, &payload).await;
        // Backward source: no data, just EOF on proxy's read side.
        test_proxy.shutdown().await.unwrap();

        let (fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        // Ensure write sides are closed so read_to_end() below terminates.
        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_proxy.read_to_end(&mut received).await.unwrap();

        assert_eq!(fwd, SIZE as u64, "forward byte count must equal 1 MiB");
        assert_eq!(bwd, 0, "no backward bytes expected");
        assert_eq!(received, payload, "forward data must arrive at proxy intact");
    }

    // ─────────────────────────────────────────────────────────────
    // Backward splice: proxy → local
    // ─────────────────────────────────────────────────────────────

    /// 1 MiB of data written into the proxy side must arrive at the local side
    /// intact, and `splice` must report the correct backward byte count.
    #[tokio::test]
    async fn splice_backward_1mb() {
        const SIZE: usize = 1_048_576;
        let payload: Vec<u8> = (0..SIZE as u32).map(|i| i.wrapping_mul(3) as u8).collect();

        let (mut local, mut test_local) = duplex(SIZE + 1);
        let (mut proxy, mut test_proxy) = duplex(SIZE + 1);

        // Forward source: no data.
        test_local.shutdown().await.unwrap();
        // Backward source: 1 MiB into proxy's read buffer, then EOF.
        feed_and_close(&mut test_proxy, &payload).await;

        let (fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_local.read_to_end(&mut received).await.unwrap();

        assert_eq!(fwd, 0, "no forward bytes expected");
        assert_eq!(bwd, SIZE as u64, "backward byte count must equal 1 MiB");
        assert_eq!(received, payload, "backward data must arrive at local intact");
    }

    // ─────────────────────────────────────────────────────────────
    // EOF propagation
    // ─────────────────────────────────────────────────────────────

    /// When the proxy closes its write side (EOF), all in-flight data must be
    /// delivered to the local side before `splice` returns.
    #[tokio::test]
    async fn eof_from_proxy_propagates() {
        let data = b"proxy-initiated-close-payload";

        let (mut local, mut test_local) = duplex(1024);
        let (mut proxy, mut test_proxy) = duplex(1024);

        // Backward source: small payload then EOF from proxy.
        feed_and_close(&mut test_proxy, data).await;
        // Forward source: no data.
        test_local.shutdown().await.unwrap();

        let (_fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_local.read_to_end(&mut received).await.unwrap();

        assert_eq!(bwd, data.len() as u64, "all backward bytes must be counted on proxy EOF");
        assert_eq!(received, data, "proxy EOF must not drop in-flight data before delivering to local");
    }

    /// When the local side closes its write side (EOF), all in-flight data must
    /// be delivered to the proxy before `splice` returns.
    #[tokio::test]
    async fn eof_from_local_propagates() {
        let data = b"local-initiated-close-payload";

        let (mut local, mut test_local) = duplex(1024);
        let (mut proxy, mut test_proxy) = duplex(1024);

        // Forward source: small payload then EOF from local.
        feed_and_close(&mut test_local, data).await;
        // Backward source: no data.
        test_proxy.shutdown().await.unwrap();

        let (fwd, _bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_proxy.read_to_end(&mut received).await.unwrap();

        assert_eq!(fwd, data.len() as u64, "all forward bytes must be counted on local EOF");
        assert_eq!(received, data, "local EOF must not drop in-flight data before delivering to proxy");
    }

    #[test]
    fn turmoil_tcp_session_reconnects_after_partition_repair() -> turmoil::Result {
        let mut sim = turmoil::Builder::new().build();

        sim.host("proxy", || async move {
            let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 1080)).await?;
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().await?;
                serve_socks5_echo(&mut stream).await?;
            }
            Ok(())
        });

        sim.client("client", async move {
            let proxy_addr = SocketAddr::new(turmoil::lookup("proxy"), 1080);
            let session =
                TcpSession::new(proxy_addr, Auth::NoAuth, TargetAddr::Domain("fixture.test".to_string(), 443));

            turmoil::partition("client", "proxy");
            let (mut failed_local, _failed_peer) = tokio::io::duplex(128);
            let err = session
                .run_with_connector(&mut failed_local, CancellationToken::new(), turmoil::net::TcpStream::connect)
                .await
                .expect_err("partitioned connect must fail");
            assert_eq!(err.kind(), io::ErrorKind::ConnectionRefused);

            turmoil::repair("client", "proxy");

            let (mut local, mut peer) = tokio::io::duplex(1024);
            let payload = b"repaired-path";
            peer.write_all(payload).await.unwrap();
            peer.shutdown().await.unwrap();

            session
                .run_with_connector(&mut local, CancellationToken::new(), turmoil::net::TcpStream::connect)
                .await
                .expect("session should reconnect after partition repair");

            let mut echoed = Vec::new();
            peer.read_to_end(&mut echoed).await.unwrap();
            assert_eq!(echoed, payload);
            Ok(())
        });

        sim.run()
    }

    #[tokio::test]
    async fn fixture_backed_tcp_session_keeps_local_side_open_while_idle() {
        init_test_tracing();
        let fixture = FixtureStack::start(FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        })
        .expect("start fixture");
        let manifest = fixture.manifest();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), manifest.socks5_port);
        let target =
            SocketAddr::new(manifest.fixture_ipv4.parse::<IpAddr>().expect("fixture ipv4"), manifest.tcp_echo_port);
        let session = TcpSession::new(proxy_addr, Auth::NoAuth, TargetAddr::Ip(target));
        let cancel = CancellationToken::new();

        let (mut local, mut peer) = tokio::io::duplex(1024);
        let cancel_clone = cancel.clone();
        let join = tokio::spawn(async move { session.run(&mut local, cancel_clone).await });

        sleep(Duration::from_millis(200)).await;

        let mut byte = [0u8; 1];
        let pending_read = timeout(Duration::from_millis(200), peer.read(&mut byte)).await;
        assert!(
            pending_read.is_err(),
            "fixture-backed session should stay open while upstream is idle, got {pending_read:?}"
        );

        cancel.cancel();
        join.await.expect("join tcp session").expect("cancel fixture-backed tcp session");
    }

    #[tokio::test]
    async fn fixture_backed_tcp_session_round_trips_payload() {
        init_test_tracing();
        let fixture = FixtureStack::start(FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        })
        .expect("start fixture");
        let manifest = fixture.manifest();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), manifest.socks5_port);
        let target =
            SocketAddr::new(manifest.fixture_ipv4.parse::<IpAddr>().expect("fixture ipv4"), manifest.tcp_echo_port);
        let session = TcpSession::new(proxy_addr, Auth::NoAuth, TargetAddr::Ip(target));
        let cancel = CancellationToken::new();

        let (mut local, mut peer) = tokio::io::duplex(1024);
        let cancel_clone = cancel.clone();
        let join = tokio::spawn(async move { session.run(&mut local, cancel_clone).await });

        let payload = b"fixture-round-trip";
        peer.write_all(payload).await.expect("write fixture payload");
        let mut echoed = vec![0u8; payload.len()];
        let echo_result = timeout(Duration::from_secs(1), peer.read_exact(&mut echoed)).await;
        let fixture_events = fixture.events().snapshot();
        echo_result
            .unwrap_or_else(|_| panic!("wait for fixture echo; fixture_events={fixture_events:?}"))
            .unwrap_or_else(|err| panic!("read fixture echo: {err}; fixture_events={fixture_events:?}"));
        assert_eq!(echoed, payload);

        cancel.cancel();
        join.await.expect("join tcp session").expect("cancel fixture-backed tcp session");
    }

    #[tokio::test]
    async fn async_socks5_client_round_trips_against_fixture() {
        init_test_tracing();
        let fixture = FixtureStack::start(FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        })
        .expect("start fixture");
        let manifest = fixture.manifest();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), manifest.socks5_port);
        let target =
            SocketAddr::new(manifest.fixture_ipv4.parse::<IpAddr>().expect("fixture ipv4"), manifest.tcp_echo_port);

        let mut stream = TcpStream::connect(proxy_addr).await.expect("connect fixture socks");
        socks5::handshake(&mut stream, &Auth::NoAuth).await.expect("async socks handshake");
        socks5::connect(&mut stream, &TargetAddr::Ip(target)).await.expect("async socks connect");

        let payload = b"fixture-async-client";
        stream.write_all(payload).await.expect("write fixture async payload");
        let mut echoed = vec![0u8; payload.len()];
        let echo_result = timeout(Duration::from_secs(1), stream.read_exact(&mut echoed)).await;
        let fixture_events = fixture.events().snapshot();
        echo_result
            .unwrap_or_else(|_| panic!("wait for async fixture echo; fixture_events={fixture_events:?}"))
            .unwrap_or_else(|err| panic!("read async fixture echo: {err}; fixture_events={fixture_events:?}"));
        assert_eq!(echoed, payload);
    }

    #[tokio::test]
    async fn splice_with_idle_tcp_peer_keeps_local_side_open() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind idle listener");
        let addr = listener.local_addr().expect("idle listener addr");
        let server = std::thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept idle peer");
            std::thread::sleep(Duration::from_millis(500));
        });

        let mut proxy = TcpStream::connect(addr).await.expect("connect idle peer");
        let (mut local, mut peer) = tokio::io::duplex(1024);
        let join = tokio::spawn(async move { splice(&mut local, &mut proxy).await });

        let mut byte = [0u8; 1];
        let pending_read = timeout(Duration::from_millis(200), peer.read(&mut byte)).await;
        assert!(pending_read.is_err(), "plain TCP idle peer should not close the local side, got {pending_read:?}");

        drop(peer);
        let _ = join.await.expect("join idle splice");
        server.join().expect("join idle server");
    }

    #[tokio::test]
    async fn splice_with_tcp_echo_peer_round_trips_payload() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind echo listener");
        let addr = listener.local_addr().expect("echo listener addr");
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept echo peer");
            let mut buf = [0u8; 256];
            let read = std::io::Read::read(&mut stream, &mut buf).expect("read echo payload");
            std::io::Write::write_all(&mut stream, &buf[..read]).expect("write echo payload");
        });

        let mut proxy = TcpStream::connect(addr).await.expect("connect echo peer");
        let (mut local, mut peer) = tokio::io::duplex(1024);
        let join = tokio::spawn(async move { splice(&mut local, &mut proxy).await });

        let payload = b"splice-echo";
        peer.write_all(payload).await.expect("write splice payload");
        let mut echoed = vec![0u8; payload.len()];
        timeout(Duration::from_secs(1), peer.read_exact(&mut echoed))
            .await
            .expect("wait for splice echo")
            .expect("read splice echo");
        assert_eq!(echoed, payload);

        drop(peer);
        let _ = join.await.expect("join echo splice");
        server.join().expect("join echo server");
    }

    #[test]
    fn turmoil_tcp_session_times_out_while_connect_path_is_held() -> turmoil::Result {
        let mut sim = turmoil::Builder::new().build();

        sim.host("proxy", || async move {
            let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 1080)).await?;
            let (mut stream, _) = listener.accept().await?;
            serve_socks5_echo(&mut stream).await?;
            Ok(())
        });

        sim.client("client", async move {
            let proxy_addr = SocketAddr::new(turmoil::lookup("proxy"), 1080);
            let session =
                TcpSession::new(proxy_addr, Auth::NoAuth, TargetAddr::Ip(SocketAddr::new(proxy_addr.ip(), 80)));
            let (mut local, _peer) = tokio::io::duplex(128);

            turmoil::hold("client", "proxy");
            let held = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                session.run_with_connector(&mut local, CancellationToken::new(), turmoil::net::TcpStream::connect),
            )
            .await;
            assert!(held.is_err(), "held connect path should block deterministically");

            turmoil::release("client", "proxy");
            Ok(())
        });

        sim.run()
    }
}
