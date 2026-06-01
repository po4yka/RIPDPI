use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

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
    pending_frame: Vec<u8>,
    pending_frame_offset: usize,
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
            pending_frame: Vec::new(),
            pending_frame_offset: 0,
            eof: false,
        }
    }
}

impl<S> AsyncRead for ShadowTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();

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
            if this.pending_frame.is_empty() {
                this.pending_frame.resize(TLS_HEADER_LEN, 0);
                this.pending_frame_offset = 0;
            }

            while this.pending_frame_offset < this.pending_frame.len() {
                let read_result = {
                    let remaining = &mut this.pending_frame[this.pending_frame_offset..];
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
                            this.eof = true;
                            return Poll::Ready(Ok(()));
                        }
                        this.pending_frame_offset += read;
                    }
                    Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                    Poll::Pending => return Poll::Pending,
                }
            }

            if this.pending_frame.len() == TLS_HEADER_LEN {
                let payload_len = u16::from_be_bytes([this.pending_frame[3], this.pending_frame[4]]) as usize;
                if payload_len > TLS_FRAME_MAX_LEN - TLS_HEADER_LEN {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "ShadowTLS frame payload too large",
                    )));
                }
                this.pending_frame.resize(TLS_HEADER_LEN + payload_len, 0);
                continue;
            }

            let decode_result = deframe_payload(&mut this.read_hmac, &mut this.handshake_hmac, &this.pending_frame);
            match decode_result {
                Ok(FrameDecode::Plaintext(payload)) => {
                    this.pending_plaintext = payload;
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
                    if this.pending_plaintext.is_empty() {
                        continue;
                    }
                    let copy_len = buf.remaining().min(this.pending_plaintext.len());
                    let chunk: Vec<u8> = this.pending_plaintext.drain(..copy_len).collect();
                    buf.put_slice(&chunk);
                    return Poll::Ready(Ok(()));
                }
                Ok(FrameDecode::IgnoredHandshake) => {
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
                }
                Ok(FrameDecode::Alert) => {
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
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

        while this.pending_frame_offset < this.pending_frame.len() {
            let write_result = {
                let frame = &this.pending_frame[this.pending_frame_offset..];
                Pin::new(&mut this.stream).poll_write(cx, frame)
            };
            match write_result {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "ShadowTLS failed to flush pending frame",
                    )));
                }
                Poll::Ready(Ok(written)) => this.pending_frame_offset += written,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }

        this.pending_frame.clear();
        this.pending_frame_offset = 0;

        let write_len = buf.len().min(MAX_WRITE_PAYLOAD_LEN);
        let payload = &buf[..write_len];
        let frame = frame_payload(&mut this.write_hmac, payload)?;
        this.pending_frame = frame;
        Poll::Ready(Ok(write_len))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();

        while this.pending_frame_offset < this.pending_frame.len() {
            let write_result = {
                let frame = &this.pending_frame[this.pending_frame_offset..];
                Pin::new(&mut this.stream).poll_write(cx, frame)
            };
            match write_result {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "ShadowTLS failed to flush pending frame",
                    )));
                }
                Poll::Ready(Ok(written)) => this.pending_frame_offset += written,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }

        this.pending_frame.clear();
        this.pending_frame_offset = 0;
        Pin::new(&mut this.stream).poll_flush(cx)
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
