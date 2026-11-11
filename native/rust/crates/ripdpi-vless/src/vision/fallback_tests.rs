use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt, DuplexStream, ReadBuf};

use super::decoder_tests::fragmented;
use super::{READ_SCRATCH, VisionStream, XtlsDirectRead};

struct LiveInput(DuplexStream);

impl AsyncRead for LiveInput {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        output: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_read(cx, output)
    }
}

impl XtlsDirectRead for LiveInput {
    fn poll_read_direct(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        output: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        self.poll_read(cx, output)
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: each short reply has a separate owned input and parser.
async fn fallback_preserves_short_replies_and_partial_matching_uuids_at_eof() {
    for limit in [1, 2, 5, 16, READ_SCRATCH] {
        for length in 1..16 {
            for byte in [0x42, 0xff] {
                let expected = vec![byte; length];
                let mut stream = fragmented(expected.clone(), limit);
                let mut actual = Vec::new();
                stream.read_to_end(&mut actual).await.expect("short unpadded reply");
                assert_eq!(actual, expected, "length={length}, byte={byte}, read limit={limit}");
            }
        }
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: timeout drops only this test's owned parser and peer.
async fn fallback_returns_a_mismatching_prefix_without_waiting_for_eof() {
    for prefix in [b"OK".as_slice(), &[0x42, 0x42, 0xff]] {
        let (input, mut peer) = tokio::io::duplex(64);
        let mut stream = VisionStream::new_vision(LiveInput(input), [0x42; 16]);
        peer.write_all(prefix).await.expect("write short response without closing");
        let mut actual = vec![0; prefix.len()];
        tokio::time::timeout(Duration::from_secs(1), stream.read_exact(&mut actual))
            .await
            .expect("mismatch must not wait for more bytes or EOF")
            .expect("read unpadded reply");
        assert_eq!(actual, prefix);
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the idle duplex stream and its peer belong only to this test.
async fn empty_read_finishes_without_waiting_for_input() {
    let (input, mut peer) = tokio::io::duplex(64);
    let mut stream = VisionStream::new_vision(LiveInput(input), [0x42; 16]);
    let mut buffer = ReadBuf::new(&mut []);
    let mut context = Context::from_waker(std::task::Waker::noop());
    assert!(matches!(Pin::new(&mut stream).poll_read(&mut context, &mut buffer), Poll::Ready(Ok(()))));
    peer.write_all(b"OK").await.expect("response after empty read");
    peer.shutdown().await.expect("response EOF");
    let mut actual = Vec::new();
    stream.read_to_end(&mut actual).await.expect("read response after empty read");
    assert_eq!(actual, b"OK");
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test uses a private in-memory input and never cancels a read.
async fn empty_read_preserves_buffered_content() {
    let mut wire = vec![0x42; 16];
    wire.extend_from_slice(&[super::CMD_END, 0, 4, 0, 0, b'A', b'B', b'C', b'D']);
    let mut stream = fragmented(wire, READ_SCRATCH);
    let mut first = [0];
    stream.read_exact(&mut first).await.expect("first content byte");
    assert_eq!(first, [b'A']);
    let mut buffer = ReadBuf::new(&mut []);
    let mut context = Context::from_waker(std::task::Waker::noop());
    assert!(matches!(Pin::new(&mut stream).poll_read(&mut context, &mut buffer), Poll::Ready(Ok(()))));
    let mut rest = Vec::new();
    stream.read_to_end(&mut rest).await.expect("buffered content after empty read");
    assert_eq!(rest, b"BCD");
}
