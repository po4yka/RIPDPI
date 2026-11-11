use super::*;
use tokio::io::AsyncReadExt;

/// # Cancel safety:
/// cancel-safe: each iteration owns its carrier and peer.
#[tokio::test]
async fn invalid_yamux_session_limits_fail_before_preamble() {
    for limit in [4097, 0, usize::MAX] {
        let (client, mut peer) = tokio::io::duplex(16);
        let Err(error) = VlessYamuxSession::establish(Box::new(client), limit).await else {
            panic!("accepted invalid capacity {limit}");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert_eq!(peer.read(&mut [0; 2]).await.expect("carrier closed"), 0);
    }
}

/// # Cancel safety:
/// cancel-safe: the last session owner aborts the driver and releases the carrier.
#[tokio::test]
async fn maximum_yamux_session_limit_still_establishes() {
    let (client, mut peer) = tokio::io::duplex(16);
    let session = VlessYamuxSession::establish(Box::new(client), 4096).await.expect("maximum session capacity");
    let mut preamble = [0; 2];
    peer.read_exact(&mut preamble).await.expect("preamble");
    assert_eq!(preamble, [0, 1]);
    drop(session);
}
