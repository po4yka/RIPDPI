use super::*;

/// # Cancel safety:
/// cancel-safe: cancellation drops only local loopback sockets.
async fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let (client, server) = tokio::join!(TcpStream::connect(listener.local_addr().unwrap()), listener.accept());
    (client.unwrap(), server.unwrap().0)
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned parser and stream after partial input.
#[tokio::test(flavor = "current_thread")]
async fn reserved_request_byte_is_rejected_before_address_read() {
    for reserved in [1, 255] {
        let (mut client, server) = pair().await;
        client.write_all(&[5, 1, reserved, 3]).await.unwrap();
        let result = tokio::time::timeout(
            Duration::from_millis(200),
            Socks5ServerProtocol::skip_auth_this_is_not_rfc_compliant(server).read_command(),
        )
        .await
        .expect("invalid RSV must not wait for address bytes");
        assert!(result.is_err());
        let mut reply = [0; 10];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply, [5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned parser and local test streams.
#[tokio::test(flavor = "current_thread")]
async fn empty_domain_is_not_a_valid_server_destination() {
    let (mut client, server) = pair().await;
    client.write_all(&[5, 1, 0, 3, 0, 0, 80]).await.unwrap();
    let result = Socks5ServerProtocol::skip_auth_this_is_not_rfc_compliant(server).read_command().await;
    assert!(result.is_err(), "zero-length domain must not reach routing");
    let mut reply = [0; 10];
    client.read_exact(&mut reply).await.unwrap();
    assert_eq!(reply, [5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
}
