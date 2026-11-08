use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns both stream halves and drops them on cancellation.
#[tokio::test]
async fn client_reply_rejects_invalid_fixed_header() {
    for header in [[4, 0, 0, 1], [5, 0, 1, 1], [5, 0, 255, 1]] {
        let (client, mut server) = tokio::io::duplex(64);
        server.write_all(&[header.as_slice(), &[127, 0, 0, 1, 0, 53]].concat()).await.unwrap();
        let mut stream = Socks5Stream { socket: client, target_addr: None, config: Config::default() };
        assert!(stream.read_request_reply().await.is_err(), "header {header:?}");
    }
}
