use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, ready};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

enum State {
    Pending,
    Payload,
    Failed,
}

/// Allows client writes before the peer sends its status with the first reply.
/// Read progress belongs to the stream, so cancelling a read cannot lose status.
pub(super) struct ResponseStream<S> {
    inner: S,
    state: State,
}

impl<S> ResponseStream<S> {
    pub(super) fn new(inner: S) -> Self {
        Self { inner, state: State::Pending }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for ResponseStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if output.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        match this.state {
            State::Pending => {
                let mut status = [0];
                let mut response = ReadBuf::new(&mut status);
                if let Err(error) = ready!(Pin::new(&mut this.inner).poll_read(cx, &mut response)) {
                    this.state = State::Failed;
                    return Poll::Ready(Err(error));
                }
                this.state = State::Failed;
                if response.filled().is_empty() {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "VLESS mux response missing",
                    )));
                }
                if status[0] != 0 {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::ConnectionRefused,
                        "VLESS mux stream request rejected",
                    )));
                }
                this.state = State::Payload;
            }
            State::Failed => return Poll::Ready(Err(response_failed())),
            State::Payload => {}
        }
        Pin::new(&mut this.inner).poll_read(cx, output)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for ResponseStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, input: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if matches!(this.state, State::Failed) {
            return Poll::Ready(Err(response_failed()));
        }
        Pin::new(&mut this.inner).poll_write(cx, input)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

fn response_failed() -> io::Error {
    io::Error::new(io::ErrorKind::BrokenPipe, "VLESS mux response validation already failed")
}

#[cfg(test)]
mod tests;
