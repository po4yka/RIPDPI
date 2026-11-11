use super::tests::{keep_frame, read_encoded_frame};
use super::*;

/// # Cancel safety:
/// cancel-safe: the timeout drops the session and both owned carrier ends.
#[tokio::test]
async fn invalid_targets_preserve_new_and_keep_state() {
    timeout(Duration::from_secs(2), async {
        let (stream, mut peer) = tokio::io::duplex(2048);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        for target in ["bad!.example:53", "bad\n.example:53", "tést.example:53"] {
            assert_eq!(
                session.send_to(target, b"rejected").await.expect_err("reject invalid NEW target").kind(),
                io::ErrorKind::InvalidInput
            );
        }
        session.send_to("192.0.2.53:53", b"first").await.expect("valid NEW");
        let first = read_encoded_frame(&mut peer).await;
        assert_eq!(&first[..14], &[0, 20, 0, 0, 1, 1, 2, 0, 53, 1, 192, 0, 2, 53]);
        assert_eq!(&first[24..], b"first");
        for target in ["bad!.example:53", "bad\0.example:53", "tést.example:53"] {
            assert_eq!(
                session.send_to(target, b"rejected").await.expect_err("reject invalid KEEP target").kind(),
                io::ErrorKind::InvalidInput
            );
        }
        session.send_to("198.51.100.8:5353", b"second").await.expect("valid KEEP");
        let second = read_encoded_frame(&mut peer).await;
        assert_eq!(&second[..14], &[0, 12, 0, 0, 2, 1, 2, 20, 233, 1, 198, 51, 100, 8]);
        assert_eq!(&second[16..], b"second");
        peer.write_all(&keep_frame(None, b"answer")).await.expect("source-free reply");
        assert_eq!(session.recv_from().await.expect("reply"), ("192.0.2.53:53".into(), b"answer".to_vec()));
    })
    .await
    .expect("bounded exchange");
}

#[test]
fn xray_domain_characters_and_length_boundary_remain_supported() {
    for domain in ["_Service.Example-1.", "xn--tst-bma.example", &"a".repeat(255)] {
        let frame = encode_datagram(false, &format!("{domain}:53"), b"x", [0; 8]).expect("supported domain");
        assert_eq!(&frame[2..10], &[0, 0, 2, 1, 2, 0, 53, 2]);
        assert_eq!(usize::from(frame[10]), domain.len());
        assert_eq!(&frame[11..11 + domain.len()], domain.as_bytes());
        assert_eq!(&frame[11 + domain.len()..], &[0, 1, b'x']);
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the complete test vector is read without external races.
#[tokio::test]
async fn explicit_downlink_domains_follow_the_xray_contract() {
    for domain in [b"bad!.example".as_slice(), b"bad\n.example", "tést.example".as_bytes(), b""] {
        // KEEP, data option, UDP, port 53, domain address. No encoder is used.
        let mut metadata = vec![0, 0, 2, 1, 2, 0, 53, 2, u8::try_from(domain.len()).expect("domain length")];
        metadata.extend_from_slice(domain);
        let mut wire = u16::try_from(metadata.len()).expect("metadata length").to_be_bytes().to_vec();
        wire.extend(metadata);
        wire.extend([0, 1, b'x']);
        let Err(error) = read_frame(&mut wire.as_slice(), &OnceLock::new()).await else {
            panic!("invalid downlink domain was accepted");
        };
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }
}

/// # Cancel safety:
/// NOT cancel-safe: each complete wire vector is consumed without cancellation.
#[tokio::test]
async fn domain_address_type_retains_xray_ip_literal_sources() {
    for (domain, expected) in [
        ("2001:db8::1", "[2001:db8::1]:53"),
        ("[2001:db8::1]", "[2001:db8::1]:53"),
        ("[ ::1 ]", "[::1]:53"),
        ("192.0.2.53", "192.0.2.53:53"),
        ("[::ffff:192.0.2.53]", "192.0.2.53:53"),
    ] {
        let mut metadata = vec![0, 0, 2, 1, 2, 0, 53, 2, u8::try_from(domain.len()).expect("domain length")];
        metadata.extend_from_slice(domain.as_bytes());
        let mut wire = u16::try_from(metadata.len()).expect("metadata length").to_be_bytes().to_vec();
        wire.extend(metadata);
        wire.extend([0, 1, b'x']);
        let (source, payload) = read_datagram(&mut wire.as_slice(), &OnceLock::new()).await.expect("IP literal source");
        assert_eq!((source, payload), (expected.into(), vec![b'x']));
    }
}
