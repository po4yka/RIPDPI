use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, Waker};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

mod flush;

pub(in crate::io_loop) use flush::{flush_pending_to_session, flush_pending_to_smoltcp};

/// Reads what the session wrote. On `None` the stream keeps `waker` and wakes it when the session
/// writes again or closes, so the io loop learns about the data without waiting for its poll timer.
pub(in crate::io_loop) fn try_read_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &mut [u8],
    waker: &Waker,
) -> Option<io::Result<usize>> {
    let mut cx = Context::from_waker(waker);
    let mut rb = ReadBuf::new(buf);
    match Pin::new(stream).poll_read(&mut cx, &mut rb) {
        Poll::Ready(Ok(())) => Some(Ok(rb.filled().len())),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

/// Writes for the session. On `None` the stream keeps `waker` and wakes it when the session reads.
pub(in crate::io_loop) fn try_write_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &[u8],
    waker: &Waker,
) -> Option<io::Result<usize>> {
    let mut cx = Context::from_waker(waker);
    match Pin::new(stream).poll_write(&mut cx, buf) {
        Poll::Ready(Ok(n)) => Some(Ok(n)),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

/// Closes the write half so the session side reads EOF; a `DuplexStream` shutdown completes at once.
pub(in crate::io_loop) fn shutdown_duplex_write(stream: &mut tokio::io::DuplexStream) {
    let mut cx = Context::from_waker(Waker::noop());
    let _ = Pin::new(stream).poll_shutdown(&mut cx);
}
