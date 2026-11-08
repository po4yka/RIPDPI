use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns both stream halves and drops them on cancellation.
#[tokio::test]
async fn configured_credentials_reject_noauth_before_request_output() {
    let (client, mut server) = tokio::io::duplex(64);
    server.write_all(&[5, 0]).await.unwrap();
    let result = Socks5Stream::use_stream(
        client,
        Some(AuthenticationMethod::Password { username: "u".into(), password: "p".into() }),
        Config::default(),
    )
    .await;
    assert!(result.is_err());
    let mut sent = Vec::new();
    server.read_to_end(&mut sent).await.unwrap();
    assert_eq!(sent, [5, 1, 2]);
}
