use super::*;
use tokio::time::{Instant, sleep, timeout};

const REQUEST_TIMEOUT: Duration = Duration::from_millis(40);
const OUTER_TIMEOUT: Duration = Duration::from_millis(400);

/// # Cancel safety:
/// cancel-safe: cancellation drops only the local listener and connection futures.
async fn tcp_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let (client, server) = tokio::join!(TcpStream::connect(listener.local_addr().unwrap()), listener.accept());
    (client.unwrap(), server.unwrap().0)
}

fn assert_request_timeout<T>(result: Result<T, SocksError>) {
    assert!(
        matches!(result, Err(SocksError::ServerError(SocksServerError::Io { ref source, .. })) if source.kind() == io::ErrorKind::TimedOut),
        "request must return a TimedOut error"
    );
}

/// # Cancel safety:
/// cancel-safe: cancellation drops each owned test connection.
#[tokio::test(flavor = "current_thread")]
async fn incomplete_handshake_stages_expire() {
    for bytes in [vec![5], vec![5, 1, 2, 1, 1, b'u'], vec![5, 1, 0, 5, 1, 0, 1]] {
        let (mut client, server) = tcp_pair().await;
        let mut config = Config::<DenyAuthentication>::default();
        config.set_request_timeout(REQUEST_TIMEOUT);
        let mut config = config.with_authentication(AcceptAuthentication::default());
        if bytes[1..].first() == Some(&1) && bytes.get(2) == Some(&0) {
            config.set_allow_no_auth(true);
        }
        client.write_all(&bytes).await.unwrap();
        let result = timeout(OUTER_TIMEOUT, Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5()).await;
        assert!(result.is_ok(), "request stayed pending beyond its timeout for {bytes:?}");
        assert_request_timeout(result.unwrap());
        let mut rest = Vec::new();
        timeout(OUTER_TIMEOUT, client.read_to_end(&mut rest)).await.unwrap().unwrap();
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned callback future and both local sockets.
#[tokio::test(flavor = "current_thread")]
async fn stalled_authentication_callback_expires() {
    struct PendingAuthentication;
    impl Authentication for PendingAuthentication {
        type Item = ();
        /// # Cancel safety:
        /// cancel-safe: the pending callback owns no external operation or resource.
        async fn authenticate(&self, _: Option<(String, String)>) -> Option<()> {
            std::future::pending().await
        }
    }
    let (mut client, server) = tcp_pair().await;
    let mut config = Config::<DenyAuthentication>::default().with_authentication(PendingAuthentication);
    config.set_request_timeout(REQUEST_TIMEOUT);
    client.write_all(&[5, 1, 2, 1, 1, b'u', 1, b'p']).await.unwrap();
    let result = timeout(OUTER_TIMEOUT, Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5()).await;
    assert!(result.is_ok(), "authentication callback exceeded the request timeout");
    assert_request_timeout(result.unwrap());
}

/// # Cancel safety:
/// cancel-safe: both joined futures own only local test sockets.
#[tokio::test(flavor = "current_thread")]
async fn handshake_uses_one_absolute_deadline() {
    let (mut client, server) = tcp_pair().await;
    let mut config = Config::<DenyAuthentication>::default();
    config.set_request_timeout(Duration::from_millis(100));
    let start = Instant::now();
    let (result, ()) = tokio::join!(
        timeout(OUTER_TIMEOUT, Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5()),
        async move {
            client.write_all(&[5]).await.unwrap();
            sleep(Duration::from_millis(65)).await;
            client.write_all(&[1, 0]).await.unwrap();
            let mut reply = [0; 2];
            client.read_exact(&mut reply).await.unwrap();
            assert_eq!(reply, [5, 0]);
            let mut eof = [0];
            assert_eq!(timeout(OUTER_TIMEOUT, client.read(&mut eof)).await.unwrap().unwrap(), 0);
        }
    );
    assert!(result.is_ok(), "slow greeting kept the request pending");
    assert_request_timeout(result.unwrap());
    assert!(start.elapsed() < Duration::from_millis(150), "command stage restarted the deadline");
}

/// # Cancel safety:
/// cancel-safe: cancellation drops only local streams, listeners, and relay futures.
#[tokio::test(flavor = "current_thread")]
async fn command_reply_writes_expire_for_tcp_and_udp() {
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = target.local_addr().unwrap().port().to_be_bytes();
    for (command, control_peer) in [(1, true), (2, true), (3, true), (3, false)] {
        let (mut client, server) = tokio::io::duplex(1);
        let mut config = Config::<DenyAuthentication>::default();
        config.set_request_timeout(REQUEST_TIMEOUT).set_skip_auth(true).set_udp_support(true);
        let mut socket = Socks5Socket::new(server, Arc::new(config));
        if control_peer {
            socket.set_control_peer_ip(Ipv4Addr::LOCALHOST.into());
        }
        socket.set_reply_ip(Ipv4Addr::LOCALHOST.into());
        let (result, ()) = tokio::join!(timeout(OUTER_TIMEOUT, socket.upgrade_to_socks5()), async move {
            client.write_all(&[5, command, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
            sleep(Duration::from_millis(150)).await;
            drop(client);
        });
        assert!(result.is_ok(), "command reply was not bounded");
        assert_request_timeout(result.unwrap());
    }
}

/// # Cancel safety:
/// cancel-safe: all joined futures own only the local test connections.
#[tokio::test(flavor = "current_thread")]
async fn established_tcp_relay_outlives_request_timeout() {
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = target.local_addr().unwrap().port().to_be_bytes();
    let (mut client, server) = tcp_pair().await;
    let mut config = Config::<DenyAuthentication>::default();
    config.set_request_timeout(REQUEST_TIMEOUT);
    let exchange = async move {
        client.write_all(&[5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        let mut replies = [0; 12];
        client.read_exact(&mut replies).await.unwrap();
        assert_eq!(&replies[..4], &[5, 0, 5, 0]);
        sleep(REQUEST_TIMEOUT * 3).await;
        client.write_all(b"ping").await.unwrap();
        client.shutdown().await.unwrap();
        let mut payload = Vec::new();
        client.read_to_end(&mut payload).await.unwrap();
        assert_eq!(payload, b"pong");
    };
    let echo = async move {
        let (mut stream, _) = target.accept().await.unwrap();
        let mut payload = Vec::new();
        stream.read_to_end(&mut payload).await.unwrap();
        assert_eq!(payload, b"ping");
        stream.write_all(b"pong").await.unwrap();
    };
    let (result, (), ()) = timeout(OUTER_TIMEOUT, async {
        tokio::join!(Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5(), exchange, echo)
    })
    .await
    .unwrap();
    assert!(result.is_ok());
}

/// # Cancel safety:
/// cancel-safe: all joined futures own only local sockets and association resources.
#[tokio::test(flavor = "current_thread")]
async fn established_udp_association_outlives_request_timeout() {
    let (mut client, server) = tcp_pair().await;
    let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let port = target.local_addr().unwrap().port().to_be_bytes();
    let mut config = Config::<DenyAuthentication>::default();
    config.set_request_timeout(REQUEST_TIMEOUT).set_udp_support(true);
    let mut socket = Socks5Socket::new(server, Arc::new(config));
    socket.set_control_peer_ip(Ipv4Addr::LOCALHOST.into());
    socket.set_reply_ip(Ipv4Addr::LOCALHOST.into());
    let exchange = async move {
        client.write_all(&[5, 1, 0, 5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
        let mut replies = [0; 12];
        client.read_exact(&mut replies).await.unwrap();
        assert_eq!(&replies[..10], &[5, 0, 5, 0, 0, 1, 127, 0, 0, 1]);
        let relay = SocketAddr::from(([127, 0, 0, 1], u16::from_be_bytes([replies[10], replies[11]])));
        sleep(REQUEST_TIMEOUT * 3).await;
        let wire = [0, 0, 0, 1, 127, 0, 0, 1, port[0], port[1], b'x'];
        peer.send_to(&wire, relay).await.unwrap();
        let mut reply = [0; 11];
        let (size, _) = peer.recv_from(&mut reply).await.unwrap();
        assert_eq!(size, reply.len());
        assert_eq!(&reply[..10], &wire[..10]);
        assert_eq!(reply[10], b'y');
        drop(client);
    };
    let echo = async move {
        let mut payload = [0];
        let (size, source) = target.recv_from(&mut payload).await.unwrap();
        assert_eq!(size, 1);
        assert_eq!(payload, [b'x']);
        target.send_to(b"y", source).await.unwrap();
    };
    let (result, (), ()) =
        timeout(OUTER_TIMEOUT, async { tokio::join!(socket.upgrade_to_socks5(), exchange, echo) }).await.unwrap();
    assert!(result.is_ok());
}

/// # Cancel safety:
/// cancel-safe: the invalid timeout returns before any I/O and drops the owned stream.
#[tokio::test(flavor = "current_thread")]
async fn unrepresentable_request_deadline_returns_error() {
    let (_client, server) = tokio::io::duplex(64);
    let mut config = Config::<DenyAuthentication>::default();
    config.set_request_timeout(Duration::MAX);
    let result = Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5().await;
    assert!(
        matches!(result, Err(SocksError::ServerError(SocksServerError::Io { source, .. })) if source.kind() == io::ErrorKind::InvalidInput)
    );
}
