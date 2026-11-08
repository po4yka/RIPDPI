use super::*;

/// # Cancel safety:
/// cancel-safe: the test owns both stream halves and drops them on cancellation.
#[tokio::test]
async fn outbound_replies_reject_invalid_fixed_header() {
    for header in [[4, 0, 0, 1], [5, 0, 1, 1], [5, 0, 255, 1]] {
        for associate in [false, true] {
            let (mut client, mut server) = tokio::io::duplex(64);
            server.write_all(&[header.as_slice(), &[127, 0, 0, 1, 0, 53]].concat()).await.unwrap();
            let result = if associate {
                read_associate_reply(&mut client).await.map(|_| ())
            } else {
                read_connect_reply(&mut client).await
            };
            assert!(result.is_err(), "header {header:?}, associate={associate}");
        }
    }
}
