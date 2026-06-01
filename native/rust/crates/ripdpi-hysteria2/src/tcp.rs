use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{Bytes, BytesMut};
use rand::Rng;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, ReadBuf, sink};

use crate::varint::{put_varint, read_varint};

pub struct DuplexStream {
    send: quinn::SendStream,
    recv: quinn::RecvStream,
}

impl DuplexStream {
    pub(crate) fn new(send: quinn::SendStream, recv: quinn::RecvStream) -> Self {
        Self { send, recv }
    }
}

impl AsyncRead for DuplexStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.recv).poll_read(cx, buf)
    }
}

impl AsyncWrite for DuplexStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.send).poll_write(cx, buf).map_err(io::Error::other)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_shutdown(cx)
    }
}

pub(crate) fn build_tcp_request(address: &str, padding_len: usize) -> Bytes {
    let mut buffer = BytesMut::with_capacity(address.len() + padding_len + 32);
    put_varint(0x401, &mut buffer);
    put_varint(address.len() as u64, &mut buffer);
    buffer.extend_from_slice(address.as_bytes());
    put_varint(padding_len as u64, &mut buffer);
    if padding_len > 0 {
        let mut padding = vec![0u8; padding_len];
        rand::rng().fill_bytes(&mut padding);
        buffer.extend_from_slice(&padding);
    }
    buffer.freeze()
}

pub(crate) async fn read_tcp_response<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<(bool, String)> {
    let status = reader.read_u8().await? == 0;
    let message_len = read_varint(reader).await? as usize;
    let mut message = vec![0u8; message_len];
    reader.read_exact(&mut message).await?;
    let padding_len = read_varint(reader).await?;
    if padding_len > 0 {
        tokio::io::copy(&mut reader.take(padding_len), &mut sink()).await?;
    }

    Ok((status, String::from_utf8_lossy(&message).to_string()))
}
