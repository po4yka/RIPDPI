use super::ResponseStream;
use std::io;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// # Cancel safety:
/// cancel-safe: each case owns its stream and peer buffer.
#[tokio::test]
async fn status_is_stripped_and_every_rejected_status_is_terminal() {
    for status in 0..=u8::MAX {
        let mut stream = ResponseStream::new(std::io::Cursor::new(vec![status, 0, b'p']));
        let mut payload = [0; 2];
        if status == 0 {
            stream.read_exact(&mut payload).await.expect("success payload");
            assert_eq!(payload, [0, b'p']);
        } else {
            let error = stream.read(&mut payload).await.expect_err("reject status");
            assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
            assert_eq!(stream.read(&mut payload).await.expect_err("terminal read").kind(), io::ErrorKind::BrokenPipe);
            assert_eq!(stream.write(b"extra").await.expect_err("terminal write").kind(), io::ErrorKind::BrokenPipe);
        }
    }
}

/// # Cancel safety:
/// cancel-safe: dropping the read future preserves status state in the stream.
#[tokio::test]
async fn cancelled_read_before_or_after_status_preserves_payload() {
    for send_status_first in [false, true] {
        let (client, mut peer) = tokio::io::duplex(16);
        let mut stream = ResponseStream::new(client);
        if send_status_first {
            peer.write_all(&[0]).await.expect("status");
        }
        let mut payload = [0];
        {
            let read = stream.read(&mut payload);
            tokio::pin!(read);
            assert!(futures::poll!(read.as_mut()).is_pending());
        }
        if !send_status_first {
            peer.write_all(&[0]).await.expect("status");
        }
        peer.write_all(&[0x42]).await.expect("payload");
        stream.read_exact(&mut payload).await.expect("resumed read");
        assert_eq!(payload, [0x42]);
    }
}

/// # Cancel safety:
/// cancel-safe: the test owns both halves and stores read progress in the stream.
#[tokio::test]
async fn empty_read_and_write_half_close_do_not_consume_status() {
    let (client, mut peer) = tokio::io::duplex(16);
    let mut stream = ResponseStream::new(client);
    assert_eq!(stream.read(&mut []).await.expect("empty read"), 0);
    stream.write_all(b"request").await.expect("write before status");
    stream.shutdown().await.expect("half close");
    let mut request = Vec::new();
    peer.read_to_end(&mut request).await.expect("request until FIN");
    assert_eq!(request, b"request");
    peer.write_all(b"\0reply").await.expect("reply after half close");
    peer.shutdown().await.expect("peer FIN");
    let mut reply = Vec::new();
    stream.read_to_end(&mut reply).await.expect("read after half close");
    assert_eq!(reply, b"reply");
}

/// # Cancel safety:
/// cancel-safe: both endpoints are owned by this test.
#[tokio::test]
async fn eof_before_status_is_an_error() {
    let (client, peer) = tokio::io::duplex(16);
    drop(peer);
    let mut stream = ResponseStream::new(client);
    assert_eq!(stream.read(&mut [0]).await.expect_err("missing status").kind(), io::ErrorKind::UnexpectedEof);
    assert_eq!(stream.read(&mut [0]).await.expect_err("terminal read").kind(), io::ErrorKind::BrokenPipe);
}
