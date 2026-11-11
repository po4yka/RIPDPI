use std::io::Cursor;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncReadExt, ReadBuf};

use super::{CMD_CONTINUE, CMD_END, READ_SCRATCH, VisionStream, XtlsDirectRead};

const UUID: [u8; 16] = [0x42; 16];
const READ_LIMITS: [usize; 5] = [1, 2, 5, 16, READ_SCRATCH];

struct FragmentedInput {
    bytes: Cursor<Vec<u8>>,
    limit: usize,
}

impl AsyncRead for FragmentedInput {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        output: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let mut scratch = [0; READ_SCRATCH];
        let length = self.limit.min(output.remaining());
        let mut input = ReadBuf::new(&mut scratch[..length]);
        std::task::ready!(Pin::new(&mut self.bytes).poll_read(cx, &mut input))?;
        output.put_slice(input.filled());
        Poll::Ready(Ok(()))
    }
}

impl XtlsDirectRead for FragmentedInput {
    fn poll_read_direct(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        output: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        self.poll_read(cx, output)
    }
}

fn fragmented(wire: Vec<u8>, limit: usize) -> VisionStream<FragmentedInput> {
    VisionStream::new_vision(FragmentedInput { bytes: Cursor::new(wire), limit }, UUID)
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns each in-memory input and shares no parser state.
async fn recognized_frame_reports_every_truncated_header_content_and_padding() {
    let mut wire = UUID.to_vec();
    wire.extend_from_slice(&[CMD_CONTINUE, 0, 4, 0, 2, b'A', b'B', b'C', b'D', 0, 0]);
    for limit in READ_LIMITS {
        for cut in UUID.len()..wire.len() {
            let mut stream = fragmented(wire[..cut].to_vec(), limit);
            let mut payload = Vec::new();
            let error = stream.read_to_end(&mut payload).await.expect_err("truncated recognized frame");
            assert_eq!(error.kind(), std::io::ErrorKind::UnexpectedEof, "cut={cut}, read limit={limit}");
            let delivered = cut.saturating_sub(UUID.len() + 5).min(4);
            assert_eq!(payload, &b"ABCD"[..delivered], "cut={cut}, read limit={limit}");
        }
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: all frame bytes and parser state belong to this test's input.
async fn eof_at_complete_continue_or_end_chunk_remains_valid() {
    for limit in READ_LIMITS {
        for command in [CMD_CONTINUE, CMD_END] {
            for body in [vec![command, 0, 4, 0, 2, b'A', b'B', b'C', b'D', 0, 0], vec![command, 0, 0, 0, 0]] {
                let mut wire = UUID.to_vec();
                wire.extend_from_slice(&body);
                let mut stream = fragmented(wire, limit);
                let mut payload = Vec::new();
                stream.read_to_end(&mut payload).await.expect("EOF at chunk boundary");
                let expected = if body.len() == 5 { b"".as_slice() } else { b"ABCD".as_slice() };
                assert_eq!(payload, expected, "command={command}, read limit={limit}");
            }
        }
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: every truncated follow-up frame is read from a private in-memory input.
async fn partial_header_after_complete_continue_chunk_is_truncated() {
    for limit in READ_LIMITS {
        for header_bytes in 1..5 {
            let mut wire = UUID.to_vec();
            wire.extend_from_slice(&[CMD_CONTINUE, 0, 2, 0, 0, b'O', b'K']);
            wire.extend_from_slice(&[CMD_END, 0, 1, 0, 0][..header_bytes]);
            let mut stream = fragmented(wire, limit);
            let mut payload = Vec::new();
            let error = stream.read_to_end(&mut payload).await.expect_err("incomplete next header");
            assert_eq!(error.kind(), std::io::ErrorKind::UnexpectedEof);
            assert_eq!(payload, b"OK");
        }
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns the complete input and does not cancel a pending operation.
async fn unknown_command_retains_tolerant_end_behavior() {
    let mut wire = UUID.to_vec();
    wire.extend_from_slice(&[0x7f, 0, 2, 0, 1, b'O', b'K', 0]);
    wire.extend_from_slice(b"tail");
    let mut stream = fragmented(wire, 1);
    let mut payload = Vec::new();
    stream.read_to_end(&mut payload).await.expect("unknown command ends padding");
    assert_eq!(payload, b"OKtail");
}
