use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{BufMut, BytesMut};
use quinn::{RecvStream, SendStream};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

use crate::protocol::{TuicAddress, COMMAND_CONNECT, TUIC_VERSION};

pub struct DuplexStream {
    pub(crate) send: SendStream,
    pub(crate) recv: RecvStream,
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

pub(crate) async fn encode_connect_header(send: &mut SendStream, address: &TuicAddress) -> io::Result<()> {
    let mut payload = BytesMut::with_capacity(2 + address.encoded_len());
    payload.put_u8(TUIC_VERSION);
    payload.put_u8(COMMAND_CONNECT);
    address.encode(&mut payload);
    send.write_all(&payload).await.map_err(io::Error::other)
}
