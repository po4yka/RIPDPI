use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns both stream halves and drops them on cancellation.
#[tokio::test]
async fn configured_credentials_reject_noauth_before_request_output() {
    let (mut client, mut server) = tokio::io::duplex(64);
    server.write_all(&[5, 0]).await.unwrap();
    let credentials = Credentials { username: "u".into(), password: "p".into() };
    assert!(greet_and_authenticate(&mut client, Some(&credentials)).await.is_err());
    drop(client);
    let mut sent = Vec::new();
    server.read_to_end(&mut sent).await.unwrap();
    assert_eq!(sent, [5, 1, 2]);
}
