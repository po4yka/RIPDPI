use super::*;

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn reality_h2_accepts_authenticated_peers_with_h2_or_no_alpn() {
    for alpn in [None, Some(b"\x02h2".as_slice()), Some(b"\x08http/1.1".as_slice())] {
        let reply = connect_to_fixture_with(
            FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
            "chrome_stable",
            None,
            alpn,
            true,
        )
        .await
        .expect("authenticated H2 carrier");
        assert_eq!(reply, *b"ok");
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
