/// # Cancel safety:
/// cancel-safe: cancellation drops the joined test futures and their local sockets.
#[tokio::test(flavor = "current_thread")]
async fn listener_udp_reply_is_reachable_and_released_on_control_eof() {
    for ip in [IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)] {
        let mut config = Config::<DenyAuthentication>::default();
        config.set_udp_support(true);
        let server =
            Socks5Server::<DenyAuthentication>::bind(SocketAddr::new(ip, 0)).await.unwrap().with_config(config);
        let mut client = TcpStream::connect(server.listener.local_addr().unwrap()).await.unwrap();
        let target = UdpSocket::bind(SocketAddr::new(ip, 0)).await.unwrap();
        let target_addr = target.local_addr().unwrap();
        let peer = UdpSocket::bind(SocketAddr::new(ip, 0)).await.unwrap();
        let exchange = async {
            tokio::join!(
                async {
                    let mut incoming = server.incoming();
                    incoming.next().await.unwrap().unwrap().upgrade_to_socks5().await
                },
                async {
                    client.write_all(&[5, 1, 0]).await.unwrap();
                    let mut auth = [0; 2];
                    client.read_exact(&mut auth).await.unwrap();
                    assert_eq!(auth, [5, 0]);
                    client.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
                    let mut prefix = [0; 4];
                    client.read_exact(&mut prefix).await.unwrap();
                    assert_eq!(&prefix[..3], &[5, 0, 0]);
                    let reply_ip = match prefix[3] {
                        1 => {
                            let mut address = [0; 4];
                            client.read_exact(&mut address).await.unwrap();
                            IpAddr::V4(Ipv4Addr::from(address))
                        }
                        4 => {
                            let mut address = [0; 16];
                            client.read_exact(&mut address).await.unwrap();
                            IpAddr::V6(Ipv6Addr::from(address))
                        }
                        other => panic!("unexpected address type {other}"),
                    };
                    assert_eq!(reply_ip, ip);
                    let mut port = [0; 2];
                    client.read_exact(&mut port).await.unwrap();
                    let relay = SocketAddr::new(reply_ip, u16::from_be_bytes(port));
                    assert_ne!(relay.port(), 0);

                    let mut request = vec![0, 0, 0];
                    match target_addr.ip() {
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
                    request.push(b'x');
                    peer.send_to(&request, relay).await.unwrap();
                    let mut received = [0; 128];
                    let (size, outbound) = target.recv_from(&mut received).await.unwrap();
                    assert_eq!(&received[..size], b"x");
                    target.send_to(b"y", outbound).await.unwrap();
                    let (size, _) = peer.recv_from(&mut received).await.unwrap();
                    *request.last_mut().unwrap() = b'y';
                    assert_eq!(&received[..size], request.as_slice());
                    drop(client);
                    (relay, outbound)
                },
            )
        };
        let (result, (relay, outbound)) = tokio::time::timeout(Duration::from_secs(2), exchange).await.unwrap();
        drop(result.unwrap());
        assert!(UdpSocket::bind(relay).await.is_ok(), "control EOF must release the relay port");
        assert!(UdpSocket::bind(outbound).await.is_ok(), "control EOF must release the outbound port");
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the joined test futures and their local sockets.
#[tokio::test(flavor = "current_thread")]
async fn generic_udp_without_reply_ip_sends_failure() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (stream, peer) = listener.accept().await.unwrap();
    let mut config = Config::<DenyAuthentication>::default();
    config.set_udp_support(true);
    let mut socket = Socks5Socket::new(stream, Arc::new(config));
    socket.set_control_peer_ip(peer.ip());
    client.write_all(&[5, 1, 0, 5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
    let exchange = async {
        tokio::join!(socket.upgrade_to_socks5(), async {
            let mut wire = Vec::new();
            client.read_to_end(&mut wire).await.unwrap();
            wire
        })
    };
    let (result, wire) = tokio::time::timeout(Duration::from_secs(2), exchange).await.unwrap();
    assert!(result.is_err());
    assert_eq!(wire, [5, 0, 5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
}
