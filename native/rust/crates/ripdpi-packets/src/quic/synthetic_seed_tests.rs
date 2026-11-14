use super::*;
use tls_parser::{TlsClientHelloContents, TlsExtension, TlsMessage, TlsMessageHandshake, parse_tls_plaintext};

fn client_hello(bytes: &[u8]) -> TlsClientHelloContents<'_> {
    let (remaining, record) = parse_tls_plaintext(bytes).expect("independent TLS parser");
    assert!(remaining.is_empty(), "trailing TLS records");
    record
        .msg
        .into_iter()
        .find_map(|message| match message {
            TlsMessage::Handshake(TlsMessageHandshake::ClientHello(hello)) => Some(hello),
            _ => None,
        })
        .expect("ClientHello")
}

#[test]
fn explicit_cid_seeds_use_quic_tls_requirements() {
    for version in [crate::QUIC_V1_VERSION, crate::QUIC_V2_VERSION] {
        for profile in
            [crate::QuicInitialBrowserProfile::ChromeAndroid, crate::QuicInitialBrowserProfile::FirefoxAndroid]
        {
            let seed = build_browser_like_quic_initial_seed_with_cids(
                version,
                Some("decoy.example.test"),
                profile,
                b"dcid1234",
                &[1, 2, 3, 4],
            )
            .expect("synthetic seed");
            let hello = client_hello(&seed.client_hello);
            assert!(hello.session_id.unwrap_or_default().is_empty());
            let (remaining, extensions) = tls_parser::parse_tls_client_hello_extensions(hello.ext.expect("extensions"))
                .expect("independent TLS extension parser");
            assert!(remaining.is_empty());
            assert!(extensions.iter().any(|extension| matches!(extension,
                TlsExtension::ALPN(protocols) if protocols.as_slice() == [b"h3".as_slice()])));
            assert!(extensions.iter().any(|extension| matches!(extension,
                TlsExtension::SupportedVersions(versions) if versions.len() == 1 && versions[0].0 == 0x0304)));
            assert!(extensions.iter().any(|extension| matches!(extension,
                TlsExtension::Unknown(kind, parameters) if kind.0 == 0x0039 && *parameters == b"\x0f\x04\x01\x02\x03\x04")));
            let packet = packetize_quic_initial(&seed, &crate::QuicInitialPacketLayout::contiguous(1200))
                .expect("packetized synthetic seed");
            let reparsed = parse_quic_initial_seed(&packet).expect("complete Initial");
            assert_eq!(packet.len(), 1200);
            assert_eq!(reparsed.version, version);
            assert_eq!(reparsed.dcid, b"dcid1234");
            assert_eq!(reparsed.scid, [1, 2, 3, 4]);
            assert_eq!(parse_quic_initial(&packet).expect("authority").host(), b"decoy.example.test");
        }
    }
}

#[test]
fn explicit_cid_seeds_have_fresh_tls_random_and_key_shares() {
    for profile in [crate::QuicInitialBrowserProfile::ChromeAndroid, crate::QuicInitialBrowserProfile::FirefoxAndroid] {
        let seeds = [0, 1].map(|_| {
            build_browser_like_quic_initial_seed_with_cids(crate::QUIC_V1_VERSION, None, profile, b"dcid1234", b"scid")
                .expect("synthetic seed")
        });
        let hellos = seeds.each_ref().map(|seed| client_hello(&seed.client_hello));
        assert_ne!(hellos[0].random, hellos[1].random);
        let shares = hellos.map(|hello| {
            tls_parser::parse_tls_client_hello_extensions(hello.ext.expect("extensions"))
                .expect("independent extension parser")
                .1
                .into_iter()
                .find_map(|extension| match extension {
                    TlsExtension::KeyShare(bytes) => Some(bytes),
                    _ => None,
                })
                .expect("key share")
        });
        assert_ne!(shares[0], shares[1]);
    }
}

#[test]
fn explicit_cid_seed_checks_header_bounds() {
    let build = |version, dcid: &[u8], scid: &[u8]| {
        build_browser_like_quic_initial_seed_with_cids(
            version,
            Some("decoy.example.test"),
            crate::QuicInitialBrowserProfile::ChromeAndroid,
            dcid,
            scid,
        )
    };
    for version in [crate::QUIC_V1_VERSION, crate::QUIC_V2_VERSION] {
        assert!(build(version, &[1; 7], &[2; 4]).is_none());
        assert!(build(version, &[1; 21], &[2; 4]).is_none());
        assert!(build(version, &[1; 8], &[2; 21]).is_none());
        assert!(build(version, &[1; 20], &[2; 20]).is_some());
        let seed = build(version, &[1; 8], &[]).expect("zero-length source CID is allowed");
        let hello = client_hello(&seed.client_hello);
        let extensions = tls_parser::parse_tls_client_hello_extensions(hello.ext.expect("extensions"))
            .expect("independent extension parser")
            .1;
        assert!(extensions.iter().any(|extension| matches!(extension,
            TlsExtension::Unknown(kind, parameters) if kind.0 == 0x0039 && *parameters == b"\x0f\x00")));
    }
    assert!(build(0, &[1; 8], &[2; 4]).is_none());
    assert!(build(0x1a2a3a4a, &[1; 8], &[2; 4]).is_none());
}
