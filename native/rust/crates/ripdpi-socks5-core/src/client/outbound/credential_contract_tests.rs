use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns both stream halves and drops them on cancellation.
#[tokio::test]
async fn empty_credentials_send_no_authentication_frame() {
    for (username, password) in [("", "p"), ("u", ""), ("", "")] {
        let (mut client, mut server) = tokio::io::duplex(64);
        server.write_all(&[1, 0]).await.unwrap();
        let credentials = Credentials { username: username.into(), password: password.into() };
        assert!(negotiate_password(&mut client, &credentials).await.is_err());
        drop(client);
        let mut sent = Vec::new();
        server.read_to_end(&mut sent).await.unwrap();
        assert!(sent.is_empty());
    }
}
