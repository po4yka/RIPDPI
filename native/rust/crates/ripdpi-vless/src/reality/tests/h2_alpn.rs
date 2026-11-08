use super::*;

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn reality_h2_accepts_an_authenticated_h2_peer() {
    let reply = connect_to_fixture_with(
        FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
        "chrome_stable",
        None,
        Some(b"\x02h2"),
        true,
    )
    .await
    .expect("authenticated H2 carrier");
    assert_eq!(reply, *b"ok");
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn reality_h2_rejects_an_authenticated_peer_without_h2() {
    for alpn in [None, Some(b"\x08http/1.1".as_slice())] {
        let error = connect_to_fixture_with(
            FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
            "chrome_stable",
            None,
            alpn,
            true,
        )
        .await
        .expect_err("H2 requires negotiated h2");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("did not negotiate h2"), "{error}");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn direct_reality_keeps_its_http11_alpn_policy() {
    let reply = connect_to_fixture_with(
        FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
        "chrome_stable",
        None,
        Some(b"\x08http/1.1"),
        false,
    )
    .await
    .expect("raw REALITY keeps its profile policy");
    assert_eq!(reply, *b"ok");
}
