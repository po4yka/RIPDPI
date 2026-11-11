use super::tests::keep_frame;
use super::*;

/// # Cancel safety:
/// cancel-safe: the timeout drops the owned session and peer together.
#[tokio::test]
async fn control_frames_preserve_fragmented_datagram_boundaries() {
    timeout(Duration::from_secs(2), async {
        // Pinned Xray FrameMetadata.WriteTo vectors: no length field when D=0.
        for control in [
            vec![0, 4, 0, 0, 4, 0],
            vec![0, 4, 0, 42, 4, 0],
            vec![0, 4, 0, 0, 4, 1, 0, 2, 7, 9],
            vec![0, 4, 0, 42, 4, 1, 0, 2, 7, 9],
            vec![0, 4, 0, 0, 2, 0],
        ] {
            let (stream, mut peer) = tokio::io::duplex(512);
            let mut session = VlessXudpSession::new(stream, false).expect("session");
            peer.write_all(&control[..3]).await.expect("partial control");
            {
                let receive = session.recv_from();
                tokio::pin!(receive);
                assert!(futures::poll!(&mut receive).is_pending());
                tokio::task::yield_now().await;
            }
            let mut tail = control[3..].to_vec();
            tail.extend(keep_frame(Some("192.0.2.53:53"), b"one"));
            tail.extend(keep_frame(Some("[2001:db8::1]:53"), b"two"));
            peer.write_all(&tail).await.expect("control and datagrams");
            assert_eq!(session.recv_from().await.expect("first"), ("192.0.2.53:53".into(), b"one".to_vec()));
            assert_eq!(session.recv_from().await.expect("second"), ("[2001:db8::1]:53".into(), b"two".to_vec()));
        }
    })
    .await
    .expect("bounded exchange");
}

/// # Cancel safety:
/// cancel-safe: cancellation drops each owned session and carrier.
#[tokio::test]
async fn end_closes_the_single_xudp_association() {
    for option in [0, 2] {
        let (stream, mut peer) = tokio::io::duplex(128);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        peer.write_all(&[0, 4, 0, 0, 3, option]).await.expect("end");
        assert_eq!(session.recv_from().await.expect_err("terminal end").kind(), io::ErrorKind::UnexpectedEof);
        assert_eq!(
            session.send_to("192.0.2.53:53", b"late").await.expect_err("closed writer").kind(),
            io::ErrorKind::BrokenPipe
        );
    }
}
