use super::tests::{keep_frame, read_encoded_frame};
use super::*;

#[tokio::test]
// NOT cancel-safe: the partial frame future is polled again, never discarded.
async fn source_authority_is_read_after_the_complete_frame_arrives() {
    let (mut stream, mut peer) = tokio::io::duplex(128);
    let initial = OnceLock::new();
    let frame = keep_frame(None, b"answer");
    peer.write_all(&frame[..frame.len() - 1]).await.expect("partial payload");
    let decoded = read_frame(&mut stream, &initial);
    tokio::pin!(decoded);
    assert!(futures::poll!(&mut decoded).is_pending());
    initial.set("192.0.2.53:53".into()).expect("initial request admitted");
    peer.write_all(&frame[frame.len() - 1..]).await.expect("complete payload");
    let DecodedFrame::Datagram(source, payload) = decoded.await.expect("reply") else {
        panic!("expected a datagram");
    };
    assert_eq!((source, payload), ("192.0.2.53:53".into(), b"answer".to_vec()));
}

#[tokio::test]
// NOT cancel-safe: the bounded test owns both carrier ends until completion.
async fn immediate_source_free_reply_uses_initial_new_target() {
    timeout(Duration::from_secs(2), async {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("listener");
        let stream = tokio::net::TcpStream::connect(listener.local_addr().expect("address")).await.expect("connect");
        let (mut peer, _) = listener.accept().await.expect("accept");
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        let peer_task = tokio::spawn(async move {
            let request = read_encoded_frame(&mut peer).await;
            assert_eq!(&request[..14], &[0, 20, 0, 0, 1, 1, 2, 0, 53, 1, 192, 0, 2, 53]);
            peer.write_all(&keep_frame(None, b"immediate answer")).await.expect("reply");
        });
        {
            let send = session.send_to("192.0.2.53:53", b"question");
            tokio::pin!(send);
            // This current-thread poll admits NEW before the writer can acknowledge it.
            assert!(futures::poll!(&mut send).is_pending());
        }
        assert_eq!(session.recv_from().await.expect("reply"), ("192.0.2.53:53".into(), b"immediate answer".to_vec()));
        peer_task.await.expect("peer");
    })
    .await
    .expect("bounded loopback exchange");
}

#[tokio::test]
// NOT cancel-safe: all full-frame reads finish before the carrier is dropped.
async fn later_uplink_and_explicit_reply_do_not_replace_initial_authority() {
    let (stream, mut peer) = tokio::io::duplex(512);
    let mut session = VlessXudpSession::new(stream, false).expect("session");
    session.send_to("192.0.2.53:53", b"first").await.expect("NEW");
    assert_eq!(read_encoded_frame(&mut peer).await[4], STATUS_NEW);
    session.send_to("198.51.100.8:5353", b"second").await.expect("KEEP");
    assert_eq!(read_encoded_frame(&mut peer).await[4], STATUS_KEEP);
    for (source, payload, expected) in [
        (None, b"initial".as_slice(), "192.0.2.53:53"),
        (Some("[2001:db8::9]:123"), b"explicit".as_slice(), "[2001:db8::9]:123"),
        (None, b"initial again".as_slice(), "192.0.2.53:53"),
    ] {
        peer.write_all(&keep_frame(source, payload)).await.expect("reply");
        assert_eq!(session.recv_from().await.expect("source"), (expected.into(), payload.to_vec()));
    }
}

#[tokio::test]
// cancel-safe: a pending queue reservation publishes no frame or source.
async fn cancelled_admission_leaves_the_next_new_target_authoritative() {
    let (stream, mut peer) = tokio::io::duplex(512);
    let mut session = VlessXudpSession::new(stream, false).expect("session");
    let writer = session.writer.clone();
    let permits = writer.reserve_many(WRITER_CHANNEL_CAPACITY).await.expect("hold capacity");
    {
        let rejected = session.send_to("192.0.2.1:53", b"cancelled");
        tokio::pin!(rejected);
        assert!(futures::poll!(&mut rejected).is_pending());
    }
    drop(permits);
    session.send_to("[2001:0db8:0:0:0:0:0:2]:0053", b"accepted").await.expect("first accepted request");
    assert_eq!(read_encoded_frame(&mut peer).await[4], STATUS_NEW);
    peer.write_all(&keep_frame(None, b"answer")).await.expect("reply");
    assert_eq!(session.recv_from().await.expect("source"), ("[2001:db8::2]:53".into(), b"answer".to_vec()));
}

#[tokio::test]
// cancel-safe: queue reservation is cancelled before any complete frame exists.
async fn cancelled_or_invalid_request_cannot_authorize_a_reply() {
    for cancel in [false, true] {
        let (stream, mut peer) = tokio::io::duplex(512);
        let mut session = VlessXudpSession::new(stream, false).expect("session");
        if cancel {
            let writer = session.writer.clone();
            let _permits = writer.reserve_many(WRITER_CHANNEL_CAPACITY).await.expect("hold capacity");
            let rejected = session.send_to("192.0.2.1:53", b"cancelled");
            tokio::pin!(rejected);
            assert!(futures::poll!(&mut rejected).is_pending());
        } else {
            assert!(session.send_to("192.0.2.1:53", b"").await.is_err());
            assert!(session.send_to("192.0.2.1:443", b"blocked").await.is_err());
        }
        peer.write_all(&keep_frame(None, b"unsolicited")).await.expect("reply");
        assert_eq!(session.recv_from().await.expect_err("no authority").kind(), io::ErrorKind::InvalidData);
    }
}

#[tokio::test]
// NOT cancel-safe: complete carrier operations run in order without external races.
async fn new_carrier_does_not_inherit_request_or_explicit_response_authority() {
    let (stream, _peer) = tokio::io::duplex(512);
    let mut old = VlessXudpSession::new(stream, false).expect("old session");
    old.send_to("192.0.2.53:53", b"old").await.expect("old request");
    drop(old);
    let (stream, mut peer) = tokio::io::duplex(512);
    let mut fresh = VlessXudpSession::new(stream, false).expect("fresh session");
    peer.write_all(&keep_frame(Some("198.51.100.8:5353"), b"explicit")).await.expect("explicit reply");
    assert_eq!(fresh.recv_from().await.expect("explicit source").0, "198.51.100.8:5353");
    peer.write_all(&keep_frame(None, b"no authority")).await.expect("omitted reply");
    assert_eq!(fresh.recv_from().await.expect_err("no initial NEW").kind(), io::ErrorKind::InvalidData);
}
