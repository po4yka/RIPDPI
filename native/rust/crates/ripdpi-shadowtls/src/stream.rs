use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll, Wake, Waker};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

use super::frames::{
    FrameDecode, MAX_WRITE_PAYLOAD_LEN, TLS_FRAME_MAX_LEN, TLS_HEADER_LEN, deframe_payload, frame_payload,
};
use super::hmac::ShadowTlsHmac;

pub struct ShadowTlsStream<S> {
    stream: S,
    read_hmac: ShadowTlsHmac,
    write_hmac: ShadowTlsHmac,
    handshake_hmac: Option<ShadowTlsHmac>,
    pending_plaintext: Vec<u8>,
    // Read and write keep SEPARATE frame buffers. They were once a single
    // shared `pending_frame`/`pending_frame_offset`, which serialized the duplex
    // into a frame-by-frame ping-pong under `tokio::io::split` /
    // `copy_bidirectional` (an inbound frame in progress and a queued outbound
    // frame could not coexist), collapsing throughput onto the ~40ms delayed-ACK
    // timer. Keeping them independent lets both directions pipeline.
    read_frame: Vec<u8>,
    read_frame_offset: usize,
    write_frame: Vec<u8>,
    write_frame_offset: usize,
    /// Waker of a write, flush or shutdown waiting for the queued frame to
    /// drain. A read that drains the frame wakes it too, so the carrier's
    /// single write-readiness slot never loses the writer's wakeup.
    write_waker: Option<Waker>,
    eof: bool,
}

impl<S> ShadowTlsStream<S> {
    pub(crate) fn new(
        stream: S,
        read_hmac: ShadowTlsHmac,
        write_hmac: ShadowTlsHmac,
        handshake_hmac: Option<ShadowTlsHmac>,
    ) -> Self {
        Self {
            stream,
            read_hmac,
            write_hmac,
            handshake_hmac,
            pending_plaintext: Vec::new(),
            read_frame: Vec::new(),
            read_frame_offset: 0,
            write_frame: Vec::new(),
            write_frame_offset: 0,
            write_waker: None,
            eof: false,
        }
    }
}

impl<S> ShadowTlsStream<S>
where
    S: AsyncWrite + Unpin,
{
    /// Writes the rest of the pending outbound frame to the carrier.
    fn poll_drain_write_frame(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while self.write_frame_offset < self.write_frame.len() {
            let write_result = {
                let frame = &self.write_frame[self.write_frame_offset..];
                Pin::new(&mut self.stream).poll_write(cx, frame)
            };
            match write_result {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "ShadowTLS failed to flush pending frame",
                    )));
                }
                Poll::Ready(Ok(written)) => self.write_frame_offset += written,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }

        self.write_frame.clear();
        self.write_frame_offset = 0;
        Poll::Ready(Ok(()))
    }

    /// [`Self::poll_drain_write_frame`] for a writer that waits on the result:
    /// remembers its waker while the drain is pending.
    fn poll_drain_write_frame_for_writer(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let result = self.poll_drain_write_frame(cx);
        self.write_waker = if result.is_pending() { Some(cx.waker().clone()) } else { None };
        result
    }
}

/// Wakes the reader and the waiting writer together.
struct ReaderAndWriterWaker {
    reader: Waker,
    writer: Waker,
}

impl Wake for ReaderAndWriterWaker {
    fn wake(self: Arc<Self>) {
        self.wake_by_ref();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        self.reader.wake_by_ref();
        self.writer.wake_by_ref();
    }
}

fn reader_and_writer_waker(reader: &Waker, writer: Option<&Waker>) -> Waker {
    match writer {
        Some(writer) if !writer.will_wake(reader) => {
            Waker::from(Arc::new(ReaderAndWriterWaker { reader: reader.clone(), writer: writer.clone() }))
        }
        _ => reader.clone(),
    }
}

impl<S> AsyncRead for ShadowTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();

        // A write-through that hit Pending leaves a frame queued, and a caller
        // that only reads next (a server-first protocol) would never send it.
        if this.write_frame_offset < this.write_frame.len() {
            let waker = reader_and_writer_waker(cx.waker(), this.write_waker.as_ref());
            match this.poll_drain_write_frame(&mut Context::from_waker(&waker)) {
                Poll::Ready(Ok(())) => {}
                // The carrier wakes this reader and any waiting writer when writable.
                Poll::Pending => {}
                // The frame stays queued; the next write, flush or shutdown retries it and reports the error.
                Poll::Ready(Err(_)) => {}
            }
        }

        if !this.pending_plaintext.is_empty() {
            let copy_len = buf.remaining().min(this.pending_plaintext.len());
            let chunk: Vec<u8> = this.pending_plaintext.drain(..copy_len).collect();
            buf.put_slice(&chunk);
            return Poll::Ready(Ok(()));
        }

        if this.eof {
            return Poll::Ready(Ok(()));
        }

        loop {
            if this.read_frame.is_empty() {
                this.read_frame.resize(TLS_HEADER_LEN, 0);
                this.read_frame_offset = 0;
            }

            while this.read_frame_offset < this.read_frame.len() {
                let read_result = {
                    let remaining = &mut this.read_frame[this.read_frame_offset..];
                    let mut read_buf = ReadBuf::new(remaining);
                    match Pin::new(&mut this.stream).poll_read(cx, &mut read_buf) {
                        Poll::Ready(Ok(())) => Poll::Ready(Ok(read_buf.filled().len())),
                        Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
                        Poll::Pending => Poll::Pending,
                    }
                };

                match read_result {
                    Poll::Ready(Ok(read)) => {
                        if read == 0 {
                            if this.read_frame_offset != 0 || this.read_frame.len() != TLS_HEADER_LEN {
                                return Poll::Ready(Err(io::Error::new(
                                    io::ErrorKind::UnexpectedEof,
                                    "ShadowTLS TLS frame ended mid-record",
                                )));
                            }
                            this.eof = true;
                            return Poll::Ready(Ok(()));
                        }
                        this.read_frame_offset += read;
                    }
                    Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                    Poll::Pending => return Poll::Pending,
                }
            }

            if this.read_frame.len() == TLS_HEADER_LEN {
                let payload_len = u16::from_be_bytes([this.read_frame[3], this.read_frame[4]]) as usize;
                if payload_len == 0 {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "ShadowTLS TLS frame has empty payload",
                    )));
                }
                if payload_len > TLS_FRAME_MAX_LEN - TLS_HEADER_LEN {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "ShadowTLS frame payload too large",
                    )));
                }
                this.read_frame.resize(TLS_HEADER_LEN + payload_len, 0);
                continue;
            }

            let decode_result = deframe_payload(&mut this.read_hmac, &mut this.handshake_hmac, &this.read_frame);
            match decode_result {
                Ok(FrameDecode::Plaintext(payload)) => {
                    this.pending_plaintext = payload;
                    this.read_frame.clear();
                    this.read_frame_offset = 0;
                    if this.pending_plaintext.is_empty() {
                        continue;
                    }
                    let copy_len = buf.remaining().min(this.pending_plaintext.len());
                    let chunk: Vec<u8> = this.pending_plaintext.drain(..copy_len).collect();
                    buf.put_slice(&chunk);
                    return Poll::Ready(Ok(()));
                }
                Ok(FrameDecode::IgnoredHandshake) => {
                    this.read_frame.clear();
                    this.read_frame_offset = 0;
                }
                Ok(FrameDecode::Alert) => {
                    this.read_frame.clear();
                    this.read_frame_offset = 0;
                    this.eof = true;
                    return Poll::Ready(Ok(()));
                }
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }
}

impl<S> AsyncWrite for ShadowTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.as_mut().get_mut();
        match this.poll_drain_write_frame_for_writer(cx) {
            Poll::Ready(Ok(())) => {}
            Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
            Poll::Pending => return Poll::Pending,
        }

        let write_len = buf.len().min(MAX_WRITE_PAYLOAD_LEN);
        let payload = &buf[..write_len];
        this.write_frame = frame_payload(&mut this.write_hmac, payload)?;
        // Write through: callers (the VLESS request header) may never flush,
        // and a server-first peer then waits forever. The payload is already
        // accepted, so this call reports success in every case.
        match this.poll_drain_write_frame(cx) {
            Poll::Ready(Ok(())) => {}
            // No writer waits now; the next read, write, flush or shutdown sends the rest.
            Poll::Pending => {}
            // The frame stays queued; the next write, flush or shutdown retries it and reports the error.
            Poll::Ready(Err(_)) => {}
        }
        Poll::Ready(Ok(write_len))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();
        match this.poll_drain_write_frame_for_writer(cx) {
            Poll::Ready(Ok(())) => Pin::new(&mut this.stream).poll_flush(cx),
            other => other,
        }
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.as_mut().poll_flush(cx) {
            Poll::Ready(Ok(())) => {
                let this = self.as_mut().get_mut();
                Pin::new(&mut this.stream).poll_shutdown(cx)
            }
            Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
            Poll::Pending => Poll::Pending,
        }
    }
}
