// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, ready};
use std::time::{SystemTime, UNIX_EPOCH};

use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf};

use crate::SnellClientConfig;
use crate::record::{Record, RecordReader, RecordWriter};
use crate::request::{encode_connect, parse_reply};

const READ_CHUNK: usize = 16 * 1024;
/// Upper bound on plaintext accepted per `poll_write`, so one call cannot buffer unbounded output.
const MAX_WRITE: usize = 64 * 1024;

/// Opens a Snell v4 tunnel to `target` over an already connected `transport` and sends the request.
///
/// # Cancel safety:
/// cancel-safe: a drop at `write_all` or `flush` drops the owned transport and request buffer, so
/// a partially written request is never reused and no stream is returned.
pub async fn connect<S>(mut transport: S, config: &SnellClientConfig, target: &str) -> io::Result<SnellStream<S>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = encode_connect(&config.user_key, target)?;
    let mut writer = RecordWriter::new(&config.psk)?;
    let mut out = Vec::new();
    writer.encode_write(&request, now_unix(), &mut out)?;
    transport.write_all(&out).await?;
    transport.flush().await?;
    out.clear();
    Ok(SnellStream {
        inner: transport,
        writer,
        out,
        out_pos: 0,
        reader: RecordReader::new(&config.psk),
        input: Vec::new(),
        plain: Vec::new(),
        plain_pos: 0,
        reply_seen: false,
        read_done: false,
        read_failed: None,
        write_ended: false,
    })
}

/// A Snell v4 tunnel. Shutdown sends the end record but keeps the carrier open, so the server's
/// response direction still arrives; dropping the stream closes the carrier.
///
/// `poll_write` accepts data into encoded records; only a later write, flush or shutdown pushes a
/// remainder that the carrier did not take, so a writer must flush before it waits on a read.
/// A read error is sticky: the decoder state is not reusable after it.
pub struct SnellStream<S> {
    inner: S,
    writer: RecordWriter,
    out: Vec<u8>,
    out_pos: usize,
    reader: RecordReader,
    input: Vec<u8>,
    plain: Vec<u8>,
    plain_pos: usize,
    reply_seen: bool,
    read_done: bool,
    read_failed: Option<io::ErrorKind>,
    write_ended: bool,
}

impl<S> SnellStream<S> {
    pub fn get_ref(&self) -> &S {
        &self.inner
    }
}

impl<S: AsyncWrite + Unpin> SnellStream<S> {
    fn poll_drain(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while self.out_pos < self.out.len() {
            let written = ready!(Pin::new(&mut self.inner).poll_write(cx, &self.out[self.out_pos..]))?;
            if written == 0 {
                return Poll::Ready(Err(io::ErrorKind::WriteZero.into()));
            }
            self.out_pos += written;
        }
        self.out.clear();
        self.out_pos = 0;
        Poll::Ready(Ok(()))
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for SnellStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(kind) = this.read_failed {
            return Poll::Ready(Err(io::Error::new(kind, "Snell stream failed earlier")));
        }
        let polled = this.poll_read_records(cx, buf);
        if let Poll::Ready(Err(error)) = &polled {
            this.read_failed = Some(error.kind());
        }
        polled
    }
}

impl<S: AsyncRead + Unpin> SnellStream<S> {
    fn poll_read_records(&mut self, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self;
        loop {
            if this.plain_pos < this.plain.len() {
                let count = buf.remaining().min(this.plain.len() - this.plain_pos);
                buf.put_slice(&this.plain[this.plain_pos..this.plain_pos + count]);
                this.plain_pos += count;
                return Poll::Ready(Ok(()));
            }
            if this.read_done {
                return Poll::Ready(Ok(()));
            }
            match this.reader.decode(&mut this.input)? {
                Some(Record::Data(data)) => {
                    this.plain = if this.reply_seen { data } else { parse_reply(&data)?.to_vec() };
                    this.plain_pos = 0;
                    this.reply_seen = true;
                }
                // sing-snell rejects a zero chunk that leaves bytes in its decrypt buffer.
                Some(Record::End) if this.reply_seen && !this.input.is_empty() => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "Snell end record has trailing data",
                    )));
                }
                Some(Record::End) if this.reply_seen => this.read_done = true,
                Some(Record::End) => return Poll::Ready(Err(closed_before_reply())),
                None => {
                    let start = this.input.len();
                    this.input.resize(start + READ_CHUNK, 0);
                    let mut chunk = ReadBuf::new(&mut this.input[start..]);
                    let polled = Pin::new(&mut this.inner).poll_read(cx, &mut chunk);
                    let filled = chunk.filled().len();
                    this.input.truncate(start + filled);
                    ready!(polled)?;
                    if filled == 0 {
                        // A carrier EOF on a record boundary after the reply is a plain end of stream.
                        if !this.reply_seen {
                            return Poll::Ready(Err(closed_before_reply()));
                        }
                        if !this.reader.at_record_boundary() || !this.input.is_empty() {
                            return Poll::Ready(Err(io::ErrorKind::UnexpectedEof.into()));
                        }
                        this.read_done = true;
                    }
                }
            }
        }
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for SnellStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.write_ended {
            return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()));
        }
        ready!(this.poll_drain(cx))?;
        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }
        let accepted = &buf[..buf.len().min(MAX_WRITE)];
        this.writer.encode_write(accepted, now_unix(), &mut this.out)?;
        // The records own the plaintext now, so report it accepted; a carrier error stays in `out`
        // and surfaces from the next write, flush or shutdown instead of inviting a duplicate retry.
        let _ = this.poll_drain(cx);
        Poll::Ready(Ok(accepted.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        ready!(this.poll_drain(cx))?;
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        ready!(this.poll_drain(cx))?;
        if !this.write_ended {
            this.writer.encode_end(&mut this.out)?;
            this.write_ended = true;
            ready!(this.poll_drain(cx))?;
        }
        Pin::new(&mut this.inner).poll_flush(cx)
    }
}

fn closed_before_reply() -> io::Error {
    io::Error::new(io::ErrorKind::ConnectionAborted, "Snell server closed the tunnel before its reply")
}

fn now_unix() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_secs() as i64)
}
