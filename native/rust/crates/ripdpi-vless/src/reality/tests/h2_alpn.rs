use super::*;

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn reality_h2_keeps_profile_alpn_and_accepts_h2_or_no_alpn() {
    for alpn in [None, Some(b"\x02h2".as_slice())] {
        let (reply, offered) = connect_to_fixture_capturing(
            FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
            "chrome_stable",
            None,
            alpn,
            true,
        )
        .await;
        assert_eq!(reply.expect("authenticated H2 carrier"), *b"ok");
        // The H2 path must not narrow the profile's ALPN list.
        assert_eq!(offered, b"\x02h2\x08http/1.1", "ClientHello ALPN must equal the chrome_stable list");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn reality_h2_rejects_a_peer_that_selects_http11() {
    let error = connect_to_fixture_with(
        FixtureCertificate::RealityAuth(REALITY_SERVER_SECRET),
        "chrome_stable",
        None,
        Some(b"\x08http/1.1"),
        true,
    )
    .await
    .expect_err("an H2 carrier must refuse http/1.1");
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    // Server authentication also fails with InvalidData; pin the ALPN cause.
    assert!(error.to_string().contains("did not negotiate h2"), "{error}");
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
