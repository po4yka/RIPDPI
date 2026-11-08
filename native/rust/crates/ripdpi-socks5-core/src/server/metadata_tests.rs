use super::*;

/// # Cancel safety:
/// cancel-safe: cancellation drops the local transports and incomplete request.
#[tokio::test(flavor = "current_thread")]
async fn wrapper_reports_password_method_after_authentication() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let (client, server) = tokio::join!(TcpStream::connect(listener.local_addr().unwrap()), listener.accept());
    let mut client = client.unwrap();
    let server = server.unwrap().0;
    client.write_all(&[5, 1, 2, 1, 1, b'u', 1, b'p', 5, 1, 0, 1, 127, 0, 0, 1, 0, 80]).await.unwrap();
    let mut config = Config::<DenyAuthentication>::default()
        .with_authentication(SimpleUserPassword { username: "u".to_owned(), password: "p".to_owned() });
    config.set_execute_command(false);
    let socket = Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5().await.unwrap();
    assert_eq!(socket.auth(), Some(2));
    let mut reply = [0; 4];
    client.read_exact(&mut reply).await.unwrap();
    assert_eq!(reply, [5, 2, 1, 0]);
}

/// # Cancel safety:
/// cancel-safe: cancellation drops local test streams and socket state.
#[tokio::test(flavor = "current_thread")]
async fn wrapper_distinguishes_no_auth_from_skipped_negotiation() {
    for skip in [false, true] {
        let (mut client, server) = tokio::io::duplex(64);
        let mut config = Config::<DenyAuthentication>::default();
        config.set_execute_command(false).set_skip_auth(skip);
        let socket = Socks5Socket::new(server, Arc::new(config));
        assert_eq!(socket.auth(), None);
        let exchange = async {
            if !skip {
                client.write_all(&[5, 1, 0]).await.unwrap();
                let mut reply = [0; 2];
                client.read_exact(&mut reply).await.unwrap();
                assert_eq!(reply, [5, 0]);
            }
            client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 80]).await.unwrap();
        };
        let (result, ()) = tokio::join!(socket.upgrade_to_socks5(), exchange);
        assert_eq!(result.unwrap().auth(), if skip { None } else { Some(0) });
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the bounded test streams and authentication state.
#[tokio::test(flavor = "current_thread")]
async fn wrapper_metadata_follows_negotiated_method_and_server_preference() {
    for password in [false, true] {
        let (mut client, server) = tokio::io::duplex(64);
        let mut config = Config::<DenyAuthentication>::default().with_authentication(AcceptAuthentication {});
        config.set_execute_command(false).set_allow_no_auth(true);
        let exchange = async {
            if password {
                client.write_all(&[5, 2, 0, 2]).await.unwrap();
            } else {
                client.write_all(&[5, 1, 0]).await.unwrap();
            }
            let mut reply = [0; 2];
            client.read_exact(&mut reply).await.unwrap();
            assert_eq!(reply, [5, if password { 2 } else { 0 }]);
            if password {
                client.write_all(&[1, 1, b'u', 1, b'p']).await.unwrap();
                client.read_exact(&mut reply).await.unwrap();
                assert_eq!(reply, [1, 0]);
            }
            client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 80]).await.unwrap();
        };
        let (result, ()) = tokio::join!(Socks5Socket::new(server, Arc::new(config)).upgrade_to_socks5(), exchange);
        assert_eq!(result.unwrap().auth(), Some(if password { 2 } else { 0 }));
    }
}
