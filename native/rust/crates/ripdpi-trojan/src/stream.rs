use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, Waker, ready};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::time::Sleep;

const HEADER_ALONE_DELAY: Duration = Duration::from_millis(100);

pub struct TrojanStream<T> {
    inner: T,
    header: Option<Vec<u8>>,
    pending: Vec<u8>,
    written: usize,
    header_timer: Option<Pin<Box<Sleep>>>,
    drain_waiter: Option<Waker>,
    read_waiter: Option<Waker>,
}

impl<T> TrojanStream<T> {
    pub(crate) fn with_deferred_header(inner: T, header: Vec<u8>) -> Self {
        Self {
            inner,
            header: Some(header),
            pending: Vec::new(),
            written: 0,
            header_timer: None,
            drain_waiter: None,
            read_waiter: None,
        }
    }

    pub(crate) fn header_sent(inner: T) -> Self {
        Self {
            inner,
            header: None,
            pending: Vec::new(),
            written: 0,
            header_timer: None,
            drain_waiter: None,
            read_waiter: None,
        }
    }

    pub fn get_ref(&self) -> &T {
        &self.inner
    }
}

impl<T> std::fmt::Debug for TrojanStream<T> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("TrojanStream").field("header_pending", &self.header.is_some()).finish_non_exhaustive()
    }
}

impl<T: AsyncWrite + Unpin> TrojanStream<T> {
    fn queue_header(&mut self, payload: &[u8]) {
        if let Some(mut frame) = self.header.take() {
            frame.extend_from_slice(payload);
            self.pending = frame;
            self.written = 0;
            self.header_timer = None;
        }
    }

    fn poll_drain(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while self.written < self.pending.len() {
            let written = ready!(Pin::new(&mut self.inner).poll_write(cx, &self.pending[self.written..]))?;
            if written == 0 {
                return Poll::Ready(Err(io::ErrorKind::WriteZero.into()));
            }
            self.written += written;
        }
        if !self.pending.is_empty() {
            self.pending = Vec::new();
            self.written = 0;
        }
        if let Some(waiter) = self.drain_waiter.take() {
            waiter.wake();
        }
        Poll::Ready(Ok(()))
    }

    fn poll_drain_for_write(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let poll = self.poll_drain(cx);
        if poll.is_pending() {
            self.drain_waiter = Some(cx.waker().clone());
        }
        poll
    }
}

impl<T: AsyncRead + AsyncWrite + Unpin> AsyncRead for TrojanStream<T> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.header.is_some() {
            let timer = this.header_timer.get_or_insert_with(|| Box::pin(tokio::time::sleep(HEADER_ALONE_DELAY)));
            if timer.as_mut().poll(cx).is_ready() {
                this.queue_header(&[]);
            }
        }
        match this.poll_drain(cx) {
            Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
            Poll::Pending => {
                if let Some(waiter) = this.drain_waiter.take() {
                    waiter.wake();
                }
            }
            Poll::Ready(Ok(())) => {}
        }
        let poll = Pin::new(&mut this.inner).poll_read(cx, buf);
        if poll.is_pending() {
            if !this.read_waiter.as_ref().is_some_and(|waiter| waiter.will_wake(cx.waker())) {
                this.read_waiter = Some(cx.waker().clone());
            }
        } else {
            this.read_waiter = None;
        }
        poll
    }
}

impl<T: AsyncWrite + Unpin> AsyncWrite for TrojanStream<T> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.header.is_some() {
            this.queue_header(buf);
            match this.poll_drain(cx) {
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => {
                    if let Some(waiter) = this.read_waiter.take() {
                        waiter.wake();
                    }
                }
                Poll::Ready(Ok(())) => {}
            }
            return Poll::Ready(Ok(buf.len()));
        }
        ready!(this.poll_drain_for_write(cx))?;
        Pin::new(&mut this.inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        this.queue_header(&[]);
        ready!(this.poll_drain_for_write(cx))?;
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        this.queue_header(&[]);
        ready!(this.poll_drain_for_write(cx))?;
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;

    #[derive(Default)]
    struct Recorder {
        writes: Vec<Vec<u8>>,
    }

    impl AsyncRead for Recorder {
        fn poll_read(self: Pin<&mut Self>, _cx: &mut Context<'_>, _buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            Poll::Pending
        }
    }

    impl AsyncWrite for Recorder {
        fn poll_write(mut self: Pin<&mut Self>, _cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            self.writes.push(buf.to_vec());
            Poll::Ready(Ok(buf.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    #[tokio::test]
    async fn header_goes_out_with_the_first_payload() {
        let mut stream = TrojanStream::with_deferred_header(Recorder::default(), b"HEADER".to_vec());
        stream.write_all(b"hello").await.expect("first write");
        stream.write_all(b"world").await.expect("second write");
        assert_eq!(stream.get_ref().writes, vec![b"HEADERhello".to_vec(), b"world".to_vec()]);
    }

    #[tokio::test(start_paused = true)]
    async fn a_stream_read_first_sends_the_header_alone_after_the_delay() {
        let mut stream = TrojanStream::with_deferred_header(Recorder::default(), b"HEADER".to_vec());
        let mut buf = [0u8; 16];
        let early = tokio::time::timeout(HEADER_ALONE_DELAY / 2, stream.read(&mut buf)).await;
        assert!(early.is_err(), "test precondition: the server sends nothing");
        assert!(stream.get_ref().writes.is_empty(), "the header waits for a first payload");

        let late = tokio::time::timeout(HEADER_ALONE_DELAY, stream.read(&mut buf)).await;
        assert!(late.is_err(), "test precondition: the server sends nothing");
        assert_eq!(stream.get_ref().writes, vec![b"HEADER".to_vec()]);
    }

    #[tokio::test]
    async fn flush_sends_the_header() {
        let mut stream = TrojanStream::with_deferred_header(Recorder::default(), b"HEADER".to_vec());
        stream.flush().await.expect("flush");
        assert_eq!(stream.get_ref().writes, vec![b"HEADER".to_vec()]);
    }

    const PIPE_CAPACITY: usize = 16;

    #[tokio::test]
    async fn a_read_that_ends_does_not_strand_a_parked_writer() {
        let (client, server) = tokio::io::duplex(PIPE_CAPACITY);
        let (mut reader, mut writer) = tokio::io::split(TrojanStream::with_deferred_header(client, b"HEADER".to_vec()));
        let (mut server_read, mut server_write) = tokio::io::split(server);
        let first = vec![b'a'; 64];
        let second = vec![b'b'; 64];
        let writer_task = tokio::spawn({
            let (first, second) = (first.clone(), second.clone());
            async move {
                writer.write_all(&first).await.expect("first write");
                writer.write_all(&second).await.expect("second write");
            }
        });
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(!writer_task.is_finished(), "test precondition: the writer is parked on a full pipe");

        server_write.shutdown().await.expect("server shutdown");
        let mut buf = [0u8; 8];
        assert_eq!(reader.read(&mut buf).await.expect("read EOF"), 0);
        drop(reader);

        let mut received = vec![0u8; 6 + first.len() + second.len()];
        tokio::time::timeout(Duration::from_secs(2), server_read.read_exact(&mut received))
            .await
            .expect("the parked writer must drain after the read ends")
            .expect("server read");
        assert_eq!(received, [b"HEADER".as_slice(), &first, &second].concat());
        writer_task.await.expect("writer task");
    }

    #[tokio::test]
    async fn a_first_write_that_never_flushes_still_reaches_the_server() {
        let (client, server) = tokio::io::duplex(PIPE_CAPACITY);
        let (mut reader, mut writer) = tokio::io::split(TrojanStream::with_deferred_header(client, b"HEADER".to_vec()));
        let request = vec![b'q'; 64];
        let expected = [b"HEADER".as_slice(), &request].concat();
        let server_task = tokio::spawn(async move {
            let mut server = server;
            let mut received = vec![0u8; expected.len()];
            server.read_exact(&mut received).await.expect("server read");
            assert_eq!(received, expected);
            server.write_all(b"ok").await.expect("server response");
            server
        });
        let reader_task = tokio::spawn(async move {
            let mut response = [0u8; 2];
            reader.read_exact(&mut response).await.map(|_| response)
        });
        tokio::time::sleep(Duration::from_millis(10)).await;
        let written = writer.write(&request).await.expect("request write");
        assert_eq!(written, request.len());

        let response = tokio::time::timeout(Duration::from_secs(2), reader_task)
            .await
            .expect("the queued request must reach the server without a flush")
            .expect("reader task")
            .expect("response read");
        assert_eq!(&response, b"ok");
        drop(writer);
        server_task.await.expect("server task");
    }
}
