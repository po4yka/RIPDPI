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
