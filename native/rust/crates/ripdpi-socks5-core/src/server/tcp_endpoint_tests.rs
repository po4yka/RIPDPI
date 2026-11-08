/// # Cancel safety:
/// cancel-safe: cancellation drops the test futures and their local sockets.
#[tokio::test(flavor = "current_thread")]
async fn connect_reply_reports_actual_bound_endpoint() {
    for ip in [IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)] {
        let target = TcpListener::bind(SocketAddr::new(ip, 0)).await.unwrap();
        let target_addr = target.local_addr().unwrap();
        let listener = TcpListener::bind(SocketAddr::new(ip, 0)).await.unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (stream, _) = listener.accept().await.unwrap();

        let mut request = vec![5, 1, 0, 5, 1, 0];
        match ip {
            IpAddr::V4(ip) => {
                request.push(1);
                request.extend_from_slice(&ip.octets());
            }
            IpAddr::V6(ip) => {
                request.push(4);
                request.extend_from_slice(&ip.octets());
            }
        }
        request.extend_from_slice(&target_addr.port().to_be_bytes());
        request.extend_from_slice(b"ping");
        client.write_all(&request).await.unwrap();
        client.shutdown().await.unwrap();

        let exchange = async {
            tokio::join!(
                async {
                    let protocol = Socks5ServerProtocol::accept_no_auth(stream).await.unwrap();
                    let (protocol, command, address) = protocol.read_command().await.unwrap();
                    assert_eq!(command, Socks5Command::TCPConnect);
                    drop(run_tcp_proxy(protocol, &address, Duration::from_secs(1), false).await.unwrap());
                },
                async {
                    let (mut stream, peer) = target.accept().await.unwrap();
                    let mut payload = Vec::new();
                    stream.read_to_end(&mut payload).await.unwrap();
                    assert_eq!(payload, b"ping");
                    stream.write_all(b"pong").await.unwrap();
                    peer
                },
                async {
                    let mut response = Vec::new();
                    client.read_to_end(&mut response).await.unwrap();
                    response
                },
            )
        };
        let (_, peer, response) = tokio::time::timeout(Duration::from_secs(2), exchange).await.unwrap();
        let mut expected = vec![5, 0, 5, 0, 0];
        match peer.ip() {
            IpAddr::V4(ip) => {
                expected.push(1);
                expected.extend_from_slice(&ip.octets());
            }
            IpAddr::V6(ip) => {
                expected.push(4);
                expected.extend_from_slice(&ip.octets());
            }
        }
        expected.extend_from_slice(&peer.port().to_be_bytes());
        expected.extend_from_slice(b"pong");
        assert_eq!(response, expected, "CONNECT must report the endpoint observed by the target");
    }
}
